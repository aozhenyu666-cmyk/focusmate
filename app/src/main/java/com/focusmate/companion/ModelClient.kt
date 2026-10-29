package com.focusmate.companion

/**
 * 模型调用的端口。抽象出来有三个目的：
 * 1. 单测可以注入假实现，不需要网络；
 * 2. 网络/模型故障时，调用方能拿到明确的失败原因，而不是一个空字符串；
 * 3. API Key 只在这一层被读取，绝不写日志、不进仓库（DECISIONS 要求）。
 */
interface ModelClient {
    /** 是否已配置（有 key 与 endpoint）。没配置时不应该发起调用。 */
    val configured: Boolean

    suspend fun complete(systemPrompt: String, userPrompt: String): ModelResult
}

sealed interface ModelResult {
    data class Ok(val text: String) : ModelResult

    /** 失败必须带原因；调用方据此决定重试、退回文字还是放弃。 */
    data class Failed(val reason: String, val retryable: Boolean = true) : ModelResult

    /** 没有配置模型：不是故障，是功能未启用。 */
    data object Unconfigured : ModelResult
}

/** 模型配置。Key 只在内存/私有配置里，不序列化进仓库文件。 */
data class ModelConfig(
    val endpoint: String,
    val model: String,
    val apiKey: String,
    val timeoutMillis: Int = 30_000,
) {
    val usable: Boolean get() = endpoint.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    /** 给界面显示的脱敏描述，永远不含 key。 */
    fun describe(): String = if (usable) {
        "已配置：" + model + " @ " + hostOf(endpoint)
    } else {
        "未配置模型（主动发言与回复都会停用，专注限制不受影响）"
    }

    private fun hostOf(url: String): String = try {
        java.net.URI(url).host ?: "自定义地址"
    } catch (t: Throwable) {
        "自定义地址"
    }
}
