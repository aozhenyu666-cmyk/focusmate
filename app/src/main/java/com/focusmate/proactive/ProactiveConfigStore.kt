package com.focusmate.proactive

import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * 主动引擎配置的落盘。
 *
 * 为什么需要它：`ProactiveConfig` 原本只是 [ProactiveEngine] 里的一个内存字段，
 * `updateConfig()` 只 persist 状态、不 persist 配置，进程重启后全部回默认值。
 * 而设置页上写着「改这里会立刻生效」——实际是「改完重启就没了」。
 *
 * 写法照抄 [FileProactiveStore]：写临时文件 → fsync → rename，
 * 避免进程在写一半被杀时留下半个 JSON，读回来直接抛异常。
 */
interface ProactiveConfigStore {
    val lastError: String?

    /** 没有配置文件、或文件损坏时返回 null（调用方退回默认值，不崩）。 */
    fun read(): ProactiveConfig?

    fun write(config: ProactiveConfig)
}

class FileProactiveConfigStore(
    private val file: File,
    private val json: Json = DefaultJson,
) : ProactiveConfigStore {

    override var lastError: String? = null
        private set

    override fun read(): ProactiveConfig? {
        if (!file.exists()) {
            lastError = null
            return null
        }
        return try {
            val parsed = json.decodeFromString(ProactiveConfig.serializer(), file.readText(Charsets.UTF_8))
            lastError = null
            parsed
        } catch (t: Throwable) {
            // 配置坏了不该让主动功能整个失效：报出来，退回默认值。
            lastError = "读取主动配置失败：" + t.javaClass.simpleName + ": " + t.message
            null
        }
    }

    override fun write(config: ProactiveConfig) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        val bytes = json.encodeToString(ProactiveConfig.serializer(), config).toByteArray(Charsets.UTF_8)
        FileOutputStream(tmp).use { fos ->
            fos.write(bytes)
            fos.flush()
            fos.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            if (file.exists() && !file.delete()) error("无法替换旧的主动配置文件")
            if (!tmp.renameTo(file)) error("无法写入主动配置文件")
        }
    }

    companion object {
        internal val DefaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        /** 与状态文件放同一目录，方便一起备份/清理。 */
        fun at(filesDir: File): FileProactiveConfigStore =
            FileProactiveConfigStore(File(filesDir, "proactive/config.json"))
    }
}
