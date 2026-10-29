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

    /**
     * 拦截层外观与 APP 其余页面共用一套设计语言（暖纸底、陶土红、圆角）。
     *
     * 为什么不用 Compose：这个 View 挂在无障碍服务的 WindowManager 上，
     * 用 ComposeView 需要额外的 LifecycleOwner 与 ViewTreeSavedStateRegistryOwner，
     * 在无障碍窗口里容易踩到生命周期问题。所以这里用纯 View 手绘，
     * 保持"稳定优先"，样式对齐即可。
     */
    private fun buildView(
        reasonText: String,
        targetLabel: String,
        onReturnToTarget: () -> Unit,
        onEmergencyEnd: () -> Unit,
    ): View {
        val density = windowContext.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val paper = Color.parseColor("#FBF7F1")
        val ink = Color.parseColor("#1E1A15")
        val inkSoft = Color.parseColor("#6B6157")
        val clay = Color.parseColor("#C75B39")
        val claySoft = Color.parseColor("#F6E3DA")

        val container = LinearLayout(windowContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(paper)
            isClickable = true
            isFocusable = true
            // 吞掉返回键，避免用返回键退出覆盖层。
            setOnKeyListener { _, keyCode, event ->
                keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP
            }
        }

        // 顶部一枚小圆点，暗示"正在守护"，比纯文字更轻。
        val dot = View(windowContext).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(clay)
                shape = android.graphics.drawable.GradientDrawable.OVAL
            }
        }
        container.addView(
            dot,
            LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(20)
            },
        )

        val title = TextView(windowContext).apply {
            text = "专注进行中"
            textSize = 26f
            setTextColor(ink)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        container.addView(title)

        val body = TextView(windowContext).apply {
            tag = "body"
            text = reasonText
            textSize = 15f
            setTextColor(inkSoft)
            gravity = Gravity.CENTER
            setLineSpacing(dp(6).toFloat(), 1f)
            setPadding(dp(40), dp(14), dp(40), dp(36))
        }
        container.addView(body)

        val back = TextView(windowContext).apply {
            text = "回到 " + targetLabel
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(clay)
                cornerRadius = dp(16).toFloat()
            }
            isClickable = true
            setOnClickListener { onReturnToTarget() }
        }
        container.addView(
            back,
            LinearLayout.LayoutParams(dp(240), dp(52)).apply { bottomMargin = dp(12) },
        )

        val end = TextView(windowContext).apply {
            text = "紧急结束"
            textSize = 15f
            setTextColor(clay)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Color.parseColor("#E8DFD3"))
                cornerRadius = dp(16).toFloat()
            }
            isClickable = true
            setOnClickListener { onEmergencyEnd() }
        }
        container.addView(end, LinearLayout.LayoutParams(dp(240), dp(48)))

        // 底部说明：把"为什么被拦"和"你现在能做什么"说清楚，
        // 而不是只留一句冷冰冰的拦截提示。
        val foot = TextView(windowContext).apply {
            text = "到点会自动解除。也可以在通知栏结束专注。"
            textSize = 12f
            setTextColor(Color.parseColor("#9A8F83"))
            gravity = Gravity.CENTER
            setPadding(dp(40), dp(28), dp(40), 0)
        }
        container.addView(foot)

        return container
    }
}
