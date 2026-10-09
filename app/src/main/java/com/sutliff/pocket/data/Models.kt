package com.sutliff.pocket.data

import kotlinx.serialization.Serializable

@Serializable
data class InferenceConfig(
    val baseUrl: String = "http://192.168.1.100:8080/v1",
    val apiKey: String = "",
    val model: String = "llama3-8b-instruct",
    val systemPrompt: String = "You are a helpful assistant running in an Alpine Linux environment on Android.",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096,
    val stream: Boolean = true
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    val name: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
)

@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String
)

@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val messages: List<ChatMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
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
    val toolCalls: List<ToolCall>? = null
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