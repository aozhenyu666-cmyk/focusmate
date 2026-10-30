package com.focusmate.proactive

import kotlin.math.max
import kotlin.math.min

/**
 * 概率摇骰器。只做一件事：判断「现在这一秒」要不要开口，并把概率算清楚。
 *
 * 不做 IO、不调模型、不写盘，所以能高频率单测。
 *
 * 修复的旧问题：
 * 1. 旧版投递固定 roll=0.5，等于没随机；这里每次重新摇。
 * 2. 旧版只有「到点/没到点」，没有概率；这里每次机会独立判定。
 * 3. 旧版固定 90~240 分钟；这里由「每小时机会数」+ 小时权重换算，天然不均匀。
 * 4. 旧版无退避；这里有连续沉默退避与长静默恢复。
 */
class RandomTriggerEngine(
    private val random: RandomSource = SystemRandomSource(),
) {

    enum class Verdict { HIT, MISS, GATED }

    data class Decision(
        val verdict: Verdict,
        val probability: Double,
        val roll: Double,
        val reason: String,
    ) {
        val hit: Boolean get() = verdict == Verdict.HIT
    }

    /** 判定输入：全是便宜状态，不读大文件、不调模型。 */
    data class Input(
        val nowMillis: Long,
        val hour: Int,
        val minuteOfDay: Int,
        val config: ProactiveConfig,
        val sinceLastSentMillis: Long,
        val sentToday: Int,
        val sentInWindow: Int,
        val silenceStreak: Int,
        val engagement: Double,
        val inFocusSession: Boolean,
        val inCall: Boolean,
        val importantFollowUpDueSoon: Boolean,
    )

    fun decide(input: Input): Decision {
        val cfg = input.config

        // 第一层：零成本闸门。顺序即产品优先级。
        if (!cfg.enabled) return gate("主动引擎已关闭")
        if (cfg.paused) return gate("用户已暂停")
        if (input.inCall) return gate("正在通话")
        if (input.inFocusSession) return gate("专注进行中")
        if (cfg.quietEnabled() && inQuiet(input.minuteOfDay, cfg)) return gate("免打扰时段")
        if (cfg.dailyMax > 0 && input.sentToday >= cfg.dailyMax) return gate("达到每日上限")
        if (input.sentInWindow >= cfg.burstMax) return gate("达到突发限流")
        if (input.sinceLastSentMillis < cfg.minGapMillis) return gate("距上次开口太近")
        if (input.importantFollowUpDueSoon) return gate("有更重要的约定即将到期")

        // 第二层：算概率。
        var p = cfg.baseProbability
        p *= cfg.weightForHour(input.hour)
        p *= 1.0 + (cfg.engagementBoostMax - 1.0) * input.engagement.coerceIn(0.0, 1.0)
        if (input.silenceStreak > 0) {
            val idx = min(input.silenceStreak, cfg.silenceBackoff.size - 1)
            p *= cfg.silenceBackoff[idx]
        }
        if (input.silenceStreak >= cfg.silenceResetThreshold) p = max(p, 0.08)
        p = min(1.0, max(0.0, p))

        val roll = random.nextDouble()
        return if (roll < p) {
            Decision(Verdict.HIT, round2(p), round2(roll), "命中 p=" + round2(p) + " roll=" + round2(roll))
        } else {
            Decision(Verdict.MISS, round2(p), round2(roll), "未命中 p=" + round2(p) + " roll=" + round2(roll))
        }
    }

    /**
     * 下一次机会的候选时刻。
     *
     * 用「每小时机会数」按小时权重换算期望间隔，再叠 0.6~1.4 抖动。
     * 所以不同小时密度不同，同一小时也不等距——不是「固定时间 + 随机秒数」。
     */
    fun nextOpportunityMillis(input: Input): Long {
        val cfg = input.config
        val opportunities = max(1, cfg.hourlyOpportunities)
        val baseGap = 3_600_000L / opportunities
        val weight = cfg.weightForHour(input.hour).coerceIn(0.05, 1.0)
        val stretched = (baseGap / weight).toLong().coerceIn(30_000L, 3_600_000L)
        val jitter = 0.6 + random.nextDouble() * 0.8
        val next = (stretched * jitter).toLong().coerceAtLeast(cfg.minGapMillis)
        return input.nowMillis + next
    }

    private fun gate(reason: String) = Decision(Verdict.GATED, 0.0, 0.0, reason)

    private fun inQuiet(minuteOfDay: Int, cfg: ProactiveConfig): Boolean {
        val s = cfg.quietStartMinute
        val e = cfg.quietEndMinute
        return if (s < e) minuteOfDay >= s && minuteOfDay < e else minuteOfDay >= s || minuteOfDay < e
    }

    private fun round2(v: Double): Double = kotlin.math.round(v * 100) / 100.0
}
