package com.focusmate.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulePolicyTest {

    private val cfg = CompanionConfig()

    private fun minuteOfDay(m: Int): (Long) -> Int = { m }

    // ---------------------------------------------------------------- 免打扰

    @Test
    fun `跨零点的免打扰要正确`() {
        val quiet = CompanionConfig(quietStartMinute = 23 * 60, quietEndMinute = 8 * 60)
        assertTrue(SchedulePolicy.inQuietHours(23 * 60 + 30, quiet))
        assertTrue(SchedulePolicy.inQuietHours(3 * 60, quiet))
        assertTrue(SchedulePolicy.inQuietHours(7 * 60 + 59, quiet))
        assertFalse(SchedulePolicy.inQuietHours(8 * 60, quiet))
        assertFalse(SchedulePolicy.inQuietHours(22 * 60 + 59, quiet))
    }

    @Test
    fun `起止相同视为不启用免打扰`() {
        val noQuiet = CompanionConfig(quietStartMinute = 0, quietEndMinute = 0)
        assertFalse(SchedulePolicy.inQuietHours(3 * 60, noQuiet))
    }

    @Test
    fun `不跨零点的免打扰区间也正确`() {
        val noonNap = CompanionConfig(quietStartMinute = 12 * 60, quietEndMinute = 14 * 60)
        assertTrue(SchedulePolicy.inQuietHours(13 * 60, noonNap))
        assertFalse(SchedulePolicy.inQuietHours(15 * 60, noonNap))
    }

    // ---------------------------------------------------------------- tick 判断顺序

    @Test
    fun `关闭时永远不说`() {
        val d = SchedulePolicy.evaluate(1000, cfg.copy(enabled = false), minuteOfDay(600), 0, 0, 0, 0, true)
        assertTrue(d is SchedulePolicy.Decision.Wait)
        assertEquals("disabled", (d as SchedulePolicy.Decision.Wait).reason)
    }

    @Test
    fun `免打扰优先于到期`() {
        val d = SchedulePolicy.evaluate(1000, cfg, minuteOfDay(23 * 60 + 30), 0, 0, 0, 0, true)
        assertEquals("quiet", (d as SchedulePolicy.Decision.Wait).reason)
    }

    @Test
    fun `达到今日上限就说够了`() {
        val d = SchedulePolicy.evaluate(1000, cfg.copy(dailyMax = 2, quietStartMinute = 0, quietEndMinute = 0), minuteOfDay(600), 0, 2, 0, 0, true)
        assertEquals("daily-max", (d as SchedulePolicy.Decision.Wait).reason)
    }

    @Test
    fun `还没到点就等`() {
        val d = SchedulePolicy.evaluate(1000, cfg.copy(quietStartMinute = 0, quietEndMinute = 0), minuteOfDay(600), 0, 0, 5000, 0, true)
        assertEquals("waiting", (d as SchedulePolicy.Decision.Wait).reason)
        assertEquals(5000, (d as SchedulePolicy.Decision.Wait).untilMillis)
    }

    @Test
    fun `没有待跟进事项又不允许闲聊时 不凭空开口`() {
        val d = SchedulePolicy.evaluate(1000, cfg.copy(allowSmallTalk = false, quietStartMinute = 0, quietEndMinute = 0), minuteOfDay(600), 0, 0, 0, 0, false)
        assertEquals("no-topic", (d as SchedulePolicy.Decision.Wait).reason)
    }

    @Test
    fun `允许闲聊时 没有事项也可以跑`() {
        val d = SchedulePolicy.evaluate(1000, cfg.copy(allowSmallTalk = true, quietStartMinute = 0, quietEndMinute = 0), minuteOfDay(600), 0, 0, 0, 0, false)
        assertEquals(SchedulePolicy.Decision.Run, d)
    }

    @Test
    fun `用户刚说过话就先等一会`() {
        val now = 10_000_000L
        val d = SchedulePolicy.evaluate(now, cfg.copy(quietStartMinute = 0, quietEndMinute = 0), minuteOfDay(600), 0, 0, 0, now - 60_000, true)
        assertEquals("user-active", (d as SchedulePolicy.Decision.Wait).reason)
        assertTrue(d.untilMillis > now)
    }

    @Test
    fun `用户已经安静足够久就可以跑`() {
        val now = 10_000_000L
        val d = SchedulePolicy.evaluate(now, cfg.copy(quietStartMinute = 0, quietEndMinute = 0), minuteOfDay(600), 0, 0, 0, now - 60 * 60_000, true)
        assertEquals(SchedulePolicy.Decision.Run, d)
    }

    @Test
    fun `专注进行中禁言 优先级高于到期`() {
        val d = SchedulePolicy.evaluate(
            nowMillis = 1000,
            config = cfg.copy(quietStartMinute = 0, quietEndMinute = 0),
            minuteOfDay = minuteOfDay(600),
            lastRunAtMillis = 0,
            countToday = 0,
            nextDueAtMillis = 0,
            lastUserSpeakAtMillis = 0,
            hasOpenFollowUp = true,
            inFocusSession = true,
        )
        assertEquals("in-focus", (d as SchedulePolicy.Decision.Wait).reason)
        assertTrue(d.untilMillis > 1000)
    }

    @Test
    fun `专注结束后恢复开口`() {
        val d = SchedulePolicy.evaluate(
            nowMillis = 1000,
            config = cfg.copy(quietStartMinute = 0, quietEndMinute = 0),
            minuteOfDay = minuteOfDay(600),
            lastRunAtMillis = 0,
            countToday = 0,
            nextDueAtMillis = 0,
            lastUserSpeakAtMillis = 0,
            hasOpenFollowUp = true,
            inFocusSession = false,
        )
        assertEquals(SchedulePolicy.Decision.Run, d)
    }

    // ---------------------------------------------------------------- 排程

    @Test
    fun `首次排程用最小间隔`() {
        val now = 1_700_000_000_000L
        val at = SchedulePolicy.planNext(now, cfg.copy(quietStartMinute = 0, quietEndMinute = 0), minuteOfDay(600), 0, 0, 0.0)
        assertEquals(now + cfg.gapMinMillis, at)
    }

    @Test
    fun `停机很久后只补一枪 不攒一堆补发`() {
        val now = 1_700_000_000_000L
        val longAgo = now - 30L * 86_400_000L
        val at = SchedulePolicy.planNext(now, cfg.copy(quietStartMinute = 0, quietEndMinute = 0), minuteOfDay(600), longAgo, 0, 0.5)
        assertEquals(now + 2 * 60_000L, at)
    }

    /** 以 startMinute 为 now 的本地分钟数，之后按时间推进换算——比固定常量真实。 */
    private fun clockFrom(startMinute: Int, now: Long): (Long) -> Int =
        { at -> ((startMinute + (at - now) / 60_000L) % 1440L).toInt() }

    @Test
    fun `落在免打扰就推到免打扰结束`() {
        val now = 1_700_000_000_000L
        // 23:40 排下一次：+90 分钟会跨进 01:10 的免打扰，必须被推到 08:00 之后
        val minuteOf = clockFrom(23 * 60 + 40, now)
        val at = SchedulePolicy.planNext(now, cfg, minuteOf, 0, 0, 0.0)
        assertTrue("应被推到之后", at > now + cfg.gapMinMillis)
        assertFalse("排出来的时刻不应在免打扰里", SchedulePolicy.inQuietHours(minuteOf(at), cfg))
    }

    @Test
    fun `达到每日上限时下次排到免打扰结束后`() {
        val now = 1_700_000_000_000L
        val minuteOf = clockFrom(15 * 60, now)
        val at = SchedulePolicy.planNext(now, cfg.copy(dailyMax = 1), minuteOf, 0, 1, 0.5)
        assertTrue(at > now)
        assertFalse(SchedulePolicy.inQuietHours(minuteOf(at), cfg))
    }
}
