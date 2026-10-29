package com.focusmate.core

/**
 * 进程重启 / 开机后的恢复决策。
 *
 * 抽成纯函数的原因：旧实现把这段逻辑写在 Service 里，出现过
 * 「文件读不出来 -> 当成没有会话 -> 限制留在系统里没人解」的问题。
 * 这里把「读到什么」和「该做什么」显式分成两支，读失败不会被当成没事。
 */
object Recovery {

    sealed interface Plan {
        /** 没有会话：什么都不用做。 */
        data object Nothing : Plan

        /** 有会话但读不出来：必须如实告知，不能当作没有。 */
        data class Unreadable(val error: String) : Plan

        /** 已是终态：只清理残留（覆盖层、通知），不复活。 */
        data class ClearLeftovers(val session: FocusSession) : Plan

        /** 还在进行但已过期：立刻本地解除。 */
        data class ExpireNow(val session: FocusSession) : Plan

        /** 还在进行且未过期：退回 PREPARING 等一次前台核验，并重排到期。 */
        data class Resume(val session: FocusSession) : Plan
    }

    fun decide(
        persisted: FocusSession?,
        readError: String?,
        nowMillis: Long,
    ): Plan {
        if (persisted == null) {
            return if (readError != null) Plan.Unreadable(readError) else Plan.Nothing
        }
        if (SessionStates.isTerminal(persisted.state)) {
            return Plan.ClearLeftovers(persisted)
        }
        if (nowMillis >= persisted.expiresAtMillis) {
            return Plan.ExpireNow(persisted)
        }
        return Plan.Resume(persisted.copy(state = SessionState.PREPARING))
    }
}
