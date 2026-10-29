package com.focusmate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeAndPresetsTest {

    @Test
    fun `沉浸舱默认十分钟 且带微信与通话`() {
        val s = Presets.geminiDock("com.google.android.apps.bard", 0L, Enforcement.OVERLAY_PLUS_A11Y)
        assertEquals(10L * 60L * 1000L, s.expiresAtMillis - s.startedAtMillis)
        assertTrue(s.exceptions.any { it.packageName == "com.tencent.mm" })
        assertEquals(AllowReason.EXCEPTION, s.exceptions.first { it.packageName == "com.tencent.mm" }.reason)
        assertEquals(SessionKind.GEMINI_DOCK, s.kind)
    }

    @Test
    fun `沉浸舱可以不带微信 用于对照验收`() {
        val s = Presets.geminiDock("com.google.android.apps.bard", 0L, Enforcement.OVERLAY_PLUS_A11Y, includeWeChat = false)
        assertTrue(s.exceptions.none { it.packageName == "com.tencent.mm" })
    }

    @Test
    fun `改系统时间不会缩短已持久化的到期时刻`() {
        val clock = FakeClock()
        val s = Presets.geminiDock("com.google.android.apps.bard", clock.nowMillis(), Enforcement.OVERLAY)
        val expected = s.expiresAtMillis
        clock.jumpWall(60L * 60L * 1000L)
        assertEquals(expected, s.expiresAtMillis)
    }

    @Test
    fun `重启后单调时钟归零 但到期仍按墙钟判断`() {
        val clock = FakeClock()
        val guard = FocusGuard(clock)
        val s = Presets.geminiDock("com.google.android.apps.bard", clock.nowMillis(), Enforcement.OVERLAY)
        clock.reboot(0L)
        assertEquals(0L, clock.elapsedMillis())
        val step = guard.onForeground(s, "com.ss.android.ugc.aweme", AllowPolicy.Verdict.BLOCK)
        // 时长还没过，重启不该让会话失效
        assertEquals(SessionState.BLOCKING, step.session.state)
    }

    @Test
    fun `单应用预设只带通话类例外 不带微信`() {
        val s = Presets.singleApp("com.xingin.xhs", 5L * 60L * 1000L, 0L, Enforcement.OVERLAY_PLUS_A11Y)
        assertTrue(s.exceptions.none { it.packageName == "com.tencent.mm" })
        assertTrue(s.exceptions.any { it.reason == AllowReason.CALL })
    }
}
