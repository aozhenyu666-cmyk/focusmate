package com.focusmate.companion

/**
 * 旧数据迁移（二次复核 R-01）。
 *
 * 背景：sourceUtteranceId 是后加字段，升级前写入的事实没有它，只有 sourceRef 字符串
 * （形如 utterance:u1）。反序列化时新字段默认 null，于是 forgetWithSource 找不到原句，
 * 遗忘后那句话仍然留在对话里继续发给模型。
 *
 * 原则：
 * - 只做能从已有数据**明确解析**的恢复，不猜、不做模糊匹配；
 * - 不删除任何用户数据；
 * - 幂等：已经填好的不覆盖，重复执行没有副作用。
 */
object FactMigration {

    private const val REF_PREFIX = "utterance:"

    /** 把 sourceRef = "utterance:<id>" 解析成来源句子 id；解析不出返回 null。 */
    fun utteranceIdFromRef(sourceRef: String?): String? {
        val ref = sourceRef?.trim() ?: return null
        if (!ref.startsWith(REF_PREFIX)) return null
        val id = ref.removePrefix(REF_PREFIX).trim()
        return id.ifEmpty { null }
    }

    /**
     * 迁移整个状态。返回 (新状态, 是否有改动)。
     * 调用方按第二项决定要不要落盘。
     */
    fun migrate(state: CompanionState): Pair<CompanionState, Boolean> {
        var changed = false
        val facts = state.facts.map { fact ->
            if (fact.sourceUtteranceId != null && fact.sourceUtteranceIds.isNotEmpty()) return@map fact
            val fromRef = utteranceIdFromRef(fact.sourceRef)
            val merged = buildList {
                fact.sourceUtteranceId?.let { add(it) }
                addAll(fact.sourceUtteranceIds)
                if (fromRef != null && fromRef !in this) add(fromRef)
            }.distinct()
            if (merged.isEmpty()) {
                fact
            } else {
                changed = true
                fact.copy(
                    sourceUtteranceId = fact.sourceUtteranceId ?: merged.first(),
                    sourceUtteranceIds = merged,
                )
            }
        }
        return if (changed) {
            state.copy(
                facts = facts,
                memoryVersion = state.memoryVersion + 1,
            ) to true
        } else {
            state to false
        }
    }
}
