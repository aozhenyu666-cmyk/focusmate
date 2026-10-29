package com.focusmate.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无障碍服务生命周期的静态约束。
 *
 * 为什么用读源码的测试：这是 Android 回调语义的坑，JVM 单测拿不到真服务，
 * 但「onInterrupt 不许当成断开」是可以用文本约束钉死的。
 * 实测故障：专注中按返回键 -> onInterrupt -> 被判 A11Y_UNAVAILABLE/FAILED
 * -> 覆盖层撤掉 -> 用户直接进到被限制应用。一个按键绕过限制。
 */
class A11yLifecycleTest {

    private fun serviceSource(): String {
        val candidates = listOf(
            File("src/main/java/com/focusmate/service/GuardAccessibilityService.kt"),
            File("app/src/main/java/com/focusmate/service/GuardAccessibilityService.kt"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到 GuardAccessibilityService.kt，工作目录=" + File(".").absolutePath)
        return file.readText()
    }

    @Test
    fun `onInterrupt 不能报告服务断开`() {
        val src = serviceSource()
        val body = src.substringAfter("override fun onInterrupt()", "").substringBefore("override fun")
        assertFalse(
            "onInterrupt 里不能调用 onAccessibilityConnected(false)：" + body,
            body.contains("onAccessibilityConnected(false)"),
        )
    }

    @Test
    fun `onUnbind 必须报告服务断开`() {
        val src = serviceSource()
        val body = src.substringAfter("override fun onUnbind", "").substringBefore("override fun")
        assertTrue("onUnbind 必须如实报告断开", body.contains("onAccessibilityConnected(false)"))
    }

    @Test
    fun `覆盖层必须吞掉返回键`() {
        val candidates = listOf(
            File("src/main/java/com/focusmate/overlay/BlockOverlay.kt"),
            File("app/src/main/java/com/focusmate/overlay/BlockOverlay.kt"),
        )
        val src = candidates.first { it.exists() }.readText()
        assertTrue("覆盖层要拦截 KEYCODE_BACK", src.contains("KEYCODE_BACK"))
    }
}
