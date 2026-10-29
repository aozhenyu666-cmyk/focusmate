package com.focusmate.companion

/**
 * 下一次唤醒该排在什么时候。纯函数，可 JVM 单测。
 *
 * 为什么单独抽出来（二次复核 R-04）：
 * 原来的 CompanionScheduler.nextWakeAt 只看「最早的 PENDING 事项」和全局 nextDueAtMillis，
 * 完全不看 SchedulePolicy 算出来的等待结果。于是在免打扰、专注中、关闭、次数上限这几种情况下，
 * 它照样把一个已经过去的时刻排给 AlarmManager；Receiver 又调 tick + scheduleNext，
 * 就变成反复排过期闹钟（闹钟一响就立即再响）。
 *
 * 现在规则集中在这一个函数里，tick 只负责把结果交给 Scheduler：
 * - 关闭 / 没有待发事项  -> null（取消闹钟，不留无意义的唤醒）
 * - 专注中               -> 过一会儿再查（专注结束后才可能开口）
 * - 免打扰               -> 排到免打扰结束
 * - 今日次数已满         -> 排到免打扰结束（次日起点）
 * - 正常                 -> 排到最早那条事项的到期时刻，且不得早于现在
 */
object WakePlanner {

    const val MINUTE = 60_000L

    /** 返回 null 表示不该排任何闹钟。 */
    fun plan(
        state: CompanionState,
        nowMillis: Long,
        minuteOfDay: (Long) -> Int,
        inFocusSession: Boolean,
    ): Long? {
        val config = state.config
        if (!config.enabled) return null

        // 只把「还没发出去」的事项算进唤醒：等回应/已完成/已取消都不该再唤醒。
        val earliest = state.followUps
            .filter { it.status == FollowUpStatus.PENDING }
            .minOfOrNull { it.dueAtMillis }
            ?: return null

        if (inFocusSession) return nowMillis + SchedulePolicy.FOCUS_RECHECK

        if (SchedulePolicy.inQuietHours(minuteOfDay(nowMillis), config)) {
            return SchedulePolicy.quietEndAfter(nowMillis, config, minuteOfDay)
        }

        if (config.dailyMax > 0 && state.countToday >= config.dailyMax) {
            return SchedulePolicy.quietEndAfter(nowMillis, config, minuteOfDay)
        }

        // 关键：不能返回过去。已经到期就立刻唤醒一次。
        return earliest.coerceAtLeast(nowMillis)
    }
}
