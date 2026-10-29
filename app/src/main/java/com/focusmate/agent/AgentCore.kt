package com.focusmate.agent

/**
 * 伙伴状态的全部迁移。纯函数：旧状态进，新状态出。运行时只负责加锁、存盘和兑现到系统。
 */
class AgentCore(
    private val time: LocalClock,
    private val newId: () -> String,
) {
    companion object {
        const val MAX_CHAT = 400
        const val MAX_FACTS = 80
        const val REVIEW_KEY = "focus:review"
    }

    fun addUser(state: AgentState, text: String, origin: Origin, nowMillis: Long): Pair<AgentState, ChatEntry> {
        val entry = ChatEntry(newId(), Role.USER, text.trim(), nowMillis, origin)
        val s = state.copy(
            chat = capChat(state.chat + entry),
            proactive = ProactivePolicy.onUserSpoke(state.proactive, nowMillis),
            revision = state.revision + 1,
        )
        return s to entry
    }

    /** tag 用于去重（例如同一次专注的「已开始」只记一次），存放在 agendaId 字段。 */
    fun addEvent(state: AgentState, text: String, nowMillis: Long, tag: String? = null): AgentState =
        state.copy(
            chat = capChat(state.chat + ChatEntry(newId(), Role.EVENT, text, nowMillis, Origin.FOCUS_EVENT, agendaId = tag)),
            revision = state.revision + 1,
        )

    /** 专注结束：安排一次复盘（同键替换，重复结束事件不会叠两条）。 */
    fun scheduleReview(state: AgentState, summary: String, nowMillis: Long, delayMillis: Long = 20_000L): AgentState {
        val item = AgendaItem(
            id = newId(),
            kind = AgendaKind.FOCUS_REVIEW,
            key = REVIEW_KEY,
            dueAtMillis = nowMillis + delayMillis,
            text = summary,
            createdAtMillis = nowMillis,
        )
        return state.copy(agenda = Agenda.upsert(state.agenda, item, nowMillis), revision = state.revision + 1)
    }

    /**
     * 提交一轮模型结果：动作 + 最终回复，一次完成。
     * 返回新状态和本轮产生的伙伴消息（没有则为 null）。
     */
    fun commit(state: AgentState, turn: Turn, outcome: AgentLoop.Outcome, nowMillis: Long): Pair<AgentState, ChatEntry?> {
        var agenda = state.agenda
        var facts = state.facts
        var proposal: FocusProposal? = null
        val lastUserEntryId = state.chat.lastOrNull { it.role == Role.USER }?.id

        for (a in outcome.actions) {
            when (a) {
                is AgentAction.SetReminder -> agenda = Agenda.upsert(agenda, AgendaItem(
                    id = newId(), kind = AgendaKind.REMINDER, key = a.key, dueAtMillis = a.dueAtMillis,
                    text = a.text, createdAtMillis = nowMillis,
                ), nowMillis)
                is AgentAction.ScheduleFollowUp -> agenda = Agenda.upsert(agenda, AgendaItem(
                    id = newId(), kind = AgendaKind.FOLLOW_UP, key = a.key, dueAtMillis = a.dueAtMillis,
                    text = a.topic, reason = a.reason, createdAtMillis = nowMillis,
                ), nowMillis)
                is AgentAction.CancelAgenda -> agenda = Agenda.cancel(agenda, a.keyOrId, nowMillis, "伙伴取消").first
                is AgentAction.Remember -> facts = remember(facts, a, nowMillis, lastUserEntryId)
                is AgentAction.Forget -> facts = facts.filterNot { it.id == a.factId }
                is AgentAction.ProposeFocus -> proposal = FocusProposal(newId(), a.mode, a.minutes, a.goal)
                is AgentAction.StaySilent -> Unit
            }
        }

        // 回访/复盘这条安排本身的结局。
        val item = when (turn) {
            is Turn.FollowUp -> turn.item
            is Turn.Review -> turn.item
            else -> null
        }
        if (item != null) {
            agenda = if (outcome.text == null) {
                Agenda.markDropped(agenda, item.id, nowMillis, "伙伴判断这次不用说")
            } else {
                Agenda.markDelivered(agenda, item.id, nowMillis)
            }
        }

        var text = outcome.text
        // 有提议却没说话，补一句，保证卡片有上下文。
        if (text == null && proposal != null) text = "要不要现在开始？"

        var proactive = state.proactive
        val entry = text?.let {
            ChatEntry(newId(), Role.ASSISTANT, it, nowMillis, turn.origin, agendaId = item?.id, proposal = proposal)
        }
        if (entry != null && turn.proactive) {
            proactive = ProactivePolicy.onProactiveDelivered(proactive, nowMillis, turn.origin, time)
        }

        val s = state.copy(
            chat = if (entry != null) capChat(state.chat + entry) else state.chat,
            facts = facts,
            agenda = agenda,
            proactive = proactive,
            revision = state.revision + 1,
        )
        return s to entry
    }

    /**
     * 提醒到点：不经过模型，直接送达。文本是创建时伙伴写好的。
     * 迟到超过两分钟（关机、系统推迟闹钟）如实注明。
     */
    fun deliverReminder(state: AgentState, item: AgendaItem, nowMillis: Long): Pair<AgentState, ChatEntry> {
        val lateMin = (nowMillis - item.dueAtMillis) / 60_000L
        val text = if (lateMin >= 2) "${item.text}\n（本该 ${Prompt.clock(item.dueAtMillis, time.zone)} 提醒，晚了 $lateMin 分钟）" else item.text
        val entry = ChatEntry(newId(), Role.ASSISTANT, text, nowMillis, Origin.REMINDER, agendaId = item.id)
        val s = state.copy(
            chat = capChat(state.chat + entry),
            agenda = Agenda.markDelivered(state.agenda, item.id, nowMillis),
            proactive = ProactivePolicy.onProactiveDelivered(state.proactive, nowMillis, Origin.REMINDER, time),
            revision = state.revision + 1,
        )
        return s to entry
    }

    /** 复盘多次生成失败：用本地文案，不能因为模型挂了就不问。 */
    fun deliverFallbackReview(state: AgentState, item: AgendaItem, nowMillis: Long): Pair<AgentState, ChatEntry> {
        val entry = ChatEntry(newId(), Role.ASSISTANT, "专注结束了（${item.text}）。感觉怎么样？", nowMillis, Origin.FOCUS_REVIEW, agendaId = item.id)
        val s = state.copy(
            chat = capChat(state.chat + entry),
            agenda = Agenda.markDelivered(state.agenda, item.id, nowMillis, "模型不可用，使用本地文案"),
            proactive = ProactivePolicy.onProactiveDelivered(state.proactive, nowMillis, Origin.FOCUS_REVIEW, time),
            revision = state.revision + 1,
        )
        return s to entry
    }

    fun setProposal(state: AgentState, proposalId: String, status: ProposalStatus, detail: String?): AgentState =
        state.copy(
            chat = state.chat.map { e ->
                val p = e.proposal
                if (p != null && p.id == proposalId) e.copy(proposal = p.copy(status = status, detail = detail)) else e
            },
            revision = state.revision + 1,
        )

    fun findProposal(state: AgentState, proposalId: String): FocusProposal? =
        state.chat.firstNotNullOfOrNull { e -> e.proposal?.takeIf { it.id == proposalId } }

    fun cancelAgenda(state: AgentState, id: String, nowMillis: Long): AgentState =
        state.copy(agenda = Agenda.cancel(state.agenda, id, nowMillis, "用户取消").first, revision = state.revision + 1)

    fun deleteFact(state: AgentState, id: String): AgentState =
        state.copy(facts = state.facts.filterNot { it.id == id }, revision = state.revision + 1)

    fun setQuiet(state: AgentState, untilMillis: Long): AgentState =
        state.copy(proactive = state.proactive.copy(quietUntilMillis = untilMillis), revision = state.revision + 1)

    private fun remember(facts: List<Fact>, a: AgentAction.Remember, nowMillis: Long, sourceEntryId: String?): List<Fact> {
        val norm = normalize(a.text)
        val existing = facts.firstOrNull { normalize(it.text) == norm }
        if (existing != null) {
            // 推断被用户亲口证实时升级来源；其余情况不重复记。
            return if (existing.source == FactSource.INFERRED && a.source == FactSource.USER_SAID) {
                facts.map { if (it.id == existing.id) it.copy(source = FactSource.USER_SAID) else it }
            } else {
                facts
            }
        }
        var out = facts + Fact(newId(), a.text, a.source, nowMillis, sourceEntryId)
        while (out.size > MAX_FACTS) {
            // 先淘汰最旧的推断，再淘汰最旧的。
            val victim = out.filter { it.source == FactSource.INFERRED }.minByOrNull { it.createdAtMillis }
                ?: out.minBy { it.createdAtMillis }
            out = out - victim
        }
        return out
    }

    private fun normalize(t: String): String = t.lowercase().filter { it.isLetterOrDigit() }

    private fun capChat(chat: List<ChatEntry>): List<ChatEntry> =
        if (chat.size <= MAX_CHAT) chat else chat.takeLast(MAX_CHAT)
}
