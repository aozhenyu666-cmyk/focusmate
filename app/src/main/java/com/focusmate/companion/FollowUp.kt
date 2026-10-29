package com.focusmate.companion

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 待跟进事项。对应 dsh-world 的 Alarm 与 SurfCore purpose-engine 的 pending 两条线的合并：
 * dsh-world 给"什么时候响 + 新约定替换旧约定"，SurfCore 给"为什么值得开口 + 依据"。
 *
 * 每个事项都必须能回答三个问题，缺一个就不该存在：
 * 1. 来源是什么（用户约定 / 对话里没说完 / 人格兴趣）
 * 2. 依据是哪句真实内容（basis 指向真实 utterance 或 fact，不能空）
 * 3. 什么时候该说（dueAtMillis）
 */
@Serializable
data class FollowUp(
    @SerialName("id") val id: String,
    @SerialName("source") val source: FollowUpSource,
    /** 开口的理由，写给人看，也进提示词。 */
    @SerialName("basis") val basis: String,
    /** 依据指向的真实内容；没有依据的事项不允许创建。 */
    @SerialName("evidence") val evidence: String,
    /** 依据来自哪一句对话。用户忘掉那句话时，这条事项也要作废。 */
    @SerialName("evidenceUtteranceId") val evidenceUtteranceId: String? = null,
    /** 关联话题，一句话，用于让新旧事项能对上同一个话题。 */
    @SerialName("topic") val topic: String,
    @SerialName("dueAtMillis") val dueAtMillis: Long,
    @SerialName("status") val status: FollowUpStatus = FollowUpStatus.PENDING,
    /** 版本：改期/取消/更正都 +1；旧的异步结果凭这个失效。 */
    @SerialName("version") val version: Int = 1,
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("updatedAt") val updatedAt: Long,
    @SerialName("attempts") val attempts: Int = 0,
    @SerialName("lastAttemptAt") val lastAttemptAt: Long = 0L,
    /** 去重键：同一话题的约定只保留一条，避免新旧提醒并存。 */
    @SerialName("dedupeKey") val dedupeKey: String,
) {
    /** 还需要处理的事项（未到期/在投递/等回应）。DONE/CANCELLED/EXPIRED/FAILED 都不算。 */
    val open: Boolean get() = status == FollowUpStatus.PENDING ||
        status == FollowUpStatus.DELIVERING ||
        status == FollowUpStatus.AWAITING_REPLY
}

@Serializable
enum class FollowUpSource {
    /** 用户明确约定的时间（"十分钟后再聊"）。 */
    @SerialName("user_agreement") USER_AGREEMENT,

    /** 对话里没说完、明确表示之后再说的事。 */
    @SerialName("unfinished_topic") UNFINISHED_TOPIC,

    /** 用户要求的主动闲聊：必须有真实旧话题或人格兴趣作为依据。 */
    @SerialName("persona_interest") PERSONA_INTEREST,
}

@Serializable
enum class FollowUpStatus {
    @SerialName("pending") PENDING,

    /** 正在生成/投递。用于防并发重复投递。 */
    @SerialName("delivering") DELIVERING,

    /**
     * 已经真的说出去了，正在等用户回应。
     * 与 DONE 分开是因为「说过了」和「这件事了结了」不是一回事：
     * 只有这一状态才会被"用户回应"结算，未来还没到期的约定不受影响（F-03）。
     */
    @SerialName("awaiting_reply") AWAITING_REPLY,

    /** 已经发出并已被回应（或用户明确表示知道了）。 */
    @SerialName("done") DONE,

    /** 用户取消或改期后作废。 */
    @SerialName("cancelled") CANCELLED,

    /** 过了有效期仍未处理。 */
    @SerialName("expired") EXPIRED,

    /** 生成或投递连续失败到上限。 */
    @SerialName("failed") FAILED,
}
