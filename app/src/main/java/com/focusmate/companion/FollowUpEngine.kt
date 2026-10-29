package com.focusmate.companion

import java.util.UUID

/**
 * 事项的产生、改期、取消、过期与回应结算。全部是纯逻辑，不碰 Android。
 *
 * 对应上游两处：
 * - dsh-world runtime.ts 的 setAlarm / cancelAlarm：**新约定替换旧约定**，旧退避与故障一并清除；
 * - surfcore purpose-engine.js 的 addPendingPurpose / resolvePendingPurpose：
 *   同一件事只留一条（相似度去重），回应后用相似度结算"这件事算说完了"。
 *
 * 这里把两者合起来，并用 version 承载"旧异步结果失效"：
 * 生成前后各比对一次 version，对不上就丢弃。
 */
object FollowUpEngine {

    /** 同一话题只保留一条：dedupeKey 相同就是同一个约定。 */
    fun dedupeKey(source: FollowUpSource, topic: String): String =
        source.name.lowercase() + "|" + topic.trim().lowercase()

    /**
     * 用户约定：创建新事项，并把同话题的旧事项作废。
     * 对应上游 setAlarm 的"新闹钟替换旧闹钟，旧 backoff 一并清掉"。
     */
    fun userAgreement(
        state: CompanionState,
        topic: String,
        evidence: String,
        dueAtMillis: Long,
        nowMillis: Long,
        evidenceUtteranceId: String? = null,
    ): Pair<CompanionState, FollowUp> {
        require(topic.isNotBlank()) { "话题不能为空" }
        require(evidence.isNotBlank()) { "必须留下依据：用哪句话定的" }
        val key = dedupeKey(FollowUpSource.USER_AGREEMENT, topic)
        val superseded = state.followUps.map {
            if (it.open && it.dedupeKey == key) {
                it.copy(status = FollowUpStatus.CANCELLED, version = it.version + 1, updatedAt = nowMillis)
            } else it
        }
        val item = FollowUp(
            id = UUID.randomUUID().toString(),
            source = FollowUpSource.USER_AGREEMENT,
            basis = "你说过要在之后接着聊这个",
            evidence = evidence,
            evidenceUtteranceId = evidenceUtteranceId,
            topic = topic,
            dueAtMillis = dueAtMillis,
            createdAt = nowMillis,
            updatedAt = nowMillis,
            dedupeKey = key,
        )
        val next = state.copy(
            followUps = (superseded + item).takeLast(MAX_ITEMS),
            nextDueAtMillis = dueAtMillis,
            epoch = state.epoch + 1,
        )
        return next to item
    }

    /** 对话里没说完的事：同样按话题替换，但不改 nextDueAt（它不是用户约定的时间点）。 */
    fun unfinishedTopic(
        state: CompanionState,
        topic: String,
        evidence: String,
        dueAtMillis: Long,
        nowMillis: Long,
        evidenceUtteranceId: String? = null,
    ): Pair<CompanionState, FollowUp> {
        require(topic.isNotBlank())
        require(evidence.isNotBlank())
        val key = dedupeKey(FollowUpSource.UNFINISHED_TOPIC, topic)
        val superseded = state.followUps.map {
            if (it.open && it.dedupeKey == key) {
                it.copy(status = FollowUpStatus.CANCELLED, version = it.version + 1, updatedAt = nowMillis)
            } else it
        }
        val item = FollowUp(
            id = UUID.randomUUID().toString(),
            source = FollowUpSource.UNFINISHED_TOPIC,
            basis = "上次这件事没说到底",
            evidence = evidence,
            evidenceUtteranceId = evidenceUtteranceId,
            topic = topic,
            dueAtMillis = dueAtMillis,
            createdAt = nowMillis,
            updatedAt = nowMillis,
            dedupeKey = key,
        )
        val next = state.copy(followUps = (superseded + item).takeLast(MAX_ITEMS), epoch = state.epoch + 1)
        return next to item
    }

    /**
     * 主动闲聊候选：必须带真实依据（旧话题或人格兴趣），否则不创建。
     * 上游对应 minePurposeSeeds 的"没有可靠种子就别硬找话"。
     */
    fun personaInterest(
        state: CompanionState,
        topic: String,
        evidence: String,
        dueAtMillis: Long,
        nowMillis: Long,
    ): Pair<CompanionState, FollowUp>? {
        if (topic.isBlank() || evidence.isBlank()) return null
        val key = dedupeKey(FollowUpSource.PERSONA_INTEREST, topic)
        // 闲聊类不做替换：同一个话题说过就说过，不再重复排。
        if (state.followUps.any { it.dedupeKey == key }) return null
        val item = FollowUp(
            id = UUID.randomUUID().toString(),
            source = FollowUpSource.PERSONA_INTEREST,
            basis = "这条与你之前说过的事有关",
            evidence = evidence,
            topic = topic,
            dueAtMillis = dueAtMillis,
            createdAt = nowMillis,
            updatedAt = nowMillis,
            dedupeKey = key,
        )
        return state.copy(followUps = (state.followUps + item).takeLast(MAX_ITEMS), epoch = state.epoch + 1) to item
    }

    /** 改期：只动时间，版本 +1，旧结果失效。对应上游 plan_next 替换同一件事。 */
    fun reschedule(state: CompanionState, id: String, dueAtMillis: Long, nowMillis: Long): CompanionState? {
        var found = false
        val next = state.followUps.map {
            if (it.id == id && it.open) {
                found = true
                it.copy(dueAtMillis = dueAtMillis, version = it.version + 1, updatedAt = nowMillis, status = FollowUpStatus.PENDING)
            } else it
        }
        if (!found) return null
        return state.copy(followUps = next, nextDueAtMillis = dueAtMillis, epoch = state.epoch + 1)
    }

    /** 取消：版本 +1，状态 CANCELLED。对应的过期生成结果一律丢弃。 */
    fun cancel(state: CompanionState, id: String, nowMillis: Long): CompanionState? {
        var found = false
        val next = state.followUps.map {
            if (it.id == id && it.open) {
                found = true
                it.copy(status = FollowUpStatus.CANCELLED, version = it.version + 1, updatedAt = nowMillis)
            } else it
        }
        if (!found) return null
        return state.copy(followUps = next, epoch = state.epoch + 1)
    }

    /**
     * 投递前把事项标记为 DELIVERING，防止并发重复投递。
     * 对应上游 `delivery ??=` 的串行化：同一时刻只允许一次投递。
     */
    fun beginDelivery(state: CompanionState, id: String, nowMillis: Long): Pair<CompanionState, FollowUp>? {
        // 只有 PENDING 能被取走：DELIVERING 表示已有一轮在跑，再取一次就会重复投递。
        val item = state.followUps.firstOrNull { it.id == id && it.status == FollowUpStatus.PENDING } ?: return null
        val updated = item.copy(
            status = FollowUpStatus.DELIVERING,
            attempts = item.attempts + 1,
            lastAttemptAt = nowMillis,
            version = item.version + 1,
            updatedAt = nowMillis,
        )
        val next = state.copy(
            followUps = state.followUps.map { if (it.id == id) updated else it },
            epoch = state.epoch + 1,
        )
        return next to updated
    }

    /**
     * 投递成功：事项完成，今日计数 +1，重新排下一次。
     * @param roll 用于 planNext 的随机数（0..1），由调用方给出以便测试固定。
     */
    fun markSent(
        state: CompanionState,
        id: String,
        nowMillis: Long,
        config: CompanionConfig,
        minuteOfDay: (Long) -> Int,
        roll: Double,
        issuedVersion: Int,
    ): CompanionState? {
        val item = state.followUps.firstOrNull { it.id == id } ?: return null
        // 版本不符 = 期间被改期/取消/更正，这条结果作废。
        if (item.version != issuedVersion) return null
        // 已经说出去但还没等到回应：标 AWAITING_REPLY 而不是 DONE，
        // 只有这一状态会被"用户回应"结算（F-03）。
        val nextItems = state.followUps.map {
            if (it.id == id) it.copy(status = FollowUpStatus.AWAITING_REPLY, updatedAt = nowMillis) else it
        }
        val count = state.countToday + 1
        return state.copy(
            followUps = nextItems,
            countToday = count,
            lastRunAtMillis = nowMillis,
            nextDueAtMillis = SchedulePolicy.planNext(
                nowMillis = nowMillis,
                config = config,
                minuteOfDay = minuteOfDay,
                lastRunAtMillis = nowMillis,
                countToday = count,
                roll = roll,
            ),
            epoch = state.epoch + 1,
        )
    }

    /**
     * 投递失败：退避重试；超过次数上限转 FAILED，不再重试。
     * 对应上游 timer.failed() 的 1/5/15/30/60 分钟退避，这里用配置里的固定值。
     */
    fun markFailed(
        state: CompanionState,
        id: String,
        nowMillis: Long,
        config: CompanionConfig,
        reason: String,
        issuedVersion: Int? = null,
    ): CompanionState? {
        val item = state.followUps.firstOrNull { it.id == id } ?: return null

        // F-04：失败回调必须能被丢弃。三种情况一律不动：
        // 1) 已是终态（取消/过期/完成/失败）——迟到的失败不能复活它；
        // 2) 不在 DELIVERING——说明这一轮已经被别人接手或已经结算；
        // 3) 版本对不上——期间被改期或换了新约定，旧的失败不该覆盖新的。
        if (item.status != FollowUpStatus.DELIVERING) return state
        if (issuedVersion != null && item.version != issuedVersion) return state

        val exhausted = item.attempts >= config.maxAttempts
        val updated = if (exhausted) {
            item.copy(status = FollowUpStatus.FAILED, updatedAt = nowMillis)
        } else {
            item.copy(
                status = FollowUpStatus.PENDING,
                dueAtMillis = nowMillis + config.retryMillis,
                version = item.version + 1,
                updatedAt = nowMillis,
            )
        }
        return state.copy(
            followUps = state.followUps.map { if (it.id == id) updated else it },
            nextDueAtMillis = if (exhausted) state.nextDueAtMillis else nowMillis + config.retryMillis,
            epoch = state.epoch + 1,
        )
    }

    /**
     * 过期清理：超过 dueAt + TTL 仍未发出的，作废。
     * 对应上游 prunePurposeState 的 pendingTtlDays。
     */
    fun pruneExpired(state: CompanionState, nowMillis: Long, config: CompanionConfig): CompanionState {
        val ttl = config.pendingTtlDays.toLong() * SchedulePolicy.DAY
        var changed = false
        val next = state.followUps.map {
            if (it.open && nowMillis > it.dueAtMillis + ttl) {
                changed = true
                it.copy(status = FollowUpStatus.EXPIRED, version = it.version + 1, updatedAt = nowMillis)
            } else it
        }
        return if (changed) state.copy(followUps = next, epoch = state.epoch + 1) else state
    }

    /**
     * 放弃一次投递（结果被丢弃时用）。
     * 与 markFailed 的区别：这不是"说失败了"，而是"这一轮作废"，
     * 所以只把仍然停在 DELIVERING 的事项还原成 PENDING，不动 attempts、不动其它状态。
     */
    fun releaseDelivery(
        state: CompanionState,
        id: String,
        nowMillis: Long,
        issuedVersion: Int? = null,
    ): CompanionState? {
        val item = state.followUps.firstOrNull { it.id == id } ?: return null
        if (item.status != FollowUpStatus.DELIVERING) return state
        if (issuedVersion != null && item.version != issuedVersion) return state
        return state.copy(
            followUps = state.followUps.map {
                if (it.id == id) it.copy(status = FollowUpStatus.PENDING, updatedAt = nowMillis) else it
            },
            epoch = state.epoch + 1,
        )
    }

    /** 是否有可投递的事项（到期且仍是 PENDING）。 */
    fun dueNow(state: CompanionState, nowMillis: Long): FollowUp? =
        state.followUps
            .filter { it.status == FollowUpStatus.PENDING && it.dueAtMillis <= nowMillis }
            .minByOrNull { it.dueAtMillis }

    /** 是否存在未处理事项（PENDING/DELIVERING）。 */
    fun hasOpen(state: CompanionState): Boolean = state.followUps.any { it.open }

    const val MAX_ITEMS = 60
}
