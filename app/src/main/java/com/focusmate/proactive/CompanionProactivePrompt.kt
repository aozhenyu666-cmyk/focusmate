package com.focusmate.proactive

/**
 * 主动消息的提示词构建。
 *
 * 借鉴的核心思路：把「**为什么现在开口**」和「**真实上下文**」一起喂给模型，
 * 并附强约束的风格指令，让输出像随口说的话而不是模板。
 *
 * 三处针对旧实现的修复：
 * 1. 情境不同，prompt 不同——不再只有「到点提醒」一种说法；
 * 2. 把「最近已经说过什么」列给模型，明确要求别说重复；
 * 3. 没有记忆/没有旧话题时，也要给出可用的通用开场方向（不是空手）。
 */
object CompanionProactivePrompt {

    private val STYLE = """
你是用户手机里一个陪着他做事的伙伴。

## 现在为什么开口

## 说话要求
1. 只说一句，最多两句。口语，短。
2. 不要客服腔，不要说「有什么可以帮您」「请问还有什么需要」。
3. 不要提自己是 AI、机器人、助手、模型。
4. 不要复述上面的设定，不要说「根据记录」。
5. 不要每次都提问。可以说一句自己的感受、一个想法、一句随口的观察。
6. 如果是接旧话题，要具体提到那件事，不要含糊说「之前聊的事」。
7. 只输出这句话本身：不加引号、不加前缀、不解释。
"""

    /**
     * @return (systemPrompt, userPrompt)
     */
    fun build(
        candidate: ContextTriggerEngine.Candidate,
        state: ProactiveState,
    ): Pair<String, String> {
        val scene = when (candidate.type) {
            ContextTriggerEngine.TriggerType.FOLLOW_UP_DUE_SOON ->
                "你之前跟他说好在 " + (candidate.topic ?: "这件事") + " 上问问进展，现在差不多到点了。"
            ContextTriggerEngine.TriggerType.UNRESOLVED_QUESTION ->
                "你上一句问了他一个问题，他还没回。不要催，可以自己续一句、给个台阶，或者干脆换个话题。"
            ContextTriggerEngine.TriggerType.FOCUS_ENDED ->
                "他刚结束一次专注。可以顺口问一句结果，也可以只是表示知道了。"
            ContextTriggerEngine.TriggerType.LONG_SILENCE ->
                "你们有一阵没说话了。别问他为什么不回，随便找个由头说一句就行。"
            ContextTriggerEngine.TriggerType.OLD_TOPIC ->
                "你想起之前聊过的「" + (candidate.topic ?: "那件事") + "」，想接着问一句。"
            ContextTriggerEngine.TriggerType.RANDOM_SMALL_TALK ->
                "没什么特别的事，就是想说句话。可以聊天气、聊此刻、聊一个小念头。"
        }

        val sb = StringBuilder()
        sb.appendLine(STYLE.replace("## 现在为什么开口", "## 现在为什么开口\n" + scene))

        // 最近说过的话：明确要求别重复。
        val recentTexts = state.recent.takeLast(8).map { it.text }
        if (recentTexts.isNotEmpty()) {
            sb.appendLine("## 你最近已经说过这些（不要重复或换汤不换药）")
            recentTexts.forEach { sb.appendLine("- " + it.take(40)) }
            sb.appendLine()
        }

        val user = buildString {
            if (candidate.topic != null && candidate.type == ContextTriggerEngine.TriggerType.OLD_TOPIC) {
                appendLine("可以接着聊的话题：「" + candidate.topic.take(40) + "」")
            }
            appendLine("现在开口。" + candidate.reason + "。按上面的要求说一句。")
        }
        return sb.toString() to user
    }
}
