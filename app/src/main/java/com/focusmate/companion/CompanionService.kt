package com.focusmate.companion

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * 主动伙伴的调度宿主。
 *
 * 诚实说明 Android 侧的条件（执行指令 §7 任务 2.2 要求）：
 * - 这里用前台服务 + 60 秒 tick。没有用 WorkManager：它的最小周期是 15 分钟，
 *   对"到点主动说话"粒度太粗，而且同样不保证准点。
 * - 前台服务能显著提高存活率，但**仍不保证**在省电白名单外准点唤醒；
 *   息屏、Doze、厂商后台清理都可能让 tick 迟到。
 * - 冷启动路径：AlarmManager 的到期闹钟 + 开机接收器会重新唤起一次检查，
 *   所以"迟到"最终会被纠正，只是可能晚几分钟。
 * - 强行停止后不会恢复（Android 不给任何广播），这点在 UI 上如实写。
 */
class CompanionService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            CompanionHolder.runtime()?.tick()
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CompanionHolder.ensureStarted(applicationContext)
        startForeground(CompanionHolder.NOTIFICATION_ID, CompanionHolder.buildNotification(this))
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MILLIS)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    companion object {
        /**
         * 60 秒 tick。取这个值的原因：比 WorkManager 的 15 分钟细得多，
         * 又不至于像 1 秒那样明显耗电；真正的准点由 AlarmManager 兜底。
         */
        const val TICK_MILLIS = 60_000L

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, CompanionService::class.java))
            } catch (t: Throwable) {
                // 后台启动被系统限制时不要崩，下一次前台时还会再起。
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CompanionService::class.java))
        }
    }
}
