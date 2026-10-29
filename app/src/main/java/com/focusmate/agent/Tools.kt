package com.focusmate.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.ZoneId

/** 模型能做的事。模型只能「提出」，真正改状态的是运行时，且每条都先经过这里的校验。 */
sealed interface AgentAction {
    data class SetReminder(val text: String, val dueAtMillis: Long, val key: String) : AgentAction
    data class ScheduleFollowUp(val topic: String, val reason: String, val dueAtMillis: Long, val key: String) : AgentAction
    data class CancelAgenda(val keyOrId: String) : AgentAction
    data class Remember(val text: String, val source: FactSource) : AgentAction
    data class Forget(val factId: String) : AgentAction
    data class ProposeFocus(val mode: FocusMode, val minutes: Int, val goal: String) : AgentAction
    data class StaySilent(val reason: String) : AgentAction
}

object Tools {

    const val SET_REMINDER = "set_reminder"
    const val SCHEDULE_FOLLOW_UP = "schedule_follow_up"
    const val CANCEL = "cancel_agenda"
    const val REMEMBER = "remember"
    const val FORGET = "forget"
    const val PROPOSE_FOCUS = "propose_focus"
    const val STAY_SILENT = "stay_silent"

    private const val MAX_REMINDER_MINUTES = 30 * 24 * 60
    private const val MIN_FOLLOW_UP_MINUTES = 5
    private const val MAX_FOLLOW_UP_MINUTES = 24 * 60

    private fun obj(required: List<String>, props: Map<String, Pair<String, String>>): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            props.forEach { (name, td) ->
                put(name, buildJsonObject { put("type", td.first); put("description", td.second) })
            }
        })
        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
    }

    private val timeProps = mapOf(
        "in_minutes" to ("integer" to "从现在起多少分钟后。和 at 二选一。"),
        "at" to ("string" to "本地时间 HH:mm（24 小时制）。和 in_minutes 二选一。"),
        "date" to ("string" to "可选，本地日期 YYYY-MM-DD，配合 at 使用；不填则取下一次出现的 at。"),
    )

    /** proactive=true 的轮次（伙伴主动开口）才提供 stay_silent。 */
    fun specs(proactive: Boolean): List<ToolSpec> = buildList {
        add(ToolSpec(
            SET_REMINDER,
            "用户明确要求提醒时使用。到点会准时弹出，text 就是到时候你要对用户说的话，写得具体、像你本人说的。",
            obj(listOf("text"), mapOf(
                "text" to ("string" to "到点时对用户说的话"),
                "key" to ("string" to "可选。话题键，同键的旧提醒会被替换。改期时沿用原键。"),
            ) + timeProps),
        ))
        add(ToolSpec(
            SCHEDULE_FOLLOW_UP,
            "你自己决定过一会儿回来找用户聊某件事（例如对方说要去复习、去面试、心情不好）。到点你会结合当时的上下文开口。" +
                "同一话题只保留一个回访，用同一个 key 改期。不要每轮都约。",
            obj(listOf("topic", "reason", "in_minutes"), mapOf(
                "topic" to ("string" to "回来要聊的话题"),
                "reason" to ("string" to "为什么要回访，引用用户说过的话"),
                "in_minutes" to ("integer" to "多少分钟后，$MIN_FOLLOW_UP_MINUTES～$MAX_FOLLOW_UP_MINUTES"),
                "key" to ("string" to "可选。话题键，同键会替换旧回访。"),
            )),
        ))
        add(ToolSpec(
            CANCEL,
            "取消一条待办的提醒或回访，用户说不用了、已经做完了时使用。",
            obj(listOf("key"), mapOf("key" to ("string" to "安排列表里的 key 或 id"))),
        ))
        add(ToolSpec(
            REMEMBER,
            "记住关于用户的稳定事实、偏好、目标或计划。只记用户说过或明确表现出来的，不要记你自己的猜测为事实。",
            obj(listOf("text", "source"), mapOf(
                "text" to ("string" to "一句话事实，第三人称，例如「下周三考六级」"),
                "source" to ("string" to "user_said（用户亲口说）或 inferred（你的推断）"),
            )),
        ))
        add(ToolSpec(
            FORGET,
            "删除一条记忆。用户纠正或要求忘掉时使用；纠正时先 forget 旧的再 remember 新的。",
            obj(listOf("fact_id"), mapOf("fact_id" to ("string" to "记忆列表里的 id"))),
        ))
        add(ToolSpec(
            PROPOSE_FOCUS,
            "提议一段专注时间。界面会出现一张卡片，用户点「开始」后手机才会真正限制其它应用。" +
                "mode=gemini：切到 Gemini 只能和 Gemini 对话；mode=deep：留在本 APP（可以和你聊）。两种都放行通话和微信。" +
                "调用后不要说已经开始，系统会在真正生效后告诉你。",
            obj(listOf("mode", "minutes", "goal"), mapOf(
                "mode" to ("string" to "gemini 或 deep"),
                "minutes" to ("integer" to "5～120"),
                "goal" to ("string" to "这段时间要做什么，用用户的话"),
            )),
        ))
        if (proactive) {
            add(ToolSpec(
                STAY_SILENT,
                "这次主动开口的时机不好或没有值得说的，就保持安静。比硬找话题好。",
                obj(listOf("reason"), mapOf("reason" to ("string" to "为什么不说"))),
            ))
        }
    }

    data class Ctx(
        val nowMillis: Long,
        val zone: ZoneId,
        val factIds: Set<String>,
        val proactive: Boolean,
    )

    /** 校验一次工具调用。失败返回给模型的错误说明。 */
    fun validate(call: ToolCall, ctx: Ctx): Result<AgentAction> {
        val a = OpenAiCodec.parseArgs(call.argumentsJson) ?: return fail("参数不是 JSON 对象")
        fun str(k: String): String? = (a[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        fun int(k: String): Int? = (a[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }

        return when (call.name) {
            SET_REMINDER -> {
                val text = str("text") ?: return fail("缺少 text")
                if (text.length > 200) return fail("text 太长，200 字以内")
                val due = resolveTime(int("in_minutes"), str("at"), str("date"), ctx).getOrElse { return fail(it.message ?: "时间无效") }
                val minutes = (due - ctx.nowMillis) / 60_000L
                if (minutes > MAX_REMINDER_MINUTES) return fail("最多提前 30 天")
                Result.success(AgentAction.SetReminder(text, due, str("key") ?: keyOf("r", text)))
            }

            SCHEDULE_FOLLOW_UP -> {
                val topic = str("topic") ?: return fail("缺少 topic")
                val reason = str("reason") ?: return fail("缺少 reason")
                val m = int("in_minutes") ?: return fail("缺少 in_minutes")
                if (m !in MIN_FOLLOW_UP_MINUTES..MAX_FOLLOW_UP_MINUTES) return fail("in_minutes 要在 $MIN_FOLLOW_UP_MINUTES～$MAX_FOLLOW_UP_MINUTES")
                Result.success(AgentAction.ScheduleFollowUp(topic, reason, ctx.nowMillis + m * 60_000L, str("key") ?: keyOf("f", topic)))
            }

            CANCEL -> Result.success(AgentAction.CancelAgenda(str("key") ?: return fail("缺少 key")))

            REMEMBER -> {
                val text = str("text") ?: return fail("缺少 text")
                if (text.length !in 2..120) return fail("text 要在 2～120 字")
                val source = when (str("source")) {
                    "user_said" -> FactSource.USER_SAID
                    "inferred" -> FactSource.INFERRED
                    else -> return fail("source 只能是 user_said 或 inferred")
                }
                Result.success(AgentAction.Remember(text, source))
            }

            FORGET -> {
                val id = str("fact_id") ?: return fail("缺少 fact_id")
                if (id !in ctx.factIds) return fail("没有这条记忆：$id")
                Result.success(AgentAction.Forget(id))
            }

            PROPOSE_FOCUS -> {
                val mode = when (str("mode")) {
                    "gemini" -> FocusMode.GEMINI
                    "deep" -> FocusMode.DEEP
                    else -> return fail("mode 只能是 gemini 或 deep")
                }
                val minutes = int("minutes") ?: return fail("缺少 minutes")
                if (minutes !in 5..120) return fail("minutes 要在 5～120")
                val goal = str("goal") ?: return fail("缺少 goal")
                Result.success(AgentAction.ProposeFocus(mode, minutes, goal.take(80)))
            }

            STAY_SILENT -> {
                if (!ctx.proactive) return fail("只有你主动开口的轮次才能保持安静；用户在等你回话")
                Result.success(AgentAction.StaySilent(str("reason") ?: ""))
            }

            else -> fail("没有这个工具：${call.name}")
        }
    }

    /** in_minutes 与 at/date 二选一，换算成绝对时刻。 */
    fun resolveTime(inMinutes: Int?, at: String?, date: String?, ctx: Ctx): Result<Long> {
        if (inMinutes != null && at != null) return Result.failure(IllegalArgumentException("in_minutes 和 at 只能给一个"))
        if (inMinutes != null) {
            if (inMinutes < 1) return Result.failure(IllegalArgumentException("in_minutes 至少为 1"))
            return Result.success(ctx.nowMillis + inMinutes * 60_000L)
        }
        if (at == null) return Result.failure(IllegalArgumentException("需要 in_minutes 或 at"))
        val m = Regex("""^(\d{1,2}):(\d{2})$""").matchEntire(at)
            ?: return Result.failure(IllegalArgumentException("at 要写成 HH:mm"))
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h > 23 || min > 59) return Result.failure(IllegalArgumentException("at 不是有效时间"))
        val time = LocalClock(ctx.zone)
        val due = if (date != null) {
            val d = try { LocalDate.parse(date) } catch (t: Throwable) {
                return Result.failure(IllegalArgumentException("date 要写成 YYYY-MM-DD"))
            }
            d.atTime(h, min).atZone(ctx.zone).toInstant().toEpochMilli()
        } else {
            time.nextAt(ctx.nowMillis, h * 60 + min)
        }
        if (due <= ctx.nowMillis) return Result.failure(IllegalArgumentException("这个时间已经过去了"))
        return Result.success(due)
    }

    fun keyOf(prefix: String, text: String): String =
        prefix + ":" + text.lowercase().filter { it.isLetterOrDigit() }.take(16)

    private fun fail(msg: String): Result<AgentAction> = Result.failure(IllegalArgumentException(msg))
}
