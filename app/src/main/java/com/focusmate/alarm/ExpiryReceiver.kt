package com.focusmate.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focusmate.runtime.GuardRuntime

/**
 * 到期闹钟入口。这里只做「让运行时重新核对一次真实状态」，
 * 不自己判断是否该解除：判断永远读持久化截止时间，避免闹钟串话。
 */
class ExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        GuardRuntime.install(context.applicationContext)
        GuardRuntime.checkExpiry()
    }

    companion object {
        const val ACTION_CHECK = "com.focusmate.action.CHECK_EXPIRY"
        const val EXTRA_SESSION_ID = "session_id"
    }
}
