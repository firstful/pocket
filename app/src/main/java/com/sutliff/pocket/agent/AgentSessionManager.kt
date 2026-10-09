package com.sutliff.pocket.agent

import android.content.Context
import com.sutliff.pocket.data.*
import com.sutliff.pocket.network.InferenceClient
import com.sutliff.pocket.tools.AndroidToolRegistry
import com.sutliff.pocket.tools.ToolContext
import com.sutliff.pocket.tools.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File

sealed class AgentEvent {
    data class ContentDelta(val text: String) : AgentEvent()
    data class ToolCallStart(val toolCall: ToolCall) : AgentEvent()
    data class ToolCallResult(val toolCallId: String, val result: String) : AgentEvent()
    data class MessageComplete(val message: ChatMessage) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
}

class AgentSessionManager(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val sessionsDir = File(context.filesDir, "sessions").apply { mkdirs() }

    private var currentSessionId: String = ""
    private var currentMessages: MutableList<ChatMessage> = mutableListOf()
    private var currentConfig: InferenceConfig = InferenceConfig()

    fun createSession(): ChatSession {
        val session = ChatSession(
            id = "session_${System.currentTimeMillis()}",
            title = "New Session"
        )
        saveSession(session)
        return session
    }

    fun loadSession(sessionId: String): ChatSession? {
        val file = File(sessionsDir, "$sessionId.json")
        if (!file.exists()) return null
        return try {
            json.decodeFromString<ChatSession>(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    fun saveSession(session: ChatSession = ChatSession(id = currentSessionId, title = "Session", messages = currentMessages)) {
        val file = File(sessionsDir, "${session.id}.json")
        file.writeText(json.encodeToString(session))
    }

    fun listSessions(): List<ChatSession> {
        return sessionsDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { f ->
                try { json.decodeFromString<ChatSession>(f.readText()) } catch (e: Exception) { null }
            }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    fun deleteSession(sessionId: String) {
        File(sessionsDir, "$sessionId.json").delete()
    }

    fun run(
        sessionId: String,
        userMessage: String,
        onEvent: (AgentEvent) -> Unit
    ) {
        currentSessionId = sessionId
        val session = loadSession(sessionId)
        currentMessages = (session?.messages ?: mutableListOf()).toMutableList()
        currentMessages.add(ChatMessage(role = "user", content = userMessage))

        val config = currentConfig
        val toolRegistry = AndroidToolRegistry(ToolContext(context, context.filesDir.absolutePath))
        val client = InferenceClient(config)
        val tools = toolRegistry.getToolDefinitions()

        var rounds = 0
        val maxRounds = 10

        while (rounds < maxRounds) {
            val stream = client.streamChat(currentMessages, tools)
            val contentBuilder = StringBuilder()
            val toolCalls = mutableListOf<ToolCall>()

            stream.collect { chunk ->
                chunk.choices.firstOrNull()?.delta?.let { delta ->
                    delta.content?.let {
                        contentBuilder.append(it)
                        onEvent(AgentEvent.ContentDelta(it))
                    }
                    delta.toolCalls?.forEach { tc ->
                        toolCalls.add(tc)
                        onEvent(AgentEvent.ToolCallStart(tc))
                    }
                }
            }

            if (toolCalls.isEmpty()) {
                val finalMsg = ChatMessage(
                    role = "assistant",
                    content = contentBuilder.toString()
                )
                currentMessages.add(finalMsg)
                onEvent(AgentEvent.MessageComplete(finalMsg))
                saveSession()
                return
            }

            val assistantMsg = ChatMessage(
                role = "assistant",
                content = contentBuilder.toString(),
                toolCalls = toolCalls
            )
            currentMessages.add(assistantMsg)
            onEvent(AgentEvent.MessageComplete(assistantMsg))

            for (toolCall in toolCalls) {
                val tool = toolRegistry.get(toolCall.function.name)
                val result = if (tool != null) {
                    val args = if (toolCall.function.arguments.isBlank()) {
                        emptyMap()
                    } else {
                        json.parseToJsonElement(toolCall.function.arguments).jsonObject.mapValues { (_, v) ->
                            v.jsonPrimitive.content
                        }
                    }
                    tool.execute(args)
                } else {
                    "Error: Unknown tool '${toolCall.function.name}'"
                }

                currentMessages.add(ChatMessage(
                    role = "tool",
                    content = result,
                    toolCallId = toolCall.id
                ))
                onEvent(AgentEvent.ToolCallResult(toolCall.id, result))
            }

            rounds++
        }

        val finalMsg = ChatMessage(role = "assistant", content = contentBuilder.toString())
        currentMessages.add(finalMsg)
        onEvent(AgentEvent.MessageComplete(finalMsg))
        saveSession()
    }

    fun updateConfig(config: InferenceConfig) {
        currentConfig = config
    }

    fun getCurrentMessages(): List<ChatMessage> = currentMessages.toList()
}