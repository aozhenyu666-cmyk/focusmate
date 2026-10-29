package com.focusmate.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
private val CLOCK = LocalClock(ZONE)
private const val MIN = 60_000L

/** 2026-10-29 当地时间 hh:mm 的时刻。 */
private fun at(h: Int, m: Int = 0, day: Int = 29): Long =
    ZonedDateTime.of(2026, 10, day, h, m, 0, 0, ZONE).toInstant().toEpochMilli()

private class Ids {
    private var n = 0
    fun next(): String = "id${++n}"
}

private fun item(kind: AgendaKind, key: String, due: Long, text: String = "t", id: String = key + due) =
    AgendaItem(id = id, kind = kind, key = key, dueAtMillis = due, text = text, createdAtMillis = due - 10 * MIN)

class AgendaTest {

    @Test
    fun `同 key 的新安排替换旧安排 不会新旧一起响`() {
        val a = item(AgendaKind.FOLLOW_UP, "f:复习", at(10, 30), id = "a")
        val b = item(AgendaKind.FOLLOW_UP, "f:复习", at(10, 40), id = "b")
        val list = Agenda.upsert(Agenda.upsert(emptyList(), a, at(10)), b, at(10, 5))
        assertEquals(listOf("b"), Agenda.pending(list).map { it.id })
        assertEquals(AgendaStatus.CANCELLED, list.first { it.id == "a" }.status)
    }

    @Test
    fun `到期列表只含待办且按时间排序`() {
        val list = listOf(
            item(AgendaKind.REMINDER, "r1", at(10, 20), id = "late"),
            item(AgendaKind.REMINDER, "r2", at(10, 10), id = "early"),
            item(AgendaKind.REMINDER, "r3", at(11), id = "future"),
        )
        assertEquals(listOf("early", "late"), Agenda.due(list, at(10, 30)).map { it.id })
        assertEquals(at(10, 10), Agenda.nextDueAt(list))
    }

    @Test
    fun `生成失败按次数退避 用尽后报告`() {
        var list = listOf(item(AgendaKind.FOLLOW_UP, "f", at(10), id = "x"))
        val (l1, out1) = Agenda.markFailed(list, "x", at(10), "网络")
        assertFalse(out1)
        assertEquals(at(10, 1), l1.single().dueAtMillis)
        val (l2, _) = Agenda.markFailed(l1, "x", at(10, 1), "网络")
        assertEquals(at(10, 4), l2.single().dueAtMillis)
        val (_, out3) = Agenda.markFailed(l2, "x", at(10, 4), "网络")
        assertTrue(out3)
        list = l2
        assertEquals(2, list.single().attempts)
    }

    @Test
    fun `回访迟到三小时以上就过期 提醒永不过期`() {
        val f = item(AgendaKind.FOLLOW_UP, "f", at(1))
        val r = item(AgendaKind.REMINDER, "r", at(1))
        assertTrue(Agenda.isStale(f, at(8)))
        assertFalse(Agenda.isStale(f, at(3)))
        assertFalse(Agenda.isStale(r, at(8)))
    }

    @Test
    fun `按 key 或 id 取消`() {
        val list = listOf(item(AgendaKind.REMINDER, "r:水", at(11), id = "x"))
        assertEquals(1, Agenda.cancel(list, "r:水", at(10), "不用了").second)
        assertEquals(1, Agenda.cancel(list, "x", at(10), "不用了").second)
        assertEquals(0, Agenda.cancel(list, "别的", at(10), "不用了").second)
    }
}

class ProactivePolicyTest {

    private val prefs = AgentPrefs()

    @Test
    fun `安静时段跨午夜`() {
        assertTrue(ProactivePolicy.isQuietHours(at(23, 30), prefs, CLOCK))
        assertTrue(ProactivePolicy.isQuietHours(at(7, 59), prefs, CLOCK))
        assertFalse(ProactivePolicy.isQuietHours(at(8), prefs, CLOCK))
        assertFalse(ProactivePolicy.isQuietHours(at(15), prefs, CLOCK))
        assertEquals(at(8, day = 30), ProactivePolicy.quietEndsAt(at(23, 30), prefs, CLOCK))
    }

    @Test
    fun `提醒在安静时段和专注中也照常送达`() {
        val r = item(AgendaKind.REMINDER, "r", at(23, 30))
        assertEquals(Gate.Now, ProactivePolicy.gate(r, Situation(at(23, 30), focusActive = true), ProactiveState(), prefs, CLOCK))
    }

    @Test
    fun `回访在专注中推到专注结束后`() {
        val f = item(AgendaKind.FOLLOW_UP, "f", at(15))
        val g = ProactivePolicy.gate(f, Situation(at(15), true, at(15, 20)), ProactiveState(), prefs, CLOCK)
        assertTrue(g is Gate.Later)
        assertEquals(at(15, 21), (g as Gate.Later).atMillis)
    }

    @Test
    fun `回访在安静时段推到早上 在安静一会时推到期满`() {
        val f = item(AgendaKind.FOLLOW_UP, "f", at(23, 30))
        val g = ProactivePolicy.gate(f, Situation(at(23, 30), false), ProactiveState(), prefs, CLOCK) as Gate.Later
        assertEquals(at(8, day = 30), g.atMillis)
        val f2 = item(AgendaKind.FOLLOW_UP, "f", at(15))
        val g2 = ProactivePolicy.gate(f2, Situation(at(15), false), ProactiveState(quietUntilMillis = at(17)), prefs, CLOCK) as Gate.Later
        assertEquals(at(17), g2.atMillis)
    }

    @Test
    fun `闲聊间隔随未回应翻倍 用户说话后复原`() {
        val base = ProactiveState(lastSpokeAtMillis = at(10), lastUserAtMillis = at(9))
        assertEquals(at(11, 30), ProactivePolicy.nextSpontaneousAt(at(10), base, prefs, CLOCK))
        assertEquals(at(13), ProactivePolicy.nextSpontaneousAt(at(10), base.copy(unanswered = 1), prefs, CLOCK))
        assertEquals(at(16), ProactivePolicy.nextSpontaneousAt(at(10), base.copy(unanswered = 2), prefs, CLOCK))
        // 最多 8 倍
        assertEquals(at(22), ProactivePolicy.nextSpontaneousAt(at(10), base.copy(unanswered = 9), prefs, CLOCK))
        val back = ProactivePolicy.onUserSpoke(base.copy(unanswered = 3), at(10, 30))
        assertEquals(0, back.unanswered)
        assertEquals(at(12), ProactivePolicy.nextSpontaneousAt(at(10, 30), back, prefs, CLOCK))
    }

    @Test
    fun `闲聊撞上安静时段推到早上再加缓冲`() {
        val s = ProactiveState(lastSpokeAtMillis = at(22))
        assertEquals(at(8, 20, day = 30), ProactivePolicy.nextSpontaneousAt(at(22), s, prefs, CLOCK))
    }

    @Test
    fun `当天次数到上限推到明天`() {
        val s = ProactiveState(lastSpokeAtMillis = at(15), dayKey = "2026-10-29", spokenToday = 6)
        assertEquals(at(8, 20, day = 30), ProactivePolicy.nextSpontaneousAt(at(15), s, prefs, CLOCK))
        assertEquals("今天主动找你的次数到上限了", ProactivePolicy.spontaneousBlocker(Situation(at(15), false), s, prefs, CLOCK))
    }

    @Test
    fun `提醒不计未回应 闲聊计入当天次数 回访不计次数`() {
        var s = ProactiveState()
        s = ProactivePolicy.onProactiveDelivered(s, at(10), Origin.REMINDER, CLOCK)
        assertEquals(0, s.unanswered)
        s = ProactivePolicy.onProactiveDelivered(s, at(11), Origin.FOLLOW_UP, CLOCK)
        assertEquals(1, s.unanswered)
        assertEquals(0, s.spokenToday)
        s = ProactivePolicy.onProactiveDelivered(s, at(12), Origin.SPONTANEOUS, CLOCK)
        assertEquals(2, s.unanswered)
        assertEquals(1, s.spokenToday)
        // 第二天重新计数
        s = ProactivePolicy.onProactiveDelivered(s, at(12, day = 30), Origin.SPONTANEOUS, CLOCK)
        assertEquals(1, s.spokenToday)
    }

    @Test
    fun `专注中不闲聊`() {
        assertEquals("专注中", ProactivePolicy.spontaneousBlocker(Situation(at(15), true), ProactiveState(), prefs, CLOCK))
        assertNull(ProactivePolicy.spontaneousBlocker(Situation(at(15), false), ProactiveState(), prefs, CLOCK))
    }
}

class ToolsTest {

    private fun ctx(now: Long = at(10), facts: Set<String> = emptySet(), proactive: Boolean = false) =
        Tools.Ctx(now, ZONE, facts, proactive)

    private fun call(name: String, args: String) = ToolCall("c1", name, args)

    @Test
    fun `相对时间换算`() {
        val a = Tools.validate(call(Tools.SET_REMINDER, """{"text":"喝水","in_minutes":30}"""), ctx()).getOrThrow()
        assertEquals(at(10, 30), (a as AgentAction.SetReminder).dueAtMillis)
    }

    @Test
    fun `今天已过的钟点取明天 指定日期按日期`() {
        val a = Tools.validate(call(Tools.SET_REMINDER, """{"text":"背单词","at":"08:00"}"""), ctx()).getOrThrow()
        assertEquals(at(8, day = 30), (a as AgentAction.SetReminder).dueAtMillis)
        val b = Tools.validate(call(Tools.SET_REMINDER, """{"text":"x","at":"9:15","date":"2026-11-02"}"""), ctx()).getOrThrow()
        assertEquals(ZonedDateTime.of(2026, 11, 2, 9, 15, 0, 0, ZONE).toInstant().toEpochMilli(), (b as AgentAction.SetReminder).dueAtMillis)
    }

    @Test
    fun `时间参数错误都被拒绝`() {
        assertTrue(Tools.validate(call(Tools.SET_REMINDER, """{"text":"x","in_minutes":5,"at":"11:00"}"""), ctx()).isFailure)
        assertTrue(Tools.validate(call(Tools.SET_REMINDER, """{"text":"x"}"""), ctx()).isFailure)
        assertTrue(Tools.validate(call(Tools.SET_REMINDER, """{"text":"x","at":"25:00"}"""), ctx()).isFailure)
        assertTrue(Tools.validate(call(Tools.SET_REMINDER, """{"text":"x","at":"09:00","date":"2026-10-29"}"""), ctx()).isFailure)
        assertTrue(Tools.validate(call(Tools.SET_REMINDER, """{"text":"x","in_minutes":0}"""), ctx()).isFailure)
        assertTrue(Tools.validate(call(Tools.SET_REMINDER, "不是json"), ctx()).isFailure)
    }

    @Test
    fun `数字写成字符串也接受`() {
        val a = Tools.validate(call(Tools.SCHEDULE_FOLLOW_UP, """{"topic":"复习","reason":"说要去复习","in_minutes":"45"}"""), ctx()).getOrThrow()
        assertEquals(at(10, 45), (a as AgentAction.ScheduleFollowUp).dueAtMillis)
        assertEquals(Tools.keyOf("f", "复习"), a.key)
    }

    @Test
    fun `回访时长有上下限`() {
        assertTrue(Tools.validate(call(Tools.SCHEDULE_FOLLOW_UP, """{"topic":"a","reason":"b","in_minutes":2}"""), ctx()).isFailure)
        assertTrue(Tools.validate(call(Tools.SCHEDULE_FOLLOW_UP, """{"topic":"a","reason":"b","in_minutes":2000}"""), ctx()).isFailure)
    }

    @Test
    fun `只能删除存在的记忆`() {
        assertTrue(Tools.validate(call(Tools.FORGET, """{"fact_id":"nope"}"""), ctx(facts = setOf("f1"))).isFailure)
        assertTrue(Tools.validate(call(Tools.FORGET, """{"fact_id":"f1"}"""), ctx(facts = setOf("f1"))).isSuccess)
    }

    @Test
    fun `回复用户时不能保持安静`() {
        assertTrue(Tools.validate(call(Tools.STAY_SILENT, """{"reason":"x"}"""), ctx()).isFailure)
        assertTrue(Tools.validate(call(Tools.STAY_SILENT, """{"reason":"x"}"""), ctx(proactive = true)).isSuccess)
        assertFalse(Tools.specs(false).any { it.name == Tools.STAY_SILENT })
        assertTrue(Tools.specs(true).any { it.name == Tools.STAY_SILENT })
    }

    @Test
    fun `专注提议的模式与时长受限`() {
        assertTrue(Tools.validate(call(Tools.PROPOSE_FOCUS, """{"mode":"lock","minutes":25,"goal":"x"}"""), ctx()).isFailure)
        assertTrue(Tools.validate(call(Tools.PROPOSE_FOCUS, """{"mode":"deep","minutes":300,"goal":"x"}"""), ctx()).isFailure)
        val ok = Tools.validate(call(Tools.PROPOSE_FOCUS, """{"mode":"gemini","minutes":25,"goal":"背单词"}"""), ctx()).getOrThrow()
        assertEquals(AgentAction.ProposeFocus(FocusMode.GEMINI, 25, "背单词"), ok)
    }

    @Test
    fun `记忆来源必须明确`() {
        assertTrue(Tools.validate(call(Tools.REMEMBER, """{"text":"考六级","source":"maybe"}"""), ctx()).isFailure)
        assertTrue(Tools.validate(call(Tools.REMEMBER, """{"text":"考六级","source":"user_said"}"""), ctx()).isSuccess)
    }

    @Test
    fun `未知工具被拒绝`() {
        assertTrue(Tools.validate(call("delete_everything", "{}"), ctx()).isFailure)
    }
}

class OpenAiCodecTest {

    @Test
    fun `请求带上工具与工具结果`() {
        val body = OpenAiCodec.request(
            "deepseek-chat",
            listOf(
                LlmMessage.System("s"),
                LlmMessage.User("u"),
                LlmMessage.Assistant(null, listOf(ToolCall("c1", "remember", """{"text":"a"}"""))),
                LlmMessage.ToolResult("c1", "已记住"),
            ),
            Tools.specs(false),
        )
        assertTrue(body.contains("\"tool_call_id\":\"c1\""))
        assertTrue(body.contains("\"type\":\"function\""))
        assertTrue(body.contains("\"name\":\"set_reminder\""))
        assertTrue(body.contains("\"content\":null"))
    }

    @Test
    fun `解析文本与工具调用`() {
        val r = OpenAiCodec.parseReply(
            """{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[
              {"id":"call_1","type":"function","function":{"name":"set_reminder","arguments":"{\"text\":\"喝水\",\"in_minutes\":5}"}}]}}]}""",
        )
        assertNull(r.content)
        assertEquals("set_reminder", r.toolCalls.single().name)
        assertTrue(r.toolCalls.single().argumentsJson.contains("喝水"))
        val t = OpenAiCodec.parseReply("""{"choices":[{"message":{"content":"你好"}}]}""")
        assertEquals("你好", t.content)
    }

    @Test
    fun `坏响应抛出可读错误`() {
        val e = runCatching { OpenAiCodec.parseReply("<html>") }.exceptionOrNull()
        assertTrue(e is LlmException)
        assertEquals("Authentication Fails", OpenAiCodec.errorMessage("""{"error":{"message":"Authentication Fails","type":"auth"}}"""))
    }
}

/** 按顺序返回预设回复的假模型，并记录每次收到的消息。 */
private class FakeLlm(vararg replies: LlmReply) : LlmClient {
    private val queue = ArrayDeque(replies.toList())
    val calls = mutableListOf<Pair<List<LlmMessage>, List<ToolSpec>>>()
    override suspend fun complete(messages: List<LlmMessage>, tools: List<ToolSpec>): LlmReply {
        calls += messages.toList() to tools
        return queue.removeFirstOrNull() ?: LlmReply("（没有更多预设回复）", emptyList())
    }
}

class AgentLoopTest {

    private val ctx = Tools.Ctx(at(10), ZONE, emptySet(), proactive = false)
    private val base = listOf<LlmMessage>(LlmMessage.System("s"), LlmMessage.User("半小时后提醒我喝水"))

    @Test
    fun `普通回复`() = runBlocking {
        val out = AgentLoop(FakeLlm(LlmReply("好嘞", emptyList()))).run(base, Turn.Reply, ctx)
        assertEquals("好嘞", out.text)
        assertTrue(out.actions.isEmpty())
    }

    @Test
    fun `工具调用后把结果交回模型 再拿最终回复`() = runBlocking {
        val llm = FakeLlm(
            LlmReply(null, listOf(ToolCall("c1", Tools.SET_REMINDER, """{"text":"该喝水了","in_minutes":30}"""))),
            LlmReply("10:30 叫你。", emptyList()),
        )
        val out = AgentLoop(llm).run(base, Turn.Reply, ctx)
        assertEquals("10:30 叫你。", out.text)
        assertEquals(listOf<AgentAction>(AgentAction.SetReminder("该喝水了", at(10, 30), Tools.keyOf("r", "该喝水了"))), out.actions)
        val second = llm.calls[1].first
        assertTrue(second.last() is LlmMessage.ToolResult)
        assertTrue((second.last() as LlmMessage.ToolResult).content.startsWith("已安排提醒"))
    }

    @Test
    fun `参数错误告诉模型 让它自己改`() = runBlocking {
        val llm = FakeLlm(
            LlmReply(null, listOf(ToolCall("c1", Tools.SET_REMINDER, """{"text":"x","at":"25:00"}"""))),
            LlmReply(null, listOf(ToolCall("c2", Tools.SET_REMINDER, """{"text":"x","at":"11:00"}"""))),
            LlmReply("好", emptyList()),
        )
        val out = AgentLoop(llm).run(base, Turn.Reply, ctx)
        assertEquals(1, out.actions.size)
        assertEquals(1, out.rejected.size)
        val firstResult = llm.calls[1].first.last() as LlmMessage.ToolResult
        assertTrue(firstResult.content.startsWith("没有执行"))
    }

    @Test
    fun `主动轮次可以选择不说`() = runBlocking {
        val llm = FakeLlm(LlmReply(null, listOf(ToolCall("c1", Tools.STAY_SILENT, """{"reason":"刚聊过"}"""))))
        val out = AgentLoop(llm).run(base, Turn.Spontaneous, ctx.copy(proactive = true))
        assertTrue(out.silent)
        assertNull(out.text)
        assertEquals(1, llm.calls.size)
    }

    @Test
    fun `工具轮数用完后不给工具逼出一句话`() = runBlocking {
        val loop = LlmReply(null, listOf(ToolCall("c", Tools.REMEMBER, """{"text":"喜欢喝茶","source":"user_said"}""")))
        val llm = FakeLlm(loop, loop, loop, LlmReply("记住了", emptyList()))
        val out = AgentLoop(llm).run(base, Turn.Reply, ctx)
        assertEquals("记住了", out.text)
        assertEquals(1, out.actions.size) // 同一动作不重复收集
        assertTrue(llm.calls.last().second.isEmpty())
    }

    @Test
    fun `去掉名字前缀`() {
        assertEquals("你好", AgentLoop.clean("小舟：你好", "小舟"))
        assertNull(AgentLoop.clean("   ", "小舟"))
    }
}

class AgentCoreStateTest {

    private val core = AgentCore(CLOCK, Ids()::next)

    private fun outcome(text: String?, vararg actions: AgentAction) = AgentLoop.Outcome(text, actions.toList(), text == null, emptyList())

    @Test
    fun `用户说话重置未回应`() {
        val s0 = AgentState(proactive = ProactiveState(unanswered = 3))
        val (s1, e) = core.addUser(s0, " 在吗 ", Origin.CHAT, at(10))
        assertEquals("在吗", e.text)
        assertEquals(0, s1.proactive.unanswered)
        assertEquals(at(10), s1.proactive.lastUserAtMillis)
        assertEquals(1, s1.revision)
    }

    @Test
    fun `十分钟后再聊 替换原来的回访而不是新增`() {
        var s = AgentState()
        s = core.commit(s, Turn.Reply, outcome("好", AgentAction.ScheduleFollowUp("复习", "说要复习", at(11), "f:复习")), at(10)).first
        s = core.commit(s, Turn.Reply, outcome("行，十分钟后找你", AgentAction.ScheduleFollowUp("复习", "要晚点聊", at(10, 15), "f:复习")), at(10, 5)).first
        val pending = Agenda.pending(s.agenda)
        assertEquals(1, pending.size)
        assertEquals(at(10, 15), pending.single().dueAtMillis)
    }

    @Test
    fun `回访轮次保持安静 这条安排记为放弃 不计主动次数`() {
        val f = item(AgendaKind.FOLLOW_UP, "f", at(10), id = "fu")
        val s0 = AgentState(agenda = listOf(f))
        val (s1, entry) = core.commit(s0, Turn.FollowUp(f), outcome(null), at(10))
        assertNull(entry)
        assertEquals(AgendaStatus.DROPPED, s1.agenda.single().status)
        assertEquals(0, s1.proactive.unanswered)
    }

    @Test
    fun `回访送达后记为已送达并计入未回应`() {
        val f = item(AgendaKind.FOLLOW_UP, "f", at(10), id = "fu")
        val (s1, entry) = core.commit(AgentState(agenda = listOf(f)), Turn.FollowUp(f), outcome("复习得咋样？"), at(10))
        assertEquals(Origin.FOLLOW_UP, entry!!.origin)
        assertEquals("fu", entry.agendaId)
        assertEquals(AgendaStatus.DELIVERED, s1.agenda.single().status)
        assertEquals(1, s1.proactive.unanswered)
    }

    @Test
    fun `提醒迟到如实注明`() {
        val r = item(AgendaKind.REMINDER, "r", at(10), text = "该喝水了", id = "r1")
        val (s, e) = core.deliverReminder(AgentState(agenda = listOf(r)), r, at(10, 7))
        assertTrue(e.text.startsWith("该喝水了"))
        assertTrue(e.text.contains("晚了 7 分钟"))
        assertEquals(AgendaStatus.DELIVERED, s.agenda.single().status)
        val (_, onTime) = core.deliverReminder(AgentState(agenda = listOf(r)), r, at(10, 1))
        assertEquals("该喝水了", onTime.text)
    }

    @Test
    fun `记忆去重 推断被证实后升级来源 忘记后消失`() {
        var s = AgentState()
        s = core.commit(s, Turn.Reply, outcome("嗯", AgentAction.Remember("下周三考六级", FactSource.INFERRED)), at(10)).first
        s = core.commit(s, Turn.Reply, outcome("嗯", AgentAction.Remember("下周三考六级！", FactSource.USER_SAID)), at(10, 1)).first
        assertEquals(1, s.facts.size)
        assertEquals(FactSource.USER_SAID, s.facts.single().source)
        s = core.commit(s, Turn.Reply, outcome("好，忘了", AgentAction.Forget(s.facts.single().id)), at(10, 2)).first
        assertTrue(s.facts.isEmpty())
    }

    @Test
    fun `记忆有上限 先淘汰最旧的推断`() {
        var s = AgentState()
        s = core.commit(s, Turn.Reply, outcome("嗯", AgentAction.Remember("用户说的旧事", FactSource.USER_SAID)), at(9)).first
        repeat(AgentCore.MAX_FACTS) { i ->
            s = core.commit(s, Turn.Reply, outcome("嗯", AgentAction.Remember("推断$i", FactSource.INFERRED)), at(10) + i).first
        }
        assertEquals(AgentCore.MAX_FACTS, s.facts.size)
        assertTrue(s.facts.any { it.text == "用户说的旧事" })
        assertFalse(s.facts.any { it.text == "推断0" })
    }

    @Test
    fun `专注提议附在回复上 只有一张`() {
        val (s, e) = core.commit(AgentState(), Turn.Reply, outcome(null, AgentAction.ProposeFocus(FocusMode.DEEP, 25, "写报告")), at(10))
        assertNotNull(e!!.proposal)
        assertEquals("要不要现在开始？", e.text)
        val id = e.proposal!!.id
        val s2 = core.setProposal(s, id, ProposalStatus.ACCEPTED, null)
        assertEquals(ProposalStatus.ACCEPTED, core.findProposal(s2, id)!!.status)
    }

    @Test
    fun `重复的专注结束事件只产生一次复盘`() {
        var s = core.scheduleReview(AgentState(), "深度专注 25 分钟", at(10))
        s = core.scheduleReview(s, "深度专注 25 分钟", at(10, 1))
        assertEquals(1, Agenda.pending(s.agenda).size)
        assertEquals(AgendaKind.FOCUS_REVIEW, Agenda.pending(s.agenda).single().kind)
    }

    @Test
    fun `复盘模型不可用时用本地文案`() {
        val s0 = core.scheduleReview(AgentState(), "沉浸舱 10 分钟", at(10))
        val r = Agenda.pending(s0.agenda).single()
        val (s, e) = core.deliverFallbackReview(s0, r, at(10, 5))
        assertTrue(e.text.contains("沉浸舱 10 分钟"))
        assertTrue(Agenda.pending(s.agenda).isEmpty())
    }
}

class PromptTest {

    @Test
    fun `所有轮次共用同一套上下文 回访带上话题与理由`() {
        val f = item(AgendaKind.FOLLOW_UP, "f:复习", at(10, 45), text = "复习进度").copy(reason = "用户说去复习高数")
        val state = AgentState(
            chat = listOf(
                ChatEntry("1", Role.USER, "我去复习高数了", at(10)),
                ChatEntry("2", Role.EVENT, "专注已开始（已核验）", at(10, 1), Origin.FOCUS_EVENT),
            ),
            facts = listOf(Fact("k1", "下周三考六级", FactSource.USER_SAID, at(9))),
            agenda = listOf(f),
        )
        val msgs = Prompt.build(AgentPrefs(), state, FocusInfo(false, "未开启"), Turn.FollowUp(f), at(10, 45), ZONE)
        val all = msgs.joinToString("\n") {
            when (it) {
                is LlmMessage.System -> it.content
                is LlmMessage.User -> it.content
                is LlmMessage.Assistant -> it.content.orEmpty()
                is LlmMessage.ToolResult -> it.content
            }
        }
        assertTrue(all.contains("[k1] 下周三考六级"))
        assertTrue(all.contains("key=f:复习"))
        assertTrue(all.contains("[系统事件 10:01] 专注已开始"))
        assertTrue((msgs.last() as LlmMessage.System).content.contains("用户说去复习高数"))
        assertTrue(all.contains("2026-10-29 周四 10:45"))
    }

    @Test
    fun `历史按预算截断 保留最近的`() {
        val chat = (0 until 100).map { ChatEntry("$it", Role.USER, "x".repeat(200) + it, at(10) + it * 1000) }
        val h = Prompt.history(chat, ZONE).filterIsInstance<LlmMessage.User>()
        assertTrue(h.size < 40)
        assertTrue(h.last().content.endsWith("99"))
    }

    @Test
    fun `回复轮次没有主动开口指令`() {
        assertNull(Prompt.trigger(Turn.Reply, AgentState(), at(10), ZONE))
        assertTrue(Prompt.trigger(Turn.Spontaneous, AgentState(), at(10), ZONE)!!.contains("stay_silent"))
    }
}

class SpontaneousSkipTest {
    @Test
    fun `选择不说后隔一个间隔再考虑 失败后隔半小时`() {
        val prefs = AgentPrefs()
        val s = ProactiveState(lastSpokeAtMillis = at(9))
        val silent = ProactivePolicy.afterSpontaneousSkipped(s, at(10, 30), prefs, failed = false)
        assertEquals(at(12), ProactivePolicy.nextSpontaneousAt(at(10, 30), silent, prefs, CLOCK))
        val failed = ProactivePolicy.afterSpontaneousSkipped(s, at(10, 30), prefs, failed = true)
        assertEquals(at(11), ProactivePolicy.nextSpontaneousAt(at(10, 30), failed, prefs, CLOCK))
    }
}

class AgentStoreTest {
    @Test
    fun `读写往返 坏文件留备份并报告`() {
        val dir = kotlin.io.path.createTempDirectory("agent").toFile()
        val f = java.io.File(dir, "state.json")
        val store = AgentStore(f)
        assertEquals(AgentState(), store.read())
        val s = AgentState(
            chat = listOf(ChatEntry("1", Role.USER, "你好", at(10))),
            agenda = listOf(item(AgendaKind.REMINDER, "r", at(11))),
            facts = listOf(Fact("k", "喜欢茶", FactSource.USER_SAID, at(9))),
            revision = 7,
        )
        store.write(s)
        assertEquals(s, store.read())
        f.writeText("{坏的")
        assertEquals(AgentState(), store.read())
        assertNotNull(store.lastError)
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("state.json.broken-") })
    }
}
