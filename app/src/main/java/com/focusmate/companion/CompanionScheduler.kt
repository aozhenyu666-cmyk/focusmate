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

    /** 下一个该醒的时刻：最近的待发事项，或已排好的下一次主动时间。没有就返回 null。 */
    fun nextWakeAt(state: CompanionState): Long? {
        val earliestPending = state.followUps
            .filter { it.status == FollowUpStatus.PENDING }
            .minOfOrNull { it.dueAtMillis }
        val scheduled = state.nextDueAtMillis.takeIf { it > 0L }
        return listOfNotNull(earliestPending, scheduled).minOrNull()
    }

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
