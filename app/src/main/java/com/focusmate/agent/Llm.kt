package com.focusmate.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 发给模型的一条消息（OpenAI 兼容格式）。 */
sealed interface LlmMessage {
    data class System(val content: String) : LlmMessage
    data class User(val content: String) : LlmMessage
    data class Assistant(val content: String?, val toolCalls: List<ToolCall> = emptyList()) : LlmMessage
    data class ToolResult(val callId: String, val content: String) : LlmMessage
}

data class ToolCall(val id: String, val name: String, val argumentsJson: String)

data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

data class LlmReply(val content: String?, val toolCalls: List<ToolCall>)

/** 模型调用失败的分类，界面直接显示 message。 */
class LlmException(val kind: Kind, override val message: String) : Exception(message) {
    enum class Kind { NO_KEY, AUTH, QUOTA, RATE_LIMIT, NETWORK, TIMEOUT, SERVER, BAD_RESPONSE }
}

interface LlmClient {
    /** 失败抛 LlmException。 */
    suspend fun complete(messages: List<LlmMessage>, tools: List<ToolSpec>): LlmReply
}

/** 请求与响应的编解码，纯 JSON，可单测。 */
object OpenAiCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun request(model: String, messages: List<LlmMessage>, tools: List<ToolSpec>, temperature: Double = 0.8): String {
        val body = buildJsonObject {
            put("model", model)
            put("temperature", temperature)
            put("messages", buildJsonArray { messages.forEach { add(encode(it)) } })
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    tools.forEach { t ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", t.name)
                                put("description", t.description)
                                put("parameters", t.parameters)
                            })
                        })
                    }
                })
            }
        }
        return body.toString()
    }

    private fun encode(m: LlmMessage): JsonObject = when (m) {
        is LlmMessage.System -> buildJsonObject { put("role", "system"); put("content", m.content) }
        is LlmMessage.User -> buildJsonObject { put("role", "user"); put("content", m.content) }
        is LlmMessage.Assistant -> buildJsonObject {
            put("role", "assistant")
            put("content", m.content?.let { JsonPrimitive(it) } ?: JsonNull)
            if (m.toolCalls.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
                    m.toolCalls.forEach { c ->
                        add(buildJsonObject {
                            put("id", c.id)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", c.name)
                                put("arguments", c.argumentsJson)
                            })
                        })
                    }
                })
            }
        }
        is LlmMessage.ToolResult -> buildJsonObject {
            put("role", "tool")
            put("tool_call_id", m.callId)
            put("content", m.content)
        }
    }

    fun parseReply(body: String): LlmReply {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (t: Throwable) {
            throw LlmException(LlmException.Kind.BAD_RESPONSE, "模型返回的不是 JSON")
        }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: throw LlmException(LlmException.Kind.BAD_RESPONSE, "模型返回里没有 choices")
        val message = choice["message"]?.jsonObject
            ?: throw LlmException(LlmException.Kind.BAD_RESPONSE, "模型返回里没有 message")
        val content = (message["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val calls = (message["tool_calls"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val fn = o["function"] as? JsonObject ?: return@mapNotNull null
            val name = (fn["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val args = when (val a = fn["arguments"]) {
                is JsonPrimitive -> a.contentOrNull ?: "{}"
                is JsonObject -> a.toString()
                else -> "{}"
            }
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: "call_${name}"
            ToolCall(id, name, args)
        }.orEmpty()
        return LlmReply(content, calls)
    }

    /** 从错误响应里取出服务端说明，取不到返回 null。 */
    fun errorMessage(body: String): String? = try {
        val o = json.parseToJsonElement(body).jsonObject
        val e: JsonElement? = o["error"]
        when (e) {
            is JsonObject -> e["message"]?.jsonPrimitive?.contentOrNull
            is JsonPrimitive -> e.contentOrNull
            else -> null
        }
    } catch (t: Throwable) {
        null
    }

    fun parseArgs(argumentsJson: String): JsonObject? = try {
        json.parseToJsonElement(argumentsJson.ifBlank { "{}" }) as? JsonObject
    } catch (t: Throwable) {
        null
    }
}
