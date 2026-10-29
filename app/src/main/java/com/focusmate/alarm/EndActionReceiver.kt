package com.focusmate.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focusmate.runtime.GuardRuntime

/** 通知上的「紧急结束」。走与覆盖层同一个入口，保证只有一条结束路径。 */
class EndActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        GuardRuntime.install(context.applicationContext)
        if (intent?.action == ACTION_END) {
            GuardRuntime.endByUser("通知栏紧急结束")
        }
    }

    companion object {
        const val ACTION_END = "com.focusmate.action.END_SESSION"
    }
}
