package com.focusmate.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 会话状态机。设计原则：
 * 1. 只有 PREPARING -> ENFORCING 之间允许「核验生效」这一步跨过；
 * 2. 任何进入失败分支的状态都不会被迟到回调改回成功；
 * 3. RELEASING 必须核验过才算 COMPLETED，核验不了就是 RELEASE_UNVERIFIED。
 */
@Serializable
enum class SessionState {
    /** 已写入意图，但还没真正执行。 */
    @SerialName("preparing") PREPARING,

    /** 限制已挂上，且已核验目标在前台。 */
    @SerialName("enforcing") ENFORCING,

    /** 用户暂时在允许的例外应用里（通话、微信）。仍在守护中。 */
    @SerialName("away_allowed") AWAY_ALLOWED,

    /** 用户进了不该进的应用，覆盖层已经拦住。 */
    @SerialName("blocking") BLOCKING,

    /** 到点了，正在解除。 */
    @SerialName("releasing") RELEASING,

    /** 解除并核验完成。 */
    @SerialName("completed") COMPLETED,

    /** 用户主动结束（走已确认的规则）。 */
    @SerialName("user_ended") USER_ENDED,

    /** 失败或结果未知，需用户处理，绝不当作完成。 */
    @SerialName("failed") FAILED,
}

object SessionStates {
    /** 终态：不会被任何迟到回调复活。 */
    val terminal = setOf(SessionState.COMPLETED, SessionState.USER_ENDED, SessionState.FAILED)

    /** 仍在守护中的状态。 */
    val active = setOf(SessionState.PREPARING, SessionState.ENFORCING, SessionState.AWAY_ALLOWED, SessionState.BLOCKING)

    fun isTerminal(s: SessionState): Boolean = s in terminal
    fun isActive(s: SessionState): Boolean = s in active
}
