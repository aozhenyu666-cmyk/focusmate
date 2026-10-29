package com.focusmate.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 全屏拦截层。
 *
 * 旧实现（C3）的 F-05：用桌宠 Service 的 Context 加 TYPE_ACCESSIBILITY_OVERLAY，
 * addView 必然抛异常又被 runCatching 吞掉，于是控制器以为已拦截、屏幕其实没变。
 * 这里改成：
 * - 优先用 AccessibilityService 的 window context；
 * - addView 失败一律如实返回失败，由运行时转成 FAILED 状态；
 * - 覆盖层自带「返回专注」与「紧急结束」两个出口，不做无出口的死锁。
 */
class BlockOverlay(
    private val windowContext: Context,
    private val windowManager: WindowManager,
) {
    private var view: View? = null
    private var currentBlocked: String? = null

    var lastError: String? = null
        private set

    fun isShowing(): Boolean = view != null

    fun blockedPackage(): String? = currentBlocked

    /**
     * 显示拦截层。返回 true 才算真的挂上；false 时 lastError 一定有内容。
     */
    fun show(
        blockedPackage: String,
        reasonText: String,
        targetLabel: String,
        onReturnToTarget: () -> Unit,
        onEmergencyEnd: () -> Unit,
    ): Boolean {
        if (view != null) {
            // 已在显示：只更新文案，不重复 addView（旧实现重复添加会崩）。
            updateText(blockedPackage, reasonText, targetLabel)
            return true
        }
        val root = buildView(reasonText, targetLabel, onReturnToTarget, onEmergencyEnd)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "focusmate-block"
        }
        return try {
            windowManager.addView(root, params)
            view = root
            currentBlocked = blockedPackage
            lastError = null
            true
        } catch (t: Throwable) {
            view = null
            currentBlocked = null
            lastError = "覆盖层添加失败：${t.javaClass.simpleName}: ${t.message}"
            false
        }
    }

    fun hide(): Boolean {
        val v = view ?: return true
        return try {
            windowManager.removeViewImmediate(v)
            view = null
            currentBlocked = null
            lastError = null
            true
        } catch (t: Throwable) {
            lastError = "覆盖层移除失败：${t.javaClass.simpleName}: ${t.message}"
            // 移除失败时保留引用，等待下一次重试，不谎报已移除。
            false
        }
    }

    private fun updateText(blockedPackage: String, reasonText: String, targetLabel: String) {
        currentBlocked = blockedPackage
        (view?.findViewWithTag<TextView>("body"))?.text = reasonText
    }

    private fun buildView(
        reasonText: String,
        targetLabel: String,
        onReturnToTarget: () -> Unit,
        onEmergencyEnd: () -> Unit,
    ): View {
        val density = windowContext.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val container = LinearLayout(windowContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FFFDF8"))
            isClickable = true
            isFocusable = true
            // 吞掉返回键，避免用返回键退出覆盖层（旧实现漏了这一点）。
            setOnKeyListener { _, keyCode, event ->
                keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP
            }
        }

        val title = TextView(windowContext).apply {
            text = "专注进行中"
            textSize = 22f
            setTextColor(Color.parseColor("#1F1B16"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        val body = TextView(windowContext).apply {
            tag = "body"
            text = reasonText
            textSize = 15f
            setTextColor(Color.parseColor("#5B534A"))
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(12), dp(32), dp(28))
        }

        val back = Button(windowContext).apply {
            text = "回到 $targetLabel"
            setOnClickListener { onReturnToTarget() }
        }

        val end = Button(windowContext).apply {
            text = "紧急结束"
            setOnClickListener { onEmergencyEnd() }
        }

        container.addView(title)
        container.addView(body)
        container.addView(back, LinearLayout.LayoutParams(dp(200), LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(end, LinearLayout.LayoutParams(dp(200), LinearLayout.LayoutParams.WRAP_CONTENT))
        return container
    }
}
