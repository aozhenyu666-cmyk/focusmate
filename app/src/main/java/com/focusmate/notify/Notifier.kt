package com.focusmate.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.focusmate.R
import com.focusmate.core.FocusSession
import com.focusmate.ui.MainActivity

/**
 * 通知。刻意区分三类：
 * - guardRunning：进行中常驻通知（带紧急结束入口）；
 * - sessionEnded：到期恢复完成；
 * - failure：失败与结果未知。
 * 发布通知不等于用户已读，这点不写在文案里假装。
 */
class Notifier(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        ensureChannels()
    }

    private fun ensureChannels() {
        val guard = NotificationChannel(CH_GUARD, "专注守护", NotificationManager.IMPORTANCE_LOW).apply {
            description = "专注进行中的常驻状态"
            setShowBadge(false)
        }
        val result = NotificationChannel(CH_RESULT, "专注结果", NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(guard)
        manager.createNotificationChannel(result)
    }

    fun guardRunning(session: FocusSession) {
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val end = PendingIntent.getBroadcast(
            context, 2,
            Intent(context, com.focusmate.alarm.EndActionReceiver::class.java).setAction(com.focusmate.alarm.EndActionReceiver.ACTION_END),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, CH_GUARD)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(context.getString(R.string.notif_guard_title))
            .setContentText("到 ${clockText(session.expiresAtMillis)} 自动结束")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.notif_action_end), end).build())
            .build()
        manager.notify(ID_GUARD, n)
    }

    fun clearGuard() = manager.cancel(ID_GUARD)

    fun sessionEnded(session: FocusSession) {
        manager.cancel(ID_GUARD)
        val n = Notification.Builder(context, CH_RESULT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("专注已结束")
            .setContentText("限制已解除。会话记录已保留。")
            .setAutoCancel(true)
            .build()
        manager.notify(ID_RESULT, n)
    }

    fun failure(title: String, body: String) {
        val n = Notification.Builder(context, CH_RESULT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        manager.notify(ID_RESULT, n)
    }

    private fun clockText(millis: Long): String {
        val d = java.util.Date(millis)
        val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return f.format(d)
    }

    companion object {
        const val CH_GUARD = "focus_guard"
        const val CH_RESULT = "focus_result"
        const val ID_GUARD = 1001
        const val ID_RESULT = 1002
    }
}
