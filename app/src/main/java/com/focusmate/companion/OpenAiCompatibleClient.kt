package com.focusmate.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 一个最小的 OpenAI 兼容 /chat/completions 客户端。
 * 不引入第三方 HTTP 库，避免为了三期功能把依赖面扩大。
 *
 * 明确不做的事：
 * - 不重试（重试策略属于上层调度，见 FollowUpEngine.markFailed 的退避）；
 * - 不打印请求/响应正文到日志（可能含用户隐私），只记录状态码与耗时；
 * - 不把 apiKey 写进任何异常消息。
 */
class OpenAiCompatibleClient(
    private val configProvider: () -> ModelConfig,
) : ModelClient {

    override val configured: Boolean get() = configProvider().usable

    override suspend fun complete(systemPrompt: String, userPrompt: String): ModelResult =
        withContext(Dispatchers.IO) {
            val cfg = configProvider()
            if (!cfg.usable) return@withContext ModelResult.Unconfigured

            val body = buildJsonObject {
                put("model", cfg.model)
                put("temperature", 0.8)
                put("max_tokens", 400)
                put("messages", buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", systemPrompt) })
                    add(buildJsonObject { put("role", "user"); put("content", userPrompt) })
                })
            }.toString()

            var conn: HttpURLConnection? = null
            try {
                conn = (URL(cfg.endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = cfg.timeoutMillis
                    readTimeout = cfg.timeoutMillis
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer " + cfg.apiKey)
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                    // 只回传状态码与前 200 字，且做一次 key 脱敏，防止服务端把 key 回显。
                    val safe = err.replace(cfg.apiKey, "***").take(200)
                    return@withContext ModelResult.Failed("HTTP " + code + " " + safe, retryable = code >= 500 || code == 429)
                }
                val text = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val parsed = extractText(text)
                if (parsed.isNullOrBlank()) {
                    ModelResult.Failed("响应里没有可用的文本", retryable = true)
                } else {
                    ModelResult.Ok(parsed.trim())
                }
            } catch (t: Throwable) {
                // 网络异常：message 可能含 URL，但不含 key（key 只在 header）。
                ModelResult.Failed((t.javaClass.simpleName + ": " + (t.message ?: "")).take(200), retryable = true)
            } finally {
                conn?.disconnect()
            }
        }

    /** 从 chat/completions 响应里取第一条 message.content；结构不认识就返回 null，不猜。 */
    private fun extractText(raw: String): String? = try {
        val root = Json.parseToJsonElement(raw).jsonObject
        val choices = root["choices"]?.jsonArray ?: return null
        val first = choices.firstOrNull()?.jsonObject ?: return null
        val message = first["message"]?.jsonObject ?: return null
        message["content"]?.jsonPrimitive?.content
    } catch (t: Throwable) {
        null
    }

    private companion object {
        val Json = Json { ignoreUnknownKeys = true }
    }
}
