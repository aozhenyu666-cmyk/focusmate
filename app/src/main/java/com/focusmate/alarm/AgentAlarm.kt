package com.focusmate.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.app.RemoteInput
import com.focusmate.runtime.AgentRuntime
import com.focusmate.service.AgentWorkService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 伙伴只用一个闹钟：指向「下一件到期的事」（提醒、回访、复盘或下一次可以闲聊的时刻）。
 * 每次状态变化都重排；闹钟只负责叫醒，到点做什么永远读盘判断。
 */
class AgentAlarm(private val context: Context) {

    private val am = context.getSystemService(AlarmManager::class.java)

    fun canExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am?.canScheduleExactAlarms() == true

    fun set(atMillis: Long) {
        val pi = pending()
        try {
            if (canExact()) am?.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            else am?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        } catch (t: Throwable) {
            am?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    fun cancel() {
        am?.cancel(pending())
    }

    private fun pending(): PendingIntent = PendingIntent.getBroadcast(
        context, 20,
        Intent(context, AgentAlarmReceiver::class.java).setAction(AgentAlarmReceiver.ACTION_WAKE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** 闹钟到点：交给前台服务处理（模型调用可能要十几秒）；服务起不来时就地处理，限时 9 秒。 */
class AgentAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        AgentRuntime.install(context.applicationContext)
        if (!AgentWorkService.start(context, AgentWorkService.Job.ProcessDue)) {
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                try {
                    withTimeoutOrNull(9_000L) { AgentRuntime.processDue() }
                } finally {
                    AgentRuntime.reschedule()
                    pending.finish()
                }
            }
        }
    }

    companion object {
        const val ACTION_WAKE = "com.focusmate.action.AGENT_WAKE"
    }
}

/** 通知上的「回复」与「安静 2 小时」。 */
class AgentActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        AgentRuntime.install(context.applicationContext)
        when (intent?.action) {
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim()
                if (!text.isNullOrEmpty()) {
                    AgentRuntime.showWaitingInNotification(text)
                    if (!AgentWorkService.start(context, AgentWorkService.Job.Reply(text))) {
                        AgentRuntime.sendUser(text, fromNotification = true)
                    }
                }
            }
            ACTION_QUIET -> AgentRuntime.quietFor(2 * 60 * 60_000L)
        }
    }

    companion object {
        const val ACTION_REPLY = "com.focusmate.action.AGENT_REPLY"
        const val ACTION_QUIET = "com.focusmate.action.AGENT_QUIET"
        const val KEY_TEXT = "text"
    }
}
