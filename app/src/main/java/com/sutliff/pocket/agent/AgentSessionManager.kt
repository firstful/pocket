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
    private var currentSessionMeta: ChatSession? = null
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

    /**
     * Runs one user turn against the gateway.
     *
     * @param onStatus optional observer for send-pipeline phases (SENDING / WAITING from the
     *   HTTP client; the UI derives STREAMING from the first ContentDelta). Invoked on the
     *   IO dispatcher — callers must hop to their own state holder safely.
     * @param onEvent stream of content/tool/message events for this turn.
     */
    suspend fun run(
        sessionId: String,
        userMessage: String,
        onStatus: (AgentStatus) -> Unit = {},
        onEvent: (AgentEvent) -> Unit
    ) {
        currentSessionId = sessionId
        val loaded = loadSession(sessionId)
        val meta = loaded ?: ChatSession(id = sessionId, title = "New Session")
        // Derive a human-readable title from the first user message (keeps createdAt intact).
        currentSessionMeta = if (
            userMessage.isNotBlank() &&
            (meta.title.isBlank() || meta.title == "New Session" || meta.title == "Session")
        ) {
            meta.copy(title = deriveTitle(userMessage))
        } else {
            meta
        }
        currentMessages = meta.messages.toMutableList()
        currentMessages.add(ChatMessage(role = "user", content = userMessage))

        val config = currentConfig
        val toolRegistry = AndroidToolRegistry(ToolContext(context, context.filesDir.absolutePath))
        val client = InferenceClient(config)
        val tools = toolRegistry.getToolDefinitions()

        var rounds = 0
        val maxRounds = 10
        var contentBuilder = StringBuilder()

        while (rounds < maxRounds) {
            val stream = client.streamChat(currentMessages, tools, onPhase = onStatus)

            // Tool calls arrive as fragmented deltas (id+name first, then argument tails,
            // possibly with blank ids). Accumulate them per call, in arrival order, so the UI
            // and the persisted assistant message both see complete, valid JSON arguments.
            val toolCalls = LinkedHashMap<String, ToolCall>()
            var lastToolId: String? = null
            var autoIdCounter = 0

            contentBuilder = StringBuilder()
            stream.collect { chunk ->
                chunk.choices.firstOrNull()?.delta?.let { delta ->
                    delta.content?.let {
                        contentBuilder.append(it)
                        onEvent(AgentEvent.ContentDelta(it))
                    }
                    delta.toolCalls?.forEach { fragment ->
                        val explicitId = fragment.id
                        val key = if (explicitId.isNotBlank()) {
                            lastToolId = explicitId
                            explicitId
                        } else {
                            lastToolId
                                ?: "call_auto_${autoIdCounter++}".also { generated -> lastToolId = generated }
                        }
                        val merged = mergeToolCall(toolCalls[key], fragment.copy(id = key))
                        toolCalls[key] = merged
                        onEvent(AgentEvent.ToolCallStart(merged))
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
                saveCurrentSession()
                return
            }

            val assistantMsg = ChatMessage(
                role = "assistant",
                content = contentBuilder.toString(),
                toolCalls = toolCalls.values.toList()
            )
            currentMessages.add(assistantMsg)
            onEvent(AgentEvent.MessageComplete(assistantMsg))

            for (toolCall in toolCalls.values) {
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
        saveCurrentSession()
    }

    /**
     * Merges a streamed tool-call fragment into the accumulated call.
     * Handles both delta-style servers (argument tails) and cumulative servers (full args each time).
     */
    private fun mergeToolCall(existing: ToolCall?, incoming: ToolCall): ToolCall {
        if (existing == null) return incoming
        val existingArgs = existing.function.arguments
        val incomingArgs = incoming.function.arguments
        val arguments = when {
            incomingArgs.isEmpty() -> existingArgs
            existingArgs.isEmpty() -> incomingArgs
            incomingArgs == existingArgs -> existingArgs
            incomingArgs.startsWith(existingArgs) -> incomingArgs // cumulative server
            else -> existingArgs + incomingArgs                   // delta fragments
        }
        return existing.copy(
            id = incoming.id.ifBlank { existing.id },
            function = existing.function.copy(
                name = incoming.function.name.ifBlank { existing.function.name },
                arguments = arguments
            )
        )
    }

    private fun deriveTitle(userMessage: String): String {
        val singleLine = userMessage.trim().replace(Regex("\\s+"), " ")
        return if (singleLine.length <= 40) singleLine else singleLine.take(39) + "…"
    }

    /** Persists the current turn without clobbering the session's title/createdAt. */
    private fun saveCurrentSession() {
        val meta = currentSessionMeta
            ?: ChatSession(id = currentSessionId, title = "Session")
        saveSession(
            meta.copy(
                messages = currentMessages.toList(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun updateConfig(config: InferenceConfig) {
        currentConfig = config
    }

    fun getCurrentMessages(): List<ChatMessage> = currentMessages.toList()
}
