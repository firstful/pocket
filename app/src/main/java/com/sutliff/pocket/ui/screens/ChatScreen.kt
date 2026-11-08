package com.sutliff.pocket.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import android.widget.Toast
import androidx.compose.material3.*
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sutliff.pocket.agent.AgentEvent
import com.sutliff.pocket.agent.AgentSessionManager
import com.sutliff.pocket.appContext
import com.sutliff.pocket.data.*
import com.sutliff.pocket.datastore.getCurrentSessionId
import com.sutliff.pocket.datastore.readConfig
import com.sutliff.pocket.datastore.setCurrentSessionId
import com.sutliff.pocket.network.NetworkMonitor
import com.sutliff.pocket.network.isNetworkAvailable
import com.sutliff.pocket.telemetry.Telemetry
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ToolCallState(
    val toolCallId: String,
    val name: String,
    val arguments: String,
    val status: ToolStatus = ToolStatus.Running,
    val result: String = "",
    val durationMs: Long = 0,
    val startedAt: Long = System.currentTimeMillis()
)

enum class ToolStatus { Running, Completed, Failed }

/** One row of the chat transcript: a chat message or an in-flight/finished tool call, in arrival order. */
sealed class TimelineEntry {
    data class Msg(
        val message: ChatMessage,
        val queued: Boolean = false,
        val queuedAt: Long = 0L
    ) : TimelineEntry()

    data class Tool(val toolCallId: String) : TimelineEntry()
}

private fun statusText(status: AgentStatus): String = when (status) {
    AgentStatus.IDLE -> "Ready"
    AgentStatus.WAITING_FOR_NETWORK -> "Offline — will send when back online"
    AgentStatus.SENDING -> "Sending…"
    AgentStatus.WAITING -> "Waiting for response…"
    AgentStatus.STREAMING -> "Receiving…"
}

private fun formatSessionTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val age = now - timestamp
    return when {
        timestamp <= 0L -> ""
        age < 60_000L -> "just now"
        age < 86_400_000L && isSameDay(timestamp, now) ->
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        age < 7L * 86_400_000L -> "${age / 86_400_000L}d ago"
        else -> SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}

private fun isSameDay(a: Long, b: Long): Boolean {
    val fmt = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    return fmt.format(Date(a)) == fmt.format(Date(b))
}

private fun formatDuration(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000L -> String.format(Locale.US, "%.1fs", ms / 1000.0)
    else -> "${ms / 60_000L}m ${(ms % 60_000L) / 1000L}s"
}

private fun prettyPrintJson(raw: String): String {
    if (raw.isBlank()) return ""
    return try {
        val element = Json.parseToJsonElement(raw)
        Json { prettyPrint = true }.encodeToJsonElement(element).toString()
    } catch (e: Exception) {
        raw
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onNavigateToSettings: () -> Unit) {
    val context = appContext()
    val scope = rememberCoroutineScope()

    // ── state ────────────────────────────────────────────────────────────────
    var config by remember { mutableStateOf(InferenceConfig()) }
    var currentSessionId by remember { mutableStateOf("") }
    var timeline by remember { mutableStateOf<List<TimelineEntry>>(emptyList()) }
    var inputText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(AgentStatus.IDLE) }
    var streamingText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showSessionDrawer by remember { mutableStateOf(false) }
    var showShortcuts by remember { mutableStateOf(false) }
    var lastUserMessage by remember { mutableStateOf("") }
    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var editingText by remember { mutableStateOf("") }
    var toolCallStates by remember { mutableStateOf<Map<String, ToolCallState>>(emptyMap()) }
    var sessions by remember { mutableStateOf<List<ChatSession>>(emptyList()) }
    var queuedMessages by remember { mutableStateOf<List<QueuedMessage>>(emptyList()) }
    var isOnline by remember(context) { mutableStateOf(isNetworkAvailable(context)) }
    var isReady by remember { mutableStateOf(false) }
    var isFlushing by remember { mutableStateOf(false) }
    var flushJob by remember { mutableStateOf<Job?>(null) }
    var streamJob by remember { mutableStateOf<Job?>(null) }

    val listState = rememberLazyListState()
    val sessionManager = remember { AgentSessionManager(context) }
    val outbox = remember { OutboxStore(context) }
    val inputFocus = remember { FocusRequester() }

    val isBusy =
        status == AgentStatus.SENDING || status == AgentStatus.WAITING || status == AgentStatus.STREAMING
    // Offline + idle still deserves a visible status: WAITING_FOR_NETWORK.
    val displayStatus =
        if (status == AgentStatus.IDLE && !isOnline) AgentStatus.WAITING_FOR_NETWORK else status

    // ── helpers (declared before first use) ──────────────────────────────────
    fun refreshSessions() {
        sessions = sessionManager.listSessions()
    }

    /** Rebuilds the transcript + tool states from a persisted session (tool results are folded into their tool bubble). */
    fun applySession(session: ChatSession) {
        val entries = mutableListOf<TimelineEntry>()
        val states = mutableMapOf<String, ToolCallState>()
        for (msg in session.messages) {
            val resultForId = if (msg.role == "tool") msg.toolCallId else null
            if (resultForId != null) {
                val existing = states[resultForId]
                if (existing != null) {
                    states[resultForId] = existing.copy(
                        status = if (msg.content.startsWith("Error")) ToolStatus.Failed else ToolStatus.Completed,
                        result = msg.content
                    )
                    continue
                }
                // Orphan tool result (no matching call in this session) — show it as a plain bubble.
                entries += TimelineEntry.Msg(msg)
                continue
            }
            entries += TimelineEntry.Msg(msg)
            msg.toolCalls?.forEach { tc ->
                if (!states.containsKey(tc.id)) {
                    entries += TimelineEntry.Tool(tc.id)
                    states[tc.id] = ToolCallState(
                        toolCallId = tc.id,
                        name = tc.function.name,
                        arguments = tc.function.arguments,
                        status = ToolStatus.Running
                    )
                }
            }
        }
        // Calls still "running" at load time were interrupted — surface them as failed,
        // never as a spinner that spins forever.
        states.keys.toList().forEach { id ->
            val st = states[id] ?: return@forEach
            if (st.status == ToolStatus.Running) {
                states[id] = st.copy(
                    status = ToolStatus.Failed,
                    result = st.result.ifEmpty { "Interrupted — no result recorded" }
                )
            }
        }
        timeline = entries
        toolCallStates = states
    }

    /** Re-syncs queued (not-yet-sent) messages for a session into the visible transcript tail. */
    fun refreshQueued(sessionId: String) {
        queuedMessages = outbox.entriesFor(sessionId)
        val queuedEntries = queuedMessages.map { qm ->
            TimelineEntry.Msg(
                message = ChatMessage(role = "user", content = qm.text),
                queued = true,
                queuedAt = qm.timestamp
            )
        }
        timeline = timeline.filterNot { it is TimelineEntry.Msg && it.queued } + queuedEntries
    }

    fun updateQueuedFlag(origin: QueuedMessage, queued: Boolean) {
        val index = timeline.indexOfFirst { entry ->
            entry is TimelineEntry.Msg &&
                entry.queuedAt == origin.timestamp &&
                entry.message.content == origin.text
        }
        if (index >= 0) {
            val entry = timeline[index] as TimelineEntry.Msg
            val updated = timeline.toMutableList()
            updated[index] = entry.copy(queued = queued)
            timeline = updated
        }
    }

    /**
     * Executes one turn against the gateway. Shared by direct sends and offline-queue flushes.
     * Returns true when the turn completed and was persisted. Never throws.
     */
    val performSend: suspend (String, QueuedMessage?) -> Boolean = handler@{ text, origin ->
        val sid = currentSessionId
        if (sid.isEmpty()) {
            errorMessage = "No active session — message not sent"
            return@handler false
        }
        status = AgentStatus.SENDING
        streamingText = ""
        errorMessage = null
        if (origin != null) updateQueuedFlag(origin, queued = false)
        var success = false
        try {
            sessionManager.run(
                sessionId = sid,
                userMessage = text,
                onStatus = { newStatus ->
                    if (sid == currentSessionId) status = newStatus
                },
                onEvent = { event ->
                    // Drop events once the user switched sessions mid-run; the run still
                    // persists into its own session file on disk.
                    if (sid == currentSessionId) {
                        when (event) {
                            is AgentEvent.ContentDelta -> {
                                streamingText += event.text
                                status = AgentStatus.STREAMING
                            }
                            is AgentEvent.ToolCallStart -> {
                                val tc = event.toolCall
                                val existing = toolCallStates[tc.id]
                                val updatedState = if (existing == null) {
                                    ToolCallState(
                                        toolCallId = tc.id,
                                        name = tc.function.name,
                                        arguments = tc.function.arguments
                                    )
                                } else {
                                    existing.copy(
                                        name = tc.function.name.ifBlank { existing.name },
                                        arguments = tc.function.arguments
                                    )
                                }
                                toolCallStates = toolCallStates + (tc.id to updatedState)
                                if (timeline.none { it is TimelineEntry.Tool && it.toolCallId == tc.id }) {
                                    timeline = timeline + TimelineEntry.Tool(tc.id)
                                }
                            }
                            is AgentEvent.ToolCallResult -> {
                                val existing = toolCallStates[event.toolCallId]
                                if (existing != null) {
                                    toolCallStates = toolCallStates + (event.toolCallId to existing.copy(
                                        status = if (event.result.startsWith("Error")) {
                                            ToolStatus.Failed
                                        } else {
                                            ToolStatus.Completed
                                        },
                                        result = event.result,
                                        durationMs = System.currentTimeMillis() - existing.startedAt
                                    ))
                                }
                            }
                            is AgentEvent.MessageComplete -> {
                                if (event.message.content.isNotEmpty() || event.message.toolCalls != null) {
                                    timeline = timeline + TimelineEntry.Msg(event.message)
                                }
                                streamingText = ""
                            }
                            is AgentEvent.Error -> {
                                errorMessage = event.message
                            }
                        }
                    }
                }
            )
            success = true
            Telemetry.log(
                context, "message_sent",
                mapOf("source" to (if (origin == null) "direct" else "queued"))
            )
        } catch (e: Exception) {
            if (sid == currentSessionId) {
                errorMessage = e.message ?: "Unknown error"
            }
            Telemetry.logError(context, "ChatScreen_send", e)
        } finally {
            if (origin != null) {
                if (success) {
                    outbox.remove(origin)
                    queuedMessages = queuedMessages.filterNot {
                        it.timestamp == origin.timestamp && it.sessionId == origin.sessionId
                    }
                } else {
                    // Keep it queued — never silently drop user input.
                    updateQueuedFlag(origin, queued = true)
                }
            }
            status = AgentStatus.IDLE
            streamingText = ""
        }
        success
    }

    /** Sends every queued message for the current session, in order, while online. */
    fun flushQueue() {
        if (!isReady || !isOnline) return
        if (currentSessionId.isEmpty() || queuedMessages.isEmpty()) return
        if (flushJob?.isActive == true) return
        val sid = currentSessionId
        flushJob = scope.launch {
            isFlushing = true
            try {
                val seen = mutableSetOf<Long>()
                var pending = outbox.entriesFor(sid)
                while (pending.isNotEmpty() && isOnline && currentSessionId == sid) {
                    val qm = pending.first()
                    if (!seen.add(qm.timestamp)) break // guarantee progress
                    val ok = performSend(qm.text, qm)
                    if (!ok) break
                    pending = outbox.entriesFor(sid)
                }
            } finally {
                isFlushing = false
            }
        }
        streamJob = flushJob
    }

    /** Cancels the active streaming turn (Stop button). Safe to call when idle. */
    fun stopStreaming() {
        val job = streamJob ?: flushJob
        if (job?.isActive == true) {
            job.cancel()
            Telemetry.log(context, "chat_stop")
            streamingText = ""
            status = AgentStatus.IDLE
        }
        streamJob = null
    }

    fun regenerate() {
        if (lastUserMessage.isBlank() || isBusy || isFlushing || !isReady) return
        Telemetry.log(context, "chat_regenerate")
        // Remove the last assistant message from timeline for visual feedback
        val lastAssistantIdx = timeline.indexOfLast { it is TimelineEntry.Msg && (it as TimelineEntry.Msg).message.role == "assistant" }
        if (lastAssistantIdx >= 0) {
            timeline = timeline.toMutableList().apply { removeAt(lastAssistantIdx) }
        }
        streamJob = scope.launch { performSend(lastUserMessage, null) }
    }

    fun exportSession() {
        scope.launch {
            val session = sessionManager.loadSession(currentSessionId) ?: return@launch
            val md = buildString {
                appendLine("# ${session.title}")
                appendLine()
                for (msg in session.messages) {
                    when (msg.role) {
                        "user" -> { appendLine("**You:** ${msg.content}"); appendLine() }
                        "assistant" -> { appendLine("**Assistant:** ${msg.content}"); appendLine() }
                        "tool" -> { appendLine("> Tool result: ${msg.content.take(200)}"); appendLine() }
                        "system" -> { appendLine("_System: ${msg.content}_"); appendLine() }
                    }
                }
            }
            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("session", md))
            Toast.makeText(context, "Session exported to clipboard", Toast.LENGTH_SHORT).show()
            Telemetry.log(context, "session_export")
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || isBusy || isFlushing || !isReady) return
        Telemetry.log(context, "chat_send")
        lastUserMessage = text
        inputText = ""
        val mustQueue = !isOnline || queuedMessages.isNotEmpty() || currentSessionId.isEmpty()
        if (mustQueue && currentSessionId.isNotEmpty()) {
            val qm = QueuedMessage(
                sessionId = currentSessionId,
                text = text,
                timestamp = System.currentTimeMillis()
            )
            outbox.add(qm)
            queuedMessages = queuedMessages + qm
            timeline = timeline + TimelineEntry.Msg(
                message = ChatMessage(role = "user", content = text),
                queued = true,
                queuedAt = qm.timestamp
            )
            Telemetry.log(
                context, "message_queued",
                mapOf("reason" to (if (!isOnline) "offline" else "backlog"))
            )
            if (isOnline) flushQueue()
            return
        }
        timeline = timeline + TimelineEntry.Msg(ChatMessage(role = "user", content = text))
        streamJob = scope.launch { performSend(text, null) }
    }

    fun editMessage(original: ChatMessage, newText: String) {
        if (newText.isBlank()) return
        scope.launch {
            // Replace the message content in the timeline
            val idx = timeline.indexOfFirst { it is TimelineEntry.Msg && (it as TimelineEntry.Msg).message === original }
            if (idx >= 0) {
                val entry = timeline[idx] as TimelineEntry.Msg
                timeline = timeline.toMutableList().apply {
                    set(idx, entry.copy(message = entry.message.copy(content = newText)))
                }
            }
            // Persist to session
            try {
                val session = sessionManager.loadSession(currentSessionId)
                if (session != null) {
                    val msgIdx = session.messages.indexOfFirst { it === original }
                    if (msgIdx >= 0) {
                        // Update message in session by recreating it
                        val updated = session.copy(messages = session.messages.toMutableList().apply {
                            set(msgIdx, session.messages[msgIdx].copy(content = newText))
                        })
                        sessionManager.saveSession(updated)
                    }
                }
            } catch (e: Exception) { /* ignore */ }
            Telemetry.log(context, "message_edit")
            // Re-send to agent
            inputText = newText
            lastUserMessage = newText
            streamJob = scope.launch { performSend(newText, null) }
        }
    }

    fun newSession() {
        scope.launch {
            val session = sessionManager.createSession()
            currentSessionId = session.id
            setCurrentSessionId(context, session.id)
            timeline = emptyList()
            toolCallStates = emptyMap()
            streamingText = ""
            errorMessage = null
            refreshQueued(session.id)
            refreshSessions()
            showSessionDrawer = false
            Telemetry.log(context, "session_new")
        }
    }

    fun loadSession(sessionId: String) {
        if (sessionId.isBlank()) return
        showSessionDrawer = false
        if (sessionId == currentSessionId) return
        scope.launch {
            val session = sessionManager.loadSession(sessionId)
            if (session == null) {
                errorMessage = "Session not found"
                refreshSessions()
                return@launch
            }
            currentSessionId = session.id
            setCurrentSessionId(context, session.id)
            applySession(session)
            refreshQueued(session.id)
            streamingText = ""
            errorMessage = null
            refreshSessions()
            Telemetry.log(context, "session_switch")
            if (isOnline && queuedMessages.isNotEmpty()) flushQueue()
        }
    }

    fun switchSession(direction: Int) {
        val idx = sessions.indexOfFirst { it.id == currentSessionId }
        if (idx < 0 || sessions.isEmpty()) return
        val newIdx = (idx + direction).coerceIn(0, sessions.lastIndex)
        if (newIdx != idx) loadSession(sessions[newIdx].id)
    }

    fun deleteSession(session: ChatSession) {
        val sessionId = session.id
        // Never delete the session that a live run is writing to.
        if (sessionId == currentSessionId && (isBusy || isFlushing)) return
        sessionManager.deleteSession(sessionId)
        outbox.removeForSession(sessionId)
        queuedMessages = queuedMessages.filterNot { it.sessionId == sessionId }
        refreshSessions()
        Telemetry.log(context, "session_delete")
        if (sessionId == currentSessionId) {
            val next = sessions.firstOrNull()
            if (next != null) {
                loadSession(next.id)
            } else {
                newSession()
            }
        }
    }

    // ── async init: config + resume last session (no runBlocking in composition) ──
    LaunchedEffect(Unit) {
        try {
            val loadedConfig = readConfig(context)
            config = loadedConfig
            sessionManager.updateConfig(loadedConfig)
        } catch (e: Exception) {
            Telemetry.logError(context, "ChatScreen_config", e)
        }
        try {
            isOnline = isNetworkAvailable(context)
            val savedId = getCurrentSessionId(context)
            val saved = savedId?.takeIf { it.isNotBlank() }?.let { sessionManager.loadSession(it) }
            val session = saved
                ?: sessionManager.listSessions().maxByOrNull { it.updatedAt }
                ?: sessionManager.createSession()
            currentSessionId = session.id
            setCurrentSessionId(context, session.id)
            applySession(session)
            refreshQueued(session.id)
            // Auto-archive sessions older than 7 days
            val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
            for (s in sessionManager.listSessions() + sessionManager.listArchivedSessions()) {
                if (s.updatedAt < cutoff && !s.archived) sessionManager.archiveSession(s.id)
            }
            refreshSessions()
            isReady = true
            Telemetry.log(
                context, "session_resume",
                mapOf(
                    "restored" to (saved != null).toString(),
                    "messages" to session.messages.size.toString()
                )
            )
            if (isOnline && queuedMessages.isNotEmpty()) flushQueue()
        } catch (e: Exception) {
            Telemetry.logError(context, "ChatScreen_init", e)
            errorMessage = e.message ?: "Failed to load session"
            try {
                val emergency = sessionManager.createSession()
                currentSessionId = emergency.id
            } catch (ignored: Exception) {
            }
            isReady = true
        }
    }

    // ── connectivity: flip online state + auto-flush the queue when back ─────
    DisposableEffect(context) {
        val monitor = NetworkMonitor(
            context = context,
            onNetworkAvailable = {
                scope.launch {
                    isOnline = true
                    Telemetry.log(context, "network_available")
                    flushQueue()
                }
            },
            onNetworkLost = {
                scope.launch { isOnline = false }
            }
        )
        monitor.register()
        onDispose { monitor.unregister() }
    }

    // ── auto-focus input when ready ──────────────────────────────────────────
    LaunchedEffect(isReady) {
        if (isReady) {
            try { inputFocus.requestFocus() } catch (e: Exception) { /* ignore */ }
        }
    }

    // ── near-bottom tracking for auto-scroll / jump-to-current ───────────────
    val isNearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            total == 0 || lastVisible >= total - 2
        }
    }

    // ── autoscroll to the newest entry (only when already near bottom) ───────
    LaunchedEffect(timeline.size, streamingText, status, isNearBottom) {
        val count = timeline.size +
            if (status != AgentStatus.IDLE || streamingText.isNotEmpty()) 1 else 0
        if (count > 0 && isNearBottom) {
            listState.animateScrollToItem(count - 1)
        }
    }

    ModalNavigationDrawer(
        drawerState = rememberDrawerState(if (showSessionDrawer) DrawerValue.Open else DrawerValue.Closed),
        drawerContent = {
            ModalDrawerSheet {
                SessionDrawerContent(
                    sessions = sessions,
                    currentSessionId = currentSessionId,
                    onSelect = { loadSession(it.id) },
                    onDelete = { deleteSession(it) },
                    onClose = { showSessionDrawer = false }
                )
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (event.isCtrlPressed) {
                    when (event.key) {
                        Key.N -> { newSession(); true }
                        Key.Comma -> { onNavigateToSettings(); true }
                        Key.K -> { inputFocus.requestFocus(); true }
                        Key.DirectionLeft -> { switchSession(-1); true }
                        Key.DirectionRight -> { switchSession(1); true }
                        Key.Slash -> { if (event.isCtrlPressed) { showShortcuts = true; true } else false }
                        else -> false
                    }
                } else {
                    when (event.key) {
                        Key.Escape -> {
                            if (showSessionDrawer) { showSessionDrawer = false; true } else false
                        }
                        else -> false
                    }
                }
            },
            topBar = {
                TopAppBar(
                    title = { Text("Pocket") },
                    navigationIcon = {
                        IconButton(onClick = { showSessionDrawer = true }) {
                            Icon(Icons.Default.Menu, contentDescription = "Sessions")
                        }
                    },
                    actions = {
                        // Connection status indicator
                        Icon(
                            imageVector = Icons.Default.Circle,
                            contentDescription = if (isOnline) "Online" else "Offline",
                            tint = if (isOnline) Color(0xFF4CAF50) else Color(0xFFF44336),
                            modifier = Modifier.size(10.dp).padding(end = 4.dp)
                        )
                        IconButton(onClick = { newSession() }) {
                            Icon(Icons.Default.Add, contentDescription = "New chat")
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                        IconButton(onClick = { showShortcuts = true }) {
                            Icon(Icons.Default.Info, contentDescription = "Keyboard shortcuts")
                        }
                        IconButton(onClick = { regenerate() }, enabled = lastUserMessage.isNotBlank() && !isBusy) {
                            Icon(Icons.Default.Refresh, contentDescription = "Regenerate last response")
                        }
                        IconButton(onClick = { exportSession() }) {
                            Icon(Icons.Default.Share, contentDescription = "Export session")
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = err,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (lastUserMessage.isNotBlank()) {
                                TextButton(onClick = { regenerate() }) {
                                    Text("Retry", color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(timeline.size) { index ->
                            when (val entry = timeline[index]) {
                                is TimelineEntry.Msg -> MessageBubble(
                                    message = entry.message,
                                    queued = entry.queued,
                                    queuedAt = entry.queuedAt,
                                    onEdit = if (entry.message.role == "user") { { _ -> inputText = entry.message.content; Toast.makeText(context, "Edit and resend", Toast.LENGTH_SHORT).show() } } else null
                                )
                                is TimelineEntry.Tool -> {
                                    val toolState = toolCallStates[entry.toolCallId]
                                    if (toolState != null) {
                                        ToolCallBubble(toolState)
                                    }
                                }
                            }
                        }

                        if (status != AgentStatus.IDLE || streamingText.isNotEmpty()) {
                            item {
                                MessageBubble(
                                    message = ChatMessage(role = "assistant", content = streamingText),
                                    isStreaming = true,
                                    statusLabel = statusText(status)
                                )
                            }
                        }
                    }

                    if (!isNearBottom) {
                        FloatingActionButton(
                            onClick = {
                                scope.launch {
                                    val count = timeline.size +
                                        if (status != AgentStatus.IDLE || streamingText.isNotEmpty()) 1 else 0
                                    if (count > 0) listState.animateScrollToItem(count - 1)
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp)
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Jump to current")
                        }
                    }
                }

                Surface(
                    tonalElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        StatusLine(
                            status = displayStatus,
                            queuedCount = queuedMessages.size,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isBusy || isFlushing) {
                                FilledTonalButton(
                                    onClick = { stopStreaming() },
                                    modifier = Modifier.padding(end = 8.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Stop")
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Stop")
                                }
                            }
                            OutlinedTextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(inputFocus)
                                    .onKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                        if (event.key == Key.Enter) {
                                            if (event.isShiftPressed) {
                                                // Shift+Enter: insert newline (default behavior)
                                                false
                                            } else {
                                                // Enter: send
                                                if (inputText.isNotBlank() && isReady && !isBusy && !isFlushing) {
                                                    sendMessage(inputText)
                                                }
                                                true
                                            }
                                        } else {
                                            false
                                        }
                                    },
                                placeholder = {
                                    Text(if (isReady) "Message…" else "Loading…")
                                },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { sendMessage(inputText) }),
                                maxLines = 4,
                                enabled = isReady && !isBusy && !isFlushing
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = {
                                    val recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(context)
                                    val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                        putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak…")
                                    }
                                    recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
                                        override fun onReadyForSpeech(params: android.os.Bundle?) {}
                                        override fun onBeginningOfSpeech() {}
                                        override fun onRmsChanged(rmsdB: Float) {}
                                        override fun onBufferReceived(buffer: ByteArray?) {}
                                        override fun onEndOfSpeech() {}
                                        override fun onError(error: Int) {
                                            Toast.makeText(context, "Speech error", Toast.LENGTH_SHORT).show()
                                            recognizer.destroy()
                                        }
                                        override fun onResults(results: android.os.Bundle?) {
                                            val matches = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                                            if (!matches.isNullOrEmpty()) {
                                                inputText = matches[0]
                                            }
                                            recognizer.destroy()
                                        }
                                        override fun onPartialResults(partialResults: android.os.Bundle?) {}
                                        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                                    })
                                    recognizer.startListening(intent)
                                },
                                enabled = isReady && !isBusy && !isFlushing
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = "Voice input")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            FilledIconButton(
                                onClick = {
                                    val vibrator = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                                    vibrator?.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                                    sendMessage(inputText)
                                },
                                enabled = isReady && !isBusy && !isFlushing && inputText.isNotBlank()
                            ) {
                                Icon(Icons.Default.Send, contentDescription = "Send")
                            }
                        }
                    }
                }
            }
        }
    }
    if (showShortcuts) {
        AlertDialog(
            onDismissRequest = { showShortcuts = false },
            title = { Text("Keyboard shortcuts") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Enter — send message")
                    Text("Shift+Enter — new line")
                    Text("Ctrl+N — new session")
                    Text("Ctrl+, — settings")
                    Text("Ctrl+K — focus input")
                    Text("Ctrl+Left/Right — swap sessions")
                    Text("Ctrl+/ — this help")
                    Text("Esc — close session drawer")
                }
            },
            confirmButton = {
                TextButton(onClick = { showShortcuts = false }) { Text("Close") }
            }
        )
    }
}

/** Status chip (+ optional queued counter and progress line) shown right above the input. */
@Composable
private fun StatusLine(
    status: AgentStatus,
    queuedCount: Int,
    modifier: Modifier = Modifier
) {
    val active =
        status == AgentStatus.SENDING || status == AgentStatus.WAITING || status == AgentStatus.STREAMING
    val offline = status == AgentStatus.WAITING_FOR_NETWORK

    val containerColor = when {
        active -> MaterialTheme.colorScheme.primaryContainer
        offline -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        active -> MaterialTheme.colorScheme.onPrimaryContainer
        offline -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = containerColor,
                shape = MaterialTheme.shapes.small
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (active) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = contentColor
                        )
                    } else {
                        Icon(
                            imageVector = if (offline) Icons.Default.Warning else Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = contentColor
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = statusText(status),
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor
                    )
                }
            }
            if (queuedCount > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "$queuedCount queued",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
        if (active) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
        }
    }
}

@Composable
fun MessageBubble(
    message: ChatMessage,
    isStreaming: Boolean = false,
    queued: Boolean = false,
    queuedAt: Long = 0L,
    statusLabel: String? = null,
    onEdit: ((String) -> Unit)? = null
) {
    val isUser = message.role == "user"
    val isSystem = message.role == "system"
    val isTool = message.role == "tool"
    val context = androidx.compose.ui.platform.LocalContext.current

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
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clickable {
                    if (message.content.isNotBlank()) {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("message", message.content))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }
                }
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (isStreaming && !statusLabel.isNullOrBlank()) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                if (queued) {
                    Surface(
                        color = contentColor.copy(alpha = 0.15f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = "Queued",
                                modifier = Modifier.size(12.dp),
                                tint = contentColor
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Queued · ${formatSessionTime(queuedAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
                if (isTool) {
                    Text(
                        text = "tool result",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                val displayContent = message.content.ifEmpty { if (isStreaming) "..." else "" }
                MarkdownText(
                    markdown = displayContent,
                    style = MaterialTheme.typography.bodyMedium.copy(color = contentColor),
                    linkColor = contentColor,
                    isTextSelectable = true,
                    maxLines = Int.MAX_VALUE,
                    disableLinkMovementMethod = false
                )
                if (!isStreaming && message.content.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = formatSessionTime(message.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.5f)
                        )
                        if (message.role == "user" && onEdit != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit message",
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable { onEdit(message.content) },
                                tint = contentColor.copy(alpha = 0.5f)
                            )
                        }
                        // Copy code blocks if present
                        val codeBlocks = remember(message.content) {
                            Regex("```[^\\n]*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(message.content).map { it.groupValues[1].trim() }.toList()
                        }
                        if (codeBlocks.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy code",
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable {
                                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        cm.setPrimaryClip(android.content.ClipData.newPlainText("code", codeBlocks.joinToString("\n\n")))
                                        Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                                    },
                                tint = contentColor.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ToolCallBubble(state: ToolCallState) {
    var argsExpanded by remember(state.toolCallId) { mutableStateOf(false) }
    var resultExpanded by remember(state.toolCallId) { mutableStateOf(false) }

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

    val prettyArgs = remember(state.arguments) { prettyPrintJson(state.arguments) }
    val statusLabel = when (state.status) {
        ToolStatus.Running -> "Running"
        ToolStatus.Completed -> "Done"
        ToolStatus.Failed -> "Failed"
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
                when (state.status) {
                    ToolStatus.Running -> CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    ToolStatus.Completed -> Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Completed",
                        modifier = Modifier.size(16.dp),
                        tint = contentColor
                    )
                    ToolStatus.Failed -> Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Failed",
                        modifier = Modifier.size(16.dp),
                        tint = contentColor
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = state.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.7f)
                )
                if (state.durationMs > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = formatDuration(state.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.7f)
                    )
                }
            }

            if (prettyArgs.isNotBlank()) {
                val argsTruncated = prettyArgs.length > 320 && !argsExpanded
                Text(
                    text = if (argsTruncated) prettyArgs.take(320) + "…" else prettyArgs,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = contentColor.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 6.dp)
                )
                if (prettyArgs.length > 320) {
                    Text(
                        text = if (argsExpanded) "Show less" else "Show more",
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable { argsExpanded = !argsExpanded }
                    )
                }
            }

            AnimatedVisibility(visible = state.result.isNotBlank()) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        text = "Result",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.6f)
                    )
                    val resultTruncated = state.result.length > 600 && !resultExpanded
                    Text(
                        text = if (resultTruncated) state.result.take(600) + "…" else state.result,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = contentColor,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    if (state.result.length > 600) {
                        Text(
                            text = if (resultExpanded) "Show less" else "Show more",
                            style = MaterialTheme.typography.labelMedium,
                            color = contentColor,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clickable { resultExpanded = !resultExpanded }
                        )
                    }
                }
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
    var searchQuery by remember { mutableStateOf("") }
    val filtered = remember(sessions, searchQuery) {
        if (searchQuery.isBlank()) sessions
        else sessions.filter { it.title.contains(searchQuery, ignoreCase = true) ||
            it.messages.any { m -> m.content.contains(searchQuery, ignoreCase = true) } }
    }
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
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search sessions…") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") }
        )
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        if (filtered.isEmpty()) {
            Text(
                text = if (searchQuery.isBlank()) "No sessions yet" else "No matching sessions",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
        LazyColumn {
            items(filtered, key = { it.id }) { session ->
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value == SwipeToDismissBoxValue.EndToStart) {
                            onDelete(session)
                            true
                        } else false
                    }
                )
                SwipeToDismissBox(
                    state = dismissState,
                    backgroundContent = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    },
                    content = {
                        val isCurrent = session.id == currentSessionId
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = session.title.ifBlank { "Untitled session" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (isCurrent) FontWeight.Bold else null
                                )
                            },
                            supportingContent = {
                                val messageCount = session.messages.size
                                Text(
                                    text = "${formatSessionTime(session.updatedAt)} · $messageCount messages",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            leadingContent = if (isCurrent) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Current session",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            } else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(session) }
                        )
                    }
                )
            }
        }
    }
}
