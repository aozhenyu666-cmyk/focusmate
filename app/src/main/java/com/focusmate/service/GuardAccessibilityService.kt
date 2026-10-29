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

    override fun onInterrupt() {
        GuardRuntime.onAccessibilityConnected(false)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        GuardRuntime.detachOverlayWindow()
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
