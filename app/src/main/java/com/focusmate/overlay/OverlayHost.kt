package com.focusmate.overlay

import android.content.Context
import android.view.WindowManager

/**
 * 覆盖层的持有者。
 *
 * 关键约束（旧实现 F-05 的根因）：TYPE_ACCESSIBILITY_OVERLAY 只能由无障碍服务的窗口加。
 * 所以这里不接受普通 Application Context 去硬加，而是在无障碍服务连上时
 * 把「服务自己的 WindowManager + 服务 Context」交给它；拿不到就直接报失败。
 */
class OverlayHost(private val appContext: Context) {

    private var blockOverlay: BlockOverlay? = null
    private var boundToService = false

    var lastError: String? = null
        private set

    fun bindServiceWindow(windowManager: WindowManager, serviceContext: Context) {
        if (blockOverlay == null) {
            blockOverlay = BlockOverlay(serviceContext, windowManager)
        }
        boundToService = true
    }

    /**
     * 服务解绑时调用。
     *
     * 注意：不要在这里把 overlay 直接 hide 掉。系统会瞬时解绑重绑，
     * 一 hide 就会在屏幕上一闪，而状态仍是 BLOCKING，用户看到的是
     * 「拦截没了但还被拦着」。这里只丢引用，重绑后由下一次前台事件重建。
     */
    fun unbindServiceWindow() {
        boundToService = false
        blockOverlay = null
    }

    fun windowReady(): Boolean = boundToService && blockOverlay != null

    fun isShowing(): Boolean = blockOverlay?.isShowing() == true

    fun blockedPackage(): String? = blockOverlay?.blockedPackage()

    fun show(
        blockedPackage: String,
        reasonText: String,
        targetLabel: String,
        onReturnToTarget: () -> Unit,
        onEmergencyEnd: () -> Unit,
    ): Boolean {
        val overlay = blockOverlay
        if (overlay == null) {
            lastError = "无障碍服务窗口未就绪，无法创建拦截层"
            return false
        }
        val ok = overlay.show(blockedPackage, reasonText, targetLabel, onReturnToTarget, onEmergencyEnd)
        lastError = overlay.lastError
        return ok
    }

    fun hide(): Boolean {
        val overlay = blockOverlay ?: return true
        val ok = overlay.hide()
        lastError = overlay.lastError
        return ok
    }
}
