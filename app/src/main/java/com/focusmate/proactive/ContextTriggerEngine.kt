package com.focusmate.proactive

/**
 * 情境触发器：回答「为什么现在开口比随便摇到更有意义」。
 *
 * 借鉴的是「有依据才开口」这个思路（微光/proactive_chat 都把触发原因写进提示词），
 * 但重新实现为纯 Kotlin 判定，不做任何隐私采集：
 * 只看本 APP 自己已有的状态（专注会话、约定、对话记录、用户是否回应），
 * 不读其它应用、不录屏、不抓通知栏。
 */
object ContextTriggerEngine {

    /** 触发类型。优先级从高到低。 */
    enum class TriggerType {
        /** 之前约好的事项即将到期。 */
        FOLLOW_UP_DUE_SOON,
        /** 上次对话有未解决的问题（我们最后一句是提问）。 */
        UNRESOLVED_QUESTION,
        /** 用户刚结束专注（这是一个自然的里程碑）。 */
        FOCUS_ENDED,
        /** 用户很久没说话了。 */
        LONG_SILENCE,
        /** 有值得继续的旧话题。 */
        OLD_TOPIC,
        /** 纯随机轻闲聊。 */
        RANDOM_SMALL_TALK,
    }

    data class Candidate(
        val type: TriggerType,
        /** 为什么想开口，进提示词也写进日志。 */
        val reason: String,
        /** 关联的话题（可空）。 */
        val topic: String?,
        /** 权重：越具体的情境越该排在前面。 */
        val weight: Double,
    )

    /**
     * 情境观察输入。全部是 APP 自身已有状态。
     */
    data class Snapshot(
        val nowMillis: Long,
        /** 是否有约定在 dueSoonWindow 内到期。 */
        val followUpDueSoonTopic: String?,
        /** 我们最后一句是不是提问（未解决）。 */
        val lastCompanionWasQuestion: Boolean,
        /** 距上次专注结束多少毫秒；没有专注记录用 Long.MAX_VALUE。 */
        val sinceFocusEndMillis: Long,
        /** 距用户上次说话多少毫秒。 */
        val sinceUserSpokeMillis: Long,
        /** 可用的旧话题（来自记忆/对话），按相关度排序。 */
        val oldTopics: List<String>,
        /** 最近已经说过的话题，用于降低重复。 */
        val recentlySaid: List<String>,
    )

    /**
     * 收集候选情境。没有候选时返回空列表——调用方应据此**不说话**，
     * 而不是硬凑一句。这是「不能反复只读取第一条记忆」的落实点。
     */
    fun candidates(s: Snapshot): List<Candidate> {
        val out = mutableListOf<Candidate>()

        s.followUpDueSoonTopic?.let {
            out += Candidate(
                type = TriggerType.FOLLOW_UP_DUE_SOON,
                reason = "之前约好的「" + it + "」快到期了",
                topic = it,
                weight = 3.0,
            )
        }

        if (s.lastCompanionWasQuestion && s.sinceUserSpokeMillis > 5 * 60_000L) {
            out += Candidate(
                type = TriggerType.UNRESOLVED_QUESTION,
                reason = "上一句我问了问题还没得到回答",
                topic = null,
                weight = 2.0,
            )
        }

        if (s.sinceFocusEndMillis in 0 until 10 * 60_000L) {
            out += Candidate(
                type = TriggerType.FOCUS_ENDED,
                reason = "刚结束一次专注",
                topic = null,
                weight = 2.2,
            )
        }

        if (s.sinceUserSpokeMillis > 45 * 60_000L && s.sinceUserSpokeMillis != Long.MAX_VALUE) {
            out += Candidate(
                type = TriggerType.LONG_SILENCE,
                reason = "已经有一阵没说话了",
                topic = null,
                weight = 1.6,
            )
        }

        // 旧话题：跳过最近说过的，避免复读。
        val fresh = s.oldTopics.filter { topic -> s.recentlySaid.none { similar(it, topic) } }
        fresh.take(2).forEach { topic ->
            out += Candidate(
                type = TriggerType.OLD_TOPIC,
                reason = "之前聊过「" + topic.take(24) + "」，可以接着问",
                topic = topic,
                weight = 1.5,
            )
        }

        // 兜底：纯随机闲聊。**即使没有任何记忆和情境也要有这一条**，
        // 否则「没有历史记忆时也能产生合适的通用闲聊」这条验收过不了。
        out += Candidate(
            type = TriggerType.RANDOM_SMALL_TALK,
            reason = "没什么特别的事，只是想找你说句话",
            topic = null,
            weight = 1.0,
        )

        return out.sortedByDescending { it.weight }
    }

    /** 挑一个候选。用权重软随机，不是永远取第一条。 */
    fun pick(candidates: List<Candidate>, random: RandomSource): Candidate? {
        if (candidates.isEmpty()) return null
        val total = candidates.sumOf { it.weight }
        var roll = random.nextDouble() * total
        for (c in candidates) {
            roll -= c.weight
            if (roll <= 0.0) return c
        }
        return candidates.last()
    }

    /** 粗糙相似度：用于判断「这个话题是不是刚说过」。 */
    fun similar(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        if (a == b) return true
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        if (shorter.length >= 4 && longer.contains(shorter)) return true
        // 双字重合率
        val ta = pairs(a).toSet()
        val tb = pairs(b).toSet()
        if (ta.isEmpty() || tb.isEmpty()) return false
        val inter = ta.intersect(tb).size.toDouble()
        val union = ta.union(tb).size.toDouble()
        return union > 0 && inter / union >= 0.6
    }

    private fun pairs(s: String): List<String> {
        val clean = s.filter { it.isLetterOrDigit() }
        if (clean.length < 2) return emptyList()
        return (0 until clean.length - 1).map { clean.substring(it, it + 2) }
    }
}
