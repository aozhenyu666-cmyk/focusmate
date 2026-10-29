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
        val partner = NotificationChannel(CH_PARTNER, "伙伴来信", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "伙伴主动发来的话"
        }
        manager.createNotificationChannel(guard)
        manager.createNotificationChannel(result)
        manager.createNotificationChannel(partner)
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

    /**
     * 主动发言的通知。文案里只放正文，不做"已送达/已读"的暗示。
     * 返回是否发布成功，调用方据此记录"文字展示"这一步。
     */
    fun companionSays(text: String): Boolean = companionSays(text, ID_PARTNER)

    /**
     * 主动消息通知（可指定 ID）。
     *
     * 为什么必须能指定 ID：旧实现所有伙伴消息共用一个固定 ID（1003），
     * 连续两条时后一条会覆盖前一条——用户只看到最后一句，高频场景等于丢消息。
     */
    fun companionSays(text: String, notificationId: Int): Boolean = try {
        val open = PendingIntent.getActivity(
            context, 3, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, CH_PARTNER)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("伙伴")
            .setContentText(text.take(60))
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        manager.notify(notificationId, n)
        true
    } catch (t: Throwable) {
        false
    }

    /** 通知是否可用。被撤销权限时主动引擎要降级，而不是静默丢消息。 */
    fun canNotify(): Boolean = manager.areNotificationsEnabled()

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
        const val CH_PARTNER = "companion_message"
        const val ID_GUARD = 1001
        const val ID_RESULT = 1002
        const val ID_PARTNER = 1003
    }
}
