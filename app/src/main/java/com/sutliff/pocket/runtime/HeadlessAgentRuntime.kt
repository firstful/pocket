package com.sutliff.pocket.runtime

import com.sutliff.pocket.data.*
import com.sutliff.pocket.network.InferenceClient
import com.sutliff.pocket.tools.AndroidToolRegistry
import com.sutliff.pocket.tools.ToolContext
import com.sutliff.pocket.tools.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*

class HeadlessAgentRuntime(
    private val inferenceConfig: InferenceConfig,
    private val workingDir: String,
    private val appContext: android.content.Context,
    private val toolRegistry: ToolRegistry = AndroidToolRegistry(ToolContext(appContext, workingDir))
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run(
        sessionId: String,
        prompt: String,
        onComplete: (String) -> Unit
    ) {
        val messages = mutableListOf(
            ChatMessage(role = "system", content = inferenceConfig.systemPrompt),
            ChatMessage(role = "user", content = prompt)
        )

        val client = InferenceClient(inferenceConfig)
        val tools = toolRegistry.getToolDefinitions()

        var currentMessages = messages
        var rounds = 0
        var contentBuilder = StringBuilder()
        val maxRounds = 10

        while (rounds < maxRounds) {
            val stream = client.streamChat(currentMessages, tools)
            val toolCalls = mutableListOf<ToolCall>()

            contentBuilder = StringBuilder()
            stream.collect { chunk ->
                chunk.choices.firstOrNull()?.delta?.let { delta ->
                    delta.content?.let { contentBuilder.append(it) }
                    delta.toolCalls?.forEach { toolCalls.add(it) }
                }
            }

            if (toolCalls.isEmpty()) {
                onComplete(contentBuilder.toString())
                return
            }

            currentMessages = currentMessages.toMutableList().apply {
                add(ChatMessage(
                    role = "assistant",
                    content = contentBuilder.toString(),
                    toolCalls = toolCalls
                ))
            }

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
                currentMessages = currentMessages.toMutableList().apply {
                    add(ChatMessage(
                        role = "tool",
                        content = result,
                        toolCallId = toolCall.id
                    ))
                }
            }

            rounds++
        }

        onComplete(contentBuilder.toString())
    }
}
