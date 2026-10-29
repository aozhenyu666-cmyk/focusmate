package com.focusmate.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.focusmate.notify.AgentNotifier
import com.focusmate.runtime.AgentRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * 短命的前台服务：闹钟到点或在通知里回复时，保证进程活到模型回完话。
 * 做完就停，不常驻。最长 90 秒，超时也停。
 */
class AgentWorkService : Service() {

    sealed interface Job {
        data object ProcessDue : Job
        data class Reply(val text: String) : Job
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AgentRuntime.install(applicationContext)
        val n = AgentRuntime.notifier().workNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(AgentNotifier.ID_WORK, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(AgentNotifier.ID_WORK, n)
        }
        val text = intent?.getStringExtra(EXTRA_REPLY)
        running.incrementAndGet()
        scope.launch {
            try {
                withTimeoutOrNull(90_000L) {
                    if (text != null) AgentRuntime.sendUserAndWait(text, fromNotification = true)
                    else AgentRuntime.processDue()
                }
            } finally {
                AgentRuntime.reschedule()
                if (running.decrementAndGet() == 0) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_REPLY = "reply"

        /** 返回是否成功启动；后台限制导致失败时调用方就地处理。 */
        fun start(context: Context, job: Job): Boolean {
            val i = Intent(context, AgentWorkService::class.java)
            if (job is Job.Reply) i.putExtra(EXTRA_REPLY, job.text)
            return try {
                context.startForegroundService(i)
                true
            } catch (t: Throwable) {
                false
            }
        }
    }
}
