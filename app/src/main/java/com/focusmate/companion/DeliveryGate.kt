package com.focusmate.companion

/**
 * 投递前的最后一道闸门：版本校验。
 *
 * 为什么单独抽出来：执行指令要求"生成前和实际输出前都检查版本"。
 * 两次检查之间可能隔着几秒钟的模型调用，用户在这期间完全可能改期、取消、更正。
 * 用纯函数把这件事固定下来，比分散在协程里靠自觉可靠。
 */
object DeliveryGate {

    sealed interface Verdict {
        /** 可以发。 */
        data object Send : Verdict

        /** 丢弃，附原因（写进 DeliveryTrace，不回给用户）。 */
        data class Drop(val reason: String) : Verdict
    }

    /**
     * @param issuedVersion 发起这次生成时事项的版本
     * @param currentVersion 现在磁盘上事项的版本
     * @param currentStatus 现在磁盘上事项的状态
     */
    fun check(
        followUpId: String,
        issuedVersion: Int,
        state: CompanionState,
        nowMillis: Long,
    ): Verdict {
        val item = state.followUps.firstOrNull { it.id == followUpId }
            ?: return Verdict.Drop("事项已不存在")
        if (item.version != issuedVersion) {
            return Verdict.Drop("事项在生成期间被改动（版本 " + issuedVersion + " -> " + item.version + "）")
        }
        return when (item.status) {
            FollowUpStatus.CANCELLED -> Verdict.Drop("事项已被取消")
            FollowUpStatus.EXPIRED -> Verdict.Drop("事项已过期")
            FollowUpStatus.DONE -> Verdict.Drop("事项已完成，不重复发")
            FollowUpStatus.FAILED -> Verdict.Drop("事项已标记失败")
            FollowUpStatus.PENDING, FollowUpStatus.DELIVERING -> {
                if (nowMillis > item.dueAtMillis + MAX_LATE_MILLIS) {
                    Verdict.Drop("迟到太久，不再补发")
                } else {
                    Verdict.Send
                }
            }
        }
    }

    /** 用户回应之后，同话题的主动事项应当视为已处理，避免"回完又冒一句"。 */
    fun closeOnUserReply(state: CompanionState, nowMillis: Long): CompanionState {
        var changed = false
        val next = state.followUps.map {
            if (it.open) {
                changed = true
                it.copy(status = FollowUpStatus.DONE, updatedAt = nowMillis, version = it.version + 1)
            } else it
        }
        return if (changed) state.copy(followUps = next, epoch = state.epoch + 1) else state
    }

    /** 与 CompanionConfig.pendingTtlDays 无关的第二道保护：单条迟到上限。 */
    const val MAX_LATE_MILLIS = 6L * 60L * 60_000L
}
