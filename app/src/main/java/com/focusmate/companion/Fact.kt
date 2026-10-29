package com.focusmate.companion

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 一条事实。对应 SurfCore memory-card.js 的 record，
 * 但只保留一期需要的部分：来源、重要度、状态、版本关系。
 *
 * 关键约束（SurfCore 更正/忘记语义的移植）：
 * - 事实有三种来源，界面与提示词都要能区分：用户说的、系统观察到的、模型推测的。
 * - 用户更正 -> 旧事实转 SUPERSEDED 并指向新事实；用户要求忘记 -> RETRACTED。
 * - 只有 ACTIVE 能进入上下文；SUPERSEDED / RETRACTED 永远不出现，也不会通过摘要复活。
 */
@Serializable
data class Fact(
    @SerialName("id") val id: String,
    @SerialName("text") val text: String,
    @SerialName("source") val source: FactSource,
    /** 来源引用：哪一句用户话、哪次会话；没有来源就必须写 SYSTEM/INFERRED 并说明。 */
    @SerialName("sourceRef") val sourceRef: String? = null,
    /**
     * 这条事实是从哪一句对话推出来的（utterance id）。
     * 存在的唯一理由：用户要求忘掉/更正时，必须能精确找到**那一句话**，
     * 把它从模型输入里排除，而不是全局删一个词（F-02）。
     */
    @SerialName("sourceUtteranceId") val sourceUtteranceId: String? = null,
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("updatedAt") val updatedAt: Long,
    @SerialName("status") val status: FactStatus = FactStatus.ACTIVE,
    /** 被哪条事实取代。 */
    @SerialName("supersededBy") val supersededBy: String? = null,
    /** 重要度 1-100，移植 SurfCore scoreMemory 的核心项（主人说的、情绪、约定、矛盾）。 */
    @SerialName("importance") val importance: Int = 20,
    @SerialName("tags") val tags: List<String> = emptyList(),
    /** 被再次提到的次数（复习效应），只影响排序，不改内容。 */
    @SerialName("recallCount") val recallCount: Int = 0,
) {
    val active: Boolean get() = status == FactStatus.ACTIVE
}

@Serializable
enum class FactSource {
    /** 用户明确说过的。 */
    @SerialName("user_stated") USER_STATED,

    /** 系统观察到的事实（例如会话真的跑完了 10 分钟）。 */
    @SerialName("system_observed") SYSTEM_OBSERVED,

    /** 模型推测。必须标出来，不能当作用户说过的话。 */
    @SerialName("model_inferred") MODEL_INFERRED,
}

@Serializable
enum class FactStatus {
    @SerialName("active") ACTIVE,

    /** 被用户更正或新事实取代。 */
    @SerialName("superseded") SUPERSEDED,

    /** 用户要求忘掉。保留墓碑以免被重新推导出来。 */
    @SerialName("retracted") RETRACTED,
}

/** 来源标签，给界面和提示词看，不能省。 */
fun FactSource.label(): String = when (this) {
    FactSource.USER_STATED -> "你说的"
    FactSource.SYSTEM_OBSERVED -> "我看到的"
    FactSource.MODEL_INFERRED -> "我猜的"
}
