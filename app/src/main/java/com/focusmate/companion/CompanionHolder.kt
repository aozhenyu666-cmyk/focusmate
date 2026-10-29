package com.focusmate.companion

import android.app.Notification
import android.content.Context
import com.focusmate.notify.Notifier

/**
 * 全局单例持有者：Service、Activity、闹钟接收器都从这里拿同一个运行时实例，
 * 避免出现两个运行时同时往同一个 state.json 写（那就是数据竞争）。
 */
object CompanionHolder {

    const val NOTIFICATION_ID = 2001

    private var instance: CompanionRuntime? = null

    @Synchronized
    fun ensureStarted(context: Context): CompanionRuntime {
        instance?.let { return it }
        val speech = SpeechOutput(context)
        val runtime = CompanionRuntime(
            context = context.applicationContext,
            modelClient = OpenAiCompatibleClient { ModelSettings.load(context).config },
            speech = speech,
        )
        speech.init(object : SpeechOutput.Listener {
            override fun onStart(utteranceId: String) { }
            override fun onDone(utteranceId: String) { }
            override fun onError(utteranceId: String, message: String) {
                // 语音失败只影响语音；文字已经展示了，这里只留痕不弹窗。
                runtime.current()
            }
        })
        // 专注进行中就禁言：这里只读 GuardRuntime 的状态，不反向依赖它的逻辑。
        runtime.focusActive = {
            val session = com.focusmate.runtime.GuardRuntime.session()
            session != null && com.focusmate.core.SessionStates.isActive(session.state)
        }
        runtime.load()
        instance = runtime
        CompanionService.start(context.applicationContext)
        return runtime
    }

    fun runtime(): CompanionRuntime? = instance

    @Synchronized
    fun shutdown() {
        instance?.shutdown()
        instance = null
    }

    fun buildNotification(context: Context): Notification =
        Notification.Builder(context, Notifier.CH_PARTNER)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("伙伴在等你")
            .setContentText("到点会主动说一句；点开可以聊天")
            .setOngoing(true)
            .build()
}
