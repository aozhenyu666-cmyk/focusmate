package com.focusmate.companion

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * 主动伙伴的持久状态。
 *
 * 与 dsh-world life.json 的对应：
 * - 上游整个生活线只存**一条**待办闹钟（state.alarm），新约定直接替换旧的；
 * - 本项目保留"多事项"是因为需要同时区分「约定」「没说完的话」「人格兴趣」，
 *   但用 dedupeKey 保证**同一话题只有一条**，效果等价于上游的替换语义。
 * - run state（今天说了几次、上次说的时间）与事项分开放，避免一处写坏整份。
 */
@Serializable
data class CompanionState(
    @SerialName("version") val version: Int = 1,
    @SerialName("followUps") val followUps: List<FollowUp> = emptyList(),
    @SerialName("facts") val facts: List<Fact> = emptyList(),
    /** 近期对话。有界长度，不做全量重放。 */
    @SerialName("dialogue") val dialogue: List<Utterance> = emptyList(),
    @SerialName("config") val config: CompanionConfig = CompanionConfig(),
    /** 今天的日期键（本地 yyyy-MM-dd），用于跨天重置。 */
    @SerialName("dayKey") val dayKey: String = "",
    @SerialName("countToday") val countToday: Int = 0,
    @SerialName("lastRunAtMillis") val lastRunAtMillis: Long = 0L,
    @SerialName("lastUserSpeakAtMillis") val lastUserSpeakAtMillis: Long = 0L,
    @SerialName("nextDueAtMillis") val nextDueAtMillis: Long = 0L,
    /** 人格版本：改人格时 +1，让旧的异步结果失效。 */
    @SerialName("personaVersion") val personaVersion: Int = 1,
    /** 这条状态属于哪个会话（用于判断是否被别的实例改写）。 */
    @SerialName("epoch") val epoch: Long = 0L,
    /**
     * 记忆/规则的版本。只在这些东西变化时 +1：事实增删改、对话追加、配置变更、人格变更。
     *
     * 为什么不复用 epoch：epoch 连"标记投递中"这种记账也会变，
     * 拿它做输出校验会把正常投递判成过期（F-05 的修法必须用更窄的版本）。
     */
    @SerialName("memoryVersion") val memoryVersion: Long = 0L,

    /** 上一次对话收尾的记录（含下次怎么接）。没有就是没收过尾。 */
    @SerialName("closure") val closure: ClosureRecord? = null,
)

interface CompanionStore {
    fun read(): CompanionState?
    fun write(state: CompanionState)
    val lastError: String?
}

/**
 * 原子写。与 dsh-world 的 durableWrite 一致：先写临时文件再改名，
 * 读取失败返回 null 并留下原因，调用方不能把它当成"没有状态"。
 */
class FileCompanionStore(
    private val file: File,
    private val json: Json = DefaultJson,
) : CompanionStore {

    override var lastError: String? = null
        private set

    override fun read(): CompanionState? {
        if (!file.exists()) { lastError = null; return null }
        return try {
            val text = file.readText(Charsets.UTF_8)
            val parsed = json.decodeFromString(CompanionState.serializer(), text)
            lastError = null
            parsed
        } catch (t: Throwable) {
            lastError = "读取伙伴状态失败：${t.javaClass.simpleName}: ${t.message}"
            null
        }
    }

    override fun write(state: CompanionState) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        val bytes = json.encodeToString(CompanionState.serializer(), state).toByteArray(Charsets.UTF_8)
        FileOutputStream(tmp).use { fos ->
            fos.write(bytes)
            fos.flush()
            fos.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            if (file.exists() && !file.delete()) error("无法替换旧的伙伴状态文件")
            if (!tmp.renameTo(file)) error("无法写入伙伴状态文件")
        }
        lastError = null
    }

    companion object {
        val DefaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
}
