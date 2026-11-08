package com.sutliff.pocket.datastore

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.sutliff.pocket.data.InferenceConfig
import com.sutliff.pocket.data.TelemetryConfig
import com.sutliff.pocket.data.PermissionOptIns
import com.sutliff.pocket.data.RemoteAgent

private val Context.dataStore by preferencesDataStore(name = "agent_config")

private val INFERENCE_CONFIG_KEY = stringPreferencesKey("inference_config")
private val REMOTE_AGENTS_KEY = stringPreferencesKey("remote_agents")
private val TELEMETRY_CONFIG_KEY = stringPreferencesKey("telemetry_config")
private val PERMISSION_OPT_INS_KEY = stringPreferencesKey("permission_opt_ins")

suspend fun saveInferenceConfig(context: Context, config: InferenceConfig) {
    val json = Json { ignoreUnknownKeys = true }
    val jsonString = json.encodeToString(InferenceConfig.serializer(), config)
    context.dataStore.edit { prefs ->
        prefs[INFERENCE_CONFIG_KEY] = jsonString
    }
}

suspend fun getInferenceConfig(context: Context): Flow<InferenceConfig> {
    return context.dataStore.data.map { prefs ->
        val jsonString = prefs[INFERENCE_CONFIG_KEY] ?: ""
        if (jsonString.isEmpty()) {
            InferenceConfig()
        } else {
            val json = Json { ignoreUnknownKeys = true }
            json.decodeFromString(InferenceConfig.serializer(), jsonString)
        }
    }
}

suspend fun readConfig(context: Context): InferenceConfig {
    return getInferenceConfig(context).first()
}

private val CURRENT_SESSION_ID_KEY = stringPreferencesKey("current_session_id")

/** Last active session id, persisted so the app can resume it across restarts. */
suspend fun getCurrentSessionId(context: Context): String? {
    return context.dataStore.data.first()[CURRENT_SESSION_ID_KEY]
}

suspend fun setCurrentSessionId(context: Context, sessionId: String) {
    context.dataStore.edit { prefs ->
        prefs[CURRENT_SESSION_ID_KEY] = sessionId
    }
}

// ── Remote agents (generic registry) ──────────────────────────────────────────

/** Built-in fallback for @rook / rook and hermes lookups when nothing is configured. */
val BuiltinHermes = RemoteAgent(
    name = "rook",
    baseUrl = "http://hermes-gateway.homelab.internal:8642",
    endpoint = "responses",
    model = "hermes-agent",
    apiKey = "", // No secret in APK — user enters it in Settings.
    enabled = true
)

suspend fun getRemoteAgents(context: Context): List<RemoteAgent> {
    val jsonString = context.dataStore.data.first()[REMOTE_AGENTS_KEY]
        ?: return emptyList()
    return try {
        Json { ignoreUnknownKeys = true }
            .decodeFromString<List<RemoteAgent>>(jsonString)
    } catch (e: Exception) {
        emptyList()
    }
}

/** Enabled remotes, in the order they were saved. */
suspend fun getEnabledRemoteAgents(context: Context): List<RemoteAgent> =
    getRemoteAgents(context).filter { it.enabled }

/**
 * Resolve a registry entry by alias (case-insensitive).
 * Returns the first configured entry whose name matches [alias], else null.
 */
suspend fun resolveRemoteAgent(context: Context, alias: String): RemoteAgent? {
    val agents = getRemoteAgents(context)
    return agents.firstOrNull { it.name.equals(alias, ignoreCase = true) }
        ?: when (alias.lowercase()) {
            "rook", "hermes" -> BuiltinHermes
            else -> null
        }
}

suspend fun saveRemoteAgents(context: Context, agents: List<RemoteAgent>) {
    val jsonString = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        .encodeToString(agents)
    context.dataStore.edit { prefs ->
        prefs[REMOTE_AGENTS_KEY] = jsonString
    }
}

// ── Telemetry config ──────────────────────────────────────────────────────────

suspend fun getTelemetryConfig(context: Context): TelemetryConfig {
    val jsonString = context.dataStore.data.first()[TELEMETRY_CONFIG_KEY]
        ?: return TelemetryConfig()
    return try {
        Json { ignoreUnknownKeys = true }
            .decodeFromString<TelemetryConfig>(jsonString)
    } catch (e: Exception) {
        TelemetryConfig()
    }
}

suspend fun saveTelemetryConfig(context: Context, config: TelemetryConfig) {
    val jsonString = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        .encodeToString(config)
    context.dataStore.edit { prefs ->
        prefs[TELEMETRY_CONFIG_KEY] = jsonString
    }
}

// ── Permission opt-ins ────────────────────────────────────────────────────────

suspend fun getPermissionOptIns(context: Context): PermissionOptIns {
    val jsonString = context.dataStore.data.first()[PERMISSION_OPT_INS_KEY]
        ?: return PermissionOptIns()
    return try {
        Json { ignoreUnknownKeys = true }
            .decodeFromString<PermissionOptIns>(jsonString)
    } catch (e: Exception) {
        PermissionOptIns()
    }
}

suspend fun savePermissionOptIns(context: Context, optIns: PermissionOptIns) {
    val jsonString = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        .encodeToString(optIns)
    context.dataStore.edit { prefs ->
        prefs[PERMISSION_OPT_INS_KEY] = jsonString
    }
}