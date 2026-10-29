package com.focusmate.companion

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 收尾记录。对应 SurfCore conversation-closure 的 makeClosureRecord。
 *
 * 为什么必须落盘：不落盘的「结束」就是「断掉」——下次只能重新开场，
 * 用户会觉得这个人不记事。所以每次收尾都留下 summary（收在哪）与
 * nextOpening（下次怎么接），下次开口时注入。
 */
@Serializable
data class ClosureRecord(
    @SerialName("at") val at: Long,
    @SerialName("reason") val reason: String = "natural",
    @SerialName("summary") val summary: String = "",
    @SerialName("nextOpening") val nextOpening: String = "",
    /** 软冷却到什么时候。只压主动开口，不压用户主动找他。 */
    @SerialName("cooldownUntil") val cooldownUntil: Long = 0L,
    /** 用户又开口时标成已重开；记录保留，供提示词说明「这是新的开始」。 */
    @SerialName("reopenedAt") val reopenedAt: Long = 0L,
) {
    fun inCooldown(nowMillis: Long): Boolean = cooldownUntil > nowMillis
}

/** 收尾的软冷却与记录生成。 */
object ClosurePolicy {

    const val COOLDOWN_MILLIS = 20L * 60_000L

    /**
     * 现在是否处于收尾冷却里。
     * 语义边界（照上游）：结束不是把人关掉——用户再找他必须照常回应，
     * 所以这个判断只用在「主动开口」那条路径上。
     */
    fun suppressesProactive(record: ClosureRecord?, nowMillis: Long): Boolean {
        if (record == null) return false
        if (!record.inCooldown(nowMillis)) return false
        // 用户已经又开口了：冷却立即失效，这是新的一段。
        if (record.reopenedAt > 0 && record.reopenedAt >= record.at) return false
        return true
    }

    /** 用户说了话：把冷却标成已重开。 */
    fun reopen(record: ClosureRecord?, nowMillis: Long): ClosureRecord? {
        if (record == null) return null
        if (record.reopenedAt > 0 && nowMillis - record.reopenedAt < 60_000L) return record
        return record.copy(reopenedAt = nowMillis)
    }

    /** 生成一条收尾记录。summary/nextOpening 都截断，避免记录本身越长越大。 */
    fun make(
        assessment: ClosureEngine.Assessment,
        summary: String,
        nextOpening: String,
        nowMillis: Long,
    ): ClosureRecord = ClosureRecord(
        at = nowMillis,
        reason = when {
            assessment.explicitEnd -> "explicit-end"
            assessment.fatigue >= 0.4 -> "tired"
            assessment.topicClosure >= 0.8 -> "topic-done"
            else -> "natural"
        },
        summary = summary.trim().take(160),
        nextOpening = nextOpening.trim().take(120),
        cooldownUntil = nowMillis + COOLDOWN_MILLIS,
    )
}
