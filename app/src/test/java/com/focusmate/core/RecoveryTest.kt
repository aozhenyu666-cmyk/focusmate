package com.focusmate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryTest {

    private val target = "com.google.android.apps.bard"

    private fun dockAt(startedAt: Long, duration: Long = Presets.DOCK_DURATION_MILLIS) =
        Presets.geminiDock(target, startedAt, Enforcement.OVERLAY_PLUS_A11Y, durationMillis = duration)

    @Test
    fun `没有会话就是什么都不做`() {
        val plan = Recovery.decide(null, null, 1_000L)
        assertEquals(Recovery.Plan.Nothing, plan)
    }

    @Test
    fun `文件读不出来必须告知 不能当成没有会话`() {
        val plan = Recovery.decide(null, "读取会话失败：JsonDecodingException", 1_000L)
        assertTrue(plan is Recovery.Plan.Unreadable)
        assertTrue((plan as Recovery.Plan.Unreadable).error.contains("读取会话失败"))
    }

    @Test
    fun `终态只清理残留 不复活`() {
        val s = dockAt(0L).copy(state = SessionState.COMPLETED)
        val plan = Recovery.decide(s, null, 1_000L)
        assertTrue(plan is Recovery.Plan.ClearLeftovers)
    }

    @Test
    fun `进行中且已过期 立刻本地解除`() {
        val s = dockAt(0L, 10_000L).copy(state = SessionState.ENFORCING)
        val plan = Recovery.decide(s, null, 20_000L)
        assertTrue(plan is Recovery.Plan.ExpireNow)
        assertEquals(SessionState.ENFORCING, (plan as Recovery.Plan.ExpireNow).session.state)
    }

    @Test
    fun `恰好到期也算到期`() {
        val s = dockAt(0L, 10_000L).copy(state = SessionState.ENFORCING)
        val plan = Recovery.decide(s, null, 10_000L)
        assertTrue(plan is Recovery.Plan.ExpireNow)
    }

    @Test
    fun `未过期则退回 PREPARING 等核验`() {
        val s = dockAt(0L, 10_000L).copy(state = SessionState.BLOCKING)
        val plan = Recovery.decide(s, null, 5_000L)
        assertTrue(plan is Recovery.Plan.Resume)
        assertEquals(SessionState.PREPARING, (plan as Recovery.Plan.Resume).session.state)
        // 其它字段一律保留，包括到期时刻与规则版本
        assertEquals(s.expiresAtMillis, plan.session.expiresAtMillis)
        assertEquals(s.ruleVersion, plan.session.ruleVersion)
        assertEquals(s.id, plan.session.id)
    }

    @Test
    fun `失败态不会因为重启被当成可以继续`() {
        val s = dockAt(0L, 10_000L).copy(state = SessionState.FAILED)
        val plan = Recovery.decide(s, null, 5_000L)
        assertTrue(plan is Recovery.Plan.ClearLeftovers)
    }

    @Test
    fun `用户结束态不会因为重启被当成可以继续`() {
        val s = dockAt(0L, 10_000L).copy(state = SessionState.USER_ENDED)
        val plan = Recovery.decide(s, null, 5_000L)
        assertTrue(plan is Recovery.Plan.ClearLeftovers)
    }
}
