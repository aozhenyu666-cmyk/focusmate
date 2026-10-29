package com.focusmate.companion

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 主动事项的闹钟兜底。
 *
 * 与 ExpiryScheduler 的关系：两者互不复用。
 * 专注到期是「必须尽快解除限制」，主动事项是「到点说句话」，容错不同，
 * 所以各自持有一个 PendingIntent（不同 requestCode），不会互相取消。
 *
 * 边界照实说：
 * - 有精确闹钟权限就用 setExactAndAllowWhileIdle，否则退到 setAndAllowWhileIdle，可能迟到；
 * - Doze / 厂商省电仍可能推迟；
 * - 迟到不是错误，tick 会按磁盘上的 dueAtMillis 重新判断。
 */
object CompanionScheduler {

    private const val REQUEST_CODE = 20

    /**
     * 按 WakePlanner 的结果排闹钟（R-04：必须遵守策略，不能把过去的时刻排进去）。
     * @param wakeAtMillis 传 null 表示取消。
     */
    fun scheduleAt(context: Context, wakeAtMillis: Long?) {
        if (wakeAtMillis == null) { cancel(context); return }
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context)
        try {
            if (canScheduleExact(manager)) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeAtMillis, pi)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeAtMillis, pi)
            }
        } catch (t: Throwable) {
            // 排不上就靠前台服务 tick；不静默假定成功，但也不崩。
        }
    }

    fun scheduleNext(context: Context, state: CompanionState) {
        val due = nextWakeAt(state) ?: run { cancel(context); return }
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context)
        try {
            if (canScheduleExact(manager)) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pi)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pi)
            }
        } catch (t: Throwable) {
            // 排不上就靠前台服务 tick；不静默假定成功，但也不崩。
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }

    /**
     * 下一个该醒的时刻：只看**还没发出去**的事项。
     *
     * 不再掺 state.nextDueAtMillis（R-04）：那是"上次排程留下的全局时间"，
     * 事项取消/失败/空队列时它会残留在过去，导致反复排过期闹钟。
     * 需要按策略修正的排程请用 WakePlanner.plan + scheduleAt。
     */
    fun nextWakeAt(state: CompanionState): Long? = state.followUps
        .filter { it.status == FollowUpStatus.PENDING }
        .minOfOrNull { it.dueAtMillis }

    private fun canScheduleExact(manager: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, CompanionWakeReceiver::class.java).apply {
            action = CompanionWakeReceiver.ACTION_CHECK
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
