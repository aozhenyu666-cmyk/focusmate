package com.focusmate.companion

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 主动交流的规则。默认值刻意保守，并且**这些默认值尚未经用户确认**（DECISIONS Q-05）。
 * 上游 SurfCore 的默认是白天每 2.5~5 小时最多一次、一天最多 4 次、23:30~08:30 不打扰；
 * 本项目不照抄，改成更少的次数，等用户确认后再调。
 */
@Serializable
data class CompanionConfig(
    /** 总开关。关掉后不快照、不调度、不生成。 */
    @SerialName("enabled") val enabled: Boolean = true,

    /** 是否需要"没有约定也主动闲聊"。Q-05 未确认，默认关闭。 */
    @SerialName("allowSmallTalk") val allowSmallTalk: Boolean = false,

    /** 两次主动之间至少/最多间隔（毫秒）。 */
    @SerialName("gapMinMillis") val gapMinMillis: Long = 90L * 60_000L,
    @SerialName("gapMaxMillis") val gapMaxMillis: Long = 240L * 60_000L,

    /** 每日上限，0 = 不限。 */
    @SerialName("dailyMax") val dailyMax: Int = 3,

    /** 免打扰时段（本地时间的分钟数）。两者相等表示不启用。 */
    @SerialName("quietStartMinute") val quietStartMinute: Int = 23 * 60,
    @SerialName("quietEndMinute") val quietEndMinute: Int = 8 * 60,

    /** 用户刚说过话多久内不打扰。 */
    @SerialName("idleThresholdMillis") val idleThresholdMillis: Long = 10L * 60_000L,

    /** 失败重试间隔与次数上限。 */
    @SerialName("retryMillis") val retryMillis: Long = 15L * 60_000L,
    @SerialName("maxAttempts") val maxAttempts: Int = 3,

    /** 事项有效期：超过就作废，不再补发。 */
    @SerialName("pendingTtlDays") val pendingTtlDays: Int = 14,

    /** 允许迟到多久仍算有效（超过就按"迟到"如实说明，不再假装准点）。 */
    @SerialName("lateToleranceMillis") val lateToleranceMillis: Long = 10L * 60_000L,
) {
    fun quietEnabled(): Boolean = quietStartMinute != quietEndMinute
}
