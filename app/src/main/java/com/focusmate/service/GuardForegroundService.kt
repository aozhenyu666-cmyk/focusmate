package com.focusmate.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.focusmate.core.SessionStates
import com.focusmate.notify.Notifier
import com.focusmate.runtime.GuardRuntime

/**
 * 前台服务：同一次开机内负责「每秒核一次到期」和保活。
 *
 * 明确的能力边界：
 * - 它不保证在系统回收、强行停止后还能活着；
 * - 它不做任何"精确"承诺，只做 1 秒粒度的核对；
 * - 真正的到期判定读磁盘 + 墙钟，所以即使本服务被杀，闹钟或下次启动仍能纠正。
 */
class GuardForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            GuardRuntime.checkExpiry()
            val s = GuardRuntime.session()
            val active = s != null && SessionStates.isActive(s.state)
            if (active) handler.postDelayed(this, 1000L) else stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        GuardRuntime.install(applicationContext)
        val s = GuardRuntime.session()
        startForeground(Notifier.ID_GUARD, buildNotification())
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1000L)
        if (s == null || !SessionStates.isActive(s.state)) stopSelf()
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val n = Notification.Builder(this, Notifier.CH_GUARD)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("专注守护运行中")
            .setContentText("到期会自动解除；点开可查看状态")
            .setOngoing(true)
            .build()
        return n
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            val i = Intent(context, GuardForegroundService::class.java)
            try { context.startForegroundService(i) } catch (_: Throwable) { }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuardForegroundService::class.java))
        }
    }
}
