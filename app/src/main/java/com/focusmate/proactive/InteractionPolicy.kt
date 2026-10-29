package com.focusmate.proactive

import kotlin.math.min

/**
 * 互动频率仲裁。
 *
 * 解决两个相反的问题：
 * - 用户热情时不要冷场（回应积极 -> 提高机会）；
 * - 用户不理时不要轰炸（连续沉默 -> 退避到很低，但不归零）。
 *
 * 还负责一件事：**随机闲聊与预约提醒分账**。
 * 有重要约定快到期时，随机消息让路——否则随机消息会挤掉真正该说的提醒。
 */
object InteractionPolicy {

    /** 上一条主动消息发出后，多久没回应就算一次沉默。 */
    const val REPLY_WINDOW_MILLIS = 20L * 60_000L

    /** 统计互动积极度的滑动窗口。 */
    const val ENGAGEMENT_WINDOW_MILLIS = 24L * 3_600_000L

    /**
     * 根据最近历史算「用户回应率」0~1。
     * @return 0.5 表示没有足够数据（中性起点，不预设用户冷淡）
     */
    fun engagementOf(messages: List<ProactiveMessage>, nowMillis: Long): Double {
        val window = messages.filter { nowMillis - it.createdAt <= ENGAGEMENT_WINDOW_MILLIS }
        // 只算真正发出去的（通知成功），没发成功的不该影响用户画像。
        val sent = window.filter { it.state != MessageState.FAILED && it.state != MessageState.DROPPED }
        if (sent.isEmpty()) return 0.5
        val replied = sent.count { it.state == MessageState.READ }
        return replied.toDouble() / sent.size.toDouble()
    }

    /**
     * 上一条发出后是否已经过了回应窗口还没回应。
     * 用于给「沉默计数」+1（退避的依据）。
     */
    fun shouldCountAsSilence(lastSentAtMillis: Long, nowMillis: Long): Boolean {
        if (lastSentAtMillis <= 0L) return false
        return nowMillis - lastSentAtMillis >= REPLY_WINDOW_MILLIS
    }

    /**
     * 今天还能说几句的余量。0 表示不该再说。
     * 同时考虑每日上限与突发限流，取更严格的那个。
     */
    fun remainingQuota(config: ProactiveConfig, sentToday: Int, sentInWindow: Int): Int {
        val dailyLeft = if (config.dailyMax <= 0) Int.MAX_VALUE else (config.dailyMax - sentToday).coerceAtLeast(0)
        val burstLeft = (config.burstMax - sentInWindow).coerceAtLeast(0)
        return min(dailyLeft, burstLeft)
    }

    /**
     * 突发窗口滚动：窗口过期就重置计数。
     * @return (新窗口起点, 新窗口计数)
     */
    fun rollWindow(
        windowStartMillis: Long,
        windowCount: Int,
        nowMillis: Long,
        config: ProactiveConfig,
    ): Pair<Long, Int> {
        if (windowStartMillis <= 0L) return nowMillis to 0
        if (nowMillis - windowStartMillis >= config.burstWindowMillis) return nowMillis to 0
        return windowStartMillis to windowCount
    }

    /**
     * 跨天重置。用本地日期键，避免「昨晚说到 80 条，今天一条都说不了」。
     */
    fun ensureDay(dayKey: String, currentDayKey: String, sentToday: Int): Pair<String, Int> {
        return if (dayKey == currentDayKey) dayKey to sentToday else currentDayKey to 0
    }

    /**
     * 暂停语义：用户点暂停后，随机闲聊与情境闲聊全停；
     * 预约的提醒**仍然照常**（那是用户自己要求的事，不能被随机开关牵连）。
     */
    fun allowsRandomTalk(config: ProactiveConfig): Boolean = config.enabled && !config.paused
}
