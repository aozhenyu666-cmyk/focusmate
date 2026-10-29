package com.focusmate.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import com.focusmate.agent.ChatEntry
import com.focusmate.agent.Role
import com.focusmate.alarm.AgentActionReceiver
import com.focusmate.ui.MainActivity

/**
 * 伙伴的出口：一条会话通知（可以直接在通知里回复），提醒各自单独一条。
 * 通知发出去不等于用户看到了，这里只负责发；是否回应由聊天记录判断。
 */
class AgentNotifier(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager?.createNotificationChannel(
            NotificationChannel(CH_CHAT, "伙伴消息", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "伙伴主动找你、回复你的消息"
            },
        )
        manager?.createNotificationChannel(
            NotificationChannel(CH_REMINDER, "提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "你让伙伴设的提醒，准点弹出"
            },
        )
        manager?.createNotificationChannel(
            NotificationChannel(CH_WORK, "后台处理", NotificationManager.IMPORTANCE_MIN).apply {
                description = "伙伴在后台处理到期事项时短暂显示"
                setShowBadge(false)
            },
        )
    }

    fun enabled(): Boolean = manager?.areNotificationsEnabled() == true

    /** 会话通知：带最近几条消息，可直接回复。 */
    fun postConversation(name: String, recent: List<ChatEntry>, waiting: Boolean = false) {
        val me = Person.Builder().setName("我").build()
        val bot = Person.Builder().setName(name).build()
        val style = Notification.MessagingStyle(me).setConversationTitle(name)
        recent.filter { it.role != Role.EVENT }.takeLast(5).forEach {
            style.addMessage(it.text, it.atMillis, if (it.role == Role.USER) me else bot)
        }
        if (waiting) style.addMessage("……", System.currentTimeMillis(), bot)

        val reply = Notification.Action.Builder(
            null, "回复",
            PendingIntent.getBroadcast(
                context, 11,
                Intent(context, AgentActionReceiver::class.java).setAction(AgentActionReceiver.ACTION_REPLY),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            ),
        ).addRemoteInput(RemoteInput.Builder(AgentActionReceiver.KEY_TEXT).setLabel("回 $name").build()).build()

        val quiet = Notification.Action.Builder(
            null, "安静 2 小时",
            PendingIntent.getBroadcast(
                context, 12,
                Intent(context, AgentActionReceiver::class.java).setAction(AgentActionReceiver.ACTION_QUIET),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()

        val n = Notification.Builder(context, CH_CHAT)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setStyle(style)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setOnlyAlertOnce(waiting)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .addAction(reply)
            .addAction(quiet)
            .build()
        manager?.notify(ID_CHAT, n)
    }

    fun postReminder(name: String, entry: ChatEntry) {
        val n = Notification.Builder(context, CH_REMINDER)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("$name 提醒你")
            .setContentText(entry.text)
            .setStyle(Notification.BigTextStyle().bigText(entry.text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        manager?.notify(ID_REMINDER_BASE + Math.floorMod((entry.agendaId ?: entry.id).hashCode(), 1000), n)
    }

    fun clearConversation() = manager?.cancel(ID_CHAT)

    fun workNotification(): Notification =
        Notification.Builder(context, CH_WORK)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle("正在处理到期的事项")
            .build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 13,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CH_CHAT = "companion"
        const val CH_REMINDER = "reminder"
        const val CH_WORK = "agent_work"
        const val ID_CHAT = 2001
        const val ID_WORK = 2002
        const val ID_REMINDER_BASE = 3000
    }
}
