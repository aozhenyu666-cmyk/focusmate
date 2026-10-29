package com.focusmate.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {

    private val json = FileSessionStore.DefaultJson

    @Test
    fun `会话序列化往返保持所有关键字段`() {
        val clock = FakeClock()
        val s = Presets.geminiDock("com.google.android.apps.bard", clock.nowMillis(), Enforcement.OVERLAY_PLUS_A11Y)
            .copy(state = SessionState.BLOCKING, failure = Failure(FailureCode.OVERLAY_ADD_FAILED, "addView 抛异常", 123L))
        val text = json.encodeToString(FocusSession.serializer(), s)
        val back = json.decodeFromString(FocusSession.serializer(), text)
        assertEquals(s.id, back.id)
        assertEquals(s.kind, back.kind)
        assertEquals(s.ruleVersion, back.ruleVersion)
        assertEquals(s.targetPackage, back.targetPackage)
        assertEquals(s.startedAtMillis, back.startedAtMillis)
        assertEquals(s.expiresAtMillis, back.expiresAtMillis)
        assertEquals(s.plannedDurationMillis, back.plannedDurationMillis)
        assertEquals(s.state, back.state)
        assertEquals(s.enforcement, back.enforcement)
        assertEquals(s.failure?.code, back.failure?.code)
        assertEquals(s.exceptions.map { it.packageName }, back.exceptions.map { it.packageName })
        assertEquals(s.exceptions.map { it.reason }, back.exceptions.map { it.reason })
    }

    @Test
    fun `没有失败时反序列化后 failure 为 null`() {
        val s = Presets.singleApp("com.xingin.xhs", 1000L, 0L, Enforcement.OVERLAY)
        val back = json.decodeFromString(FocusSession.serializer(), json.encodeToString(FocusSession.serializer(), s))
        assertNull(back.failure)
    }

    @Test
    fun `枚举使用稳定字符串 便于跨版本读旧文件`() {
        val text = json.encodeToString(FocusSession.serializer(), Presets.singleApp("a.b", 1000L, 0L, Enforcement.OVERLAY))
        assertTrue(text.contains("\"single_app\""))
        assertTrue(text.contains("\"overlay\""))
        assertTrue(text.contains("\"preparing\""))
    }

    @Test
    fun `未知字段被忽略 不因新增字段读不出旧会话`() {
        val text = """
        {
          "id": "x",
          "kind": "single_app",
          "ruleVersion": 1,
          "targetPackage": "a.b",
          "exceptions": [],
          "startedAtMillis": 0,
          "expiresAtMillis": 1000,
          "plannedDurationMillis": 1000,
          "state": "enforcing",
          "enforcement": "overlay",
          "owned": { "overlayFor": [], "suspendRequested": [] },
          "someFutureField": 42
        }
        """.trimIndent()
        val back = json.decodeFromString(FocusSession.serializer(), text)
        assertEquals(SessionState.ENFORCING, back.state)
    }

    @Test
    fun `终态与活动状态集合互不重叠`() {
        val overlap = SessionStates.terminal intersect SessionStates.active
        assertTrue("终态与活动集合不能有交集，实际：" + overlap, overlap.isEmpty())
    }

    @Test
    fun `每个状态恰好属于一类 解除中是唯一的过渡态`() {
        for (s in SessionState.values()) {
            val terminal = SessionStates.isTerminal(s)
            val active = SessionStates.isActive(s)
            if (s == SessionState.RELEASING) {
                // 解除中是过渡态：既不能当终态，也不能再接受新的前台事件。
                assertTrue("解除中不能是终态", !terminal)
                assertTrue("解除中不能再算守护中", !active)
            } else {
                assertTrue("状态 " + s + " 必须属于且只属于一类", terminal != active)
            }
        }
    }

    @Test
    fun `解除中不再接受前台事件 避免边解除边拦截`() {
        val clock = FakeClock()
        val guard = FocusGuard(clock)
        val s = Presets.geminiDock("com.google.android.apps.bard", clock.nowMillis(), Enforcement.OVERLAY_PLUS_A11Y)
        val releasing = guard.expire(guard.onTargetVerified(guard.begin(s).session, "com.google.android.apps.bard").session).session
        assertEquals(SessionState.RELEASING, releasing.state)
        val late = guard.onForeground(releasing, "com.ss.android.ugc.aweme", AllowPolicy.Verdict.BLOCK)
        assertEquals(SessionState.RELEASING, late.session.state)
        assertTrue(late.effects.isEmpty())
    }

    @Test
    fun `规则版本变更可用来让旧异步结果失效`() {
        val clock = FakeClock()
        val guard = FocusGuard(clock)
        val s = Presets.geminiDock("com.google.android.apps.bard", clock.nowMillis(), Enforcement.OVERLAY_PLUS_A11Y)
        val begun = guard.begin(s).session
        val newer = begun.copy(ruleVersion = begun.ruleVersion + 1)
        // 旧版本结果不应被当作当前会话的结果：调用方比对 ruleVersion 即可丢弃
        assertTrue(newer.ruleVersion != begun.ruleVersion)
        assertEquals(1, begun.ruleVersion)
    }

    @Test
    fun `自有限制记录默认是空的 不会误解除别人的限制`() {
        val s = Presets.geminiDock("com.google.android.apps.bard", 0L, Enforcement.OVERLAY)
        assertTrue(s.owned.overlayFor.isEmpty())
        assertTrue(s.owned.suspendRequested.isEmpty())
    }

    @Test
    fun `旧会话文件没有新字段也能读 默认无别名且非用户结束`() {
        val text = """
        {
          "id": "x",
          "kind": "gemini_dock",
          "ruleVersion": 1,
          "targetPackage": "com.google.android.apps.bard",
          "exceptions": [],
          "startedAtMillis": 0,
          "expiresAtMillis": 1000,
          "plannedDurationMillis": 1000,
          "state": "enforcing",
          "enforcement": "overlay_plus_a11y",
          "owned": { "overlayFor": [], "suspendRequested": [] }
        }
        """.trimIndent()
        val back = json.decodeFromString(FocusSession.serializer(), text)
        assertTrue(back.targetAliases.isEmpty())
        assertEquals(false, back.endedByUser)
        assertEquals(false, back.isTarget("com.google.android.googlequicksearchbox"))
    }

    @Test
    fun `别名与用户结束标记序列化往返不丢`() {
        val s = Presets.geminiDock("com.google.android.apps.bard", 0L, Enforcement.OVERLAY_PLUS_A11Y)
            .copy(endedByUser = true)
        val back = json.decodeFromString(FocusSession.serializer(), json.encodeToString(FocusSession.serializer(), s))
        assertEquals(s.targetAliases, back.targetAliases)
        assertTrue(back.endedByUser)
    }
}
