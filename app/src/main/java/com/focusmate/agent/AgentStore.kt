package com.focusmate.agent

import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * 伙伴状态的文件存储：临时文件 + 原子改名。
 * 读坏时把坏文件留作备份再从空状态开始，并把原因交给调用方显示，
 * 不静默当成「什么都没有」（与专注会话的存储同一原则）。
 */
class AgentStore(private val file: File) {

    var lastError: String? = null
        private set

    fun read(): AgentState {
        if (!file.exists()) { lastError = null; return AgentState() }
        return try {
            val s = json.decodeFromString(AgentState.serializer(), file.readText(Charsets.UTF_8))
            lastError = null
            s
        } catch (t: Throwable) {
            val backup = File(file.parentFile, file.name + ".broken-" + System.currentTimeMillis())
            file.renameTo(backup)
            lastError = "伙伴记录读取失败，已备份为 ${backup.name}：${t.javaClass.simpleName}"
            AgentState()
        }
    }

    fun write(state: AgentState) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(AgentState.serializer(), state).toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            if (file.exists() && !file.delete()) error("无法替换旧的伙伴记录")
            if (!tmp.renameTo(file)) error("无法写入伙伴记录")
        }
    }

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
