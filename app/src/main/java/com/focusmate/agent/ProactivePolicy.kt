package com.focusmate.agent

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** 主动程度。数值是本项目自定的，不照搬上游默认值（执行指令 S4）。 */
enum class Proactivity(val label: String, val dailyCap: Int, val minGapMinutes: Long) {
    LOW("克制", dailyCap = 3, minGapMinutes = 180),
    MEDIUM("适中", dailyCap = 6, minGapMinutes = 90),
    HIGH("话多", dailyCap = 12, minGapMinutes = 45),
}

/** 伙伴的可调设置。API Key 不在这里，单独加密保存。 */
data class AgentPrefs(
    val name: String = "小舟",
    val style: String = DEFAULT_STYLE,
    val proactivity: Proactivity = Proactivity.MEDIUM,
    /** 安静时段，按本地「当天第几分钟」表示，可以跨午夜。 */
    val quietStartMinute: Int = 23 * 60,
    val quietEndMinute: Int = 8 * 60,
    val speakAloud: Boolean = false,
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-chat",
) {
    companion object {
        const val DEFAULT_STYLE = "直接、敏锐、有点幽默，像一个懂你的老同学。关心但不黏人，不说教，不灌鸡汤。"
    }
}

/** 本地时间换算。时区作为参数传入，方便测试。 */
class LocalClock(val zone: ZoneId) {
    private fun at(millis: Long): ZonedDateTime = Instant.ofEpochMilli(millis).atZone(zone)

    fun minuteOfDay(millis: Long): Int = at(millis).let { it.hour * 60 + it.minute }

    fun dayKey(millis: Long): String = at(millis).toLocalDate().toString()

    /** 严格晚于 millis 的下一个「当天第 minute 分钟」。 */
    fun nextAt(millis: Long, minute: Int): Long {
        val base = at(millis)
        var candidate = base.toLocalDate().atStartOfDay(zone).plusMinutes(minute.toLong())
        if (!candidate.isAfter(base)) candidate = candidate.plusDays(1)
        return candidate.toInstant().toEpochMilli()
    }

    fun startOfNextDay(millis: Long): Long =
        at(millis).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun date(millis: Long): LocalDate = at(millis).toLocalDate()
}

/** 伙伴当下能否开口的外部条件。 */
data class Situation(
    val nowMillis: Long,
    val focusActive: Boolean,
    /** 专注预计结束时刻；不在专注时为 null。 */
    val focusEndsAtMillis: Long? = null,
)

sealed interface Gate {
    data object Now : Gate
    data class Later(val atMillis: Long, val why: String) : Gate
}

object ProactivePolicy {

    /** 主动闲聊不会在醒来的第一刻就开口。 */
    private const val AFTER_QUIET_GRACE_MILLIS: Long = 20 * 60_000L
    private const val MIN_LEAD_MILLIS: Long = 60_000L
    private const val MAX_BACKOFF_STEPS: Int = 3

    fun isQuietHours(millis: Long, prefs: AgentPrefs, time: LocalClock): Boolean {
        val m = time.minuteOfDay(millis)
        val s = prefs.quietStartMinute
        val e = prefs.quietEndMinute
        if (s == e) return false
        return if (s < e) m in s until e else (m >= s || m < e)
    }

    fun quietEndsAt(millis: Long, prefs: AgentPrefs, time: LocalClock): Long = time.nextAt(millis, prefs.quietEndMinute)

    /**
     * 到期的安排现在能不能说。
     * - 提醒：用户亲自要求的，安静时段和专注中也照常送达（不出声由通知渠道决定）。
     * - 复盘：专注刚结束才会产生，直接说。
     * - 回访：专注中推到结束后；安静时段与「安静一会」推到结束后。
     */
    fun gate(item: AgendaItem, s: Situation, state: ProactiveState, prefs: AgentPrefs, time: LocalClock): Gate {
        if (item.kind != AgendaKind.FOLLOW_UP) return Gate.Now
        if (s.focusActive) {
            val until = (s.focusEndsAtMillis ?: (s.nowMillis + 10 * 60_000L)) + MIN_LEAD_MILLIS
            return Gate.Later(until, "专注中，结束后再聊")
        }
        if (state.quietUntilMillis > s.nowMillis) return Gate.Later(state.quietUntilMillis, "你让我安静一会")
        if (isQuietHours(s.nowMillis, prefs, time)) return Gate.Later(quietEndsAt(s.nowMillis, prefs, time), "安静时段")
        return Gate.Now
    }

    /** 闲聊不能开口的原因；可以开口返回 null。 */
    fun spontaneousBlocker(s: Situation, state: ProactiveState, prefs: AgentPrefs, time: LocalClock): String? {
        if (s.focusActive) return "专注中"
        if (state.quietUntilMillis > s.nowMillis) return "你让我安静一会"
        if (isQuietHours(s.nowMillis, prefs, time)) return "安静时段"
        if (spokenOn(state, time.dayKey(s.nowMillis)) >= prefs.proactivity.dailyCap) return "今天主动找你的次数到上限了"
        return null
    }

    /**
     * 下一次可以主动闲聊的时刻。没回应就把间隔翻倍（最多 8 倍），用户一说话就复原。
     * 不会因为沉默永久停止。
     */
    fun nextSpontaneousAt(nowMillis: Long, state: ProactiveState, prefs: AgentPrefs, time: LocalClock): Long {
        val gap = prefs.proactivity.minGapMinutes * 60_000L shl state.unanswered.coerceIn(0, MAX_BACKOFF_STEPS)
        val base = maxOf(state.lastSpokeAtMillis, state.lastUserAtMillis).takeIf { it > 0 } ?: nowMillis
        var t = maxOf(base + gap, nowMillis + MIN_LEAD_MILLIS)
        if (state.quietUntilMillis > t) t = state.quietUntilMillis
        if (state.spontaneousNotBeforeMillis > t) t = state.spontaneousNotBeforeMillis
        if (isQuietHours(t, prefs, time)) t = quietEndsAt(t, prefs, time) + AFTER_QUIET_GRACE_MILLIS
        if (spokenOn(state, time.dayKey(t)) >= prefs.proactivity.dailyCap) {
            t = time.nextAt(time.startOfNextDay(t) - 1, prefs.quietEndMinute) + AFTER_QUIET_GRACE_MILLIS
        }
        return t
    }

    /** 这次考虑后没说（安静或失败）：至少隔这么久再考虑。 */
    fun afterSpontaneousSkipped(state: ProactiveState, nowMillis: Long, prefs: AgentPrefs, failed: Boolean): ProactiveState {
        val wait = if (failed) 30 * 60_000L else prefs.proactivity.minGapMinutes * 60_000L
        return state.copy(spontaneousNotBeforeMillis = nowMillis + wait)
    }

    fun onUserSpoke(state: ProactiveState, nowMillis: Long): ProactiveState =
        state.copy(lastUserAtMillis = nowMillis, unanswered = 0)

    /**
     * 伙伴主动说了一句。提醒不需要回应，不计入未回应；闲聊计入当天次数。
     */
    fun onProactiveDelivered(state: ProactiveState, nowMillis: Long, origin: Origin, time: LocalClock): ProactiveState {
        if (origin == Origin.REMINDER) return state
        val day = time.dayKey(nowMillis)
        val today = spokenOn(state, day)
        return state.copy(
            lastSpokeAtMillis = nowMillis,
            unanswered = state.unanswered + 1,
            dayKey = day,
            spokenToday = if (origin == Origin.SPONTANEOUS) today + 1 else today,
        )
    }

    private fun spokenOn(state: ProactiveState, day: String): Int = if (state.dayKey == day) state.spokenToday else 0
}
