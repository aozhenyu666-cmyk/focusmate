package com.focusmate.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.focusmate.runtime.GuardRuntime

/**
 * 只做两件事：
 * 1. 把「当前前台是哪个包」稳定报给运行时；
 * 2. 在连上时把本服务的 WindowManager 交给运行时，用于挂拦截层。
 *
 * 与旧实现（C3 FocusGuard）的差异：
 * - 状态迁移全在纯 Kotlin 的 FocusGuard 里，服务不持有规则（旧实现把规则散在服务里，出现过 F-01/F-07）；
 * - 每次事件后用 rootInActiveWindow 复核真实前台，拿不到就报 null，不猜测；
 * - 不吞 addView 异常（F-05）。
 */
class GuardAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        GuardRuntime.install(applicationContext)
        val wm = getSystemService(WindowManager::class.java)
        if (wm != null) {
            GuardRuntime.attachOverlayWindow(wm, this)
        }
        GuardRuntime.onAccessibilityConnected(true)
        GuardRuntime.reportForeground(activePackage())
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            -> GuardRuntime.reportForeground(activePackage())
        }
    }

    /**
     * onInterrupt 不等于服务断开。
     *
     * Android 文档：系统要求无障碍服务中断当前反馈时调用（例如用户按返回键触发导航、
     * 系统弹窗抢焦点）。服务本身仍然绑定、仍然会收到窗口事件。
     *
     * 实测踩到的坑：之前这里调用了 onAccessibilityConnected(false)，于是
     * 「专注中按返回键」→ 状态被判成 A11Y_UNAVAILABLE/FAILED → 覆盖层被撤掉
     * → 用户直接进到被限制的应用里。这等于一个按键就能绕过限制。
     *
     * 真正的断开只有 onUnbind / onDestroy。这里什么都不做。
     */
    override fun onInterrupt() {
        // 故意留空：见上方注释。
    }

    override fun onUnbind(intent: Intent?): Boolean {
        GuardRuntime.detachOverlayWindow()
        // 只有这里才是真的断开：用户关掉了无障碍，或系统解绑。
        GuardRuntime.onAccessibilityConnected(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        GuardRuntime.detachOverlayWindow()
        GuardRuntime.onAccessibilityConnected(false)
        super.onDestroy()
    }

    /** 复核真实前台；拿不到返回 null，由上层按未知处理。 */
    private fun activePackage(): String? = try {
        rootInActiveWindow?.packageName?.toString()
    } catch (t: Throwable) {
        null
    }
}
