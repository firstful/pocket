package com.sutliff.pocket.network

import com.sutliff.pocket.data.AgentStatus
import com.sutliff.pocket.data.RemoteAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import android.util.Log

/**
 * Generic OpenAI-shaped remote-agent client.
 *
 * Supports two wire shapes a remote backend may speak:
 *  - [RemoteAgent.endpoint] == "responses": POST $baseUrl/v1/responses,
 *    body {model, input, stream}, deltas from `response.output_text.delta`.
 *  - [RemoteAgent.endpoint] == "chat_completions": POST $baseUrl/v1/chat/completions,
 *    body {model, messages:[{role:user, content:input}], stream},
 *    deltas from `choices[0].delta.content`.
 *
 * Auth: bearer [RemoteAgent.apiKey] when non-empty; the key is user-entered
 * in Settings (DataStore) and never baked into the APK.
 */
class RemoteAgentClient(private val agent: RemoteAgent) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** Normalized host root: trims whitespace + trailing "/" and a trailing "/v1". */
    private fun hostRoot(): String {
        var h = agent.baseUrl.trim().trimEnd('/')
        if (h.endsWith("/v1")) h = h.removeSuffix("/v1").trimEnd('/')
        return h
    }

    private fun path(): String = when (agent.endpoint.trim().lowercase()) {
        "chat_completions", "chat-completions", "chat_completions" -> "/v1/chat/completions"
        else -> "/v1/responses"
    }

    private fun isChatCompletions(): Boolean =
        agent.endpoint.trim().lowercase() in setOf("chat_completions", "chat-completions")

    /**
     * Streams text deltas as a Flow<String>. [onPhase] (optional) receives
     * SENDING / WAITING / STREAMING on the IO dispatcher — same contract as RookClient.
     */
    fun streamResponses(
        input: String,
        onPhase: ((AgentStatus) -> Unit)? = null
    ): Flow<String> = flow {
        val url = "${hostRoot()}${path()}"
        val body = if (isChatCompletions()) {
            buildJsonObject {
                put("model", agent.model)
                putJsonArray("messages") {
                    add(buildJsonObject { put("role", "user"); put("content", input) })
                }
                put("stream", true)
            }
        } else {
            buildJsonObject {
                put("model", agent.model)
                put("input", input)
                put("stream", true)
            }
        }

        val request = Request.Builder()
            .url(url)
            .apply { if (agent.apiKey.isNotEmpty()) header("Authorization", "Bearer ${agent.apiKey}") }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        onPhase?.invoke(AgentStatus.SENDING)
        Log.d("RemoteAgentClient", "streamResponses: executing url=$url model=${agent.model}")
        val response = client.newCall(request).execute()
        Log.d("RemoteAgentClient", "streamResponses: code=${response.code}")
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${response.body?.string()}")
        onPhase?.invoke(AgentStatus.WAITING)

        val source = response.body?.source() ?: throw IOException("Empty response body")
        var emitted = false
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data: ")) continue
            val data = line.removePrefix("data: ").trim()
            if (data == "[DONE]") break
            try {
                val delta = parseDelta(data)
                if (!delta.isNullOrEmpty()) {
                    if (!emitted) { emitted = true; onPhase?.invoke(AgentStatus.STREAMING) }
                    emit(delta)
                }
            } catch (e: Exception) {
                Log.w("RemoteAgentClient", "skip malformed SSE: ${e.message}")
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Non-streaming call; returns the full assistant text. */
    fun nonStream(input: String): String {
        val url = "${hostRoot()}${path()}"
        val body = if (isChatCompletions()) {
            buildJsonObject {
                put("model", agent.model)
                putJsonArray("messages") {
                    add(buildJsonObject { put("role", "user"); put("content", input) })
                }
                put("stream", false)
            }
        } else {
            buildJsonObject {
                put("model", agent.model)
                put("input", input)
                put("stream", false)
            }
        }
        val request = Request.Builder()
            .url(url)
            .apply { if (agent.apiKey.isNotEmpty()) header("Authorization", "Bearer ${agent.apiKey}") }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${response.body?.string()}")
        val raw = response.body?.string() ?: throw IOException("Empty response")
        val parsed = json.parseToJsonElement(raw).jsonObject
        return if (isChatCompletions()) {
            parsed["choices"]?.let { choices ->
                val first = choices.jsonArray.firstOrNull()?.jsonObject
                val msg = first?.get("message")?.jsonObject
                msg?.get("content")?.jsonPrimitive?.content ?: ""
            } ?: ""
        } else {
            val output = parsed["output"]?.jsonArray ?: return ""
            for (item in output) {
                val obj = item.jsonObject
                if (obj["type"]?.jsonPrimitive?.content == "message") {
                    val content = obj["content"]?.jsonArray ?: continue
                    for (c in content) {
                        val co = c.jsonObject
                        if (co["type"]?.jsonPrimitive?.content == "output_text") {
                            return co["text"]?.jsonPrimitive?.content ?: ""
                        }
                    }
                }
            }
            ""
        }
    }

    private fun parseDelta(data: String): String? {
        val obj = json.parseToJsonElement(data).jsonObject
        if (isChatCompletions()) {
            val choices = obj["choices"]?.jsonArray
            val first = choices?.firstOrNull()?.jsonObject ?: return null
            val delta = first["delta"]?.jsonObject ?: return null
            val el: JsonPrimitive? = delta["content"] as? JsonPrimitive
            return el?.content
        }
        val type = obj["type"]?.jsonPrimitive?.content
        if (type == "response.output_text.delta") {
            return obj["delta"]?.jsonPrimitive?.content
        }
        // Some servers emit {"delta":"..."} without the type envelope.
        if (obj.containsKey("delta") && obj["delta"] is JsonPrimitive) {
            return obj["delta"]?.jsonPrimitive?.content
        }
        return null
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    try { this.content } catch (e: Exception) { null }
