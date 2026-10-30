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

            // 主动伙伴也要在这一刻重新排上，否则重启后它就只剩「等用户再次打开」。
            // 与专注恢复互不影响：这里任何异常都不应该挡住上面的 GuardRuntime。
            runCatching {
                val runtime = com.focusmate.companion.CompanionHolder.ensureStarted(context.applicationContext)
                runtime.tick()
            }

            // 主动引擎必须在这里单独拉起：它不在伙伴链路上，
            // 少了这一行 ProactiveService 永远不会启动，「主动开口」等于没实现。
            // 单独一个 runCatching，它失败不影响上面的专注与伙伴恢复。
            runCatching {
                com.focusmate.proactive.ProactiveRuntime.ensureService(context.applicationContext)
            }
        }
    }
}
