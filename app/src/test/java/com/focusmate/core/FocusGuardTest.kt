package com.focusmate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusGuardTest {

    private val selfPkg = "com.focusmate"
    private val target = "com.google.android.apps.bard"

    private fun dock(clock: FakeClock): FocusSession =
        Presets.geminiDock(target, clock.nowMillis(), Enforcement.OVERLAY_PLUS_A11Y)

    private fun readySession(clock: FakeClock): FocusSession {
        val guard = FocusGuard(clock)
        val beginning = guard.begin(dock(clock))
        return guard.onTargetVerified(beginning.session, target).session
    }

    private fun policyOf(session: FocusSession) =
        AllowPolicy(session.targetPackage, session.exceptions.map { it.packageName }.toSet())

    @Test
    fun `begin 写入意图但不声称已生效`() {
        val clock = FakeClock()
        val guard = FocusGuard(clock)
        val step = guard.begin(dock(clock))
        assertEquals(SessionState.PREPARING, step.session.state)
        assertEquals(listOf(FocusGuard.Effect.LaunchTarget), step.effects)
    }

    @Test
    fun `目标核验通过才进入 ENFORCING`() {
        val clock = FakeClock()
        val guard = FocusGuard(clock)
        val verified = guard.onTargetVerified(guard.begin(dock(clock)).session, target)
        assertEquals(SessionState.ENFORCING, verified.session.state)
        assertNull(verified.session.failure)
    }

    @Test
    fun `启动后前台不是目标就是失败 不能假装已开始`() {
        val clock = FakeClock()
        val guard = FocusGuard(clock)
        val step = guard.onTargetVerified(guard.begin(dock(clock)).session, "com.tencent.mm")
        assertEquals(SessionState.FAILED, step.session.state)
        assertEquals(FailureCode.TARGET_LAUNCH_FAILED, step.session.failure?.code)
        assertTrue(step.effects.contains(FocusGuard.Effect.NotifyFailure))
    }

    @Test
    fun `前台未知也算失败 不默认成功`() {
        val clock = FakeClock()
        val guard = FocusGuard(clock)
        val step = guard.onTargetVerified(guard.begin(dock(clock)).session, null)
        assertEquals(SessionState.FAILED, step.session.state)
    }

    @Test
    fun `进入非白名单应用产生拦截 并记录本 APP 自己挂的包名`() {
        val clock = FakeClock()
        val guard = FocusGuard(clock)
        val session = readySession(clock)
        val policy = policyOf(session)
        val step = guard.onForeground(session, "com.ss.android.ugc.aweme", policy.classify("com.ss.android.ugc.aweme", selfPkg))
        assertEquals(SessionState.BLOCKING, step.session.state)
        assertEquals(listOf(FocusGuard.Effect.ShowOverlay("com.ss.android.ugc.aweme")), step.effects)
        assertEquals(listOf("com.ss.android.ugc.aweme"), step.session.owned.overlayFor)
    }

    @Test
    fun `微信按已确认规则放行 但仍留在守护中`() {
        val clock = FakeClock()
        val guard = FocusGuard(clock)
        val session = readySession(clock)
        val step = guard.onForeground(session, "com.tencent.mm", policyOf(session).classify("com.tencent.mm", selfPkg))
        assertEquals(SessionState.AWAY_ALLOWED, step.session.state)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun `通话应用放行 不弹覆盖层`() {
        val clock = FakeClock()
        val guard = FocusGuard(clock)
        val session = readySession(clock)
        val step = guard.onForeground(session, "com.android.dialer", policyOf(session).classify("com.android.dialer", selfPkg))
        assertEquals(SessionState.AWAY_ALLOWED, step.session.state)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun `系统界面与输入法不触发拦截 也不改变状态`() {
        val clock = FakeClock()
        val guard = FocusGuard(clock)
        val session = readySession(clock)
        val policy = policyOf(session)
        for (p in listOf("com.android.systemui", "com.android.permissioncontroller", "com.sohu.inputmethod.sogou", selfPkg)) {
            val step = guard.onForeground(session, p, policy.classify(p, selfPkg))
            assertEquals("包名 $p 不该改变状态", SessionState.ENFORCING, step.session.state)
            assertTrue("包名 $p 不该产生效果", step.effects.isEmpty())
        }
    }

    @Test
    fun `回到目标时收起拦截层`() {
        val clock = FakeClock()
        val guard = FocusGuard(clock)
        val session = readySession(clock)
        val policy = policyOf(session)
        val blocked = guard.onForeground(session, "com.ss.android.ugc.aweme", policy.classify("com.ss.android.ugc.aweme", selfPkg)).session
        assertEquals(SessionState.BLOCKING, blocked.state)
        val back = guard.onForeground(blocked, target, policy.classify(target, selfPkg))
        assertEquals(SessionState.ENFORCING, back.session.state)
        assertTrue(back.effects.contains(FocusGuard.Effect.HideOverlay("回到目标应用")))
    }

    @Test
    fun `到期走本地解除 不等待任何外部服务`() {
        val clock = FakeClock()
        val guard = FocusGuard(clock)
        val session = readySession(clock)
        clock.advance(Presets.DOCK_DURATION_MILLIS + 1)
        val step = guard.onForeground(session, "com.ss.android.ugc.aweme", policyOf(session).classify("com.ss.android.ugc.aweme", selfPkg))
        assertEquals(SessionState.RELEASING, step.session.state)
        assertTrue(step.effects.contains(FocusGuard.Effect.ReleaseRestrictions))
        assertTrue("到期后不再新增拦截", step.effects.none { it is FocusGuard.Effect.ShowOverlay })
    }

    @Test
    fun `到期解除核验通过才算完成`() {
        val clock = FakeClock()
        val guard = FocusGuard(clock)
        val session = readySession(clock)
        val releasing = guard.expire(session).session
        val done = guard.onReleaseVerified(releasing, emptyList())
        assertEquals(SessionState.COMPLETED, done.session.state)
        assertTrue(done.effects.contains(FocusGuard.Effect.NotifySessionEnded))
    }

    @Test
    fun `解除核验失败就是失败 不能记成完成`() {
        val clock = FakeClock()
        val guard = FocusGuard(clock)
        val session = readySession(clock)
        val releasing = guard.expire(session).session
        val step = guard.onReleaseVerified(releasing, listOf("com.ss.android.ugc.aweme"))
        assertEquals(SessionState.FAILED, step.session.state)
        assertEquals(FailureCode.RELEASE_UNVERIFIED, step.session.failure?.code)
    }

    @Test
    fun `终态不被迟到的前台回调复活`() {
        val clock = FakeClock()
        val guard = FocusGuard(clock)
        val session = readySession(clock)
        val completed = guard.onReleaseVerified(guard.expire(session).session, emptyList()).session
        val late = guard.onForeground(completed, "com.ss.android.ugc.aweme", policyOf(session).classify("com.ss.android.ugc.aweme", selfPkg))
        assertEquals(SessionState.COMPLETED, late.session.state)
        assertTrue(late.effects.isEmpty())
        assertNotNull(late.rejected)
    }

    @Test
    fun `终态不被迟到的生效回调改写`() {
        val clock = FakeClock()
        val guard = FocusGuard(clock)
        val session = readySession(clock)
        val failed = guard.fail(session, FailureCode.A11Y_UNAVAILABLE, "服务断开").session
        val late = guard.onTargetVerified(failed, target)
        assertEquals(SessionState.FAILED, late.session.state)
        assertNotNull(late.rejected)
    }

    @Test
    fun `重复开始同一终态会话被拒绝`() {
        val clock = FakeClock()
        val guard = FocusGuard(clock)
        val session = readySession(clock)
        val completed = guard.onReleaseVerified(guard.expire(session).session, emptyList()).session
        var threw = false
        try { guard.begin(completed) } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun `重名拦截多次只保留一条自有记录`() {
        val clock = FakeClock()
        val guard = FocusGuard(clock)
        val session = readySession(clock)
        val policy = policyOf(session)
        val pkg = "com.ss.android.ugc.aweme"
        var s = guard.onForeground(session, pkg, policy.classify(pkg, selfPkg)).session
        s = guard.onForeground(s.copy(state = SessionState.BLOCKING), pkg, policy.classify(pkg, selfPkg)).session
        assertEquals(listOf(pkg), s.owned.overlayFor)
    }

    @Test
    fun `没有包名时不拦截 也不报错`() {
        val clock = FakeClock()
        val guard = FocusGuard(clock)
        val session = readySession(clock)
        val step = guard.onForeground(session, null, AllowPolicy.Verdict.BLOCK)
        assertEquals(SessionState.ENFORCING, step.session.state)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun `时长必须为正 不然开始就报错`() {
        val clock = FakeClock()
        val guard = FocusGuard(clock)
        val bad = Presets.geminiDock(target, clock.nowMillis(), Enforcement.OVERLAY_PLUS_A11Y, true, 0L)
        var threw = false
        try { guard.begin(bad) } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun `用户结束经核验后记为 USER_ENDED 而不是 COMPLETED`() {
        val clock = FakeClock()
        val guard = FocusGuard(clock)
        val releasing = guard.userEnd(readySession(clock), "紧急结束").session
        val done = guard.onReleaseVerified(releasing, emptyList())
        assertEquals(SessionState.USER_ENDED, done.session.state)
    }

    @Test
    fun `Gemini 实际跑在 Google App 里也算进入目标`() {
        val clock = FakeClock()
        val guard = FocusGuard(clock)
        val verified = guard.onTargetVerified(guard.begin(dock(clock)).session, "com.google.android.googlequicksearchbox")
        assertEquals(SessionState.ENFORCING, verified.session.state)
    }

    @Test
    fun `拦截中回到 Google App 里的 Gemini 会收起拦截层`() {
        val clock = FakeClock()
        val guard = FocusGuard(clock)
        val session = readySession(clock)
        val policy = AllowPolicy(
            session.targetPackage,
            session.exceptions.map { it.packageName }.toSet(),
            targetAliases = session.targetAliases.toSet(),
        )
        val blocked = guard.onForeground(session, "com.ss.android.ugc.aweme", policy.classify("com.ss.android.ugc.aweme", selfPkg)).session
        val back = guard.onForeground(blocked, "com.google.android.googlequicksearchbox", policy.classify("com.google.android.googlequicksearchbox", selfPkg))
        assertEquals(SessionState.ENFORCING, back.session.state)
        assertTrue(back.effects.any { it is FocusGuard.Effect.HideOverlay })
    }
}
