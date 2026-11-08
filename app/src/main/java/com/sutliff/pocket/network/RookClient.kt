package com.sutliff.pocket.network

import com.sutliff.pocket.data.AgentStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit

import com.sutliff.pocket.BuildConfig

/**
 * Client for the Hermes gateway OpenAI *Responses* API used to talk to Rook.
 * Endpoint: http://hermes-gateway.homelab.internal:8642/v1/responses, model hermes-agent, auth via API_SERVER_KEY (BuildConfig).
 *
 * Exposes a streaming flow of text deltas (strings) plus a non-streaming helper.
 */
class RookClient(
    val baseUrl = "http://hermes-gateway.homelab.internal:8642"
    private val apiKey: String = BuildConfig.API_SERVER_KEY,
    private val model: String = "hermes-agent"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Streams text deltas from the Responses API. Emits one String per
     * `response.output_text.delta` event. onPhase is invoked on the IO dispatcher.
     */
    fun streamResponses(
        input: String,
        onPhase: ((AgentStatus) -> Unit)? = null
    ): Flow<String> = flow {
        val body = JsonObjectBuilder()
            .add("model", model)
            .add("input", input)
            .add("stream", true)
            .build()

        val request = Request.Builder()
            .url("$baseUrl/v1/responses")
            .apply {
                if (apiKey.isNotEmpty()) header("Authorization", "Bearer $apiKey")
            }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        onPhase?.invoke(AgentStatus.SENDING)
        Log.d("RookClient", "streamResponses: executing url=$baseUrl/v1/responses")
        val response = client.newCall(request).execute()
        Log.d("RookClient", "streamResponses: code=${response.code}")
        if (!response.isSuccessful) {
            val err = response.body?.string() ?: "HTTP ${response.code}"
            throw IOException("HTTP ${response.code}: $err")
        }
        onPhase?.invoke(AgentStatus.WAITING)

        val source = response.body?.source() ?: throw IOException("Empty response body")
        var emitted = false
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (line.startsWith("data: ")) {
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") break
                try {
                    val obj = json.parseToJsonElement(data).jsonObject
                    val type = obj["type"]?.jsonPrimitive?.content
                    if (type == "response.output_text.delta") {
                        val delta = obj["delta"]?.jsonPrimitive?.content
                        if (!delta.isNullOrEmpty()) {
                            if (!emitted) {
                                emitted = true
                                onPhase?.invoke(AgentStatus.STREAMING)
                            }
                            emit(delta)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("RookClient", "skip malformed SSE: ${e.message}")
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Non-streaming call; returns the full assistant text.
     */
    fun nonStreamResponses(input: String): String {
        val body = JsonObjectBuilder()
            .add("model", model)
            .add("input", input)
            .add("stream", false)
            .build()

        val request = Request.Builder()
            .url("$baseUrl/v1/responses")
            .apply {
                if (apiKey.isNotEmpty()) header("Authorization", "Bearer $apiKey")
            }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code}: ${response.body?.string()}")
        }
        val responseBody = response.body?.string() ?: throw IOException("Empty response")
        val parsed = json.parseToJsonElement(responseBody).jsonObject
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
        return ""
    }
}

// Reuse the existing builder (same package).
// JsonObjectBuilder is defined in InferenceClient.kt.