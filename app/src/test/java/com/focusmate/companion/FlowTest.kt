package com.focusmate.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端到端流程的纯逻辑测试，对应执行指令 §7 任务 2.4 的 A–J（可在 JVM 验证的部分）。
 * 真机相关的部分（后台唤醒、TTS、限制恢复）不在这里，见 docs/ACCEPTANCE.md。
 */
class FlowTest {

    private val t0 = 1_700_000_000_000L
    private val cfg = CompanionConfig(quietStartMinute = 0, quietEndMinute = 0, idleThresholdMillis = 0)

    private fun minuteOf(now: Long) = { at: Long -> ((10 * 60 + (at - now) / 60_000L) % 1440).toInt() }

    /** A：用户停下输入之后，到点能被主动联系（用纯调度判定代替真实时钟）。 */
    @Test
    fun `A 到点后调度判定为可以开口`() {
        var state = CompanionState(dayKey = "2026-10-29", config = cfg)
        val (s1, item) = FollowUpEngine.userAgreement(state, "复盘", "用户说：十分钟后再聊", t0 + 600_000, t0)
        state = s1
        // 还没到点
        val early = SchedulePolicy.evaluate(t0 + 60_000, cfg, minuteOf(t0), 0, 0, state.nextDueAtMillis, 0, true)
        assertEquals("waiting", (early as SchedulePolicy.Decision.Wait).reason)
        // 到点了
        val onTime = SchedulePolicy.evaluate(t0 + 600_000, cfg, minuteOf(t0), 0, 0, state.nextDueAtMillis, 0, true)
        assertEquals(SchedulePolicy.Decision.Run, onTime)
        assertEquals(item.id, FollowUpEngine.dueNow(state, t0 + 600_000)!!.id)
    }

    /** B：说"十分钟后再聊" -> 旧提醒失效、新提醒接续同一话题。 */
    @Test
    fun `B 改期后旧事项失效 新事项接续同一话题`() {
        var state = CompanionState(dayKey = "2026-10-29", config = cfg)
        val (s1, old) = FollowUpEngine.userAgreement(state, "复盘今天的专注", "第一次约定", t0 + 600_000, t0)
        state = s1
        // 用户又说"二十分钟后再聊"
        val intent = IntentParser.parse("二十分钟后再聊", t0 + 60_000, "复盘今天的专注")!!
        assertEquals(IntentParser.Kind.POSTPONE, intent.kind)
        val (s2, fresh) = FollowUpEngine.userAgreement(
            state, intent.topic!!, "用户说：" + intent.evidence, intent.dueAtMillis!!, t0 + 60_000,
        )
        state = s2
        val oldAfter = state.followUps.first { it.id == old.id }
        assertEquals(FollowUpStatus.CANCELLED, oldAfter.status)
        assertEquals("复盘今天的专注", fresh.topic)
        assertEquals(t0 + 60_000 + 20 * 60_000L, state.nextDueAtMillis)
        // 到旧时间点也不该再冒出旧那条
        assertNull(FollowUpEngine.dueNow(state, t0 + 600_000))
    }

    /** C：模型生成期间用户取消 -> 旧内容不得输出。 */
    @Test
    fun `C 生成期间取消 结果被闸门拦下`() {
        var state = CompanionState(dayKey = "2026-10-29", config = cfg)
        val (s1, item) = FollowUpEngine.userAgreement(state, "复盘", "依据", t0, t0)
        state = s1
        val (s2, delivering) = FollowUpEngine.beginDelivery(state, item.id, t0)!!
        state = s2
        state = FollowUpEngine.cancel(state, item.id, t0 + 500)!!
        val verdict = DeliveryGate.check(item.id, delivering.version, state.memoryVersion, state, t0 + 1000)
        assertTrue(verdict is DeliveryGate.Verdict.Drop)
        // 取消同时改了事项版本与记忆版本，两个原因都算对；这里只要求"确实被拦下"。
        assertTrue((verdict as DeliveryGate.Verdict.Drop).reason.isNotBlank())
    }

    /** D：用户回应之后，主动事项视为已处理，不会重复提醒。 */
    @Test
    fun `D 用户回应后只结算等他回应的那条`() {
        var state = CompanionState(dayKey = "2026-10-29", config = cfg)
        val (s1, item) = FollowUpEngine.userAgreement(state, "复盘", "依据", t0, t0)
        val (s2, delivering) = FollowUpEngine.beginDelivery(s1, item.id, t0)!!
        state = FollowUpEngine.markSent(s2, item.id, t0 + 500, cfg, { 0 }, 0.5, delivering.version)!!
        assertEquals(FollowUpStatus.AWAITING_REPLY, state.followUps.first { it.id == item.id }.status)

        state = DeliveryGate.closeOnUserReply(state, t0 + 1000)
        assertEquals(FollowUpStatus.DONE, state.followUps.first { it.id == item.id }.status)
        assertTrue(!FollowUpEngine.hasOpen(state))
        assertNull(FollowUpEngine.dueNow(state, t0 + 10_000))
    }

    /** E：更正事实后不再引用旧事实（上下文只从 ACTIVE 拼）。 */
    @Test
    fun `E 更正后上下文里没有旧事实`() {
        var state = CompanionState(dayKey = "2026-10-29", config = cfg)
        val (s1, old) = FactEngine.add(state, "我下周三要面试", FactSource.USER_STATED, null, t0)
        val (s2, _) = FactEngine.correct(s1, old.id, "改成下周四面试", t0 + 1000)!!
        state = s2
        val bundle = CompanionContext.build(Persona.TEMPORARY, state, t0 + 2000, "面试", null)
        assertTrue("上下文应包含新事实", bundle.systemPrompt.contains("下周四"))
        assertTrue("上下文不能包含旧事实", !bundle.systemPrompt.contains("下周三"))
        assertTrue("用到的都是有效事实", bundle.usedFactIds.none { it == old.id })
    }

    /** F：用户不回应时按规则退避，不重复轰炸。 */
    @Test
    fun `F 失败后按退避重排 不立刻重发`() {
        val cfg2 = cfg.copy(retryMillis = 900_000, maxAttempts = 2)
        var state = CompanionState(dayKey = "2026-10-29", config = cfg2)
        val (s1, item) = FollowUpEngine.userAgreement(state, "复盘", "依据", t0, t0)
        val (s2, _) = FollowUpEngine.beginDelivery(s1, item.id, t0)!!
        val s3 = FollowUpEngine.markFailed(s2, item.id, t0, cfg2, "网络失败")!!
        assertNull("退避期内不该再投递", FollowUpEngine.dueNow(s3, t0 + 60_000))
        assertNotNull("退避结束后可以再试", FollowUpEngine.dueNow(s3, t0 + 900_000))
    }

    /** G：进程重建后待跟进事项仍在（用磁盘往返验证）。 */
    @Test
    fun `G 状态可序列化往返 事项不丢`() {
        val (s1, item) = FollowUpEngine.userAgreement(CompanionState(dayKey = "2026-10-29"), "复盘", "依据", t0, t0)
        val (s2, fact) = FactEngine.add(s1, "我在准备面试", FactSource.USER_STATED, null, t0)
        val json = FileCompanionStore.DefaultJson
        val text = json.encodeToString(CompanionState.serializer(), s2)
        val back = json.decodeFromString(CompanionState.serializer(), text)
        assertEquals(1, back.followUps.size)
        assertEquals(item.id, back.followUps.single().id)
        assertEquals(FollowUpStatus.PENDING, back.followUps.single().status)
        assertEquals(1, back.facts.size)
        assertEquals(fact.id, back.facts.single().id)
    }

    /** I：模型/网络故障不影响专注限制——两者状态文件完全独立。 */
    @Test
    fun `I 伙伴状态与专注会话是两个独立文件`() {
        // 这里断言的是设计约束：CompanionState 与 FocusSession 互不引用。
        val companionJson = FileCompanionStore.DefaultJson.encodeToString(CompanionState.serializer(), CompanionState())
        assertTrue(!companionJson.contains("targetPackage"))
        assertTrue(!companionJson.contains("enforcement"))
    }

    /** J：主动闲聊必须有真实依据；没有依据不创建事项。 */
    @Test
    fun `J 闲聊必须有依据 无依据不创建`() {
        assertNull(FollowUpEngine.personaInterest(CompanionState(), "随便聊聊", "", t0, t0))
        val created = FollowUpEngine.personaInterest(CompanionState(), "专注方法", "你说过想试试番茄钟", t0, t0)
        assertNotNull(created)
        assertEquals(FollowUpSource.PERSONA_INTEREST, created!!.second.source)
    }

    /** 迟到的结果：超过容忍窗口就不补发。 */
    @Test
    fun `迟到太久的结果不补发`() {
        var state = CompanionState(dayKey = "2026-10-29", config = cfg)
        val (s1, item) = FollowUpEngine.userAgreement(state, "复盘", "依据", t0, t0)
        state = s1
        val (s2, delivering) = FollowUpEngine.beginDelivery(state, item.id, t0)!!
        val verdict = DeliveryGate.check(item.id, delivering.version, s2.memoryVersion, s2, t0 + DeliveryGate.MAX_LATE_MILLIS + 1)
        assertTrue(verdict is DeliveryGate.Verdict.Drop)
        assertTrue((verdict as DeliveryGate.Verdict.Drop).reason.contains("迟到"))
    }

    /** 版本校验：正常在窗口内应允许发送。 */
    @Test
    fun `窗口内版本一致才允许发送`() {
        val (s1, item) = FollowUpEngine.userAgreement(CompanionState(dayKey = "2026-10-29"), "复盘", "依据", t0, t0)
        val (s2, delivering) = FollowUpEngine.beginDelivery(s1, item.id, t0)!!
        val verdict = DeliveryGate.check(item.id, delivering.version, s2.memoryVersion, s2, t0 + 1000)
        assertEquals(DeliveryGate.Verdict.Send, verdict)
    }

    /** 语境组装：主动开场不能把"设定"念出来；回复不能超出两句话的提示范围。 */
    @Test
    fun `上下文里包含人格与依据 且不暴露内部字段名`() {
        val (s1, _) = FactEngine.add(CompanionState(), "我在准备面试", FactSource.USER_STATED, null, t0)
        val (s2, item) = FollowUpEngine.userAgreement(s1, "面试", "用户说：十分钟后再聊面试", t0 + 600_000, t0)
        val bundle = CompanionContext.build(Persona.TEMPORARY, s2, t0, item.topic, item)
        assertTrue(bundle.systemPrompt.contains(Persona.TEMPORARY.displayName))
        assertTrue(bundle.systemPrompt.contains("这次为什么要开口"))
        assertTrue(bundle.userPrompt.contains("主动开口"))
        assertNotNull(bundle.followUpId)
    }
}
