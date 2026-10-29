package com.focusmate.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 界面文案测试。
 *
 * 要求原文：「不要把内部枚举、版本号、异常堆栈直接显示给普通用户。」
 * 这条最容易被后续改动破坏（随手加一句 debug 文案就漏了），所以用测试钉住。
 */
class StatusTextTest {

    private val t0 = 1_700_000_000_000L

    private fun state(config: CompanionConfig = CompanionConfig()) =
        CompanionState(dayKey = "2026-10-29", config = config)

    @Test
    fun `专注中给出明确说明`() {
        val text = CompanionStatus.describe(state(), t0, 600, inFocusSession = true)
        assertTrue(text.contains("专注结束后"))
    }

    @Test
    fun `免打扰时说明时段 不暴露内部原因码`() {
        val cfg = CompanionConfig(quietStartMinute = 23 * 60, quietEndMinute = 8 * 60)
        val text = CompanionStatus.describe(state(cfg), t0, 23 * 60 + 30, inFocusSession = false)
        assertTrue(text.contains("免打扰"))
        assertTrue("要给出具体时段", text.contains("23:00") && text.contains("08:00"))
        assertFalse("不能出现内部原因码", text.contains("quiet"))
    }

    @Test
    fun `次数用完时说明今天不再主动`() {
        val cfg = CompanionConfig(quietStartMinute = 0, quietEndMinute = 0, dailyMax = 3)
        val s = state(cfg).copy(countToday = 3)
        val text = CompanionStatus.describe(s, t0, 600, inFocusSession = false)
        assertTrue(text.contains("次数已用完"))
    }

    @Test
    fun `没有约定时告诉用户怎么建一个`() {
        val cfg = CompanionConfig(quietStartMinute = 0, quietEndMinute = 0)
        val text = CompanionStatus.describe(state(cfg), t0, 600, inFocusSession = false)
        assertTrue(text.contains("还没有待跟进的约定"))
        assertTrue("要给出可操作提示", text.contains("十分钟"))
    }

    @Test
    fun `有待跟进的约定时说清话题与大概时间`() {
        val cfg = CompanionConfig(quietStartMinute = 0, quietEndMinute = 0)
        val (s1, _) = FollowUpEngine.userAgreement(
            state(cfg), "面试准备", "用户说十分钟后", t0 + 10 * 60_000L, t0,
        )
        val text = CompanionStatus.describe(s1, t0, 600, inFocusSession = false)
        assertTrue(text.contains("面试准备"))
        assertTrue(text.contains("10 分钟后"))
        assertFalse("不能泄漏时间戳", text.contains(t0.toString()))
    }

    @Test
    fun `关闭时明确说明`() {
        val cfg = CompanionConfig(enabled = false)
        val text = CompanionStatus.describe(state(cfg), t0, 600, inFocusSession = false)
        assertTrue(text.contains("已关闭"))
    }

    @Test
    fun `专注状态标签不暴露枚举名`() {
        val label = CompanionStatus.focusLabel("enforcing")
        assertTrue(label.contains("专注进行中"))
        assertFalse(label.contains("ENFORCING"))
        assertFalse(label.lowercase().contains("enforcing"))
    }

    @Test
    fun `失败原因翻成人话 且未知码不瞎猜`() {
        val a11y = CompanionStatus.failureLabel("a11y_unavailable", "x")
        assertTrue(a11y.contains("无障碍"))
        assertFalse("不能出现内部码", a11y.contains("a11y_unavailable"))
        // 未知码原样返回，不编造原因
        val unknown = CompanionStatus.failureLabel("some_new_code", "原始说明")
        assertTrue(unknown.contains("原始说明"))
    }

    @Test
    fun `所有状态标签都不含下划线枚举名`() {
        val names = listOf("preparing", "enforcing", "away_allowed", "blocking", "releasing", "completed", "user_ended", "failed")
        for (n in names) {
            val label = CompanionStatus.focusLabel(n)
            assertFalse("标签不能含枚举名：" + n + " -> " + label, label.contains(n))
            assertTrue("标签不能为空：" + n, label.isNotBlank())
        }
    }
}
