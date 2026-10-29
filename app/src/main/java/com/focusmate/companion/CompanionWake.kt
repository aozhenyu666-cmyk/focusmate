package com.focusmate.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 主动伙伴自己的唤醒入口。
 *
 * 为什么必须有它（云端复测指出）：之前文档说「有 AlarmManager 与开机恢复兜底」，
 * 但实际 ExpiryScheduler / ExpiryReceiver / BootReceiver 只服务 GuardRuntime，
 * 主动事项其实只靠前台服务的 60 秒 tick。前台服务被杀就再也不会醒。
 *
 * 这里补上：
 *  - COMPANION_CHECK：闹钟或开机后触发一次 tick，并把 tick 链重新拉起来；
 *  - COMPANION_RESCHEDULE：事项变化后重排下一次唤醒（由运行时调用）。
 *
 * 仍然不承诺的行为（和专注到期一致）：强行停止后收不到任何广播，必须重开 APP。
 */
class CompanionWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != ACTION_CHECK && action != ACTION_RESCHEDULE) return
        val app = context.applicationContext
        // 先确保运行时起来（会顺带拉前台服务），再让它按磁盘状态重新判定。
        val runtime = CompanionHolder.ensureStarted(app)
        runtime.tick()
        val s = runtime.current()
        CompanionScheduler.scheduleAt(
            app,
            WakePlanner.plan(
                state = s,
                nowMillis = System.currentTimeMillis(),
                minuteOfDay = { millis ->
                    val c = java.util.Calendar.getInstance().apply { timeInMillis = millis }
                    c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
                },
                inFocusSession = runtime.focusActive(),
            ),
        )
    }

    companion object {
        const val ACTION_CHECK = "com.focusmate.action.COMPANION_CHECK"
        const val ACTION_RESCHEDULE = "com.focusmate.action.COMPANION_RESCHEDULE"
    }
}
