package com.sutliff.pocket.network

import com.sutliff.pocket.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import android.util.Log
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.SerialName
import com.sutliff.pocket.telemetry.Telemetry
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

/**
 * Wire DTO for OpenAI chat completions — clean snake_case, no null fields,
 * no Android/internal fields (timestamp, camelCase toolCalls). The gateway
 * rejects the persisted ChatMessage shape with a 400
 * ("messages[0].toolCalls: cannot preserve this message field across
 * protocols"). Epoch at app startup: [SessionEpoch] increments on cold
 * start so old composite state never leaks into the new wire stream.
 * ChatMessage itself is unchanged (session persistence uses it as-is).
 */
@Serializable
data class WireMsg(
    val role: String,
    val content: String,
    @SerialName("tool_calls") val toolCalls: List<WireToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null
)

@Serializable
data class WireToolCall(
    val id: String,
    val type: String = "function",
    val function: WireFunction
)

@Serializable
data class WireFunction(
    val name: String,
    val arguments: String
)

object SessionEpoch {
    @Volatile var value: Int = 1
        private set
    fun next(): Int = ++value
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

    /** Request-serialization instance: no null fields on the wire. */
    private val wireJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
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
        Log.d("PocketNet", "streamChat: executing request url=${config.baseUrl}/chat/completions")
        val response = client.newCall(request).execute()
        val contentType = response.header("Content-Type") ?: "(none)"
        Log.d("PocketNet", "streamChat: response code=${response.code} content-type=$contentType")
        if (!response.isSuccessful) {
            val errBody = response.body?.string()
            val errMsg = "HTTP ${response.code} $contentType: ${errBody?.take(500)}"
            Log.e("PocketNet", "streamChat: $errMsg")
            Telemetry.logError(com.sutliff.pocket.AgentApp.instance!!, "streamChat_http", IOException(errMsg))
            throw IOException(errMsg)
        }
        onPhase?.invoke(AgentStatus.WAITING)

        val source = response.body?.source() ?: throw IOException("Empty response body")
        var lineCount = 0
        var dataCount = 0
        var firstDataLogged = false
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            lineCount++
            if (line.startsWith("data: ")) {
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") { Log.d("PocketNet", "streamChat: [DONE] after $lineCount lines ($dataCount data)"); break }
                dataCount++
                if (!firstDataLogged) {
                    Log.d("PocketNet", "streamChat: first data (${data.length} bytes): ${data.take(500)}")
                    firstDataLogged = true
                }
                try {
                    val chunk = json.decodeFromString(StreamChunk.serializer(), data)
                    emit(chunk)
                } catch (e: Exception) {
                    Log.w("PocketNet", "streamChat: skip malformed chunk: ${e.message} :: ${data.take(500)}")
                }
            } else if (line.isNotBlank()) {
                // Non-SSE line (e.g. a 200-with-error JSON body) — log it loud.
                Log.w("PocketNet", "streamChat: non-data line #$lineCount (${line.length}b): ${line.take(500)}")
            }
        }
        Log.d("PocketNet", "streamChat: loop done, lines=$lineCount data=$dataCount")
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
        // Wire mapper: normalize ChatMessage → WireMsg (clean snake_case, no nulls).
        // wireJson has explicitNulls=false so absent tool_calls/tool_call_id are
        // omitted entirely — the gateway's stateful-history validator rejects
        // null toolCalls fields even on non-assistant messages.
        val wireMessages = messages.map { m ->
            WireMsg(
                role = m.role,
                content = m.content,
                toolCalls = m.toolCalls?.takeIf { it.isNotEmpty() }?.map { tc ->
                    WireToolCall(
                        id = tc.id,
                        type = tc.type,
                        function = WireFunction(
                            name = tc.function.name,
                            arguments = tc.function.arguments
                        )
                    )
                },
                toolCallId = m.toolCallId?.takeIf { it.isNotBlank() }
            )
        }
        return JsonObjectBuilder()
            .add("model", config.model)
            .add("messages", wireJson.encodeToJsonElement(wireMessages))
            .add("temperature", config.temperature)
            .add("max_tokens", config.maxTokens)
            .add("stream", stream)
            .addIfNotNull("tools", tools?.let { wireJson.encodeToJsonElement(it) })
            .build()
    }
}