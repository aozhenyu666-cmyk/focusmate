package com.focusmate.proactive

import android.content.Context
import android.util.Log
import com.focusmate.notify.Notifier

/**
 * 独立消息投递器。**本次最高优先级模块**。
 *
 * 旧实现的问题（真实故障）：
 * `onUtterance` 回调只在 CompanionScreen 的 LaunchedEffect 里绑定。
 * 也就是说：如果用户从没打开过伙伴页，或者打开后又退出/被回收，
 * 后台生成的消息**不会发通知**——生成成功了，但没人看得到。
 * 这就是「主动交流不生效」的直接原因之一。
 *
 * 本类的约束：
 * 1. 不依赖 Activity / Compose / 任何 UI 回调；
 * 2. 先落盘再发通知（崩溃后仍可恢复并知道发过什么）；
 * 3. 每条消息独立通知 ID，连续多条不会互相覆盖；
 * 4. 通知失败与 TTS 失败都只影响自己，不影响文本已经落盘；
 * 5. 明确区分「生成成功 / 通知发布 / 界面展示 / 用户已读」。
 */
class MessageDispatcher(
    private val context: Context,
    private val store: ProactiveStore,
    private val notifier: Notifier,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** 可选语音通道。失败只留痕，绝不影响通知与文本。 */
    private val speak: ((String, String) -> Boolean)? = null,
) {

    /** 供非 UI 的观察者（例如日志/测试）使用；不影响投递。 */
    var onDelivered: ((ProactiveMessage) -> Unit)? = null

    /**
     * 投递一条已经生成好的消息。
     *
     * 顺序固定：落盘 -> 通知 -> 语音 -> 更新状态。任何一步失败都不会回滚已落盘的内容。
     * @return 更新后的消息（state 反映真正到了哪一步）
     */
    fun dispatch(state: ProactiveState, text: String, trigger: String, reason: String): Pair<ProactiveState, ProactiveMessage> {
        val t = now()
        val notifId = state.nextNotificationId
        var msg = ProactiveMessage(
            id = "pm-" + t + "-" + notifId,
            text = text,
            createdAt = t,
            trigger = trigger,
            reason = reason,
            state = MessageState.GENERATED,
            notificationId = notifId,
            generatedAt = t,
        )

        // 1) 先落盘：即使下一步就崩，也知道「生成过这句、还没通知」。
        var next = state.copy(
            nextNotificationId = notifId + 1,
            pending = state.pending + msg,
        )
        runCatching { store.write(next) }.onFailure { Log.w(TAG, "pending 落盘失败: " + it.message) }

        // 2) 通知。这一步与 UI 完全无关。
        val notified = runCatching { notifier.companionSays(text, notifId) }.getOrElse { false }
        msg = if (notified) {
            msg.copy(state = MessageState.NOTIFIED, notifiedAt = now())
        } else {
            // 通知被禁用/失败：文本仍在 pending 里，标记失败原因但**不丢弃**。
            msg.copy(state = MessageState.FAILED, error = "通知发布失败（可能被禁用通知权限）")
        }

        // 3) 语音：可选，失败只留痕。
        val speaker = speak
        if (msg.state == MessageState.NOTIFIED && speaker != null) {
            val ok = runCatching { speaker(msg.id, text) }.getOrElse { false }
            msg = if (ok) msg.copy(ttsAt = now()) else msg.copy(ttsError = "语音播放失败，文字已发布")
        }

        // 4) 从 pending 移到 recent，并结算计数。
        next = next.copy(
            pending = next.pending.filterNot { it.id == msg.id },
            recent = (next.recent + msg).takeLast(MAX_RECENT),
            lastSentAtMillis = if (msg.state == MessageState.NOTIFIED) now() else next.lastSentAtMillis,
            sentToday = if (msg.state == MessageState.NOTIFIED) next.sentToday + 1 else next.sentToday,
            windowCount = if (msg.state == MessageState.NOTIFIED) next.windowCount + 1 else next.windowCount,
        )
        runCatching { store.write(next) }.onFailure { Log.w(TAG, "recent 落盘失败: " + it.message) }

        onDelivered?.invoke(msg)
        return next to msg
    }

    /**
     * 标记用户已读/已回。
     * 这是**唯一**能声称「送达」的状态：通知发布 != 用户看到。
     */
    fun markRead(state: ProactiveState, messageId: String): ProactiveState {
        val t = now()
        val next = state.copy(
            recent = state.recent.map { if (it.id == messageId) it.copy(state = MessageState.READ, readAt = t) else it },
            // 用户回应了：沉默计数归零，互动积极度上调。
            silenceStreak = 0,
            engagement = (state.engagement + 0.15).coerceAtMost(1.0),
        )
        runCatching { store.write(next) }
        return next
    }

    /**
     * 用户没有任何回应时结算一次沉默：退避会因此生效。
     * 由 InteractionPolicy 在检测到「上一条发出后超过回应窗口」时调用。
     */
    fun markUnanswered(state: ProactiveState): ProactiveState {
        val next = state.copy(
            silenceStreak = state.silenceStreak + 1,
            engagement = (state.engagement - 0.1).coerceAtLeast(0.0),
        )
        runCatching { store.write(next) }
        return next
    }

    /** 页面打开时把「界面已展示」标出来（仍然不等于已读）。 */
    fun markShown(state: ProactiveState, ids: List<String>): ProactiveState {
        if (ids.isEmpty()) return state
        val t = now()
        val set = ids.toSet()
        val next = state.copy(
            recent = state.recent.map {
                if (it.id in set && it.state == MessageState.NOTIFIED) it.copy(state = MessageState.SHOWN, shownAt = t) else it
            },
        )
        runCatching { store.write(next) }
        return next
    }

    companion object {
        private const val TAG = "MessageDispatcher"
        /** 历史保留条数。够查重与回溯，又不至于无限增长。 */
        const val MAX_RECENT = 60
    }
}
