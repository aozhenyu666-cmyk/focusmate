package com.focusmate.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 到期调度。这里刻意不假装存在精确定时器：
 *
 * - 优先用 setExactAndAllowWhileIdle；没有精确闹钟权限时退回 setAndAllowWhileIdle（可能迟到）；
 * - 闹钟只是"尽快跑一次 checkExpiry"的提醒，真正的判定永远读盘比对墙钟，
 *   所以闹钟迟到或丢失都不会造成"该恢复却没恢复"以外的状态错误；
 * - 前台服务每秒 tick 作为同一次开机内的主通道，闹钟只兜底息屏与进程被杀。
 */
class ExpiryScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    var lastError: String? = null
        private set

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager?.canScheduleExactAlarms() == true

    fun schedule(expiresAtMillis: Long, sessionId: String) {
        val pi = pendingIntent(sessionId)
        val triggerAt = expiresAtMillis
        try {
            if (canScheduleExact()) {
                alarmManager?.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                lastError = null
            } else {
                alarmManager?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                lastError = "没有精确闹钟权限，到期提醒可能迟到；请允许「闹钟和提醒」"
            }
        } catch (t: Throwable) {
            lastError = "安排到期闹钟失败：${t.javaClass.simpleName}: ${t.message}"
        }
    }

    fun cancel() {
        alarmManager?.cancel(pendingIntent(""))
    }

    private fun pendingIntent(sessionId: String): PendingIntent {
        val intent = Intent(context, ExpiryReceiver::class.java).apply {
            action = ExpiryReceiver.ACTION_CHECK
            putExtra(ExpiryReceiver.EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
