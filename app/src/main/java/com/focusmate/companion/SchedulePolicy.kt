package com.focusmate.companion

/**
 * 调度规则的唯一实现：纯函数，不碰 Android，不读真实时钟。
 *
 * 移植自 surfcore/src/purpose-engine.js 的 evaluatePurposeTick / planNextFire / inQuietHours，
 * 保留判断顺序（顺序本身就是规则，不能调换）：
 *   关闭 -> 免打扰 -> 今日上限 -> 还没到点 -> 没有话题 -> 用户刚说话 -> 可以跑
 *
 * 与上游的差异：
 * - 上游 tick 内会做随机抖动；这里 tick 保持确定性（随机只出现在 planNext），便于测试。
 * - 时区换算不在内部做，而是由调用方传入 `minuteOfDay: (Long) -> Int`，
 *   这样跨天/夏令时不会在这里算错，测试也能直接给常量。
 */
object SchedulePolicy {

    const val MINUTE = 60_000L

    /** 专注中每隔这么久重查一次，而不是等下一个到期点。 */
    const val FOCUS_RECHECK = 2 * MINUTE
    const val DAY = 24 * 60 * MINUTE

    /** 落在免打扰区间？跨零点（23:00→08:00）也要正确；起止相同视为不启用。 */
    fun inQuietHours(minuteOfDay: Int, config: CompanionConfig): Boolean {
        if (!config.quietEnabled()) return false
        val s = config.quietStartMinute
        val e = config.quietEndMinute
        return if (s < e) minuteOfDay >= s && minuteOfDay < e else minuteOfDay >= s || minuteOfDay < e
    }

    sealed interface Decision {
        /** 可以生成并投递。 */
        data object Run : Decision

        /** 现在不说。reason 只用于诊断日志，不展示给用户。 */
        data class Wait(val untilMillis: Long, val reason: String) : Decision
    }

    /**
     * @param countToday             今天已经主动说了几次
     * @param nextDueAtMillis        下一次该开口的时间（planNext 产出），0 表示未排
     * @param lastUserSpeakAtMillis  用户最后一次说话的时间，0 表示没说过
     * @param hasOpenFollowUp        是否存在尚未处理的待跟进事项
     * @param inFocusSession         是否正处于专注会话中（专注期间不主动开口）
     */
    fun evaluate(
        nowMillis: Long,
        config: CompanionConfig,
        minuteOfDay: (Long) -> Int,
        lastRunAtMillis: Long,
        countToday: Int,
        nextDueAtMillis: Long,
        lastUserSpeakAtMillis: Long,
        hasOpenFollowUp: Boolean,
        inFocusSession: Boolean = false,
    ): Decision {
        if (!config.enabled) return Decision.Wait(0L, "disabled")

        // 专注进行中禁言：这是唯一一条"优先级高于到期"的规则。
        // 理由：专注会话是用户明确设定的边界，到点提醒可以等他出来后再说，
        // 但不能在专注期间突然说话（执行指令 §7 任务 2.3）。
        if (inFocusSession) return Decision.Wait(nowMillis + FOCUS_RECHECK, "in-focus")

        if (inQuietHours(minuteOfDay(nowMillis), config)) {
            return Decision.Wait(quietEndAfter(nowMillis, config, minuteOfDay), "quiet")
        }
        if (config.dailyMax > 0 && countToday >= config.dailyMax) {
            return Decision.Wait(quietEndAfter(nowMillis, config, minuteOfDay), "daily-max")
        }
        if (nextDueAtMillis > 0L && nowMillis < nextDueAtMillis) {
            return Decision.Wait(nextDueAtMillis, "waiting")
        }
        // 没有待跟进事项，又不允许主动闲聊：不该凭空开口（对应上游 minMaterials 的保守性）。
        if (!hasOpenFollowUp && !config.allowSmallTalk) {
            return Decision.Wait(nowMillis + config.gapMinMillis, "no-topic")
        }
        val idle = nowMillis - lastUserSpeakAtMillis
        if (lastUserSpeakAtMillis > 0L && idle in 0 until config.idleThresholdMillis) {
            val remain = (config.idleThresholdMillis - idle).coerceAtLeast(MINUTE)
            return Decision.Wait(nowMillis + remain, "user-active")
        }
        return Decision.Run
    }

    /**
     * 下一次该在什么时候主动。
     * 上游规则原样保留：
     * - 距上次至少 gapMin~gapMax；
     * - 停机很久后只补一枪（2 分钟后），不攒一堆补发；
     * - 算出来落在免打扰就推到免打扰结束。
     */
    fun planNext(
        nowMillis: Long,
        config: CompanionConfig,
        minuteOfDay: (Long) -> Int,
        lastRunAtMillis: Long,
        countToday: Int,
        roll: Double,
    ): Long {
        val min = config.gapMinMillis.coerceAtLeast(5 * MINUTE)
        val max = config.gapMaxMillis.coerceAtLeast(min)
        val gap = min + (roll.coerceIn(0.0, 1.0) * (max - min)).toLong()
        val base = when {
            config.dailyMax > 0 && countToday >= config.dailyMax ->
                quietEndAfter(nowMillis, config, minuteOfDay)
            lastRunAtMillis > 0L -> {
                val at = lastRunAtMillis + gap
                if (at < nowMillis + 2 * MINUTE) nowMillis + 2 * MINUTE else at
            }
            else -> nowMillis + min
        }
        return if (inQuietHours(minuteOfDay(base), config)) quietEndAfter(base, config, minuteOfDay) else base
    }

    /**
     * 严格晚于 nowMillis 的下一个免打扰结束时刻。
     *
     * 关键区别（曾经写错导致"今天说够了"却没推到明天）：
     * - 现在就在免打扰里 -> 找到本次免打扰结束的那一刻；
     * - 现在不在免打扰里 -> 取**次日**的免打扰结束，而不是"现在"。
     * 没设免打扰时退回次日 00:05。
     */
    fun quietEndAfter(nowMillis: Long, config: CompanionConfig, minuteOfDay: (Long) -> Int): Long {
        if (!config.quietEnabled()) {
            val todayStart = nowMillis - nowMillis % DAY
            val next = todayStart + DAY + 5 * MINUTE
            return if (next > nowMillis) next else next + DAY
        }
        if (!inQuietHours(minuteOfDay(nowMillis), config)) {
            // 不在免打扰：从明天同一分钟继续找，跳过一整天的探测。
            return firstNonQuietFrom(nowMillis + DAY, config, minuteOfDay)
        }
        return firstNonQuietFrom(nowMillis + MINUTE, config, minuteOfDay)
    }

    /** 从 fromMillis 起逐分钟找第一个不在免打扰里的时刻；上限两天，够用且不会死循环。 */
    private fun firstNonQuietFrom(fromMillis: Long, config: CompanionConfig, minuteOfDay: (Long) -> Int): Long {
        var probe = fromMillis
        val limit = fromMillis + 2 * DAY
        while (probe <= limit) {
            if (!inQuietHours(minuteOfDay(probe), config)) return probe
            probe += MINUTE
        }
        return fromMillis
    }
}
