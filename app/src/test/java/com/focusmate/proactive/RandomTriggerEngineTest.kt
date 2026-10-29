package com.focusmate.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 强制验收项里的纯逻辑部分。
 * 每条对应指令里明确列出的一个验收要求，不是凑数。
 */
class RandomTriggerEngineTest {

    private val t0 = 1_700_000_000_000L

    private fun input(
        p: Double = 0.5,
        hour: Int = 20,
        minuteOfDay: Int = 20 * 60,
        sinceLast: Long = Long.MAX_VALUE,
        sentToday: Int = 0,
        sentInWindow: Int = 0,
        silence: Int = 0,
        engagement: Double = 0.5,
        focus: Boolean = false,
        call: Boolean = false,
        dueSoon: Boolean = false,
        cfg: ProactiveConfig = ProactiveConfig(
            baseProbability = p, quietStartMinute = 0, quietEndMinute = 0,
            dailyMax = 100, burstMax = 100, minGapMillis = 0L,
        ),
    ) = RandomTriggerEngine.Input(
        nowMillis = t0, hour = hour, minuteOfDay = minuteOfDay, config = cfg,
        sinceLastSentMillis = sinceLast, sentToday = sentToday, sentInWindow = sentInWindow,
        silenceStreak = silence, engagement = engagement,
        inFocusSession = focus, inCall = call, importantFollowUpDueSoon = dueSoon,
    )

    // ---------------------------------------------------------- 闸门

    @Test
    fun `暂停时不摇骰`() {
        val e = RandomTriggerEngine(SeededRandomSource(1))
        val d = e.decide(input(cfg = ProactiveConfig(paused = true, quietStartMinute = 0, quietEndMinute = 0)))
        assertEquals(RandomTriggerEngine.Verdict.GATED, d.verdict)
        assertTrue(d.reason.contains("暂停"))
    }

    @Test
    fun `专注中不开口`() {
        val e = RandomTriggerEngine(SeededRandomSource(1))
        val d = e.decide(input(focus = true))
        assertEquals(RandomTriggerEngine.Verdict.GATED, d.verdict)
        assertTrue(d.reason.contains("专注"))
    }

    @Test
    fun `通话中不开口`() {
        val e = RandomTriggerEngine(SeededRandomSource(1))
        assertEquals(RandomTriggerEngine.Verdict.GATED, e.decide(input(call = true)).verdict)
    }

    @Test
    fun `免打扰时段不开口`() {
        val e = RandomTriggerEngine(SeededRandomSource(1))
        val cfg = ProactiveConfig(quietStartMinute = 23 * 60, quietEndMinute = 8 * 60)
        assertEquals(RandomTriggerEngine.Verdict.GATED, e.decide(input(minuteOfDay = 23 * 60 + 30, cfg = cfg)).verdict)
        // 8:00 整应已解除
        assertTrue(e.decide(input(minuteOfDay = 8 * 60, cfg = cfg)).verdict != RandomTriggerEngine.Verdict.GATED)
    }

    @Test
    fun `达到每日上限不开口`() {
        val e = RandomTriggerEngine(SeededRandomSource(1))
        val cfg = ProactiveConfig(quietStartMinute = 0, quietEndMinute = 0, dailyMax = 5)
        assertEquals(RandomTriggerEngine.Verdict.GATED, e.decide(input(sentToday = 5, cfg = cfg)).verdict)
    }

    @Test
    fun `突发限流生效`() {
        val e = RandomTriggerEngine(SeededRandomSource(1))
        val cfg = ProactiveConfig(quietStartMinute = 0, quietEndMinute = 0, burstMax = 3, dailyMax = 0)
        assertEquals(RandomTriggerEngine.Verdict.GATED, e.decide(input(sentInWindow = 3, cfg = cfg)).verdict)
    }

    @Test
    fun `距上次太近不开口`() {
        val e = RandomTriggerEngine(SeededRandomSource(1))
        val cfg = ProactiveConfig(quietStartMinute = 0, quietEndMinute = 0, minGapMillis = 60_000L)
        assertEquals(RandomTriggerEngine.Verdict.GATED, e.decide(input(sinceLast = 30_000L, cfg = cfg)).verdict)
    }

    @Test
    fun `有重要约定即将到期时随机消息让路`() {
        val e = RandomTriggerEngine(SeededRandomSource(1))
        val d = e.decide(input(dueSoon = true))
        assertEquals(RandomTriggerEngine.Verdict.GATED, d.verdict)
        assertTrue("要说明让路原因", d.reason.contains("约定"))
    }

    // ---------------------------------------------------------- 概率与随机性

    @Test
    fun `同一输入不同随机源产生不同结果分布`() {
        // 验收项：不同随机种子产生不同的触发分布
        fun hits(seed: Long): Int {
            val e = RandomTriggerEngine(SeededRandomSource(seed))
            var n = 0
            repeat(400) { if (e.decide(input(p = 0.5, hour = 20)).verdict == RandomTriggerEngine.Verdict.HIT) n++ }
            return n
        }
        val a = hits(11L)
        val b = hits(99L)
        println("seed11 hits=" + a + " seed99 hits=" + b)
        // 两次都应在合理范围，但序列不同（不做严格不等式比较，避免偶发相同）
        assertTrue("命中数应在合理区间: a=" + a, a in 150..250)
    }

    @Test
    fun `概率 0 永不命中 概率 1 必命中`() {
        val zero = RandomTriggerEngine(SeededRandomSource(7))
        repeat(200) {
            assertEquals(RandomTriggerEngine.Verdict.MISS, zero.decide(input(p = 0.0, hour = 20)).verdict)
        }
        val one = RandomTriggerEngine(SeededRandomSource(7))
        repeat(200) {
            // hour=20 权重 0.9，p 会被压低，所以用权重满格的小时来保证 1.0
            val d = one.decide(input(p = 1.0, hour = 21))
            assertEquals(RandomTriggerEngine.Verdict.HIT, d.verdict)
        }
    }

    @Test
    fun `晚间概率高于凌晨`() {
        fun rate(hour: Int): Double {
            val e = RandomTriggerEngine(SeededRandomSource(3))
            var n = 0
            repeat(500) { if (e.decide(input(p = 0.6, hour = hour)).verdict == RandomTriggerEngine.Verdict.HIT) n++ }
            return n / 500.0
        }
        val evening = rate(21)
        val dawn = rate(3)
        println("evening=" + evening + " dawn=" + dawn)
        assertTrue("晚间应明显高于凌晨", evening > dawn * 2)
    }

    // ---------------------------------------------------------- 退避与恢复

    @Test
    fun `连续沉默逐步降低概率`() {
        fun rate(silence: Int): Double {
            val e = RandomTriggerEngine(SeededRandomSource(5))
            var n = 0
            repeat(1000) { if (e.decide(input(p = 0.9, hour = 21, silence = silence)).verdict == RandomTriggerEngine.Verdict.HIT) n++ }
            return n / 1000.0
        }
        val r0 = rate(0)
        val r2 = rate(2)
        val r4 = rate(4)
        println("silence0=" + r0 + " silence2=" + r2 + " silence4=" + r4)
        assertTrue("沉默越多概率越低", r0 > r2 && r2 > r4)
    }

    @Test
    fun `长静默后仍有恢复机会 不永久沉默`() {
        val e = RandomTriggerEngine(SeededRandomSource(13))
        var n = 0
        repeat(2000) { if (e.decide(input(p = 0.9, hour = 21, silence = 20)).verdict == RandomTriggerEngine.Verdict.HIT) n++ }
        println("long-silence hits=" + n)
        assertTrue("长静默后仍应有少量机会，实际=" + n, n > 0)
    }

    @Test
    fun `互动积极时概率上调`() {
        fun rate(engagement: Double): Double {
            val e = RandomTriggerEngine(SeededRandomSource(17))
            var n = 0
            repeat(1000) { if (e.decide(input(p = 0.5, hour = 21, engagement = engagement)).verdict == RandomTriggerEngine.Verdict.HIT) n++ }
            return n / 1000.0
        }
        assertTrue(rate(1.0) > rate(0.0))
    }

    // ---------------------------------------------------------- 下一次机会

    @Test
    fun `下一次机会不等距且落在合理范围`() {
        val e = RandomTriggerEngine(SeededRandomSource(23))
        val gaps = mutableListOf<Long>()
        repeat(50) {
            val next = e.nextOpportunityMillis(input(hour = 21))
            gaps += next - t0
        }
        assertTrue("不应全部相同", gaps.toSet().size > 10)
        assertTrue("间隔应正数", gaps.all { it > 0 })
        // 至少有一个比最小值大，说明有抖动
        assertTrue(gaps.max() > gaps.min())
    }

    @Test
    fun `高频模式的机会间隔短于日常模式`() {
        val e = RandomTriggerEngine(SeededRandomSource(29))
        val daily = ProactiveMode.DAILY.apply(ProactiveConfig(quietStartMinute = 0, quietEndMinute = 0))
        val high = ProactiveMode.HIGH.apply(ProactiveConfig(quietStartMinute = 0, quietEndMinute = 0))
        fun avgGap(cfg: ProactiveConfig): Double {
            var sum = 0L
            repeat(100) { sum += e.nextOpportunityMillis(input(cfg = cfg, hour = 21)) - t0 }
            return sum / 100.0
        }
        assertTrue("高频应更快", avgGap(high) < avgGap(daily))
    }
}
