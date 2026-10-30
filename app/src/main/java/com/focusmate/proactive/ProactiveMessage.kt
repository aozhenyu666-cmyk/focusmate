package com.focusmate.proactive

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 一条主动消息的状态机。
 *
 * 关键设计：把「生成成功」「通知发布成功」「界面展示」「用户已读」拆成四个独立状态。
 * 绝不假定发布 = 已读——旧实现只有一个 boolean，无法回答「到底送到哪一步」。
 */
@Serializable
data class ProactiveMessage(
    @SerialName("id") val id: String,
    @SerialName("text") val text: String,
    @SerialName("createdAt") val createdAt: Long,
    /** 为什么开口：随机/情境/约定/追问。 */
    @SerialName("trigger") val trigger: String,
    /** 触发理由，写给人看，也进提示词。 */
    @SerialName("reason") val reason: String,
    @SerialName("state") val state: MessageState = MessageState.QUEUED,
    /** 通知 ID（每条唯一，避免互相覆盖）。 */
    @SerialName("notificationId") val notificationId: Int,
    @SerialName("attempts") val attempts: Int = 0,
    @SerialName("lastAttemptAt") val lastAttemptAt: Long = 0L,
    @SerialName("generatedAt") val generatedAt: Long? = null,
    @SerialName("notifiedAt") val notifiedAt: Long? = null,
    @SerialName("shownAt") val shownAt: Long? = null,
    @SerialName("ttsAt") val ttsAt: Long? = null,
    @SerialName("ttsError") val ttsError: String? = null,
    @SerialName("readAt") val readAt: Long? = null,
    @SerialName("error") val error: String? = null,
)

@Serializable
enum class MessageState {
    /** 已排入队列，等待生成。 */
    @SerialName("queued") QUEUED,
    /** 模型生成中。 */
    @SerialName("generating") GENERATING,
    /** 生成成功，文本已落盘。 */
    @SerialName("generated") GENERATED,
    /** 通知已发布（不代表用户看到）。 */
    @SerialName("notified") NOTIFIED,
    /** 界面已展示（不代表已读）。 */
    @SerialName("shown") SHOWN,
    /** 用户确实看/回了（唯一能声称送达的状态）。 */
    @SerialName("read") READ,
    /** 生成或投递失败。 */
    @SerialName("failed") FAILED,
    /** 被主动丢弃（过期/取消/审查拦下）。 */
    @SerialName("dropped") DROPPED,
    ;

    val terminal: Boolean
        get() = this == READ || this == FAILED || this == DROPPED
}
