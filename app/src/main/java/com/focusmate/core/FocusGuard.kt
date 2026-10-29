package com.focusmate.core

/**
 * 纯状态机：不碰 Android API，全部可做 JVM 单测。
 *
 * 对照 DetoxDroid 的 BLOCK/SUSPEND/DEACTIVATE 三档与本项目已确认的能力边界，
 * 一期只用 BLOCK 语义（覆盖层），并把每条路径都做成显式状态，
 * 避免旧实现里「控制器以为拦住了、其实 addView 抛异常」的假成功。
 */
class FocusGuard(private val clock: WallClock) {

    sealed interface Effect {
        /** 需要真正去执行的动作，由 Android 层兑现。 */
        data class ShowOverlay(val blockedPackage: String) : Effect
        data class HideOverlay(val reason: String) : Effect
        data object LaunchTarget : Effect
        data object ReleaseRestrictions : Effect
        data object NotifySessionEnded : Effect
        data object NotifyFailure : Effect
    }

    data class Step(
        val session: FocusSession,
        val effects: List<Effect>,
        val rejected: String? = null,
    )

    /**
     * 开始一次会话。先落盘意图（由调用方负责），再挂限制，最后核验。
     * 这里只决定「下一个状态应该是 PREPARING」，不做任何系统调用。
     */
    fun begin(session: FocusSession): Step {
        require(!SessionStates.isTerminal(session.state)) { "终态会话不能重新开始" }
        require(session.expiresAtMillis > session.startedAtMillis) { "到期时间必须晚于开始时间" }
        val preparing = session.copy(state = SessionState.PREPARING, failure = null)
        return Step(preparing, listOf(Effect.LaunchTarget))
    }

    /**
     * 目标确实回到前台之后才允许进入 ENFORCING。
     * 未返回目标（例如厂商系统禁止后台启动）必须明确失败，不能假装已开始。
     */
    fun onTargetVerified(session: FocusSession, observedForeground: String?): Step {
        if (SessionStates.isTerminal(session.state) || session.state == SessionState.RELEASING) {
            return Step(session, emptyList(), rejected = "终态或解除中不接受生效回调")
        }
        if (!session.isTarget(observedForeground)) {
            val failed = session.copy(
                state = SessionState.FAILED,
                failure = Failure(
                    code = FailureCode.TARGET_LAUNCH_FAILED,
                    detail = "启动后前台是 ${observedForeground ?: "未知"}，不是目标 ${session.targetPackage}",
                    atMillis = clock.nowMillis(),
                ),
            )
            return Step(failed, listOf(Effect.NotifyFailure))
        }
        return Step(session.copy(state = SessionState.ENFORCING, failure = null), emptyList())
    }

    /** 前台变化。allow 由 AllowPolicy 给出，这里只做状态迁移与效果生成。 */
    fun onForeground(
        session: FocusSession,
        packageName: String?,
        verdict: AllowPolicy.Verdict,
    ): Step {
        if (!SessionStates.isActive(session.state)) {
            return Step(session, emptyList(), rejected = "会话不在守护中")
        }
        // 到期判定优先于前台判定：到点了就不再新增拦截。
        if (clock.nowMillis() >= session.expiresAtMillis) {
            return expire(session)
        }
        return when (verdict) {
            AllowPolicy.Verdict.TARGET -> {
                val effects = mutableListOf<Effect>()
                if (session.state == SessionState.BLOCKING) effects += Effect.HideOverlay("回到目标应用")
                Step(session.copy(state = SessionState.ENFORCING), effects)
            }

            AllowPolicy.Verdict.EXCEPTION -> {
                val effects = mutableListOf<Effect>()
                if (session.state == SessionState.BLOCKING) effects += Effect.HideOverlay("进入允许的例外应用")
                Step(session.copy(state = SessionState.AWAY_ALLOWED), effects)
            }

            AllowPolicy.Verdict.NEUTRAL -> Step(session, emptyList())

            AllowPolicy.Verdict.BLOCK -> {
                val pkg = packageName ?: return Step(session, emptyList(), rejected = "无包名不拦截")
                val owned = session.owned.copy(overlayFor = (session.owned.overlayFor + pkg).distinct())
                Step(
                    session.copy(state = SessionState.BLOCKING, owned = owned),
                    listOf(Effect.ShowOverlay(pkg)),
                )
            }
        }
    }

    /** 到期：交给本地规则，绝不等待模型或网络。 */
    fun expire(session: FocusSession): Step {
        if (SessionStates.isTerminal(session.state)) {
            return Step(session, emptyList(), rejected = "已是终态")
        }
        val releasing = session.copy(state = SessionState.RELEASING)
        return Step(releasing, listOf(Effect.HideOverlay("到期解除"), Effect.ReleaseRestrictions))
    }

    /** 用户主动结束。规则未确认前，只允许「立即结束」这一种，且必须留痕。 */
    fun userEnd(session: FocusSession, reason: String): Step {
        if (SessionStates.isTerminal(session.state)) {
            return Step(session, emptyList(), rejected = "已是终态")
        }
        val ending = session.copy(state = SessionState.RELEASING, endedByUser = true)
        return Step(ending, listOf(Effect.HideOverlay("用户结束：$reason"), Effect.ReleaseRestrictions))
    }

    /** 解除结果核验。核验不了就是失败，不能记成完成。 */
    fun onReleaseVerified(session: FocusSession, stillBlocked: List<String>): Step {
        if (session.state != SessionState.RELEASING) {
            return Step(session, emptyList(), rejected = "不在解除流程中")
        }
        if (stillBlocked.isNotEmpty()) {
            val failed = session.copy(
                state = SessionState.FAILED,
                failure = Failure(
                    code = FailureCode.RELEASE_UNVERIFIED,
                    detail = "解除后仍在拦截：${stillBlocked.joinToString()}",
                    atMillis = clock.nowMillis(),
                ),
            )
            return Step(failed, listOf(Effect.NotifyFailure))
        }
        val completed = session.copy(
            state = if (session.endedByUser) SessionState.USER_ENDED else SessionState.COMPLETED,
            owned = session.owned.copy(overlayFor = emptyList()),
        )
        return Step(completed, listOf(Effect.NotifySessionEnded))
    }

    /** 标记失败：任何一步出问题时统一入口，保证不会静默降级成成功。 */
    fun fail(session: FocusSession, code: FailureCode, detail: String): Step {
        if (SessionStates.isTerminal(session.state)) {
            return Step(session, emptyList(), rejected = "已是终态")
        }
        return Step(
            session.copy(state = SessionState.FAILED, failure = Failure(code, detail, clock.nowMillis())),
            listOf(Effect.HideOverlay("失败清理"), Effect.NotifyFailure),
        )
    }
}
