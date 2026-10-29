package com.focusmate.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AllowPolicyTest {

    private val self = "com.focusmate"
    private val policy = AllowPolicy(
        target = "com.google.android.apps.bard",
        exceptions = setOf("com.tencent.mm", "com.android.dialer"),
    )

    @Test
    fun `目标应用判定为目标`() {
        assertEquals(AllowPolicy.Verdict.TARGET, policy.classify("com.google.android.apps.bard", self))
    }

    @Test
    fun `白名单应用判定为例外`() {
        assertEquals(AllowPolicy.Verdict.EXCEPTION, policy.classify("com.tencent.mm", self))
    }

    @Test
    fun `本 APP 自己不算离开`() {
        assertEquals(AllowPolicy.Verdict.NEUTRAL, policy.classify("com.focusmate", self))
    }

    @Test
    fun `空包名与空白包名不拦截`() {
        assertEquals(AllowPolicy.Verdict.NEUTRAL, policy.classify(null, self))
        assertEquals(AllowPolicy.Verdict.NEUTRAL, policy.classify("   ", self))
    }

    @Test
    fun `其它应用判定为拦截`() {
        assertEquals(AllowPolicy.Verdict.BLOCK, policy.classify("com.tencent.mobileqq", self))
        assertEquals(AllowPolicy.Verdict.BLOCK, policy.classify("com.xingin.xhs", self))
    }

    @Test
    fun `输入法不当作离开`() {
        val c = SystemUiClassifier()
        assertEquals(true, c.isNeutral("com.sohu.inputmethod.sogou"))
        assertEquals(true, c.isNeutral("com.baidu.input.ime"))
        assertEquals(false, c.isNeutral("com.ss.android.ugc.aweme"))
    }

    @Test
    fun `未知应用不会被默认放行`() {
        val c = SystemUiClassifier()
        assertEquals(false, c.isNeutral("com.some.new.app"))
    }
}
