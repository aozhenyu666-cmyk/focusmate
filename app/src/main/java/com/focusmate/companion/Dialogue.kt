package com.focusmate.companion

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 一句对话。近期上下文只从这里取，不另建"角色台词库"。 */
@Serializable
data class Utterance(
    @SerialName("id") val id: String,
    @SerialName("role") val role: Role,
    @SerialName("text") val text: String,
    @SerialName("at") val at: Long,
    /** 这条是不是主动发起的（主动发言与回复共用同一份记录，便于追责）。 */
    @SerialName("proactive") val proactive: Boolean = false,
    /** 主动发言时，对应哪条待跟进事项。 */
    @SerialName("followUpId") val followUpId: String? = null,
    /**
     * 这句话不能再次进入模型输入（F-02）。
     *
     * 为什么必须在句子级别留墓碑：用户说"忘掉我下周三面试"时，
     * 只把事实标成 RETRACTED 不够——原句还躺在最近对话里，照样会发给模型。
     * 界面上仍可显示（那是给用户看的），但组装上下文时被过滤掉。
     */
    @SerialName("hidden") val hidden: Boolean = false,
    @SerialName("hiddenReason") val hiddenReason: String? = null,
) {
    @Serializable
    enum class Role {
        @SerialName("user") USER,
        @SerialName("companion") COMPANION,
    }
}

/**
 * 只保留最近 N 条的环形日志。
 * 对应 dsh-world 的 activity/对话窗口思路：上下文有界，不做全量重放。
 */
class DialogueLog(private val limit: Int = 80) {
    private val items = ArrayDeque<Utterance>()

    fun append(u: Utterance) {
        items.addLast(u)
        while (items.size > limit) items.removeFirst()
    }

    fun all(): List<Utterance> = items.toList()

    /** 最近 n 条，按时间正序。 */
    fun recent(n: Int): List<Utterance> = if (n >= items.size) items.toList() else items.toList().takeLast(n)

    fun lastByRole(role: Utterance.Role): Utterance? = items.lastOrNull { it.role == role }

    fun size(): Int = items.size
}
