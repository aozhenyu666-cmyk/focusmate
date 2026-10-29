package com.focusmate.proactive

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.focusmate.companion.CompanionHolder
import com.focusmate.notify.Notifier

/**
 * 主动引擎的后台宿主服务。
 *
 * 为什么需要它（这是「不主动」的根因之二）：
 * 旧实现把投递挂在前台服务的 tick 上，而 tick 只有 CompanionService 起来才跑；
 * CompanionService 又是在 CompanionHolder.ensureStarted 里被拉起的。
 * 结果：只要用户没打开过伙伴页，整个主动链路根本不会启动。
 *
 * 本服务与 UI 解耦：开机/进程重建后也能被拉起，独立跑 tick。
 *
 * 诚实的边界：
 * - Android 的精确闹钟有频率限制，不能做到「每分钟强制唤醒」；
 * - 前台服务能显著提高存活率，但 Doze/厂商省电仍会推迟；
 * - 强行停止后收不到任何广播，必须用户重新打开。
 * 所以策略是：前台服务内高频 tick（默认 20 秒）+ 闹钟兜底，
 * 并且**承认可能迟到**，不假装准点。
 */
class ProactiveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var engine: ProactiveEngine? = null

    private val tick = object : Runnable {
        override fun run() {
            val e = engine ?: return
            try {
                val r = e.tick()
                if (r.fired) Log.i(TAG, "tick 触发：" + r.reason)
            } catch (t: Throwable) {
                // 单次 tick 失败不能让服务崩，否则一次异常就永久停止主动。
                Log.w(TAG, "tick 异常: " + (t.message ?: t.javaClass.simpleName))
            }
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        engine = ProactiveRuntime.engine(this)
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, Notifier.CH_GUARD)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("伙伴在后台")
            .setContentText("会按你设置的频率偶尔主动说句话")
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ProactiveService"

        /**
         * tick 间隔。20 秒的原因：
         * - 比每小时若干次的期望间隔细得多，能抓住机会窗口；
         * - 又不至于像 1 秒那样明显耗电；
         * - 真正的判定很便宜（纯内存计算），只有命中才调模型。
         */
        const val TICK_MILLIS = 20_000L
        const val NOTIFICATION_ID = 3101

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, ProactiveService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "启动失败: " + t.message)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProactiveService::class.java))
        }
    }
}
