package com.sutliff.pocket.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.sutliff.pocket.agent.AgentEvent
import com.sutliff.pocket.agent.AgentSessionManager
import com.sutliff.pocket.data.ChatMessage
import com.sutliff.pocket.data.InferenceConfig
import com.sutliff.pocket.datastore.saveInferenceConfig
import kotlinx.coroutines.launch

data class ToolCallState(
    val toolCallId: String,
    val name: String,
    val arguments: String,
    var status: ToolStatus = ToolStatus.Running,
    var result: String = "",
    var durationMs: Long = 0
)

enum class ToolStatus { Running, Completed, Failed }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onNavigateToSettings: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(InferenceConfig()) }
    var currentSessionId by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var inputText by remember { mutableStateOf("") }
    var isStreaming by remember { mutableStateOf(false) }
    var streamingText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showSessionDrawer by remember { mutableStateOf(false) }
    var toolCallStates by remember { mutableStateOf<Map<String, ToolCallState>>(emptyMap()) }

    val listState = rememberLazyListState()
    val sessionManager = remember { AgentSessionManager(context) }

    LaunchedEffect(Unit) {
        scope.launch {
            config = com.sutliff.pocket.datastore.readConfig(context)
            sessionManager.updateConfig(config)
            val session = sessionManager.createSession()
            currentSessionId = session.id
        }
    }

    LaunchedEffect(messages.size, streamingText, toolCallStates.size) {
        if (messages.isNotEmpty() || streamingText.isNotEmpty() || toolCallStates.isNotEmpty()) {
            listState.animateScrollToItem(maxOf(0, messages.size + toolCallStates.size - 1))
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || isStreaming) return
        val userMsg = ChatMessage(role = "user", content = text)
        messages = messages + userMsg
        inputText = ""
        isStreaming = true
        streamingText = ""
        errorMessage = null

        scope.launch {
            try {
                sessionManager.run(currentSessionId, text) { event ->
                    when (event) {
                        is AgentEvent.ContentDelta -> {
                            streamingText += event.text
                        }
                        is AgentEvent.ToolCallStart -> {
                            val state = ToolCallState(
                                toolCallId = event.toolCall.id,
                                name = event.toolCall.function.name,
                                arguments = event.toolCall.function.arguments
                            )
                            toolCallStates = toolCallStates + (event.toolCall.id to state)
                        }
                        is AgentEvent.ToolCallResult -> {
                            val existing = toolCallStates[event.toolCallId]
                            if (existing != null) {
                                toolCallStates = toolCallStates + (event.toolCallId to existing.copy(
                                    status = ToolStatus.Completed,
                                    result = event.result
                                ))
                            }
                        }
                        is AgentEvent.MessageComplete -> {
                            if (event.message.content.isNotEmpty() || event.message.toolCalls != null) {
                                messages = messages + event.message
                            }
                            streamingText = ""
                        }
                        is AgentEvent.Error -> {
                            errorMessage = event.message
                        }
                    }
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "Unknown error"
            } finally {
                isStreaming = false
                streamingText = ""
            }
        }
    }

    fun newSession() {
        scope.launch {
            val session = sessionManager.createSession()
            currentSessionId = session.id
            messages = emptyList()
            streamingText = ""
            errorMessage = null
            toolCallStates = emptyMap()
        }
    }

    fun loadSession(sessionId: String) {
        scope.launch {
            val session = sessionManager.loadSession(sessionId)
            if (session != null) {
                currentSessionId = session.id
                messages = session.messages
                showSessionDrawer = false
                toolCallStates = emptyMap()
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = rememberDrawerState(if (showSessionDrawer) DrawerValue.Open else DrawerValue.Closed),
        drawerContent = {
            ModalDrawerSheet {
                SessionDrawerContent(
                    sessions = sessionManager.listSessions(),
                    currentSessionId = currentSessionId,
                    onSelect = { loadSession(it.id) },
                    onDelete = { sessionManager.deleteSession(it.id) },
                    onClose = { showSessionDrawer = false }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Pocket") },
                    navigationIcon = {
                        IconButton(onClick = { showSessionDrawer = true }) {
                            Icon(Icons.Default.Menu, contentDescription = "Sessions")
                        }
                    },
                    actions = {
                        IconButton(onClick = { newSession() }) {
                            Icon(Icons.Default.Add, contentDescription = "New chat")
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                errorMessage?.let { err ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(messages) { msg ->
                        MessageBubble(msg)
                    }

                    // Show tool calls with state
                    toolCallStates.values.forEach { state ->
                        item {
                            ToolCallBubble(state)
                        }
                    }

                    if (streamingText.isNotEmpty() || isStreaming) {
                        item {
                            MessageBubble(
                                ChatMessage(role = "assistant", content = streamingText),
                                isStreaming = true
                            )
                        }
                    }
                }

                Surface(
                    tonalElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Message...") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { sendMessage(inputText) }),
                            maxLines = 4,
                            enabled = !isStreaming
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledIconButton(
                            onClick = { sendMessage(inputText) },
                            enabled = !isStreaming && inputText.isNotBlank()
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage, isStreaming: Boolean = false) {
    val isUser = message.role == "user"
    val isSystem = message.role == "system"
    val isTool = message.role == "tool"

    val containerColor = when {
        isUser -> MaterialTheme.colorScheme.primary
        isSystem -> MaterialTheme.colorScheme.surfaceVariant
        isTool -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }

    val contentColor = when {
        isUser -> MaterialTheme.colorScheme.onPrimary
        isSystem -> MaterialTheme.colorScheme.onSurfaceVariant
        isTool -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = containerColor,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (isTool) {
                    Text(
                        text = "tool result",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Text(
                    text = message.content.ifEmpty { if (isStreaming) "..." else "" },
                    color = contentColor,
                    style = MaterialTheme.typography.bodyMedium
                )
                message.toolCalls?.forEach { tc ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        color = contentColor.copy(alpha = 0.1f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "→ ${tc.function.name}(${tc.function.arguments.take(80)})",
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor,
                            modifier = Modifier.padding(6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ToolCallBubble(state: ToolCallState) {
    val containerColor = when (state.status) {
        ToolStatus.Running -> MaterialTheme.colorScheme.surfaceVariant
        ToolStatus.Completed -> MaterialTheme.colorScheme.secondaryContainer
        ToolStatus.Failed -> MaterialTheme.colorScheme.errorContainer
    }

    val contentColor = when (state.status) {
        ToolStatus.Running -> MaterialTheme.colorScheme.onSurfaceVariant
        ToolStatus.Completed -> MaterialTheme.colorScheme.onSecondaryContainer
        ToolStatus.Failed -> MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        color = containerColor,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.status == ToolStatus.Running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = state.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor
                )
            }
            if (state.arguments.isNotBlank()) {
                Text(
                    text = state.arguments.take(200),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            AnimatedVisibility(visible = state.status == ToolStatus.Completed && state.result.isNotBlank()) {
                Text(
                    text = state.result.take(500),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
fun SessionDrawerContent(
    sessions: List<com.sutliff.pocket.data.ChatSession>,
    currentSessionId: String,
    onSelect: (com.sutliff.pocket.data.ChatSession) -> Unit,
    onDelete: (com.sutliff.pocket.data.ChatSession) -> Unit,
    onClose: () -> Unit
) {
    Column(modifier = Modifier.fillMaxHeight()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Sessions", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }
        HorizontalDivider()
        LazyColumn {
            items(sessions) { session ->
                ListItem(
                    headlineContent = { Text(session.title) },
                    supportingContent = {
                        Text(
                            text = session.id.take(20),
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { onDelete(session) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}