package com.focusmate.companion

/**
 * 从用户一句话里提取"要不要改事项"的结构化意图。
 *
 * 为什么不用模型：改期/取消属于会改变行为的动作，必须能用测试固定。
 * 上游 dsh-world 用工具调用（plan_next / cancel）而不是自由文本改闹钟；
 * 这里先做一个确定性的最小实现，模型只负责理解其余部分。
 *
 * 语义边界（DECISIONS D-03）：
 * - "十分钟后再聊" 只推迟**对话**，绝不改变专注会话的结束时间；
 * - 想改锁定时长必须另外走确认流程，这里识别出来后直接拒绝，不执行。
 */
object IntentParser {

    data class Intent(
        val kind: Kind,
        val dueAtMillis: Long? = null,
        val topic: String? = null,
        val evidence: String,
    )

    enum class Kind {
        /** "十分钟后再聊" 之类：改期，同一话题。 */
        POSTPONE,

        /** "先不用提醒我了"：取消。 */
        CANCEL,

        /** 明显想改锁定时长：不属于对话改期，必须拒绝并说明。 */
        LOCK_CHANGE_REQUEST,
    }

    private val postponeRe = Regex("""(?:过|再)?\s*(\d+|[一二三四五六七八九十两]+|半)\s*个?\s*(分钟|分|小时|钟头|天)?\s*后?(?:再聊|再说|再讲|接着聊|找我|提醒我|说)""")
    private val postponeWords = listOf("等下再聊", "回头再说", "晚点再聊", "一会儿再聊", "待会再说")
    private val cancelRe = Regex("(不用|别再|不要)(再)?(提醒|说|催|找我)")
    private val lockWords = listOf("提前结束专注", "缩短专注", "延长专注", "锁定再久一点", "多锁一会", "少锁一会", "取消专注限制", "改成二十分钟专注", "改一下专注时长")

    fun parse(text: String, nowMillis: Long, currentTopic: String?): Intent? {
        val clean = text.trim()
        if (clean.isEmpty()) return null

        // 先判锁定变更：这类必须单独确认，不能当成改期处理。
        if (lockWords.any { clean.contains(it) }) {
            return Intent(Kind.LOCK_CHANGE_REQUEST, evidence = clean)
        }
        cancelRe.find(clean)?.let {
            return Intent(Kind.CANCEL, topic = currentTopic, evidence = it.value)
        }
        postponeRe.find(clean)?.let { m ->
            val millis = unitMillis(m.groupValues[1], m.groupValues[2]) ?: return@let
            return Intent(Kind.POSTPONE, dueAtMillis = nowMillis + millis, topic = currentTopic, evidence = m.value)
        }
        postponeWords.firstOrNull { clean.contains(it) }?.let {
            return Intent(Kind.POSTPONE, dueAtMillis = nowMillis + 10 * 60_000L, topic = currentTopic, evidence = it)
        }
        return null
    }

    /** "半" = 0.5 个单位；"两"/"十" 等中文数字也认。解析不出来就返回 null（不硬猜）。 */
    /** rawUnit 可能为空（"十分钟再聊"省略了单位后缀时不会为空；这里兜底按分钟算）。 */
    private fun unitMillis(rawAmount: String, rawUnit: String): Long? {
        val amount: Double = when {
            rawAmount == "半" -> 0.5
            rawAmount.all { it.isDigit() } -> rawAmount.toDoubleOrNull() ?: return null
            else -> chineseNumber(rawAmount)?.toDouble() ?: return null
        }
        val unit = when (rawUnit) {
            "分钟", "分" -> 60_000L
            "小时", "钟头" -> 3_600_000L
            "天" -> 86_400_000L
            // 省略单位时按分钟算；"半小时" 的 "半" 遇到空单位也走这里。
            "" -> 60_000L
            else -> return null
        }
        val millis = (unit * amount).toLong()
        return if (millis > 0) millis else null
    }

    /** 只处理 1..99 的常见写法：一/二/两/三…十/十一/二十/二十三。 */
    fun chineseNumber(text: String): Int? {
        val digits = mapOf(
            '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
        )
        if (text.isEmpty()) return null
        if ('十' !in text) {
            if (text.length != 1) return null
            return digits[text[0]]
        }
        val idx = text.indexOf('十')
        if (text.count { it == '十' } > 1) return null
        val before = text.substring(0, idx)
        val after = text.substring(idx + 1)
        val tens = if (before.isEmpty()) 1 else digits[before.singleOrNull() ?: return null] ?: return null
        val ones = if (after.isEmpty()) 0 else digits[after.singleOrNull() ?: return null] ?: return null
        return tens * 10 + ones
    }
}
