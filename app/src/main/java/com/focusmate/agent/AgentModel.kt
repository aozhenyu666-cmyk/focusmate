package com.focusmate.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 伙伴的全部持久状态放在一个文件里，一次变更一次原子写入。
 * 分多个文件会出现「提醒存上了、聊天没存上」这类半截状态，旧项目吃过这个亏。
 */
@Serializable
data class AgentState(
    @SerialName("chat") val chat: List<ChatEntry> = emptyList(),
    @SerialName("facts") val facts: List<Fact> = emptyList(),
    @SerialName("agenda") val agenda: List<AgendaItem> = emptyList(),
    @SerialName("proactive") val proactive: ProactiveState = ProactiveState(),
    /** 每次变更加一。异步生成开始时记下它，交付前用它判断有没有被新情况作废。 */
    @SerialName("revision") val revision: Long = 0,
)

@Serializable
enum class Role {
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,

    /** 系统观察到的事实，例如「专注已开始（已核验）」。不是任何一方说的话。 */
    @SerialName("event") EVENT,
}

@Serializable
enum class Origin {
    @SerialName("chat") CHAT,
    @SerialName("notification_reply") NOTIFICATION_REPLY,
    @SerialName("reminder") REMINDER,
    @SerialName("follow_up") FOLLOW_UP,
    @SerialName("focus_review") FOCUS_REVIEW,
    @SerialName("spontaneous") SPONTANEOUS,
    @SerialName("focus_event") FOCUS_EVENT,
}

@Serializable
data class ChatEntry(
    @SerialName("id") val id: String,
    @SerialName("role") val role: Role,
    @SerialName("text") val text: String,
    @SerialName("atMillis") val atMillis: Long,
    @SerialName("origin") val origin: Origin = Origin.CHAT,
    /** 由哪条安排触发（主动消息才有）。 */
    @SerialName("agendaId") val agendaId: String? = null,
    /** 伙伴提出的专注提议，用户点了才会开始。 */
    @SerialName("proposal") val proposal: FocusProposal? = null,
)

@Serializable
enum class FocusMode {
    /** 切到 Gemini，只放行 Gemini + 通话 + 微信。 */
    @SerialName("gemini") GEMINI,

    /** 留在专注伙伴里，只放行本 APP + 通话 + 微信。 */
    @SerialName("deep") DEEP,
}

@Serializable
enum class ProposalStatus {
    @SerialName("pending") PENDING,
    @SerialName("accepted") ACCEPTED,
    @SerialName("declined") DECLINED,
    @SerialName("failed") FAILED,
}

@Serializable
data class FocusProposal(
    @SerialName("id") val id: String,
    @SerialName("mode") val mode: FocusMode,
    @SerialName("minutes") val minutes: Int,
    @SerialName("goal") val goal: String,
    @SerialName("status") val status: ProposalStatus = ProposalStatus.PENDING,
    @SerialName("detail") val detail: String? = null,
)

@Serializable
enum class FactSource {
    /** 用户亲口说的。 */
    @SerialName("user_said") USER_SAID,

    /** 伙伴从对话里推断的，可能不对。 */
    @SerialName("inferred") INFERRED,
}

@Serializable
data class Fact(
    @SerialName("id") val id: String,
    @SerialName("text") val text: String,
    @SerialName("source") val source: FactSource,
    @SerialName("createdAtMillis") val createdAtMillis: Long,
    /** 来自哪条聊天，便于核对原话。 */
    @SerialName("sourceEntryId") val sourceEntryId: String? = null,
)

@Serializable
enum class AgendaKind {
    /** 用户明确要求的提醒：准点、必达，不依赖模型。 */
    @SerialName("reminder") REMINDER,

    /** 伙伴自己约的回访：到点由模型结合上下文开口。 */
    @SerialName("follow_up") FOLLOW_UP,

    /** 专注结束后的复盘。 */
    @SerialName("focus_review") FOCUS_REVIEW,
}

@Serializable
enum class AgendaStatus {
    @SerialName("pending") PENDING,
    @SerialName("delivered") DELIVERED,
    @SerialName("cancelled") CANCELLED,

    /** 过期太久或多次失败，放弃。保留记录，不再发。 */
    @SerialName("dropped") DROPPED,
}

@Serializable
data class AgendaItem(
    @SerialName("id") val id: String,
    @SerialName("kind") val kind: AgendaKind,
    /** 话题键：同键的待办只保留一条，新约定替换旧约定。 */
    @SerialName("key") val key: String,
    @SerialName("dueAtMillis") val dueAtMillis: Long,
    /** 提醒：要说的话。回访：要回来聊的话题。 */
    @SerialName("text") val text: String,
    /** 为什么要做这件事（回访的依据）。 */
    @SerialName("reason") val reason: String = "",
    @SerialName("createdAtMillis") val createdAtMillis: Long,
    @SerialName("status") val status: AgendaStatus = AgendaStatus.PENDING,
    @SerialName("attempts") val attempts: Int = 0,
    @SerialName("note") val note: String? = null,
    @SerialName("doneAtMillis") val doneAtMillis: Long? = null,
)

@Serializable
data class ProactiveState(
    /** 伙伴最近一次主动开口（不含回复用户）。 */
    @SerialName("lastSpokeAtMillis") val lastSpokeAtMillis: Long = 0,
    /** 用户最近一次说话。 */
    @SerialName("lastUserAtMillis") val lastUserAtMillis: Long = 0,
    /** 连续多少条主动消息没有得到回应，用于退避。 */
    @SerialName("unanswered") val unanswered: Int = 0,
    /** 当天（本地日期 yyyy-MM-dd）已主动闲聊的次数。 */
    @SerialName("dayKey") val dayKey: String = "",
    @SerialName("spokenToday") val spokenToday: Int = 0,
    /** 用户点了「安静一会」，到这个时刻前不闲聊、不回访（提醒照常）。 */
    @SerialName("quietUntilMillis") val quietUntilMillis: Long = 0,
    /** 上次考虑闲聊后选择不说或生成失败，到这个时刻前不再考虑，避免反复调用模型。 */
    @SerialName("spontaneousNotBeforeMillis") val spontaneousNotBeforeMillis: Long = 0,
)
