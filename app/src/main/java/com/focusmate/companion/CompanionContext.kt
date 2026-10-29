package com.focusmate.companion

/**
 * 唯一上下文入口。所有主动发言和所有回复都必须从这里取上下文，
 * 不允许另建"高频台词"通道绕过记忆（执行指令 §0 第 7 条）。
 *
 * 组装顺序固定：
 *   人格 -> 规则(何时能说/不能说什么) -> 召回的事实 -> 近期对话 -> 当前事项
 * 每一步都做长度裁剪，保证提示词有界。
 */
object CompanionContext {

    const val DEFAULT_MAX_CHARS = 6000

    data class Bundle(
        val systemPrompt: String,
        val userPrompt: String,
        /** 这次用了哪些事实（便于事后追责：模型说错话时能查到它看到的原文）。 */
        val usedFactIds: List<String>,
        val followUpId: String?,
        /**
         * 组装这份上下文时的记忆版本。输出前拿它和目标状态再比一次，
         * 不一致说明"生成期间记忆变了"，旧结果必须丢弃（F-05）。
         */
        val memoryVersion: Long = 0L,
    )

    /**
     * @param triggerText 非空表示这是"到点主动开口"；为空表示"回应用户"。
     */
    fun build(
        persona: Persona,
        state: CompanionState,
        nowMillis: Long,
        query: String,
        trigger: FollowUp?,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): Bundle {
        val facts = FactEngine.recall(state, query + " " + (trigger?.topic ?: ""), limit = 8)
        // 被用户要求忘掉/更正过的句子不进模型输入（F-02）。
        // 界面上仍然显示，那是给人看的；"别再发给模型"和"从界面消失"是两件事。
        val recent = state.dialogue.filter { !it.hidden }.takeLast(12)

        val sb = StringBuilder()
        sb.appendLine("# 你是谁")
        sb.appendLine(persona.displayName + "：" + persona.selfDescription)
        sb.appendLine()
        sb.appendLine("## 说话方式")
        persona.styleRules.forEach { sb.appendLine("- " + it) }
        sb.appendLine()
        sb.appendLine("## 底线")
        persona.boundaries.forEach { sb.appendLine("- " + it) }
        sb.appendLine()

        if (facts.isNotEmpty()) {
            sb.appendLine("## 你记得的事（只能引用这里写明的，不要自己补细节）")
            facts.forEach { f ->
                sb.appendLine("- [" + f.source.label() + "] " + f.text)
            }
            sb.appendLine()
        } else {
            sb.appendLine("## 你记得的事")
            sb.appendLine("- 暂时没有可用的记录。不要假装记得。")
            sb.appendLine()
        }

        if (recent.isNotEmpty()) {
            sb.appendLine("## 最近的对话")
            recent.forEach { u ->
                val who = if (u.role == Utterance.Role.USER) "对方" else "你"
                sb.appendLine("- " + who + "：" + u.text)
            }
            sb.appendLine()
        }

        trigger?.let {
            // 依据句子若已被用户忘记/更正（hidden），就不能再进模型输入（R-03 的第二道保险：
            // 正常路径上这类事项已经被作废，这里防止旧数据或竞态把它带进来）。
            val evidenceHidden = it.evidenceUtteranceId != null &&
                state.dialogue.any { u -> u.id == it.evidenceUtteranceId && u.hidden }
            if (!evidenceHidden) {
                sb.appendLine("## 这次为什么要开口")
                sb.appendLine("- 这个话题：" + it.topic)
                sb.appendLine("- 依据：" + it.evidence)
                sb.appendLine()
            }
        }

        val system = sb.toString().let {
            if (it.length <= maxChars) it else it.take(maxChars) + "\n（内容过长已截断）"
        }

        val user = if (trigger != null) {
            "现在是 " + timeText(nowMillis) + "。你主动开口，围绕上面的依据说一句话。" +
                "不要提及你在读设定，不要说'根据记录'。两句话以内。"
        } else {
            "对方说：" + query + "\n直接回应，两句话以内。"
        }

        return Bundle(system, user, facts.map { it.id }, trigger?.id, state.memoryVersion)
    }

    private fun timeText(millis: Long): String {
        val fmt = java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(millis))
    }
}
