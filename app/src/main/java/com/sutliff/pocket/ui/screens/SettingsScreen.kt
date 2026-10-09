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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sutliff.pocket.data.InferenceConfig
import com.sutliff.pocket.datastore.saveInferenceConfig
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.sutliff.pocket.appContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateBack: () -> Unit) {
    val context = appContext()
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(InferenceConfig()) }
    var showApiKey by remember { mutableStateOf(false) }

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
                placeholder = { Text("http://192.168.1.100:8080/v1") },
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
                placeholder = { Text("llama3-8b-instruct") },
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