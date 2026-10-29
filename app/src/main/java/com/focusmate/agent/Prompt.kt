package com.focusmate.agent

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 这一轮为什么要调用模型。 */
sealed interface Turn {
    /** 回复用户刚说的话。 */
    data object Reply : Turn

    /** 到点的回访。 */
    data class FollowUp(val item: AgendaItem) : Turn

    /** 专注结束后的复盘。 */
    data class Review(val item: AgendaItem) : Turn

    /** 没有约定，伙伴自己考虑要不要找用户聊。 */
    data object Spontaneous : Turn
}

val Turn.proactive: Boolean get() = this !is Turn.Reply

val Turn.origin: Origin
    get() = when (this) {
        Turn.Reply -> Origin.CHAT
        is Turn.FollowUp -> Origin.FOLLOW_UP
        is Turn.Review -> Origin.FOCUS_REVIEW
        Turn.Spontaneous -> Origin.SPONTANEOUS
    }

/** 专注锁的现状，由运行时从 GuardRuntime 读出后传进来。 */
data class FocusInfo(
    val active: Boolean,
    val description: String,
    val endsAtMillis: Long? = null,
)

/**
 * 上下文组装。所有轮次（回复、回访、复盘、闲聊）走同一套组装，
 * 不另建绕过记忆的「主动台词」路径（执行指令 §0 第 7 条）。
 */
object Prompt {

    const val HISTORY_CHAR_BUDGET = 6000
    const val HISTORY_MAX_ENTRIES = 40
    const val FACT_CHAR_BUDGET = 1500

    private val week = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun build(
        prefs: AgentPrefs,
        state: AgentState,
        focus: FocusInfo,
        turn: Turn,
        nowMillis: Long,
        zone: ZoneId,
    ): List<LlmMessage> {
        val out = mutableListOf<LlmMessage>()
        out += LlmMessage.System(persona(prefs))
        out += LlmMessage.System(situation(state, focus, nowMillis, zone))
        out += history(state.chat, zone)
        trigger(turn, state, nowMillis, zone)?.let { out += LlmMessage.System(it) }
        return out
    }

    fun persona(prefs: AgentPrefs): String = """
你是「${prefs.name}」，住在用户手机里的 AI 伙伴。性格：${prefs.style}

说话方式：
- 中文口语，一般 1～3 句。用户要解释、总结、方案时可以写长，但不堆空话。
- 不说教，不灌鸡汤，不重复用户的话，不用「作为 AI」这类话。
- 具体。引用用户说过的细节，而不是泛泛地问「在干嘛」。

主动性（你的核心价值）：
- 用户提到要去做一件事、有计划、有压力、情绪波动，或话题没聊完，就用 schedule_follow_up 约个合适的时间回来问。一次对话最多约一个，别滥用。
- 用户说「X 分钟后再聊」「晚点说」，就用同一个 key 改期回访，不是取消，也不改专注时长。
- 用户明确要提醒才用 set_reminder；提醒文本写成到点时你要说的话。
- 用户想专注、学习、戒手机时，用 propose_focus 提议；用户在卡片上点了开始才会生效，你不能自己开始，也不要说已经开始。

记忆：
- 稳定的事实、偏好、目标、近期计划用 remember 记下。用户纠正时先 forget 旧的，再 remember 新的。
- 只说你真的知道的。没有联网，你没看过任何视频、文章、网页，也没有自己的生活经历，不要编。
- 「系统事件」是手机真实观察到的事；用户说「做完了」只是用户自述，不要说成你确认过。

工具调用后，最后一定要用自然的话回复用户（stay_silent 除外）。
""".trim()

    fun situation(state: AgentState, focus: FocusInfo, nowMillis: Long, zone: ZoneId): String {
        val sb = StringBuilder()
        sb.append("现在：").append(dateTime(nowMillis, zone)).append('\n')
        val lastUser = state.proactive.lastUserAtMillis
        if (lastUser > 0) sb.append("用户上次说话：").append(ago(nowMillis - lastUser)).append('\n')
        sb.append("专注锁：").append(focus.description).append('\n')

        sb.append("\n你记得的事（id 用于 forget）：\n")
        if (state.facts.isEmpty()) {
            sb.append("（还没有）\n")
        } else {
            var used = 0
            for (f in state.facts.sortedByDescending { it.createdAtMillis }) {
                val line = "- [${f.id}] ${f.text}（${if (f.source == FactSource.USER_SAID) "用户说的" else "你推断的"}，${shortDate(f.createdAtMillis, zone)}）\n"
                if (used + line.length > FACT_CHAR_BUDGET) break
                sb.append(line)
                used += line.length
            }
        }

        val pending = Agenda.pending(state.agenda)
        sb.append("\n已安排（key 用于改期或取消）：\n")
        if (pending.isEmpty()) {
            sb.append("（没有）\n")
        } else {
            pending.take(15).forEach {
                val kind = when (it.kind) {
                    AgendaKind.REMINDER -> "提醒"
                    AgendaKind.FOLLOW_UP -> "回访"
                    AgendaKind.FOCUS_REVIEW -> "复盘"
                }
                sb.append("- [key=${it.key}] ").append(kind).append(' ').append(dateTime(it.dueAtMillis, zone))
                    .append("：").append(it.text)
                if (it.reason.isNotBlank()) sb.append("（因为：").append(it.reason).append('）')
                sb.append('\n')
            }
        }
        return sb.toString().trim()
    }

    /** 最近的对话，按字数预算截断；间隔超过一小时插入时间标记，让模型知道时间流逝。 */
    fun history(chat: List<ChatEntry>, zone: ZoneId): List<LlmMessage> {
        val picked = ArrayList<ChatEntry>()
        var used = 0
        for (e in chat.asReversed()) {
            if (picked.size >= HISTORY_MAX_ENTRIES) break
            val cost = e.text.length + 16
            if (used + cost > HISTORY_CHAR_BUDGET && picked.isNotEmpty()) break
            picked += e
            used += cost
        }
        picked.reverse()

        val out = mutableListOf<LlmMessage>()
        var prevAt = 0L
        for (e in picked) {
            if (prevAt == 0L || e.atMillis - prevAt > 60 * 60_000L) {
                out += LlmMessage.System("[时间] " + dateTime(e.atMillis, zone))
            }
            prevAt = e.atMillis
            out += when (e.role) {
                Role.USER -> LlmMessage.User(e.text)
                Role.ASSISTANT -> LlmMessage.Assistant(e.text)
                Role.EVENT -> LlmMessage.System("[系统事件 ${clock(e.atMillis, zone)}] ${e.text}")
            }
        }
        return out
    }

    fun trigger(turn: Turn, state: AgentState, nowMillis: Long, zone: ZoneId): String? = when (turn) {
        Turn.Reply -> null
        is Turn.FollowUp -> buildString {
            append("[主动开口] 这是你在 ").append(dateTime(turn.item.createdAtMillis, zone))
            append(" 自己约的回访。话题：").append(turn.item.text)
            if (turn.item.reason.isNotBlank()) append("。当时的理由：").append(turn.item.reason)
            val late = nowMillis - turn.item.dueAtMillis
            if (late > 10 * 60_000L) append("。比约定晚了 ").append(ago(late).removeSuffix("前"))
            append("。现在直接对用户说话，自然地接上那个话题。如果从对话看已经不需要了（用户已经说过结果），就 stay_silent。")
        }
        is Turn.Review -> buildString {
            append("[主动开口] 刚才的专注结束了：").append(turn.item.text)
            append("。现在像朋友一样问问做得怎么样。用户说的完成情况只是自述。别长篇总结。")
        }
        Turn.Spontaneous -> buildString {
            append("[主动开口] 没有约定，这是你自己考虑要不要找用户聊。")
            append("今天你已经主动找过 ").append(spokenToday(state, nowMillis, zone)).append(" 次，")
            append("连续 ").append(state.proactive.unanswered).append(" 条没得到回应。")
            append("只有能从上面的对话、记忆或安排里找到真实的切入点时才开口（比如跟进一件没说完的事、一个临近的计划、问问之前的状态）。")
            append("别问「在干嘛」，别编造你的经历。没有好的切入点就 stay_silent。")
        }
    }

    private fun spokenToday(state: AgentState, nowMillis: Long, zone: ZoneId): Int =
        if (state.proactive.dayKey == LocalClock(zone).dayKey(nowMillis)) state.proactive.spokenToday else 0

    fun dateTime(millis: Long, zone: ZoneId): String {
        val z = Instant.ofEpochMilli(millis).atZone(zone)
        return z.format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)) + " " +
            week[z.dayOfWeek.value - 1] + " " + z.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))
    }

    fun clock(millis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))

    private fun shortDate(millis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT))

    fun ago(deltaMillis: Long): String {
        val m = deltaMillis / 60_000L
        return when {
            m < 1 -> "刚刚"
            m < 60 -> "${m} 分钟前"
            m < 24 * 60 -> "${m / 60} 小时前"
            else -> "${m / (24 * 60)} 天前"
        }
    }
}
