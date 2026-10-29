package com.focusmate.net

import com.focusmate.agent.LlmClient
import com.focusmate.agent.LlmException
import com.focusmate.agent.LlmMessage
import com.focusmate.agent.LlmReply
import com.focusmate.agent.OpenAiCodec
import com.focusmate.agent.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * OpenAI 兼容的 /chat/completions 客户端（DeepSeek、通义千问、OpenAI 等）。
 * 不跟随重定向、不自动重试（重试策略在安排簿里统一管），响应体有上限。
 */
class OpenAiHttpClient(private val config: () -> Config) : LlmClient {

    data class Config(val baseUrl: String, val model: String, val apiKey: String?)

    override suspend fun complete(messages: List<LlmMessage>, tools: List<ToolSpec>): LlmReply = withContext(Dispatchers.IO) {
        val c = config()
        val key = c.apiKey?.takeIf { it.isNotBlank() }
            ?: throw LlmException(LlmException.Kind.NO_KEY, "还没有填 API Key（设置页）")
        val url = try {
            URL(endpoint(c.baseUrl))
        } catch (t: Throwable) {
            throw LlmException(LlmException.Kind.NETWORK, "接口地址无效：${c.baseUrl}")
        }
        val body = OpenAiCodec.request(c.model, messages, tools).toByteArray(Charsets.UTF_8)

        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }

            val code = conn.responseCode
            val text = readCapped(if (code in 200..299) conn.inputStream else conn.errorStream)
            if (code in 200..299) return@withContext OpenAiCodec.parseReply(text)

            val server = OpenAiCodec.errorMessage(text)?.let { "（$it）" }.orEmpty()
            throw when (code) {
                401, 403 -> LlmException(LlmException.Kind.AUTH, "API Key 无效或没有权限$server")
                402 -> LlmException(LlmException.Kind.QUOTA, "账户余额不足$server")
                404 -> LlmException(LlmException.Kind.BAD_RESPONSE, "接口地址或模型名不对$server")
                429 -> LlmException(LlmException.Kind.RATE_LIMIT, "请求太频繁或额度用完$server")
                in 300..399 -> LlmException(LlmException.Kind.NETWORK, "接口地址发生了重定向，请检查地址（不自动跟随）")
                in 500..599 -> LlmException(LlmException.Kind.SERVER, "模型服务暂时出错（HTTP $code）$server")
                else -> LlmException(LlmException.Kind.BAD_RESPONSE, "HTTP $code$server")
            }
        } catch (e: LlmException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw LlmException(LlmException.Kind.TIMEOUT, "模型响应超时")
        } catch (e: UnknownHostException) {
            throw LlmException(LlmException.Kind.NETWORK, "网络不可用或域名解析失败")
        } catch (e: SSLException) {
            throw LlmException(LlmException.Kind.NETWORK, "安全连接失败：${e.message}")
        } catch (e: java.io.IOException) {
            throw LlmException(LlmException.Kind.NETWORK, "网络错误：${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val MAX_BYTES = 1 shl 20

        fun endpoint(baseUrl: String): String {
            val b = baseUrl.trim().trimEnd('/')
            return if (b.endsWith("/chat/completions")) b else "$b/chat/completions"
        }

        private fun readCapped(input: InputStream?): String {
            if (input == null) return ""
            input.use { s ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) throw LlmException(LlmException.Kind.BAD_RESPONSE, "模型返回过大")
                    out.write(buf, 0, n)
                }
                return out.toString(Charsets.UTF_8.name())
            }
        }
    }
}
