package com.focusmate.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowUpEngineTest {

    private val t0 = 1_700_000_000_000L
    private fun fresh() = CompanionState(dayKey = "2026-10-29")

    // ---------------------------------------------------------------- 约定与替换

    @Test
    fun `用户约定创建事项并排到期时间`() {
        val (s, item) = FollowUpEngine.userAgreement(fresh(), "复盘一下今天的专注", "用户说：十分钟后再聊这个", t0 + 600_000, t0)
        assertEquals(1, s.followUps.size)
        assertEquals(FollowUpStatus.PENDING, item.status)
        assertEquals(t0 + 600_000, s.nextDueAtMillis)
        assertEquals(FollowUpSource.USER_AGREEMENT, item.source)
    }

    @Test
    fun `同一话题再约定一次 旧的被取消而不是并存`() {
        val (s1, old) = FollowUpEngine.userAgreement(fresh(), "复盘", "第一次", t0 + 600_000, t0)
        val (s2, fresh2) = FollowUpEngine.userAgreement(s1, "复盘", "第二次", t0 + 1_800_000, t0 + 60_000)
        assertEquals(2, s2.followUps.size)
        val cancelled = s2.followUps.first { it.id == old.id }
        assertEquals(FollowUpStatus.CANCELLED, cancelled.status)
        assertEquals(old.version + 1, cancelled.version)
        assertEquals(FollowUpStatus.PENDING, s2.followUps.first { it.id == fresh2.id }.status)
        assertEquals(t0 + 1_800_000, s2.nextDueAtMillis)
    }

    @Test
    fun `没有依据不能创建事项`() {
        var threw = false
        try { FollowUpEngine.userAgreement(fresh(), "x", "  ", t0, t0) } catch (e: IllegalArgumentException) { threw = true }
        assertTrue("依据为空应拒绝", threw)
    }

    @Test
    fun `人格兴趣必须带依据 否则返回 null`() {
        assertNull(FollowUpEngine.personaInterest(fresh(), "专注方法", "", t0, t0))
        assertNull(FollowUpEngine.personaInterest(fresh(), "", "依据", t0, t0))
        assertNotNull(FollowUpEngine.personaInterest(fresh(), "专注方法", "你说过想试试番茄钟", t0, t0))
    }

    @Test
    fun `人格兴趣同一话题不重复排`() {
        val (s1, _) = FollowUpEngine.personaInterest(fresh(), "专注方法", "依据A", t0, t0)!!
        assertNull(FollowUpEngine.personaInterest(s1, "专注方法", "依据B", t0 + 1000, t0 + 1000))
    }

    // ---------------------------------------------------------------- 改期 / 取消

    @Test
    fun `改期只动时间 版本加一 旧结果失效`() {
        val (s1, item) = FollowUpEngine.userAgreement(fresh(), "复盘", "依据", t0 + 600_000, t0)
        val s2 = FollowUpEngine.reschedule(s1, item.id, t0 + 1_200_000, t0 + 1000)!!
        val updated = s2.followUps.first { it.id == item.id }
        assertEquals(t0 + 1_200_000, updated.dueAtMillis)
        assertEquals(item.version + 1, updated.version)
        assertEquals(t0 + 1_200_000, s2.nextDueAtMillis)
    }

    @Test
    fun `取消后状态为 CANCELLED 且不再可投递`() {
        val (s1, item) = FollowUpEngine.userAgreement(fresh(), "复盘", "依据", t0 + 600_000, t0)
        val s2 = FollowUpEngine.cancel(s1, item.id, t0 + 1000)!!
        assertEquals(FollowUpStatus.CANCELLED, s2.followUps.first { it.id == item.id }.status)
        assertNull(FollowUpEngine.dueNow(s2, t0 + 700_000))
        assertTrue(!FollowUpEngine.hasOpen(s2))
    }

    @Test
    fun `取消不存在的事项返回 null`() {
        assertNull(FollowUpEngine.cancel(fresh(), "nope", t0))
    }

    // ---------------------------------------------------------------- 防止并发重复投递

    @Test
    fun `beginDelivery 把事项转成 DELIVERING 并累加尝试次数`() {
        val (s1, item) = FollowUpEngine.userAgreement(fresh(), "复盘", "依据", t0, t0)
        val (s2, delivering) = FollowUpEngine.beginDelivery(s1, item.id, t0)!!
        assertEquals(FollowUpStatus.DELIVERING, delivering.status)
        assertEquals(1, delivering.attempts)
        // 已经在投递中的事项不能再被 beginDelivery 拿到（避免并发重复）
        assertNull(FollowUpEngine.beginDelivery(s2, item.id, t0))
    }

    // ---------------------------------------------------------------- 结果版本校验

    @Test
    fun `生成期间被改期 旧结果必须丢弃`() {
        val (s1, item) = FollowUpEngine.userAgreement(fresh(), "复盘", "依据", t0, t0)
        val (s2, delivering) = FollowUpEngine.beginDelivery(s1, item.id, t0)!!
        val issuedVersion = delivering.version
        // 用户中途改期
        val s3 = FollowUpEngine.reschedule(s2, item.id, t0 + 900_000, t0 + 500)!!
        // 旧结果带着 issuedVersion 回来
        assertNull(FollowUpEngine.markSent(s3, item.id, t0 + 1000, CompanionConfig(), { 0 }, 0.5, issuedVersion))
    }

    @Test
    fun `生成期间被取消 旧结果必须丢弃`() {
        val (s1, item) = FollowUpEngine.userAgreement(fresh(), "复盘", "依据", t0, t0)
        val (s2, delivering) = FollowUpEngine.beginDelivery(s1, item.id, t0)!!
        val s3 = FollowUpEngine.cancel(s2, item.id, t0 + 500)!!
        assertNull(FollowUpEngine.markSent(s3, item.id, t0 + 1000, CompanionConfig(), { 0 }, 0.5, delivering.version))
    }

    @Test
    fun `版本一致的正常投递会被记成完成 并累加今日次数`() {
        val (s1, item) = FollowUpEngine.userAgreement(fresh(), "复盘", "依据", t0, t0)
        val (s2, delivering) = FollowUpEngine.beginDelivery(s1, item.id, t0)!!
        val s3 = FollowUpEngine.markSent(s2, item.id, t0 + 1000, CompanionConfig(), { 0 }, 0.5, delivering.version)!!
        assertEquals(FollowUpStatus.DONE, s3.followUps.first { it.id == item.id }.status)
        assertEquals(1, s3.countToday)
        assertEquals(t0 + 1000, s3.lastRunAtMillis)
    }

    // ---------------------------------------------------------------- 失败与过期

    @Test
    fun `投递失败按重试间隔排下一次 不增加每日次数`() {
        val cfg = CompanionConfig(retryMillis = 900_000, maxAttempts = 3)
        val (s1, item) = FollowUpEngine.userAgreement(fresh(), "复盘", "依据", t0, t0)
        val (s2, _) = FollowUpEngine.beginDelivery(s1, item.id, t0)!!
        val s3 = FollowUpEngine.markFailed(s2, item.id, t0 + 1000, cfg, "网络错误")!!
        val updated = s3.followUps.first { it.id == item.id }
        assertEquals(FollowUpStatus.PENDING, updated.status)
        assertEquals(t0 + 1000 + 900_000, updated.dueAtMillis)
        assertEquals(0, s3.countToday)
    }

    @Test
    fun `超过重试上限转 FAILED 不再重试`() {
        val cfg = CompanionConfig(retryMillis = 1000, maxAttempts = 2)
        val (s1, item) = FollowUpEngine.userAgreement(fresh(), "复盘", "依据", t0, t0)
        var s = s1
        repeat(2) {
            val (sd, _) = FollowUpEngine.beginDelivery(s, item.id, t0)!!
            s = FollowUpEngine.markFailed(sd, item.id, t0, cfg, "失败")!!
        }
        assertEquals(FollowUpStatus.FAILED, s.followUps.first { it.id == item.id }.status)
    }

    @Test
    fun `超过有效期的事项被清理成 EXPIRED`() {
        val cfg = CompanionConfig(pendingTtlDays = 1)
        val (s1, _) = FollowUpEngine.userAgreement(fresh(), "复盘", "依据", t0, t0)
        val s2 = FollowUpEngine.pruneExpired(s1, t0 + 2L * 86_400_000L, cfg)
        assertTrue(s2.followUps.all { it.status == FollowUpStatus.EXPIRED })
    }

    @Test
    fun `没过期的事项不被清理`() {
        val cfg = CompanionConfig(pendingTtlDays = 14)
        val (s1, _) = FollowUpEngine.userAgreement(fresh(), "复盘", "依据", t0, t0)
        val s2 = FollowUpEngine.pruneExpired(s1, t0 + 60_000, cfg)
        assertEquals(FollowUpStatus.PENDING, s2.followUps.single().status)
    }

    @Test
    fun `dueNow 只挑到期且仍待发的 最早一条`() {
        val (s1, a) = FollowUpEngine.userAgreement(fresh(), "话题A", "依据A", t0 + 5000, t0)
        val (s2, b) = FollowUpEngine.userAgreement(s1, "话题B", "依据B", t0 + 1000, t0)
        assertEquals(b.id, FollowUpEngine.dueNow(s2, t0 + 6000)!!.id)
        assertNull(FollowUpEngine.dueNow(s2, t0 + 500))
    }
}
