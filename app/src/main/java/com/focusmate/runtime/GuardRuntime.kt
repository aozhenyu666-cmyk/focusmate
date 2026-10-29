package com.focusmate.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.focusmate.alarm.ExpiryScheduler
import com.focusmate.core.AllowPolicy
import com.focusmate.core.FailureCode
import com.focusmate.core.FileSessionStore
import com.focusmate.core.FocusGuard
import com.focusmate.core.FocusSession
import com.focusmate.core.Recovery
import com.focusmate.core.SessionState
import com.focusmate.core.SessionStates
import com.focusmate.core.SystemWallClock
import com.focusmate.notify.Notifier
import com.focusmate.overlay.OverlayHost
import com.focusmate.platform.Permissions
import com.focusmate.service.GuardForegroundService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 运行时的唯一协调点。四条硬约束（都对应旧实现踩过的坑）：
 *
 * 1. 只有这里能改会话状态；无障碍服务只上报「当前前台包名」和它的窗口。
 * 2. 每次状态迁移先存盘再执行系统动作；存盘失败就不执行，避免内存与磁盘不一致。
 * 3. 所有异步回调都带 sessionId 校验，迟到的旧会话回调直接丢弃（旧实现 F-06）。
 * 4. 系统动作失败一定落到 FAILED 并如实通知，不静默当作成功（旧实现 F-05/F-04）。
 */
object GuardRuntime {

    private val wallClock = SystemWallClock()
    private val guard = FocusGuard(wallClock)
    private val main = Handler(Looper.getMainLooper())

    private lateinit var appContext: Context
    private lateinit var store: FileSessionStore
    private lateinit var notifier: Notifier
    private lateinit var overlayHost: OverlayHost
    private lateinit var expiry: ExpiryScheduler

    private val installed = AtomicBoolean(false)

    @Volatile private var current: FocusSession? = null
    @Volatile private var a11yConnected = false
    @Volatile private var lastForeground: String? = null
    /** 上一次用于展示的提示，UI 读走即清。 */
    @Volatile private var pendingMessage: String? = null

    /** UI 观察用。 */
    @Volatile var onSessionChanged: ((FocusSession?) -> Unit)? = null

    val selfPackage: String get() = appContext.packageName

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        appContext = context.applicationContext
        store = FileSessionStore(File(appContext.filesDir, "focus/session.json"))
        notifier = Notifier(appContext)
        overlayHost = OverlayHost(appContext)
        expiry = ExpiryScheduler(appContext)
        restoreOnProcessStart()
    }

    fun session(): FocusSession? = current

    fun message(): String? = pendingMessage.also { pendingMessage = null }

    private fun publish(session: FocusSession?) {
        current = session
        onSessionChanged?.invoke(session)
    }

    private fun say(text: String) {
        pendingMessage = text
        onSessionChanged?.invoke(current)
    }

    // ---------------------------------------------------------------- 无障碍窗口绑定

    fun attachOverlayWindow(windowManager: WindowManager, serviceContext: Context) {
        overlayHost.bindServiceWindow(windowManager, serviceContext)
    }

    fun detachOverlayWindow() {
        overlayHost.unbindServiceWindow()
    }

    // ---------------------------------------------------------------- 进程恢复

    /**
     * 进程被杀后重启：不假设任何事，先读盘。
     * - 终态：清理覆盖层与通知，不复活。
     * - 进行中但已过期：立刻走本地解除。
     * - 进行中未过期：退回 PREPARING，重排闹钟，等一次前台核验。
     */
    private fun restoreOnProcessStart() {
        val persisted = store.read()
        val plan = Recovery.decide(persisted, store.lastError, wallClock.nowMillis())
        when (plan) {
            is Recovery.Plan.Nothing -> publish(null)

            is Recovery.Plan.Unreadable -> {
                // 读坏了不能当成"没有会话"：那样会让系统里残留的限制没人解。
                notifier.failure("会话记录读取失败", plan.error)
                publish(null)
            }

            is Recovery.Plan.ClearLeftovers -> {
                overlayHost.hide()
                notifier.clearGuard()
                publish(plan.session)
            }

            is Recovery.Plan.ExpireNow -> {
                publish(plan.session)
                applyEffects(guard.expire(plan.session))
            }

            is Recovery.Plan.Resume -> {
                persist(plan.session)
                publish(plan.session)
                expiry.schedule(plan.session.expiresAtMillis, plan.session.id)
                GuardForegroundService.start(appContext)
                notifier.guardRunning(plan.session)
                scheduleTargetVerify(plan.session.id, RESTORE_VERIFY_WINDOW_MILLIS)
            }
        }
    }

    // ---------------------------------------------------------------- 开始 / 结束

    /** 能力检查通过并真正落盘后才算开始；任何一步失败都回传原因给 UI。 */
    fun startSession(session: FocusSession, onError: (String) -> Unit) {
        val existing = current
        if (existing != null && SessionStates.isActive(existing.state)) {
            onError("已有进行中的专注，不能同时开第二个")
            return
        }
        if (!Permissions.hasOverlay(appContext)) {
            onError("缺少「显示在其他应用上层」权限，无法拦截其它应用")
            return
        }
        if (!Permissions.isAccessibilityEnabled(appContext)) {
            onError("未开启无障碍服务，无法识别当前前台应用")
            return
        }

        val begun = guard.begin(session)
        if (!persist(begun.session)) {
            onError("会话状态写入失败，未开始")
            return
        }
        publish(begun.session)
        expiry.schedule(begun.session.expiresAtMillis, begun.session.id)
        notifier.guardRunning(begun.session)
        applyEffects(begun)

        scheduleTargetVerify(begun.session.id, START_VERIFY_WINDOW_MILLIS)
    }

    /** 冷启动的 Gemini 常常超过 1 秒才到前台；在窗口期内轮询，到期仍不是目标才判失败。 */
    private const val START_VERIFY_WINDOW_MILLIS = 6_000L
    /** 重启/进程恢复后无障碍服务可能晚几秒才连上，给更长的窗口。 */
    private const val RESTORE_VERIFY_WINDOW_MILLIS = 15_000L
    private const val VERIFY_POLL_MILLIS = 400L

    private fun scheduleTargetVerify(sessionId: String, windowMillis: Long) {
        val deadline = android.os.SystemClock.elapsedRealtime() + windowMillis
        main.postDelayed({ verifyTargetStillForeground(sessionId, deadline) }, VERIFY_POLL_MILLIS)
    }

    /**
     * 启动后复核前台。这里是旧实现 F-01/F-06 的直接修复点：
     * 启动被厂商系统拒绝时，必须变成 FAILED，而不是停在 PREPARING 假装在守护。
     */
    private fun verifyTargetStillForeground(sessionId: String, deadlineElapsed: Long) {
        val s = current ?: return
        if (s.id != sessionId) return
        if (!SessionStates.isActive(s.state)) return
        val observed = lastForeground
        if (s.isTarget(observed)) {
            applyEffects(guard.onTargetVerified(s, observed))
            return
        }
        if (android.os.SystemClock.elapsedRealtime() < deadlineElapsed) {
            main.postDelayed({ verifyTargetStillForeground(sessionId, deadlineElapsed) }, VERIFY_POLL_MILLIS)
            return
        }
        if (observed == null) {
            val detail = if (a11yConnected) {
                "无障碍服务已连接但读不到前台应用，无法核验是否真的进入目标"
            } else {
                "无障碍服务未连接，无法核验前台"
            }
            applyEffects(guard.fail(s, FailureCode.A11Y_UNAVAILABLE, detail))
            return
        }
        applyEffects(guard.onTargetVerified(s, observed))
    }

    fun endByUser(reason: String) {
        val s = current ?: return
        applyEffects(guard.userEnd(s, reason))
    }

    fun emergencyEnd() = endByUser("紧急结束")

    // ---------------------------------------------------------------- 前台事件

    fun onAccessibilityConnected(connected: Boolean) {
        a11yConnected = connected
        if (!connected) {
            val s = current ?: return
            if (SessionStates.isActive(s.state)) {
                applyEffects(guard.fail(s, FailureCode.A11Y_UNAVAILABLE, "无障碍服务断开，无法继续识别前台；限制已停止"))
            }
        }
    }

    fun reportForeground(packageName: String?) {
        lastForeground = packageName
        val s = current ?: return
        if (!SessionStates.isActive(s.state)) return
        if (packageName == null) return

        if (wallClock.nowMillis() >= s.expiresAtMillis) {
            applyEffects(guard.expire(s))
            return
        }

        val policy = AllowPolicy(
            target = s.targetPackage,
            exceptions = s.exceptions.map { it.packageName }.toSet(),
            targetAliases = s.targetAliases.toSet(),
        )
        applyEffects(guard.onForeground(s, packageName, policy.classify(packageName, selfPackage)))
    }

    /** 用户点覆盖层上的「回到目标」。走和开始相同的启动路径，并核验。 */
    fun returnToTarget() {
        val s = current ?: return
        if (!SessionStates.isActive(s.state)) return
        launchTarget(s)
        main.postDelayed({ verifyReturnedToTarget(s.id) }, 900L)
    }

    /** 回到目标的核验：只有真的回到目标才收起拦截层（旧实现 F-03）。 */
    private fun verifyReturnedToTarget(sessionId: String) {
        val s = current ?: return
        if (s.id != sessionId) return
        if (!SessionStates.isActive(s.state)) return
        if (s.isTarget(lastForeground)) {
            retryCount = 0
            applyEffects(guard.onForeground(s, lastForeground, AllowPolicy.Verdict.TARGET))
        } else {
            // 没回去就保持拦截，不谎报成功；重试有上限，避免无限拉回。
            retryCount++
            if (retryCount <= MAX_RETURN_RETRY) {
                main.postDelayed({ returnToTarget() }, backoffMillis())
            } else {
                retryCount = 0
                say("没能自动回到目标应用，请手动打开，或选择紧急结束")
            }
        }
    }

    private val MAX_RETURN_RETRY = 5
    private var retryCount = 0
    private fun backoffMillis(): Long = (600L * (retryCount + 1)).coerceAtMost(3000L)

    // ---------------------------------------------------------------- 到期

    /** 闹钟、前台服务 tick、开机广播都走这里；到期判定纯本地，不依赖网络或模型。 */
    fun checkExpiry() {
        val s = current ?: return
        if (!SessionStates.isActive(s.state)) return
        if (wallClock.nowMillis() >= s.expiresAtMillis) {
            applyEffects(guard.expire(s))
        }
    }

    // ---------------------------------------------------------------- 效果执行

    private fun applyEffects(step: FocusGuard.Step) {
        if (step.rejected != null) return
        if (!persist(step.session)) {
            notifier.failure("状态保存失败", "本次状态变更未执行，请检查存储空间")
            publish(step.session)
            return
        }
        publish(step.session)

        for (effect in step.effects) {
            when (effect) {
                is FocusGuard.Effect.LaunchTarget -> launchTarget(step.session)
                is FocusGuard.Effect.ShowOverlay -> showOverlay(step.session, effect.blockedPackage)
                is FocusGuard.Effect.HideOverlay -> overlayHost.hide()
                is FocusGuard.Effect.ReleaseRestrictions -> releaseAndVerify(step.session)
                is FocusGuard.Effect.NotifySessionEnded -> {
                    overlayHost.hide()
                    notifier.sessionEnded(step.session)
                    GuardForegroundService.stop(appContext)
                }
                is FocusGuard.Effect.NotifyFailure -> {
                    overlayHost.hide()
                    notifier.failure("专注没能按预期执行", step.session.failure?.detail ?: "未知原因")
                    GuardForegroundService.stop(appContext)
                }
            }
        }

        if (SessionStates.isTerminal(step.session.state)) expiry.cancel()
    }

    private fun launchTarget(session: FocusSession) {
        val intent = try {
            appContext.packageManager.getLaunchIntentForPackage(session.targetPackage)
        } catch (t: Throwable) {
            null
        }
        if (intent == null) {
            val s = current ?: return
            applyEffects(guard.fail(s, FailureCode.TARGET_LAUNCH_FAILED, "找不到 ${session.targetPackage} 的启动入口，可能未安装"))
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            appContext.startActivity(intent)
            GuardForegroundService.start(appContext)
        } catch (t: Throwable) {
            val s = current ?: return
            applyEffects(guard.fail(s, FailureCode.TARGET_LAUNCH_FAILED, "启动目标被系统拒绝：${t.message}"))
        }
    }

    private fun showOverlay(session: FocusSession, blockedPackage: String) {
        val label = sessionLabel(session)
        val ok = overlayHost.show(
            blockedPackage = blockedPackage,
            reasonText = "这段时间只开放 " + label + "。\n回到 " + label + "，或选择紧急结束。",
            targetLabel = label,
            onReturnToTarget = { returnToTarget() },
            onEmergencyEnd = { emergencyEnd() },
        )
        if (!ok) {
            val s = current ?: return
            applyEffects(guard.fail(s, FailureCode.OVERLAY_ADD_FAILED, overlayHost.lastError ?: "覆盖层添加失败"))
        }
    }

    /** 解除并核验。核验不看「我刚调了 hide 就说明成功」，而是再读一次覆盖层状态。 */
    private fun releaseAndVerify(session: FocusSession) {
        overlayHost.hide()
        val stillBlocked = if (overlayHost.isShowing()) {
            listOf(overlayHost.blockedPackage() ?: session.targetPackage)
        } else {
            emptyList()
        }
        if (stillBlocked.isNotEmpty()) {
            val s = current ?: session
            applyEffects(guard.fail(s, FailureCode.RELEASE_UNVERIFIED, overlayHost.lastError ?: "拦截层未能移除"))
            return
        }
        applyEffects(guard.onReleaseVerified(current ?: session, stillBlocked))
    }

    private fun sessionLabel(session: FocusSession): String = try {
        val pm = appContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(session.targetPackage, 0)).toString()
    } catch (t: Throwable) {
        session.targetPackage
    }

    // ---------------------------------------------------------------- 工具

    private fun persist(session: FocusSession): Boolean = try {
        store.write(session)
        true
    } catch (t: Throwable) {
        notifier.failure("状态保存失败", "${t.javaClass.simpleName}: ${t.message}")
        false
    }

    /** 能力差距说明，UI 直接显示，不美化。 */
    fun capabilityNotes(): List<String> = buildList {
        add(if (Permissions.hasOverlay(appContext)) "悬浮窗权限：已授予" else "悬浮窗权限：未授予（无法拦截其它应用）")
        add(if (Permissions.isAccessibilityEnabled(appContext)) "无障碍服务：已开启" else "无障碍服务：未开启（无法识别前台应用）")
        add(if (expiry.canScheduleExact()) "精确闹钟：可用" else "精确闹钟：不可用（到期提醒可能迟到）")
        add("一期不含：锁屏、挂起应用、Force Stop、设备所有者。这些都需要额外系统权限或重置设备。")
        add("厂商系统可能阻止后台启动目标应用；被拒绝时会如实报失败，不会显示为已开始。")
        add("「强行停止」后不会自动恢复，需要重新打开本 APP。")
    }

    fun setServiceMessage(text: String) = say(text)

    fun context(): Context = appContext
}
