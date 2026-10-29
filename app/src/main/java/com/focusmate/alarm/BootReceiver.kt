package com.focusmate.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focusmate.runtime.GuardRuntime

/**
 * 重启与覆盖安装后的恢复入口。
 * 注意：Android 的「强行停止」之后不会收到任何广播，这条通道对那种情况无效；
 * 那种情况必须靠用户重新打开 APP，UI 上会明确写出来，不承诺后台自动恢复。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            GuardRuntime.install(context.applicationContext)
            GuardRuntime.checkExpiry()
            // 开机后系统闹钟全部清空：伙伴重排闹钟，并补处理关机期间到期的提醒。
            com.focusmate.runtime.AgentRuntime.install(context.applicationContext)
            com.focusmate.runtime.AgentRuntime.reschedule()
            com.focusmate.service.AgentWorkService.start(context, com.focusmate.service.AgentWorkService.Job.ProcessDue)
        }
    }
}
