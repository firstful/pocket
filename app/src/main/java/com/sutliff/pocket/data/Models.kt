package com.sutliff.pocket.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class InferenceConfig(
    val baseUrl: String = "http://llm.homelab.internal/v1",
    val apiKey: String = "dummy",
    val model: String = "auto",
    val systemPrompt: String = "You are a helpful assistant running in an Alpine Linux environment on Android.",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096,
    val stream: Boolean = true,
    val darkMode: Int = 0
)

@Serializable
data class TelemetryConfig(
    /** Empty = telemetry off (no-op). Non-empty = OTLP receiver endpoint URL. */
    val endpoint: String = "",
    /** "otlp_json" (OpenTelemetry Logs JSON) or "off" (hard disable, overrides endpoint). */
    val format: String = TelemetryConfig.FORMAT_OTLP_JSON
) {
    companion object {
        const val FORMAT_OTLP_JSON = "otlp_json"
        const val FORMAT_OFF = "off"
    }
}

/**
 * A generic remote (OpenAI-shaped) agent backend, e.g. Hermes /v1/responses
 * or OpenClaw. Configured by the user in Settings and stored in DataStore —
 * no secret is baked into the APK (the old BuildConfig.API_SERVER_KEY field
 * has been removed; [apiKey] is user-entered and lives only on-device).
 *
 * @param name      short id; surfaced to the primary LLM as tool `remote_<name>`
 *                  (sanitized: lowercase a-z0-9_). Looked up by `rook`/`hermes` for @rook.
 * @param baseUrl   host root WITHOUT a trailing /v1 (e.g. http://host:8642);
 *                  a trailing /v1 is stripped defensively.
 * @param endpoint  "responses" (/v1/responses, SSE deltas) or
 *                  "chat_completions" (/v1/chat/completions, SSE deltas).
 * @param model     model id sent to the backend.
 * @param apiKey    bearer token, user-entered. Empty = no Authorization header.
 * @param enabled   disabled remotes are not exposed as tools.
 */
@Serializable
data class RemoteAgent(
    val name: String,
    val baseUrl: String,
    val endpoint: String = "responses",
    val model: String,
    val apiKey: String = "",
    val enabled: Boolean = true
)

/** Opt-in toggles for sensitive device access. ALL default OFF. */
@Serializable
data class PermissionOptIns(
    val notificationListenerEnabled: Boolean = false,
    val locationEnabled: Boolean = false,
    // SMS opt-in is tracked via runtime permission state (SmsPermissions.isGranted)
    // and intentionally has no DataStore flag: the permission grant IS the opt-in.
    val smsEnabled: Boolean = false
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    val name: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class ToolCall(
    val id: String = "",
    val type: String = "function",
    val function: FunctionCall,
    // OpenAI streams parallel tool calls as index-ordered fragments; capture
    // the index so ordering is preserved across deltas (2nd positional field
    // is avoided: Room/Persistence may map by position — append-only).
    val index: Int = 0
)

@Serializable
data class FunctionCall(
    val name: String = "",
    val arguments: String = ""
)

@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val messages: List<ChatMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val archived: Boolean = false,
    val pinned: Boolean = false
)

@Serializable
data class StreamChunk(
    val id: String = "",
    val choices: List<StreamChoice> = emptyList()
)

@Serializable
data class StreamChoice(
    val index: Int = 0,
    val delta: DeltaContent = DeltaContent(),
    val finishReason: String? = null
)

@Serializable
data class DeltaContent(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null
)

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: ToolFunction
)

@Serializable
data class ToolFunction(
    val name: String,
    val description: String,
    val parameters: kotlinx.serialization.json.JsonObject? = null
)

/**
 * Explicit send-pipeline state surfaced in the chat UI.
 *
 * IDLE               – nothing in flight
 * WAITING_FOR_NETWORK – device offline / no route to the gateway; messages are queued
 * SENDING            – request written (or being written), waiting for response headers
 * WAITING            – connected, waiting for the first streamed token
 * STREAMING          – receiving content deltas
 */
enum class AgentStatus {
    IDLE,
    WAITING_FOR_NETWORK,
    SENDING,
    WAITING,
    STREAMING
}

/** A user message composed while offline (or while a backlog existed), stored durably until it is sent. */
@Serializable
data class QueuedMessage(
    val sessionId: String,
    val text: String,
    val timestamp: Long
)