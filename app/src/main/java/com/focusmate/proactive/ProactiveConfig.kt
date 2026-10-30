package com.focusmate.proactive

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 主动引擎配置。默认「活跃但可控」：每小时若干次机会，独立摇骰，不排队不补发。 */
@Serializable
data class ProactiveConfig(
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("mode") val mode: ProactiveMode = ProactiveMode.DAILY,
    @SerialName("paused") val paused: Boolean = false,
    @SerialName("hourlyOpportunities") val hourlyOpportunities: Int = 6,
    @SerialName("baseProbability") val baseProbability: Double = 0.5,
    @SerialName("minGapMillis") val minGapMillis: Long = 360_000L,
    @SerialName("burstWindowMillis") val burstWindowMillis: Long = 3_600_000L,
    @SerialName("burstMax") val burstMax: Int = 8,
    @SerialName("dailyMax") val dailyMax: Int = 80,
    @SerialName("quietStartMinute") val quietStartMinute: Int = 23 * 60,
    @SerialName("quietEndMinute") val quietEndMinute: Int = 8 * 60,
    @SerialName("hourlyWeights") val hourlyWeights: Map<Int, Double> = DEFAULT_HOUR_WEIGHTS,
    @SerialName("silenceBackoff") val silenceBackoff: List<Double> = listOf(1.0, 0.7, 0.45, 0.25, 0.1),
    @SerialName("silenceResetThreshold") val silenceResetThreshold: Int = 6,
    @SerialName("engagementBoostMax") val engagementBoostMax: Double = 1.35,
    @SerialName("retryMillis") val retryMillis: Long = 180_000L,
    @SerialName("maxAttempts") val maxAttempts: Int = 3,
    @SerialName("dueSoonWindowMillis") val dueSoonWindowMillis: Long = 600_000L,
) {
    fun quietEnabled(): Boolean = quietStartMinute != quietEndMinute

    fun weightForHour(hour: Int): Double = hourlyWeights[hour] ?: 0.35

    companion object {
        /** 小时权重：刻意做成有生活节奏。晚间高、凌晨近零，因此触发时间天然不均匀。 */
        val DEFAULT_HOUR_WEIGHTS: Map<Int, Double> = mapOf(
            0 to 0.02, 1 to 0.01, 2 to 0.01, 3 to 0.01, 4 to 0.01, 5 to 0.03,
            6 to 0.15, 7 to 0.45, 8 to 0.7, 9 to 0.6,
            10 to 0.5, 11 to 0.55, 12 to 0.7, 13 to 0.5,
            14 to 0.45, 15 to 0.5, 16 to 0.55, 17 to 0.6,
            18 to 0.7, 19 to 0.8, 20 to 0.9, 21 to 0.95,
            22 to 0.7, 23 to 0.3,
        )
    }
}
