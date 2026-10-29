package com.focusmate.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 二次复核（focusmate-整改二次复核.md）里 4 条失败的用例，逐条复刻。
 * 修复前必须失败、修复后必须通过；不允许放宽断言换取通过。
 */
class RepairBoundaryTest {

    private val t0 = 1_700_000_000_000L

    // ---------------------------------------------------------------- R-01

    @Test
    fun `legacySourceRefMustStillSupportForget`() {
        // 旧版写入的事实：只有 sourceRef，没有 sourceUtteranceId。
        val utterance = Utterance(id = "u1", role = Utterance.Role.USER, text = "我下周三参加面试", at = t0)
        val legacyFact = Fact(
            id = "legacy-1",
            text = "我下周三参加面试",
            source = FactSource.USER_STATED,
            sourceRef = "utterance:u1",
            createdAt = t0,
            updatedAt = t0,
        )
        var state = CompanionState(
            dayKey = "2026-10-29",
            dialogue = listOf(utterance),
            facts = listOf(legacyFact),
        )

        // 经过一次序列化往返，模拟真实的读盘。
        val json = FileCompanionStore.DefaultJson
        state = json.decodeFromString(
            CompanionState.serializer(),
            json.encodeToString(CompanionState.serializer(), state),
        )
        assertNull("旧数据反序列化后新字段应为 null", state.facts.single().sourceUtteranceId)

        // 迁移应从 sourceRef 恢复关联。
        val (migrated, changed) = FactMigration.migrate(state)
        assertTrue("应发生迁移", changed)
        assertEquals("u1", migrated.facts.single().sourceUtteranceId)

        // 迁移后再遗忘，原句必须退出模型输入。
        val forgotten = FactEngine.forgetWithSource(migrated, "legacy-1", t0 + 1000) ?: error("遗忘应成功")
        val bundle = CompanionContext.build(Persona.TEMPORARY, forgotten, t0 + 2000, "面试", null)
        assertFalse("迁移后遗忘必须仍能挡住原句：\n" + bundle.systemPrompt, bundle.systemPrompt.contains("我下周三参加面试"))
    }

    @Test
    fun `migrationIsIdempotentAndDoesNotDeleteData`() {
        val f = Fact(id = "x", text = "旧事实", source = FactSource.USER_STATED, sourceRef = "utterance:u9", createdAt = t0, updatedAt = t0)
        val s = CompanionState(dayKey = "d", facts = listOf(f))
        val (once, c1) = FactMigration.migrate(s)
        val (twice, c2) = FactMigration.migrate(once)
        assertTrue(c1)
        assertFalse("二次迁移不应再改动", c2)
        assertEquals(1, twice.facts.size)
        assertEquals(once.facts.single(), twice.facts.single())
    }

    // ---------------------------------------------------------------- R-02

    @Test
    fun `repeatedStatementBeforeForgetMustAlsoBeHidden`() {
        val u1 = Utterance(id = "u1", role = Utterance.Role.USER, text = "我下周三参加面试", at = t0)
        val u2 = Utterance(id = "u2", role = Utterance.Role.USER, text = "我下周三参加面试", at = t0 + 60_000)
        var state = CompanionState(dayKey = "2026-10-29", dialogue = listOf(u1, u2))

        val (s1, fact) = FactEngine.add(state, u1.text, FactSource.USER_STATED, "utterance:u1", t0, "u1")
        state = s1
        // 遗忘前又说了一次：事实去重，但来源必须合并。
        val (s2, _) = FactEngine.add(state, u2.text, FactSource.USER_STATED, "utterance:u2", t0 + 60_000, "u2")
        state = s2
        assertEquals("去重后仍只有一条事实", 1, state.facts.size)
        assertEquals("两个来源都要记住", setOf("u1", "u2"), state.facts.single().sourceUtteranceIds.toSet())

        val forgotten = FactEngine.forgetWithSource(state, fact.id, t0 + 120_000) ?: error("遗忘应成功")
        val bundle = CompanionContext.build(Persona.TEMPORARY, forgotten, t0 + 130_000, "面试", null)
        assertFalse("两次原句都不能再进模型输入：\n" + bundle.systemPrompt, bundle.systemPrompt.contains("我下周三参加面试"))
    }

    @Test
    fun `reprovidingAfterForgetStillWorks`() {
        val u1 = Utterance(id = "u1", role = Utterance.Role.USER, text = "我喜欢咖啡", at = t0)
        var state = CompanionState(dayKey = "d", dialogue = listOf(u1))
        val (s1, fact) = FactEngine.add(state, u1.text, FactSource.USER_STATED, null, t0, "u1")
        state = FactEngine.forgetWithSource(s1, fact.id, t0 + 1000) ?: error("遗忘应成功")

        // 用户之后自己又说了一次：这是重新提供，不是系统复活。
        val u3 = Utterance(id = "u3", role = Utterance.Role.USER, text = "我喜欢咖啡", at = t0 + 2000)
        state = state.copy(dialogue = state.dialogue + u3)
        val (s3, fresh) = FactEngine.add(state, u3.text, FactSource.USER_STATED, null, t0 + 2000, "u3")
        assertTrue("重新提供后应有一条新的 ACTIVE 事实", s3.facts.any { it.id == fresh.id && it.status == FactStatus.ACTIVE })
        val bundle = CompanionContext.build(Persona.TEMPORARY, s3, t0 + 3000, "咖啡", null)
        assertTrue("重新提供的内容应能被看到", bundle.systemPrompt.contains("我喜欢咖啡"))
    }

    // ---------------------------------------------------------------- R-03

    @Test
    fun `correctedFactMustNotLeakViaFollowUpEvidence`() {
        val u1 = Utterance(id = "u1", role = Utterance.Role.USER, text = "我下周三参加面试", at = t0)
        var state = CompanionState(dayKey = "2026-10-29", dialogue = listOf(u1))
        val (s1, fact) = FactEngine.add(state, u1.text, FactSource.USER_STATED, "utterance:u1", t0, "u1")
        val (s2, fu) = FollowUpEngine.userAgreement(
            state = s1,
            topic = "面试准备",
            evidence = "用户说「我下周三参加面试」",
            dueAtMillis = t0 + 600_000,
            nowMillis = t0,
            evidenceUtteranceId = "u1",
        )
        state = s2

        val (corrected, _) = FactEngine.correctWithSource(state, fact.id, "改成下周四参加面试", t0 + 1000)
            ?: error("更正应成功")

        // 关联事项必须被处理，不能让旧依据继续被带进提示词。
        assertEquals("以旧句为依据的事项应作废", FollowUpStatus.CANCELLED, corrected.followUps.first { it.id == fu.id }.status)
        val bundle = CompanionContext.build(Persona.TEMPORARY, corrected, t0 + 2000, "面试", corrected.followUps.first { it.id == fu.id })
        assertFalse("旧依据不能再出现：\n" + bundle.systemPrompt, bundle.systemPrompt.contains("我下周三参加面试"))
    }

    @Test
    fun `hiddenEvidenceIsNotRenderedEvenIfItemStillOpen`() {
        val u1 = Utterance(id = "u1", role = Utterance.Role.USER, text = "旧句子内容", at = t0, hidden = true)
        val item = FollowUp(
            id = "f1", source = FollowUpSource.USER_AGREEMENT, basis = "b",
            evidence = "旧句子内容", evidenceUtteranceId = "u1", topic = "话题",
            dueAtMillis = t0, createdAt = t0, updatedAt = t0, dedupeKey = "k",
        )
        val state = CompanionState(dayKey = "d", dialogue = listOf(u1), followUps = listOf(item))
        val bundle = CompanionContext.build(Persona.TEMPORARY, state, t0, "话题", item)
        assertFalse("依据句子已 hidden，不得渲染", bundle.systemPrompt.contains("旧句子内容"))
    }

    // ---------------------------------------------------------------- R-04

    @Test
    fun `quietHoursMustNotRearmAlarmInPast`() {
        // 23:40 的免打扰窗口里，有一条已经到期的事项。
        val config = CompanionConfig(quietStartMinute = 23 * 60, quietEndMinute = 8 * 60)
        val item = FollowUp(
            id = "f1", source = FollowUpSource.USER_AGREEMENT, basis = "b", evidence = "e",
            topic = "t", dueAtMillis = t0 - 60_000, createdAt = t0 - 120_000, updatedAt = t0 - 120_000, dedupeKey = "k",
        )
        val state = CompanionState(dayKey = "d", config = config, followUps = listOf(item))
        val minuteOf = { _: Long -> 23 * 60 + 40 }

        val wake = WakePlanner.plan(state, t0, minuteOf, inFocusSession = false)
        assertNotNull("免打扰里仍应有一个唤醒时间", wake)
        assertTrue("不能排到过去：wake=\$wake now=\$t0", wake!! > t0)
    }

    @Test
    fun `disabledOrEmptyMustCancelAlarm`() {
        val item = FollowUp(
            id = "f1", source = FollowUpSource.USER_AGREEMENT, basis = "b", evidence = "e",
            topic = "t", dueAtMillis = t0, createdAt = t0, updatedAt = t0, dedupeKey = "k",
        )
        val disabled = CompanionState(dayKey = "d", config = CompanionConfig(enabled = false), followUps = listOf(item))
        assertNull("关闭时不该排闹钟", WakePlanner.plan(disabled, t0, { 600 }, false))
        assertNull("没有 PENDING 时不该排闹钟", WakePlanner.plan(CompanionState(dayKey = "d"), t0, { 600 }, false))
    }

    @Test
    fun `focusAndDailyMaxDeferInsteadOfFiringNow`() {
        val item = FollowUp(
            id = "f1", source = FollowUpSource.USER_AGREEMENT, basis = "b", evidence = "e",
            topic = "t", dueAtMillis = t0 - 1000, createdAt = t0, updatedAt = t0, dedupeKey = "k",
        )
        val base = CompanionState(dayKey = "d", config = CompanionConfig(quietStartMinute = 0, quietEndMinute = 0, dailyMax = 1), followUps = listOf(item))

        val focusWake = WakePlanner.plan(base, t0, { 600 }, inFocusSession = true)
        assertTrue("专注中应推迟，不立即唤醒", focusWake!! > t0)

        val maxed = base.copy(countToday = 1)
        val maxWake = WakePlanner.plan(maxed, t0, { 600 }, inFocusSession = false)
        assertTrue("次数已满应推迟", maxWake!! > t0)
    }

    @Test
    fun `dueItemSchedulesNowNotPast`() {
        val item = FollowUp(
            id = "f1", source = FollowUpSource.USER_AGREEMENT, basis = "b", evidence = "e",
            topic = "t", dueAtMillis = t0 - 5 * 60_000, createdAt = t0, updatedAt = t0, dedupeKey = "k",
        )
        val state = CompanionState(dayKey = "d", config = CompanionConfig(quietStartMinute = 0, quietEndMinute = 0), followUps = listOf(item))
        val wake = WakePlanner.plan(state, t0, { 600 }, inFocusSession = false)
        assertEquals("已到期就立即唤醒（等于 now），不能给过去", t0, wake)
    }

    @Test
    fun `awaitingReplyAndCancelledDoNotWake`() {
        val awaiting = FollowUp(
            id = "f1", source = FollowUpSource.USER_AGREEMENT, basis = "b", evidence = "e",
            topic = "t", dueAtMillis = t0 - 1000, createdAt = t0, updatedAt = t0,
            dedupeKey = "k", status = FollowUpStatus.AWAITING_REPLY,
        )
        val state = CompanionState(dayKey = "d", followUps = listOf(awaiting))
        assertNull("等回应的条目不唤醒", WakePlanner.plan(state, t0, { 600 }, false))
    }
}
