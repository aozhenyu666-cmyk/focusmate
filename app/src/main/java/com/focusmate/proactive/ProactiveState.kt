package com.focusmate.proactive

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/** 待发队列 + 已发历史。原子写，读坏留痕不静默。 */
@Serializable
data class ProactiveState(
    @SerialName("version") val version: Int = 1,
    /** 待发（生成中/已生成未通知）。重启后恢复，但不集中补发。 */
    @SerialName("pending") val pending: List<ProactiveMessage> = emptyList(),
    /** 最近发过的消息（有界）。用于查重与「上次说了什么」。 */
    @SerialName("recent") val recent: List<ProactiveMessage> = emptyList(),
    /** 今天已发条数。 */
    @SerialName("sentToday") val sentToday: Int = 0,
    @SerialName("dayKey") val dayKey: String = "",
    /** 突发窗口起点与计数。 */
    @SerialName("windowStartMillis") val windowStartMillis: Long = 0L,
    @SerialName("windowCount") val windowCount: Int = 0,
    @SerialName("lastSentAtMillis") val lastSentAtMillis: Long = 0L,
    /** 连续沉默次数（发出后用户没回）。 */
    @SerialName("silenceStreak") val silenceStreak: Int = 0,
    /** 互动积极度 0~1（滑窗统计）。 */
    @SerialName("engagement") val engagement: Double = 0.5,
    /** 下次机会的候选时刻。 */
    @SerialName("nextOpportunityAtMillis") val nextOpportunityAtMillis: Long = 0L,
    /** 下一个可用通知 ID（递增，避免覆盖）。 */
    @SerialName("nextNotificationId") val nextNotificationId: Int = 5000,
)

interface ProactiveStore {
    fun read(): ProactiveState?
    fun write(state: ProactiveState)
    val lastError: String?
}

class FileProactiveStore(
    private val file: File,
    private val json: Json = DefaultJson,
) : ProactiveStore {

    override var lastError: String? = null
        private set

    override fun read(): ProactiveState? {
        if (!file.exists()) { lastError = null; return null }
        return try {
            val parsed = json.decodeFromString(ProactiveState.serializer(), file.readText(Charsets.UTF_8))
            lastError = null
            parsed
        } catch (t: Throwable) {
            lastError = "读取主动状态失败：" + t.javaClass.simpleName + ": " + t.message
            null
        }
    }

    override fun write(state: ProactiveState) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        val bytes = json.encodeToString(ProactiveState.serializer(), state).toByteArray(Charsets.UTF_8)
        FileOutputStream(tmp).use { fos ->
            fos.write(bytes)
            fos.flush()
            fos.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            if (file.exists() && !file.delete()) error("无法替换旧的主动状态文件")
            if (!tmp.renameTo(file)) error("无法写入主动状态文件")
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
