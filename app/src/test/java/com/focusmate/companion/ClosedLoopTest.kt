package com.focusmate.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SurfCore 借鉴来的两块能力：对话收尾判断（ClosureEngine）与出站审查（OutboundGuard）。
 * 每条用例对应一个我们真实会遇到的场景，不是凑数。
 */
class ClosedLoopTest {

    private val t0 = 1_700_000_000_000L

    private fun u(text: String, at: Long) = Utterance(id = "u" + at, role = Utterance.Role.USER, text = text, at = at)
    private fun c(text: String, at: Long) = Utterance(id = "c" + at, role = Utterance.Role.COMPANION, text = text, at = at)

    // ---------------------------------------------------------------- 分类

    @Test
    fun `道别句被识别为收束`() {
        assertEquals(ClosureEngine.Kind.ENDING, ClosureEngine.classify("晚安啦"))
        assertEquals(ClosureEngine.Kind.ENDING, ClosureEngine.classify("先这样吧"))
        assertEquals(ClosureEngine.Kind.ENDING, ClosureEngine.classify("我去忙了"))
    }

    @Test
    fun `问句被识别为提问 不因结尾语气词漏判`() {
        assertEquals(ClosureEngine.Kind.QUESTION, ClosureEngine.classify("明天几点开始？"))
        assertEquals(ClosureEngine.Kind.QUESTION, ClosureEngine.classify("你去吗"))
        assertEquals(ClosureEngine.Kind.QUESTION, ClosureEngine.classify("这个怎么用"))
    }

    @Test
    fun `请求帮忙被识别为请求`() {
        assertEquals(ClosureEngine.Kind.REQUEST, ClosureEngine.classify("帮我看看这段代码"))
        assertEquals(ClosureEngine.Kind.REQUEST, ClosureEngine.classify("推荐一部片子"))
    }

    @Test
    fun `纯附和被识别为应答`() {
        assertEquals(ClosureEngine.Kind.ACK, ClosureEngine.classify("嗯"))
        assertEquals(ClosureEngine.Kind.ACK, ClosureEngine.classify("好的"))
        assertEquals(ClosureEngine.Kind.ACK, ClosureEngine.classify("收到"))
    }

    @Test
    fun `有内容的话不被误判成附和`() {
        assertEquals(ClosureEngine.Kind.SUBSTANTIVE, ClosureEngine.classify("我今天下午去医院复查了一下"))
    }

    // ---------------------------------------------------------------- 硬闸：不许用收尾逃避

    @Test
    fun `对方在提问且我还没答 不允许收尾`() {
        val recent = listOf(u("这个怎么弄？", t0 - 60_000), c("嗯", t0 - 120_000))
        val a = ClosureEngine.assess(recent, pendingThoughts = 0, nowMillis = t0)
        assertTrue("必须识别为有未答提问", a.awaitingTheirAnswer)
        assertEquals("有未答提问时结论必须是继续", ClosureEngine.Verdict.CONTINUE, a.verdict)
    }

    @Test
    fun `对方道别时允许收尾`() {
        val recent = listOf(u("那我先睡了，晚安", t0 - 60_000), c("好", t0 - 120_000))
        val a = ClosureEngine.assess(recent, pendingThoughts = 0, nowMillis = t0)
        assertEquals(ClosureEngine.Verdict.CLOSE, a.verdict)
        assertTrue(a.explicitEnd)
    }

    @Test
    fun `我还有想说的话时不该收尾`() {
        val recent = listOf(u("嗯", t0 - 60_000), c("嗯", t0 - 120_000))
        val quiet = ClosureEngine.assess(recent, pendingThoughts = 0, nowMillis = t0)
        val pending = ClosureEngine.assess(recent, pendingThoughts = 2, nowMillis = t0)
        assertTrue("有待跟进事项时继续意愿应更高", pending.ownIntent > quiet.ownIntent)
        assertTrue("有话说时不能判成收尾", pending.verdict != ClosureEngine.Verdict.CLOSE)
    }

    @Test
    fun `没有对话时不做判断`() {
        val a = ClosureEngine.assess(emptyList(), nowMillis = t0)
        assertEquals(ClosureEngine.Verdict.CONTINUE, a.verdict)
        assertNull(a.lastUserKind)
    }

    @Test
    fun `判断块只给依据 不含内部字段名`() {
        val recent = listOf(u("晚安", t0 - 1000))
        val a = ClosureEngine.assess(recent, nowMillis = t0)
        val block = ClosureEngine.promptBlock(a)
        assertTrue(block.contains("这段该不该接着说"))
        assertFalse("不能把内部枚举名写进提示词", block.contains("ENDING"))
        assertFalse(block.contains("topicClosure"))
    }

    // ---------------------------------------------------------------- 收尾记录与软冷却

    @Test
    fun `收尾记录落盘并带下次接法`() {
        val a = ClosureEngine.assess(listOf(u("晚安", t0 - 1000)), nowMillis = t0)
        val rec = ClosurePolicy.make(a, summary = "聊到今天面试", nextOpening = "问问他准备得怎么样", nowMillis = t0)
        assertEquals(t0, rec.at)
        assertEquals("聊到今天面试", rec.summary)
        assertTrue(rec.nextOpening.isNotBlank())
        assertTrue("冷却应已生效", rec.inCooldown(t0 + 1000))
    }

    @Test
    fun `冷却只压主动开口 不压用户主动找他`() {
        val rec = ClosureRecord(at = t0, cooldownUntil = t0 + 20 * 60_000L)
        assertTrue(ClosurePolicy.suppressesProactive(rec, t0 + 1000))
        // 用户开口后重开：冷却立即失效
        val reopened = ClosurePolicy.reopen(rec, t0 + 2000)
        assertFalse(ClosurePolicy.suppressesProactive(reopened, t0 + 3000))
        // 冷却过期后也不再压
        assertFalse(ClosurePolicy.suppressesProactive(rec, t0 + 21 * 60_000L))
    }

    @Test
    fun `收尾冷却期间 WakePlanner 不排主动唤醒`() {
        val item = FollowUp(
            id = "f1", source = FollowUpSource.USER_AGREEMENT, basis = "b", evidence = "e",
            topic = "t", dueAtMillis = t0 + 1000, createdAt = t0, updatedAt = t0, dedupeKey = "k",
        )
        val state = CompanionState(
            dayKey = "d",
            config = CompanionConfig(quietStartMinute = 0, quietEndMinute = 0),
            followUps = listOf(item),
            closure = ClosureRecord(at = t0, cooldownUntil = t0 + 20 * 60_000L),
        )
        val wake = WakePlanner.plan(state, t0 + 30_000, { 600 }, inFocusSession = false)
        assertNotNull(wake)
        assertTrue("冷却期内应推到冷却结束，wake=" + wake, wake!! >= t0 + 20 * 60_000L)
    }

    // ---------------------------------------------------------------- 出站审查

    @Test
    fun `已知密钥被精确拦掉`() {
        val secret = "sk-live-abcdef1234567890"
        val r = OutboundGuard.inspect("你的 key 是 " + secret + " 吗", listOf(secret))
        assertTrue(r.redacted)
        assertFalse("原文不能留下", r.text.contains(secret))
    }

    @Test
    fun `本机路径被打码`() {
        val r = OutboundGuard.inspect("文件在 C:\\Users\\me\\focusmate\\a.txt 里")
        assertTrue(r.redacted)
        assertFalse(r.text.contains("Users"))
    }

    @Test
    fun `unix 家目录路径也被打码`() {
        val r = OutboundGuard.inspect("配置在 /home/me/.config/app.json")
        assertTrue(r.redacted)
    }

    @Test
    fun `凭据赋值被打码 但普通提到关键词不误伤`() {
        val hit = OutboundGuard.inspect("我记得 token=abcd1234efgh")
        assertTrue("带赋值要打码", hit.redacted)
        val clean = OutboundGuard.inspect("这个功能需要输入密码才能用，但我不记得密码是什么了")
        assertFalse("单纯提到「密码」不该被打码：" + clean.text, clean.redacted)
    }

    @Test
    fun `正常对话不被审查改动`() {
        val r = OutboundGuard.inspect("今天面试准备得怎么样？我有点紧张")
        assertTrue(r.clean)
        assertEquals("今天面试准备得怎么样？我有点紧张", r.text)
    }

    @Test
    fun `整句都是路径时判为不该发`() {
        assertTrue(OutboundGuard.shouldDropEntirely("C:\\Users\\me\\secret.txt"))
        assertFalse("正常句子不该被丢弃", OutboundGuard.shouldDropEntirely("我在 C 盘找不到那个文件"))
    }

    // ---------------------------------------------------------------- 上下文集成

    @Test
    fun `上下文里有收尾判断与上次收尾记录`() {
        val recent = listOf(u("晚安", t0 - 1000))
        val a = ClosureEngine.assess(recent, nowMillis = t0)
        val state = CompanionState(
            dayKey = "d",
            dialogue = recent,
            closure = ClosureRecord(at = t0 - 86_400_000L, summary = "聊到面试", nextOpening = "问他准备得如何", cooldownUntil = t0 - 86_000_000L),
        )
        val bundle = CompanionContext.build(Persona.TEMPORARY, state, t0, "在吗", null, a)
        assertTrue(bundle.systemPrompt.contains("这段该不该接着说"))
        assertTrue("上次收尾要能被看到", bundle.systemPrompt.contains("上次这段怎么收的"))
        assertTrue(bundle.systemPrompt.contains("问他准备得如何"))
    }
}
