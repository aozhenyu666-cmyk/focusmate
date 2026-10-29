package com.focusmate.proactive

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 三种预设。改的不只是次数：命中率与最小间隔一起变，否则「高频」不会真的更快。 */
@Serializable
enum class ProactiveMode {
    /** 日常：约每小时 6 次机会。 */
    @SerialName("daily") DAILY,
    /** 高频：约每小时 12 次机会。 */
    @SerialName("high") HIGH,
    /** 实验：每小时 30 次机会，每日不限（突发限流仍生效）。 */
    @SerialName("experiment") EXPERIMENT,
    ;

    fun apply(config: ProactiveConfig): ProactiveConfig = when (this) {
        DAILY -> config.copy(
            mode = DAILY, hourlyOpportunities = 6, baseProbability = 0.50,
            minGapMillis = 360_000L, burstMax = 8, dailyMax = 80,
        )
        HIGH -> config.copy(
            mode = HIGH, hourlyOpportunities = 12, baseProbability = 0.60,
            minGapMillis = 180_000L, burstMax = 12, dailyMax = 120,
        )
        EXPERIMENT -> config.copy(
            mode = EXPERIMENT, hourlyOpportunities = 30, baseProbability = 0.50,
            minGapMillis = 60_000L, burstMax = 25, dailyMax = 0,
        )
    }
}
