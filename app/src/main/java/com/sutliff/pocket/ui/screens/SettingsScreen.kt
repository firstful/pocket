package com.sutliff.pocket.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sutliff.pocket.data.InferenceConfig
import com.sutliff.pocket.data.PermissionOptIns
import com.sutliff.pocket.data.RemoteAgent
import com.sutliff.pocket.data.TelemetryConfig
import com.sutliff.pocket.datastore.BuiltinHermes
import com.sutliff.pocket.datastore.getPermissionOptIns
import com.sutliff.pocket.datastore.getRemoteAgents
import com.sutliff.pocket.datastore.getTelemetryConfig
import com.sutliff.pocket.datastore.saveInferenceConfig
import com.sutliff.pocket.datastore.savePermissionOptIns
import com.sutliff.pocket.datastore.saveRemoteAgents
import com.sutliff.pocket.datastore.saveTelemetryConfig
import com.sutliff.pocket.permissions.NotificationOptIns
import com.sutliff.pocket.permissions.SmsPermissions
import com.sutliff.pocket.telemetry.Telemetry
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.sutliff.pocket.appContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateBack: () -> Unit) {
    val context = appContext()
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(InferenceConfig()) }
    var showApiKey by remember { mutableStateOf(false) }
    var smsGranted by remember(context) { mutableStateOf(SmsPermissions.isGranted(context)) }
    var smsNotice by remember { mutableStateOf<String?>(null) }

    var remoteAgents by remember { mutableStateOf<List<RemoteAgent>>(emptyList()) }
    var telemetryConfig by remember { mutableStateOf(TelemetryConfig()) }
    var optIns by remember { mutableStateOf(PermissionOptIns()) }
    var notifNotice by remember { mutableStateOf<String?>(null) }
    var notifListenerGranted by remember(context) { mutableStateOf(NotificationOptIns.isListenerEnabled(context)) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Grant state is re-checked on the next composition via optIns toggle.
        notifNotice = "Location permission result received — toggle Location opt-in above to also enable the agent tool."
    }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        smsGranted = SmsPermissions.isGranted(context)
        smsNotice = if (smsGranted) "SMS permissions granted." else "SMS is disabled — toggle again to retry."
        Telemetry.log(context, "sms_opt_in", mapOf("granted" to smsGranted.toString()))
    }

    // Initialize config eagerly at composition time (runBlocking is safe here, not in a suspend lambda)
    remember(context) {
        runBlocking {
            config = com.sutliff.pocket.datastore.readConfig(context)
            remoteAgents = getRemoteAgents(context)
            telemetryConfig = getTelemetryConfig(context)
            optIns = getPermissionOptIns(context)
        }
    }

    fun saveConfig() {
        scope.launch {
            saveInferenceConfig(context, config)
        }
    }

    fun saveOptIns(newOptIns: PermissionOptIns) {
        optIns = newOptIns
        scope.launch { savePermissionOptIns(context, newOptIns) }
    }

    Scaffold(
        modifier = Modifier.onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (event.key) {
                Key.Escape -> { onNavigateBack(); true }
                else -> {
                    if (event.isCtrlPressed && event.key == Key.S) { saveConfig(); onNavigateBack(); true } else false
                }
            }
        },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Inference Server
            Text("Inference Server", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = config.baseUrl,
                onValueChange = { config = config.copy(baseUrl = it); saveConfig() },
                label = { Text("Base URL") },
                placeholder = { Text("http://llm.homelab.internal/v1") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = config.apiKey,
                onValueChange = { config = config.copy(apiKey = it); saveConfig() },
                label = { Text("API Key") },
                visualTransformation = if (showApiKey) PasswordVisualTransformation() else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showApiKey) "Hide" else "Show"
                        )
                    }
                }
            )

            OutlinedTextField(
                value = config.model,
                onValueChange = { config = config.copy(model = it); saveConfig() },
                label = { Text("Model") },
                placeholder = { Text("auto") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Generation
            Text("Generation", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = config.temperature.toString(),
                onValueChange = {
                    it.toFloatOrNull()?.let { t ->
                        config = config.copy(temperature = t)
                        saveConfig()
                    }
                },
                label = { Text("Temperature") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = config.maxTokens.toString(),
                onValueChange = {
                    it.toIntOrNull()?.let { t ->
                        config = config.copy(maxTokens = t)
                        saveConfig()
                    }
                },
                label = { Text("Max Tokens") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // System Prompt
            Text("System Prompt", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = config.systemPrompt,
                onValueChange = { config = config.copy(systemPrompt = it); saveConfig() },
                label = { Text("System Prompt") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6
            )

            // Stream toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Stream responses")
                Switch(
                    checked = config.stream,
                    onCheckedChange = { config = config.copy(stream = it); saveConfig() }
                )
            }

            // Dark mode toggle
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Dark mode")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val labels = listOf("System", "Dark", "Light")
                    labels.forEachIndexed { idx, label ->
                        FilterChip(
                            selected = config.darkMode == idx,
                            onClick = { config = config.copy(darkMode = idx); saveConfig() },
                            label = { Text(label) }
                        )
                    }
                }
            }

            // ── Remote Agents (generic) ──
            Text("Remote Agents", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "OpenAI-shaped backends (e.g. Hermes /v1/responses). Enabled remotes are callable by the primary agent as tools remote_<name>. Use @rook to delegate to the 'rook'/'hermes' entry. No secret is baked into the APK — the API key lives only on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (remoteAgents.isEmpty()) {
                Text(
                    text = "No remotes configured. Add the built-in Hermes defaults below, or fill in your own backend. Built-in defaults: http://hermes-gateway.homelab.internal:8642, model hermes-agent (API key still must be entered).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = {
                        val updated = listOf(BuiltinHermes.copy(apiKey = ""))
                        remoteAgents = updated
                        scope.launch { saveRemoteAgents(context, updated) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add built-in Hermes defaults") }
            }

            remoteAgents.forEachIndexed { idx, agent ->
                RemoteAgentEditor(
                    agent = agent,
                    onChange = { updated ->
                        val list = remoteAgents.toMutableList()
                        list[idx] = updated
                        remoteAgents = list
                        scope.launch { saveRemoteAgents(context, list) }
                    },
                    onRemove = {
                        val list = remoteAgents.toMutableList()
                        list.removeAt(idx)
                        remoteAgents = list
                        scope.launch { saveRemoteAgents(context, list) }
                    }
                )
            }

            if (remoteAgents.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        val list = remoteAgents + RemoteAgent(
                            name = "agent${remoteAgents.size + 1}",
                            baseUrl = "",
                            endpoint = "responses",
                            model = "",
                            apiKey = "",
                            enabled = true
                        )
                        remoteAgents = list
                        scope.launch { saveRemoteAgents(context, list) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add remote agent") }
            }

            // ── Permissions (opt-in, all default off) ──
            Text("Permissions", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Nothing is requested at launch. Each area is opt-in and disabled by default — flip a toggle here to grant it, or revoke it in Android Settings > Apps > Pocket > Permissions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // SMS permissions (opt-in, disabled by default)
            Text("SMS permissions", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SMS tools")
                    Text(
                        text = if (smsGranted) "Granted — SMS tools are enabled." else "Disabled by default. SMS tools are opt-in.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = smsGranted,
                    onCheckedChange = { enable ->
                        if (enable) {
                            smsPermissionLauncher.launch(SmsPermissions.REQUIRED_PERMISSIONS)
                        } else {
                            smsNotice = "To disable SMS, revoke it in Android Settings > Apps > Pocket > Permissions > SMS."
                            smsGranted = SmsPermissions.isGranted(context)
                        }
                    }
                )
            }
            Text(
                text = "State: ${SmsPermissions.stateLabel(context)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            smsNotice?.let { notice ->
                Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Location opt-in
            Text("Location", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Location tool")
                    Text(
                        text = if (optIns.locationEnabled) "Enabled — location tool is active." else "Disabled by default. Location tool is opt-in.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = optIns.locationEnabled,
                    onCheckedChange = { enable ->
                        if (enable) {
                            // Request runtime location permissions AND set the flag.
                            // The tool additionally fails closed if the OS permission is missing.
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                        saveOptIns(optIns.copy(locationEnabled = enable))
                    }
                )
            }
            Text(
                text = "Blast radius: Pocket can read your device's last-known location when the agent calls the location tool.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Notification-listener opt-in
            Text("Notification triggers", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Notification listener")
                    Text(
                        text = if (notifListenerGranted && optIns.notificationListenerEnabled) "Enabled — triggers active." else "Disabled by default. Notification triggers are opt-in.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = optIns.notificationListenerEnabled,
                    onCheckedChange = { enable ->
                        if (enable) {
                            // Open the system Notification Listener settings screen.
                            try {
                                context.startActivity(
                                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                                notifNotice = "Grant Pocket in the system screen, then return here. If already granted, triggers activate immediately."
                            } catch (e: Exception) {
                                notifNotice = "Could not open system settings: ${e.message}"
                            }
                        }
                        saveOptIns(optIns.copy(notificationListenerEnabled = enable))
                        notifListenerGranted = NotificationOptIns.isListenerEnabled(context)
                    }
                )
            }
            Text(
                text = "State: ${NotificationOptIns.stateLabel(context)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Blast radius: this grants system Notification Listener access — Pocket can read titles/bodies of notifications from ALL apps (not just Pocket's). Only messages matching the keyword rule reach the agent.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            notifNotice?.let { notice ->
                Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ── Telemetry (OTEL) ──
            Text("Telemetry", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "OTEL Logs JSON (otlp_json) — posts {resourceLogs: [{resource: {attributes: [{key: service.name, value: {stringValue: pocket}}]}, scopeLogs: [{logRecords: [...]}]}]} to the endpoint below. Empty endpoint = off (no-op). Fire-and-forget, bounded queue (128). No PII.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = telemetryConfig.endpoint,
                onValueChange = {
                    telemetryConfig = telemetryConfig.copy(endpoint = it)
                    scope.launch { saveTelemetryConfig(context, telemetryConfig) }
                },
                label = { Text("OTEL ingest endpoint") },
                placeholder = { Text("https://your-otel-collector:4318/v1/logs") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Text(
                text = if (telemetryConfig.endpoint.isNotBlank()) "Format: otlp_json (OTEL Logs)" else "Status: Off (empty endpoint)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Save button
            Button(
                onClick = {
                    saveConfig()
                    onNavigateBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun RemoteAgentEditor(
    agent: RemoteAgent,
    onChange: (RemoteAgent) -> Unit,
    onRemove: () -> Unit
) {
    var showKey by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("remote_${agent.name.ifBlank { "?" }}", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled")
                    Switch(checked = agent.enabled, onCheckedChange = { onChange(agent.copy(enabled = it)) })
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onRemove) { Text("Remove") }
                }
            }
            OutlinedTextField(
                value = agent.name,
                onValueChange = { onChange(agent.copy(name = it)) },
                label = { Text("Name") },
                placeholder = { Text("rook / hermes / openclaw") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = agent.baseUrl,
                onValueChange = { onChange(agent.copy(baseUrl = it)) },
                label = { Text("Base URL") },
                placeholder = { Text("http://hermes-gateway.homelab.internal:8642") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = agent.endpoint,
                onValueChange = { onChange(agent.copy(endpoint = it)) },
                label = { Text("Endpoint (responses | chat_completions)") },
                placeholder = { Text("responses") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = agent.model,
                onValueChange = { onChange(agent.copy(model = it)) },
                label = { Text("Model") },
                placeholder = { Text("hermes-agent") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = agent.apiKey,
                onValueChange = { onChange(agent.copy(apiKey = it)) },
                label = { Text("API Key (stored on-device only)") },
                visualTransformation = if (showKey) PasswordVisualTransformation() else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showKey) "Hide" else "Show"
                        )
                    }
                }
            )
        }
    }
}
