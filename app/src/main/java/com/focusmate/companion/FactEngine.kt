package com.focusmate.companion

import java.util.UUID

/**
 * 事实的增、更正、忘记、复习与召回。纯逻辑。
 *
 * 移植自 surfcore/src/memory-card.js：
 * - scoreMemory 的核心加权项（主人说的/情绪/约定/矛盾/太短扣分），保留为可测规则；
 * - addMemoryRecord 的重复判定（同来源同文本 2 天内不重复占容量）；
 * - touchMemories 的"被提到就是复习"；
 * - 以及本项目额外强调的一点：**更正/忘记必须让旧内容退出上下文**，
 *   所以用 SUPERSEDED / RETRACTED 两个状态，而不是删除记录（删了会被重新推导出来）。
 */
object FactEngine {

    /** 情绪浓度 0..24，和上游 detectEmotionScore 同量级（这里只保留词表，不引入分词）。 */
    private val EMOTION_STRONG = listOf("喜欢", "想念", "想你", "难过", "委屈", "心疼", "感动", "害怕", "孤单", "抱歉", "对不起", "谢谢", "陪伴", "永远", "在乎", "重要")
    private val EMOTION_MEDIUM = listOf("开心", "高兴", "笑死", "可爱", "温柔", "生气", "烦", "郁闷", "害羞", "讨厌", "累", "困", "饿", "紧张", "激动")
    private val PROMISE = listOf("答应", "约定", "说好", "以后", "下次", "记得", "别忘了", "保证")
    private val CONFLICT = listOf("吵架", "生气", "道歉", "对不起", "和好", "误会", "冷战", "原谅")
    private val FIRST = listOf("第一次", "初次", "头一次", "最早", "纪念日", "第一天")

    fun detectEmotionScore(text: String): Int {
        var score = 0
        EMOTION_STRONG.forEach { if (text.contains(it)) score += 6 }
        EMOTION_MEDIUM.forEach { if (text.contains(it)) score += 3 }
        return score.coerceIn(0, 24)
    }

    /** 重要度评分。规则本身就是产品语义，改了要同步改测试。 */
    fun score(
        text: String,
        source: FactSource,
        isFirst: Boolean = false,
    ): Pair<Int, List<String>> {
        var importance = 8
        val reasons = mutableListOf<String>()

        when (source) {
            FactSource.USER_STATED -> { importance += 32; reasons += "你亲口说的" }
            FactSource.SYSTEM_OBSERVED -> { importance += 14; reasons += "系统看到的" }
            FactSource.MODEL_INFERRED -> { importance += 4; reasons += "我猜的，需要你确认" }
        }
        val emotional = detectEmotionScore(text)
        if (emotional > 0) { importance += emotional; reasons += "情绪浓度 " + emotional }
        if (PROMISE.any { text.contains(it) }) { importance += 16; reasons += "约定/承诺" }
        if (CONFLICT.any { text.contains(it) }) { importance += 14; reasons += "矛盾/道歉" }
        if (FIRST.any { text.contains(it) }) { importance += 30; reasons += "第一次" }
        if (isFirst) importance += 10
        if (text.length < 6) { importance -= 12; reasons += "太短/信息量低" }

        // 模型推测永远不能凭"说得好听"就变成高重要度，压一档。
        if (source == FactSource.MODEL_INFERRED) importance = (importance * 0.6).toInt()

        return importance.coerceIn(1, 100) to reasons
    }

    /** 新增一条事实；重复的（同来源同文本 2 天内）只刷新 recallCount，不重复占位。 */
    fun add(
        state: CompanionState,
        text: String,
        source: FactSource,
        sourceRef: String?,
        nowMillis: Long,
        sourceUtteranceId: String? = null,
    ): Pair<CompanionState, Fact> {
        val clean = text.trim()
        require(clean.isNotEmpty()) { "事实内容不能为空" }

        val duplicate = state.facts.firstOrNull {
            it.status == FactStatus.ACTIVE &&
                it.source == source &&
                it.text == clean &&
                nowMillis - it.createdAt <= 2L * SchedulePolicy.DAY
        }
        if (duplicate != null) {
            // R-02：同一句话在遗忘前说过多次时，必须把**每一次**的来源都记下来。
            // 否则遗忘只隐藏第一句，第二句仍会在对话里发给模型。
            val mergedSources = buildList {
                duplicate.sourceUtteranceId?.let { add(it) }
                addAll(duplicate.sourceUtteranceIds)
                if (sourceUtteranceId != null && sourceUtteranceId !in this) add(sourceUtteranceId)
            }.distinct()
            val touched = duplicate.copy(
                recallCount = duplicate.recallCount + 1,
                updatedAt = nowMillis,
                sourceUtteranceIds = mergedSources,
            )
            return state.copy(
                facts = state.facts.map { if (it.id == duplicate.id) touched else it },
                memoryVersion = state.memoryVersion + 1,
            ) to touched
        }

        val (importance, reasons) = score(clean, source)
        val fact = Fact(
            id = UUID.randomUUID().toString(),
            text = clean,
            source = source,
            sourceRef = sourceRef,
            sourceUtteranceId = sourceUtteranceId,
            sourceUtteranceIds = sourceUtteranceId?.let { listOf(it) } ?: emptyList(),
            createdAt = nowMillis,
            updatedAt = nowMillis,
            importance = importance,
            tags = reasons,
        )
        // 条数上限：先丢最不重要、最老的 ACTIVE。被更正/遗忘的墓碑不丢（它们防止复活）。
        val kept = if (state.facts.count { it.status == FactStatus.ACTIVE } >= MAX_FACTS) {
            val removable = state.facts
                .filter { it.status == FactStatus.ACTIVE }
                .sortedWith(compareBy({ it.importance }, { it.createdAt }))
                .take(1)
                .map { it.id }
                .toSet()
            state.facts.filterNot { it.id in removable }
        } else {
            state.facts
        }
        return state.copy(
            facts = kept + fact,
            epoch = state.epoch + 1,
            memoryVersion = state.memoryVersion + 1,
        ) to fact
    }

    /**
     * 更正：旧事实转 SUPERSEDED 并指向新事实。
     * 关键点——旧文本仍留在磁盘上（有墓碑），但**永远不会进入上下文**，
     * 也不会因为"摘要"重新出现，因为上下文只从 ACTIVE 事实拼。
     */
    fun correct(
        state: CompanionState,
        oldFactId: String,
        newText: String,
        nowMillis: Long,
    ): Pair<CompanionState, Fact>? {
        val old = state.facts.firstOrNull { it.id == oldFactId && it.status == FactStatus.ACTIVE } ?: return null
        val (withNew, fresh) = add(
            state = state.copy(facts = state.facts.map {
                if (it.id == oldFactId) it.copy(status = FactStatus.SUPERSEDED, updatedAt = nowMillis) else it
            }),
            text = newText,
            source = FactSource.USER_STATED,
            sourceRef = "更正自 " + old.id,
            nowMillis = nowMillis,
        )
        val linked = withNew.facts.map {
            if (it.id == oldFactId) it.copy(supersededBy = fresh.id) else it
        }
        return withNew.copy(
            facts = linked,
            epoch = withNew.epoch + 1,
            memoryVersion = withNew.memoryVersion + 1,
        ) to fresh
    }

    /** 忘记：转 RETRACTED。保留墓碑，防止后续对话又把同样的内容"推导"回来。 */
    fun forget(state: CompanionState, factId: String, nowMillis: Long): CompanionState? {
        val target = state.facts.firstOrNull { it.id == factId && it.status != FactStatus.RETRACTED } ?: return null
        return state.copy(
            facts = state.facts.map {
                if (it.id == target.id) it.copy(status = FactStatus.RETRACTED, updatedAt = nowMillis) else it
            },
            epoch = state.epoch + 1,
            memoryVersion = state.memoryVersion + 1,
        )
    }

    /**
     * 按用户那句话做"忘记"（F-02 的关键补齐）。
     *
     * 只把事实标 RETRACTED 不够：原句还躺在最近对话里，下次组装上下文照样发给模型。
     * 所以这里同时做三件事：
     *   1) 该事实 -> RETRACTED
     *   2) 产生它的那句对话 -> hidden（界面仍可见，不再进模型输入）
     *   3) 以那句话为唯一依据的待跟进事项 -> CANCELLED（否则它会继续把那句话往回捞）
     *
     * 用户之后自己再说一次同样的内容，会生成新的 utterance 与新的 ACTIVE 事实，
     * 属于"用户重新提供"，不是"系统复活旧内容"。
     */
    fun forgetWithSource(state: CompanionState, factId: String, nowMillis: Long): CompanionState? {
        val target = state.facts.firstOrNull { it.id == factId && it.status != FactStatus.RETRACTED } ?: return null

        // 这个事实关联的**全部**原句（R-02：可能不止一句）。
        val utteranceIds = buildList {
            target.sourceUtteranceId?.let { add(it) }
            addAll(target.sourceUtteranceIds)
        }.distinct().toSet()

        val facts = state.facts.map {
            if (it.id == target.id) it.copy(status = FactStatus.RETRACTED, updatedAt = nowMillis) else it
        }
        val dialogue = if (utteranceIds.isEmpty()) {
            state.dialogue
        } else {
            state.dialogue.map {
                if (it.id in utteranceIds) it.copy(hidden = true, hiddenReason = "user_forget") else it
            }
        }
        // R-03：以这些句子为唯一依据的待跟进事项一并作废，
        // 否则它下次开口时会把"要聊的那句话"又从 evidence 里捞回来。
        val followUps = if (utteranceIds.isEmpty()) {
            state.followUps
        } else {
            state.followUps.map {
                if (it.open && it.evidenceUtteranceId in utteranceIds) {
                    it.copy(status = FollowUpStatus.CANCELLED, version = it.version + 1, updatedAt = nowMillis)
                } else it
            }
        }

        return state.copy(
            facts = facts,
            dialogue = dialogue,
            followUps = followUps,
            epoch = state.epoch + 1,
            memoryVersion = state.memoryVersion + 1,
        )
    }

    /**
     * 更正：同样要处理原句（F-02）。
     * 旧事实 -> SUPERSEDED；产生它的那句话 -> hidden；新事实 -> ACTIVE。
     * 这样模型只看到新内容，旧句子不会以"你之前说"的形式再出现。
     */
    fun correctWithSource(
        state: CompanionState,
        oldFactId: String,
        newText: String,
        nowMillis: Long,
    ): Pair<CompanionState, Fact>? {
        val old = state.facts.firstOrNull { it.id == oldFactId && it.status == FactStatus.ACTIVE } ?: return null

        // 旧事实关联的全部原句（R-02）。
        val utteranceIds = buildList {
            old.sourceUtteranceId?.let { add(it) }
            addAll(old.sourceUtteranceIds)
        }.distinct().toSet()

        // R-03：更正时，以旧句为依据的事项必须一起处理，不能只改事实表和对话表。
        // 这些事项的 evidence 就是被更正掉的那句话，留着它下次开口会说错。
        val followUps = if (utteranceIds.isEmpty()) {
            state.followUps
        } else {
            state.followUps.map {
                if (it.open && it.evidenceUtteranceId in utteranceIds) {
                    it.copy(status = FollowUpStatus.CANCELLED, version = it.version + 1, updatedAt = nowMillis)
                } else it
            }
        }

        val withOldRetired = state.copy(
            facts = state.facts.map {
                if (it.id == oldFactId) it.copy(status = FactStatus.SUPERSEDED, updatedAt = nowMillis) else it
            },
            dialogue = if (utteranceIds.isEmpty()) {
                state.dialogue
            } else {
                state.dialogue.map {
                    if (it.id in utteranceIds) it.copy(hidden = true, hiddenReason = "user_correction") else it
                }
            },
            followUps = followUps,
        )
        val (withNew, fresh) = add(
            state = withOldRetired,
            text = newText,
            source = FactSource.USER_STATED,
            sourceRef = "更正自 " + old.id,
            nowMillis = nowMillis,
        )
        val linked = withNew.facts.map {
            if (it.id == oldFactId) it.copy(supersededBy = fresh.id) else it
        }
        return withNew.copy(facts = linked, epoch = withNew.epoch + 1) to fresh
    }

    /** 被提到 = 复习：只刷新 recallCount/updatedAt，不改内容。 */
    fun touch(state: CompanionState, text: String, nowMillis: Long): CompanionState {
        if (text.length < 4) return state
        val tokens = tokensOf(text)
        if (tokens.isEmpty()) return state
        var touched = 0
        val next = state.facts.map { f ->
            if (f.status != FactStatus.ACTIVE || touched >= 5) return@map f
            val body = f.text
            if (tokens.any { body.contains(it) }) {
                touched++
                f.copy(recallCount = f.recallCount + 1, updatedAt = nowMillis)
            } else f
        }
        return if (touched > 0) state.copy(facts = next, memoryVersion = state.memoryVersion + 1) else state
    }

    /** 中文双字 + 英文词袋，与上游 textTokens 同思路，用于召回与复习。 */
    fun tokensOf(text: String, limit: Int = 24): List<String> {
        val stop = setOf("一个", "这个", "那个", "就是", "什么", "怎么", "可以", "不是", "没有", "还是", "已经", "我们", "你们", "他们", "自己", "现在", "今天", "明天", "昨天", "然后", "因为", "所以")
        val result = LinkedHashSet<String>()
        val segments = text.split(Regex("[^\u4e00-\u9fa5A-Za-z0-9]+"))
        for (segment in segments) {
            if (segment.isEmpty()) continue
            if (segment.any { it in '\u4e00'..'\u9fa5' }) {
                var i = 0
                while (i + 2 <= segment.length && result.size < limit) {
                    val token = segment.substring(i, i + 2)
                    if (token !in stop) result += token
                    i++
                }
            } else if (segment.length >= 3 && result.size < limit) {
                result += segment
            }
        }
        return result.toList().take(limit)
    }

    /** 上下文用的召回：只从 ACTIVE 里挑，按"相关度 + 重要度"排序，条数有上限。 */
    fun recall(state: CompanionState, query: String, limit: Int = 8): List<Fact> {
        val active = state.facts.filter { it.status == FactStatus.ACTIVE }
        if (active.isEmpty()) return emptyList()
        val tokens = tokensOf(query)
        val scored = active.map { f ->
            val hits = if (tokens.isEmpty()) 0 else tokens.count { f.text.contains(it) }
            val score = hits * 40.0 + f.importance + f.recallCount * 2
            f to score
        }
        return scored
            .filter { (f, score) -> score > 0 && (tokens.isEmpty() || f.importance >= 40 || tokens.any { f.text.contains(it) }) }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    const val MAX_FACTS = 400
}
