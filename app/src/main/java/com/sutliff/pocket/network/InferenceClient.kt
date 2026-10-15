package com.sutliff.pocket.network

import com.sutliff.pocket.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class JsonObjectBuilder {
    private val map = mutableMapOf<String, JsonElement>()

    fun add(key: String, value: String) = apply { map[key] = JsonPrimitive(value) }
    fun add(key: String, value: Number) = apply { map[key] = JsonPrimitive(value) }
    fun add(key: String, value: Boolean) = apply { map[key] = JsonPrimitive(value) }
    fun add(key: String, value: JsonElement) = apply { map[key] = value }
    fun addIfNotNull(key: String, value: JsonElement?) = apply { if (value != null) map[key] = value }

    fun build(): JsonObject = JsonObject(map)
}

class InferenceClient(private val config: InferenceConfig) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun streamChat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>? = null,
        // Optional phase observer: SENDING = request written / waiting for response headers,
        // WAITING = connected, waiting for the first token. Invoked on the flow's IO dispatcher.
        onPhase: ((AgentStatus) -> Unit)? = null
    ): Flow<StreamChunk> = flow {
        val requestBody = buildChatRequest(messages, tools, stream = true)
        val request = Request.Builder()
            .url("${config.baseUrl}/chat/completions")
            .apply {
                if (config.apiKey.isNotEmpty()) {
                    header("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        onPhase?.invoke(AgentStatus.SENDING)
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code}: ${response.body?.string()}")
        }
        onPhase?.invoke(AgentStatus.WAITING)

        val source = response.body?.source() ?: throw IOException("Empty response body")

        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (line.startsWith("data: ")) {
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") break
                try {
                    val chunk = json.decodeFromString(StreamChunk.serializer(), data)
                    emit(chunk)
                } catch (e: Exception) {
                    // skip malformed chunks
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    fun nonStreamChat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>? = null
    ): ChatMessage {
        val requestBody = buildChatRequest(messages, tools, stream = false)
        val request = Request.Builder()
            .url("${config.baseUrl}/chat/completions")
            .apply {
                if (config.apiKey.isNotEmpty()) {
                    header("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code}: ${response.body?.string()}")
        }

        val responseBody = response.body?.string() ?: throw IOException("Empty response")
        val parsed = json.parseToJsonElement(responseBody).jsonObject
        val choice = parsed["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw IOException("No choices in response")
        val message = choice["message"]?.jsonObject
            ?: throw IOException("No message in response")

        return ChatMessage(
            role = message["role"]?.jsonPrimitive?.content ?: "assistant",
            content = message["content"]?.jsonPrimitive?.content ?: "",
            toolCalls = message["tool_calls"]?.jsonArray?.map { tc ->
                ToolCall(
                    id = tc.jsonObject["id"]?.jsonPrimitive?.content ?: "",
                    type = tc.jsonObject["type"]?.jsonPrimitive?.content ?: "function",
                    function = FunctionCall(
                        name = tc.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "",
                        arguments = tc.jsonObject["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.content ?: ""
                    )
                )
            }
        )
    }

    fun listModels(): List<String> {
        val request = Request.Builder()
            .url("${config.baseUrl}/models")
            .apply {
                if (config.apiKey.isNotEmpty()) {
                    header("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .get()
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return emptyList()

        val body = response.body?.string() ?: return emptyList()
        return try {
            val parsed = json.parseToJsonElement(body).jsonObject
            parsed["data"]?.jsonArray?.map { it.jsonObject["id"]?.jsonPrimitive?.content ?: "" } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun buildChatRequest(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        stream: Boolean
    ): JsonObject {
        return JsonObjectBuilder()
            .add("model", config.model)
            .add("messages", json.encodeToJsonElement(messages))
            .add("temperature", config.temperature)
            .add("max_tokens", config.maxTokens)
            .add("stream", stream)
            .addIfNotNull("tools", tools?.let { json.encodeToJsonElement(it) })
            .build()
    }
}