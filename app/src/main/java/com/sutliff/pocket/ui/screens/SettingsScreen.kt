package com.sutliff.pocket.ui.screens

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
import com.sutliff.pocket.datastore.saveInferenceConfig
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
        }
    }

    fun saveConfig() {
        scope.launch {
            saveInferenceConfig(context, config)
        }
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