package com.focusmate.proactive

import android.content.Context
import android.content.Intent
import com.focusmate.companion.CompanionHolder

/**
 * 全局单例：保证只有一个 ProactiveEngine 实例（两个实例会同时摇骰、重复发消息）。
 *
 * 与 CompanionHolder 的关系：这里**只借用**模型客户端，不依赖伙伴页是否被打开。
 */
object ProactiveRuntime {

    @Volatile private var instance: ProactiveEngine? = null

    @Synchronized
    fun engine(context: Context): ProactiveEngine {
        instance?.let { return it }
        val app = context.applicationContext
        // 借用伙伴运行时已经配置好的模型客户端（含 Key）。
        val companion = CompanionHolder.ensureStarted(app)
        val e = ProactiveEngine(
            context = app,
            modelClient = companion.modelClientForProactive(),
        )
        // 宿主状态：只读，不反向调用专注逻辑。
        e.hostState = {
            val snap = com.focusmate.runtime.GuardRuntime.session()
            val focusActive = snap != null && com.focusmate.core.SessionStates.isActive(snap.state)
            val focusEndedAgo = if (snap != null && com.focusmate.core.SessionStates.isTerminal(snap.state)) {
                (System.currentTimeMillis() - snap.expiresAtMillis).coerceAtLeast(0L)
            } else Long.MAX_VALUE
            ProactiveEngine.HostSnapshot(
                inFocusSession = focusActive,
                inCall = false,
                sinceFocusEndMillis = focusEndedAgo,
                dueSoonTopic = null,
                lastCompanionWasQuestion = false,
                sinceUserSpokeMillis = companion.millisSinceUserSpoke(),
                oldTopics = companion.recentTopicsForProactive(),
            )
        }
        e.hostSpeak = { id, text -> companion.speakForProactive(id, text) }
        e.load()
        instance = e
        return e
    }

    fun peek(): ProactiveEngine? = instance

    @Synchronized
    fun shutdown() { instance = null }

    /** 确保后台服务在跑。重复调用无害。 */
    fun ensureService(context: Context) {
        val app = context.applicationContext
        app.startForegroundService(Intent(app, ProactiveService::class.java))
    }
}
