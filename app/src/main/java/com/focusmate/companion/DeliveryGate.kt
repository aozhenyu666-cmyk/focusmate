package com.focusmate.companion

/**
 * 投递前的最后一道闸门。
 *
 * 执行指令要求「生成前和实际输出前都检查版本」。两次检查之间可能隔着几秒的模型调用，
 * 用户在这期间完全可能改期、取消、更正事实，甚至把免打扰/专注状态改了。
 * 所以这里要查四件事，少一件就会出现「旧结果照发」：
 *
 *   1. 事项版本（改期/取消/换话题）
 *   2. 事项状态（已取消/已过期/已完成/已失败）
 *   3. 记忆版本（事实被更正/遗忘、对话被追加）—— F-05
 *   4. 当前是否仍然允许开口（免打扰、专注中、开关）—— F-05 后半
 */
object DeliveryGate {

    sealed interface Verdict {
        /** 可以发。 */
        data object Send : Verdict

        /** 丢弃，附原因（写进 DeliveryTrace，不回给用户）。 */
        data class Drop(val reason: String) : Verdict
    }

    /**
     * 输出前的完整校验。
     *
     * @param issuedVersion       发起生成时事项的版本
     * @param issuedMemoryVersion 发起生成时的记忆版本
     * @param nowAllowsSpeaking   此刻是否仍然允许开口（由调用方用 SchedulePolicy 判定）
     */
    fun check(
        followUpId: String,
        issuedVersion: Int,
        issuedMemoryVersion: Long,
        state: CompanionState,
        nowMillis: Long,
        nowAllowsSpeaking: Boolean = true,
    ): Verdict {
        val item = state.followUps.firstOrNull { it.id == followUpId }
            ?: return Verdict.Drop("事项已不存在")

        // 1) 事项版本
        if (item.version != issuedVersion) {
            return Verdict.Drop("事项在生成期间被改动（版本 " + issuedVersion + " -> " + item.version + "）")
        }

        // 2) 记忆版本：事实被更正/遗忘、又说了新话，都会让旧上下文过期。
        if (state.memoryVersion != issuedMemoryVersion) {
            return Verdict.Drop("记忆在生成期间有更新（记忆版本 " + issuedMemoryVersion + " -> " + state.memoryVersion + "）")
        }

        // 3) 事项状态
        when (item.status) {
            FollowUpStatus.CANCELLED -> return Verdict.Drop("事项已被取消")
            FollowUpStatus.EXPIRED -> return Verdict.Drop("事项已过期")
            FollowUpStatus.DONE -> return Verdict.Drop("事项已完成，不重复发")
            FollowUpStatus.FAILED -> return Verdict.Drop("事项已标记失败")
            else -> Unit
        }

        // 4) 迟到
        if (nowMillis > item.dueAtMillis + MAX_LATE_MILLIS) {
            return Verdict.Drop("迟到太久，不再补发")
        }

        // 5) 生成开始时允许、结束时已经不允许（进入专注 / 进入免打扰 / 关掉开关）
        if (!nowAllowsSpeaking) {
            return Verdict.Drop("输出时已不允许开口（专注中/免打扰/已关闭）")
        }

        return Verdict.Send
    }

    /**
     * 用户回应之后，结算**正在等他回应**的事项。
     *
     * 关键约束（F-03）：只处理 AWAITING_REPLY。
     * 未来还没到期的 PENDING 约定与用户这句话无关，绝不能一起标成已完成。
     * 之前把 `it.open` 全关掉，等于「随口说句别的，明天的提醒就没了」。
     */
    fun closeOnUserReply(state: CompanionState, nowMillis: Long): CompanionState {
        var changed = false
        val next = state.followUps.map {
            if (it.status == FollowUpStatus.AWAITING_REPLY) {
                changed = true
                it.copy(status = FollowUpStatus.DONE, updatedAt = nowMillis, version = it.version + 1)
            } else it
        }
        return if (changed) state.copy(followUps = next, epoch = state.epoch + 1) else state
    }

    /** 与 CompanionConfig.pendingTtlDays 无关的第二道保护：单条迟到上限。 */
    const val MAX_LATE_MILLIS = 6L * 60L * 60_000L
}
