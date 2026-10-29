package com.focusmate.companion

import android.content.Context

/**
 * 模型配置的本地存放。
 *
 * 安全边界（执行指令明确要求）：
 * - API Key 只存在 APP 私有目录的 shared_prefs 里，不写仓库、不写日志、不进 APK；
 * - 界面上只显示脱敏描述（主机名 + 模型名），不显示 key；
 * - 未配置时模型层返回 Unconfigured，主动功能停用，但专注限制不受影响。
 */
object ModelSettings {

    private const val PREFS = "companion_model"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_MODEL = "model"
    private const val KEY_API = "api_key"

    data class Snapshot(val config: ModelConfig, val hasKey: Boolean)

    fun load(context: Context): Snapshot {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val endpoint = p.getString(KEY_ENDPOINT, "") ?: ""
        val model = p.getString(KEY_MODEL, "") ?: ""
        val key = p.getString(KEY_API, "") ?: ""
        return Snapshot(ModelConfig(endpoint = endpoint, model = model, apiKey = key), key.isNotBlank())
    }

    fun save(context: Context, endpoint: String, model: String, apiKey: String?) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().apply {
            putString(KEY_ENDPOINT, endpoint.trim())
            putString(KEY_MODEL, model.trim())
            if (!apiKey.isNullOrBlank()) putString(KEY_API, apiKey.trim())
        }.apply()
    }

    /**
     * 连接测试：用一个极小的请求验证「地址 + 模型名 + 密钥」三者能否走通。
     *
     * 三条约束：
     * - 返回的文案里**绝不包含密钥**；
     * - 不把请求/响应正文写进日志（可能含用户隐私）；
     * - 只发一句「ping」，不消耗上下文、不写入记忆。
     */
    suspend fun testConnection(config: ModelConfig): ModelResult {
        if (!config.usable) {
            return ModelResult.Failed("还没填完整：地址、模型名、密钥都要有", retryable = false)
        }
        val client = OpenAiCompatibleClient { config }
        return client.complete(
            systemPrompt = "You are a health check. Reply with the single word: ok",
            userPrompt = "ping",
        )
    }

    fun clearKey(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_API).apply()
    }
}
