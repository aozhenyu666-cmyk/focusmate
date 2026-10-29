package com.focusmate.agent

import java.time.ZoneId

/**
 * 一轮「想 → 用工具 → 说」。纯逻辑，模型通过接口注入，单测用假模型。
 *
 * 关键约束：这里不改任何状态。工具调用只校验并收集成 actions，
 * 由运行时在确认这轮结果没有过时之后，连同最终回复一次性提交。
 * 这样「用户已经改口、旧结果被丢弃」时，不会留下半截副作用。
 */
class AgentLoop(private val llm: LlmClient) {

    data class Outcome(
        /** 要对用户说的话；保持安静时为 null。 */
        val text: String?,
        val actions: List<AgentAction>,
        val silent: Boolean,
        /** 被拒绝的工具调用说明，用于诊断。 */
        val rejected: List<String>,
    )

    suspend fun run(base: List<LlmMessage>, turn: Turn, ctx: Tools.Ctx, name: String = ""): Outcome {
        val tools = Tools.specs(turn.proactive)
        val messages = base.toMutableList()
        val actions = mutableListOf<AgentAction>()
        val rejected = mutableListOf<String>()
        var silent = false

        repeat(MAX_ROUNDS) {
            val reply = llm.complete(messages, tools)
            if (reply.toolCalls.isEmpty()) {
                val text = clean(reply.content, name)
                if (text == null && turn.proactive) return Outcome(null, actions, true, rejected)
                return Outcome(text, actions, silent && text == null, rejected)
            }
            messages += LlmMessage.Assistant(reply.content, reply.toolCalls)
            for (call in reply.toolCalls) {
                val result = Tools.validate(call, ctx).fold(
                    onSuccess = { action ->
                        if (action is AgentAction.StaySilent) {
                            silent = true
                        } else if (!isDuplicate(actions, action)) {
                            actions += action
                        }
                        describe(action, ctx.zone)
                    },
                    onFailure = { e ->
                        rejected += "${call.name}: ${e.message}"
                        "没有执行：${e.message}"
                    },
                )
                messages += LlmMessage.ToolResult(call.id, result)
            }
            if (silent) return Outcome(null, actions, true, rejected)
        }
        // 工具轮数用完：不再给工具，逼出一句话。
        val final = llm.complete(messages, emptyList())
        return Outcome(clean(final.content, name), actions, false, rejected)
    }

    private fun isDuplicate(list: List<AgentAction>, a: AgentAction): Boolean = when (a) {
        is AgentAction.ProposeFocus -> list.any { it is AgentAction.ProposeFocus }
        else -> a in list
    }

    companion object {
        const val MAX_ROUNDS = 3

        /** 工具执行结果的描述。动作在本轮结束后统一提交，这里如实说「已安排」。 */
        fun describe(a: AgentAction, zone: ZoneId): String = when (a) {
            is AgentAction.SetReminder -> "已安排提醒：${Prompt.dateTime(a.dueAtMillis, zone)}（key=${a.key}）"
            is AgentAction.ScheduleFollowUp -> "已约回访：${Prompt.dateTime(a.dueAtMillis, zone)}（key=${a.key}）"
            is AgentAction.CancelAgenda -> "已取消：${a.keyOrId}"
            is AgentAction.Remember -> "已记住"
            is AgentAction.Forget -> "已删除这条记忆"
            is AgentAction.ProposeFocus -> "提议卡片已显示给用户，等用户点开始。现在还没开始。"
            is AgentAction.StaySilent -> "好，这次不说。"
        }

        /** 去掉模型偶尔加的「名字：」前缀与首尾空白；空内容返回 null。 */
        fun clean(content: String?, name: String): String? {
            var t = content?.trim() ?: return null
            if (name.isNotBlank()) {
                for (p in listOf("$name：", "$name:")) if (t.startsWith(p)) t = t.removePrefix(p).trim()
            }
            return t.takeIf { it.isNotEmpty() }
        }
    }
}
