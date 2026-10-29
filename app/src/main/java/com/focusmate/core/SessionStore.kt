package com.focusmate.core

import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/** 存储抽象，便于 JVM 单测与未来换实现。 */
interface SessionStore {
    fun read(): FocusSession?
    fun write(session: FocusSession)
    fun clear()
}

/**
 * 文件实现：写入用「临时文件 + 原子改名」，避免掉电留下半个 JSON。
 * 读不出来时返回 null 并把原因交给调用方，不允许静默当作「没有会话」；
 * 因为「没有会话」和「有会话但读坏了」在恢复时处理方式不同。
 */
class FileSessionStore(
    private val file: File,
    private val json: Json = DefaultJson,
) : SessionStore {

    var lastError: String? = null
        private set

    override fun read(): FocusSession? {
        if (!file.exists()) { lastError = null; return null }
        return try {
            val text = file.readText(Charsets.UTF_8)
            val parsed = json.decodeFromString(FocusSession.serializer(), text)
            lastError = null
            parsed
        } catch (t: Throwable) {
            lastError = "读取会话失败：${t.javaClass.simpleName}: ${t.message}"
            null
        }
    }

    override fun write(session: FocusSession) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        val bytes = json.encodeToString(FocusSession.serializer(), session).toByteArray(Charsets.UTF_8)
        FileOutputStream(tmp).use { fos ->
            fos.write(bytes)
            fos.flush()
            fos.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            // 某些文件系统上 rename 到已存在目标会失败，退化为删除后重命名。
            if (file.exists() && !file.delete()) error("无法替换旧会话文件")
            if (!tmp.renameTo(file)) error("无法写入会话文件")
        }
        lastError = null
    }

    override fun clear() {
        if (file.exists()) file.delete()
    }

    companion object {
        val DefaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
}
