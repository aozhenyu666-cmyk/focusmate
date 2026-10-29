package com.focusmate.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云端复测报告（focusmate-云端复测报告.md）里 4 条失败的回归用例，逐条复刻。
 * 这些用例的价值不在「证明能过」，而在把每条失败的确切前提固定下来。
 * 修复前它们必须失败；修复后必须通过；不允许为了让它们过而放宽断言。
 */
class CloudRegressionTest {

    private val t0 = 1_700_000_000_000L

    // ---------------------------------------------------------------- F-02

    @Test
    fun `forgottenFactMustNotLeakThroughDialogue`() {
        // 1) 近期对话里出现过这句话，并据此建立事实
        val utterance = Utterance(
            id = "u-1",
            role = Utterance.Role.USER,
            text = "我下周三参加面试",
            at = t0,
        )
        var state = CompanionState(dayKey = "2026-10-29", dialogue = listOf(utterance))
        val (s1, fact) = FactEngine.add(
            state = state,
            text = utterance.text,
            source = FactSource.USER_STATED,
            sourceRef = "utterance:" + utterance.id,
            nowMillis = t0,
            sourceUtteranceId = utterance.id,
        )
        state = s1

        // 2) 用户要求忘掉
        val forgotten = FactEngine.forgetWithSource(state, fact.id, t0 + 1000)
            ?: error("忘记应成功")

        // 3) 用真实上下文组装验证
        val bundle = CompanionContext.build(Persona.TEMPORARY, forgotten, t0 + 2000, "面试", null)
        assertFalse(
            "遗忘后原句不能再出现在模型输入里：\n" + bundle.systemPrompt,
            bundle.systemPrompt.contains("我下周三参加面试"),
        )
        // 事实侧也要干净
        assertTrue(FactEngine.recall(forgotten, "面试", 10).isEmpty())
    }

    @Test
    fun `correctedFactMustNotLeakThroughDialogue`() {
        val utterance = Utterance(id = "u-2", role = Utterance.Role.USER, text = "我下周三参加面试", at = t0)
        var state = CompanionState(dayKey = "2026-10-29", dialogue = listOf(utterance))
        val (s1, fact) = FactEngine.add(state, utterance.text, FactSource.USER_STATED, null, t0, utterance.id)
        state = s1

        val (corrected, _) = FactEngine.correctWithSource(state, fact.id, "改成下周四参加面试", t0 + 1000)
            ?: error("更正应成功")

        val bundle = CompanionContext.build(Persona.TEMPORARY, corrected, t0 + 2000, "面试", null)
        assertFalse("更正后旧句不能再进模型输入", bundle.systemPrompt.contains("我下周三参加面试"))
        assertTrue("新内容要能被模型看到", bundle.systemPrompt.contains("下周四"))
    }

    // ---------------------------------------------------------------- F-03

    @Test
    fun `unrelatedReplyMustNotCloseFutureAgreement`() {
        var state = CompanionState(dayKey = "2026-10-29")
        val (s1, future) = FollowUpEngine.userAgreement(
            state = state,
            topic = "明天的复盘",
            evidence = "用户说明天再聊",
            dueAtMillis = t0 + 24L * 3_600_000L, // 明天才到期，还没投递
            nowMillis = t0,
        )
        state = s1

        // 用户聊了一句完全无关的话
        val after = DeliveryGate.closeOnUserReply(state, t0 + 60_000)

        assertEquals(
            "无关回复不能把明天的约定标成完成",
            FollowUpStatus.PENDING,
            after.followUps.first { it.id == future.id }.status,
        )
    }

    @Test
    fun `replyClosesOnlyAwaitingItem`() {
        var state = CompanionState(dayKey = "2026-10-29")
        val (s1, future) = FollowUpEngine.userAgreement(state, "明天的复盘", "说明天", t0 + 24L * 3_600_000L, t0)
        val (s2, sent) = FollowUpEngine.userAgreement(s1, "刚才那句", "说十分钟后", t0, t0)
        state = s2
        // 让第二条真的发出去，进入 AWAITING_REPLY
        val (s3, delivering) = FollowUpEngine.beginDelivery(state, sent.id, t0)!!
        val s4 = FollowUpEngine.markSent(s3, sent.id, t0 + 1000, CompanionConfig(), { 0 }, 0.5, delivering.version)!!

        val after = DeliveryGate.closeOnUserReply(s4, t0 + 2000)
        assertEquals(FollowUpStatus.DONE, after.followUps.first { it.id == sent.id }.status)
        assertEquals(
            "未来约定不受这次回应影响",
            FollowUpStatus.PENDING,
            after.followUps.first { it.id == future.id }.status,
        )
    }

    // ---------------------------------------------------------------- F-04

    @Test
    fun `lateNetworkFailureMustNotResurrectCancelledItem`() {
        val cfg = CompanionConfig(maxAttempts = 3)
        var state = CompanionState(dayKey = "2026-10-29", config = cfg)
        val (s1, item) = FollowUpEngine.userAgreement(state, "复盘", "依据", t0, t0)
        val (s2, delivering) = FollowUpEngine.beginDelivery(s1, item.id, t0)!!
        // 用户取消
        val s3 = FollowUpEngine.cancel(s2, item.id, t0 + 500)!!
        // 旧请求随后返回失败
        val s4 = FollowUpEngine.markFailed(
            state = s3,
            id = item.id,
            nowMillis = t0 + 1000,
            config = cfg,
            reason = "网络错误",
            issuedVersion = delivering.version,
        )
        assertEquals(
            "取消后迟到的失败不能把它复活成 PENDING",
            FollowUpStatus.CANCELLED,
            (s4 ?: s3).followUps.first { it.id == item.id }.status,
        )
    }

    @Test
    fun `lateFailureMustNotOverwriteRescheduledAgreement`() {
        val cfg = CompanionConfig(maxAttempts = 3, retryMillis = 900_000)
        var state = CompanionState(dayKey = "2026-10-29", config = cfg)
        val (s1, item) = FollowUpEngine.userAgreement(state, "复盘", "依据", t0, t0)
        val (s2, delivering) = FollowUpEngine.beginDelivery(s1, item.id, t0)!!
        val s3 = FollowUpEngine.reschedule(s2, item.id, t0 + 1_800_000, t0 + 500)!!
        val s4 = FollowUpEngine.markFailed(s3, item.id, t0 + 1000, cfg, "网络错误", delivering.version) ?: s3
        val after = s4.followUps.first { it.id == item.id }
        assertEquals("改期后的新时间不能被旧失败改掉", t0 + 1_800_000, after.dueAtMillis)
    }

    // ---------------------------------------------------------------- F-05

    @Test
    fun `factChangeMustInvalidateInFlightProactiveOutput`() {
        var state = CompanionState(dayKey = "2026-10-29", config = CompanionConfig(quietStartMinute = 0, quietEndMinute = 0))
        val (s1, item) = FollowUpEngine.userAgreement(state, "面试", "依据", t0, t0)
        val (s2, delivering) = FollowUpEngine.beginDelivery(s1, item.id, t0)!!
        val issuedMemory = s2.memoryVersion

        // 生成期间加入一条新事实（记忆变了）
        val (s3, _) = FactEngine.add(s2, "我在准备面试", FactSource.USER_STATED, null, t0 + 500, null)

        val verdict = DeliveryGate.check(
            followUpId = item.id,
            issuedVersion = delivering.version,
            issuedMemoryVersion = issuedMemory,
            state = s3,
            nowMillis = t0 + 1000,
            nowAllowsSpeaking = true,
        )
        assertTrue("记忆在生成期间变了，旧结果必须丢弃：" + verdict, verdict is DeliveryGate.Verdict.Drop)
    }

    @Test
    fun `enteringFocusDuringGenerationDropsOutput`() {
        var state = CompanionState(dayKey = "2026-10-29", config = CompanionConfig(quietStartMinute = 0, quietEndMinute = 0))
        val (s1, item) = FollowUpEngine.userAgreement(state, "复盘", "依据", t0, t0)
        val (s2, delivering) = FollowUpEngine.beginDelivery(s1, item.id, t0)!!
        val verdict = DeliveryGate.check(
            followUpId = item.id,
            issuedVersion = delivering.version,
            issuedMemoryVersion = s2.memoryVersion,
            state = s2,
            nowMillis = t0 + 1000,
            nowAllowsSpeaking = false,
        )
        assertTrue("生成结束已进入专注时不能再播", verdict is DeliveryGate.Verdict.Drop)
    }

    @Test
    fun `unchangedStateStillSends`() {
        val state = CompanionState(dayKey = "2026-10-29", config = CompanionConfig(quietStartMinute = 0, quietEndMinute = 0))
        val (s1, item) = FollowUpEngine.userAgreement(state, "复盘", "依据", t0, t0)
        val (s2, delivering) = FollowUpEngine.beginDelivery(s1, item.id, t0)!!
        val verdict = DeliveryGate.check(item.id, delivering.version, s2.memoryVersion, s2, t0 + 1000, true)
        assertEquals(DeliveryGate.Verdict.Send, verdict)
    }
}
