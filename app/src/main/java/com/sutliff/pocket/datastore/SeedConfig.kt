package com.sutliff.pocket.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import com.sutliff.pocket.data.InferenceConfig
import com.sutliff.pocket.data.PermissionOptIns
import com.sutliff.pocket.data.RemoteAgent
import com.sutliff.pocket.data.TelemetryConfig

/**
 * Optional first-launch seed config.
 *
 * Drop a file named `config.seed.json` into `app/src/main/assets/` (gitignored) to
 * pre-populate Pocket's DataStore on first launch / on a seed-version bump. When the
 * asset is absent (the public build), this is a no-op and Pocket ships with its
 * default empty config — no secrets are baked in.
 *
 * Format:
 * {
 *   "seedVersion": "1",
 *   "inference": { "baseUrl": "...", "apiKey": "...", "model": "...", ... },
 *   "remoteAgents": [ { "name": "rook", "baseUrl": "...", "apiKey": "...", ... } ],
 *   "telemetry": { "endpoint": "...", "format": "otlp_json" },
 *   "permissions": { "locationEnabled": false, ... }
 * }
 *
 * All fields are optional. Only present fields are applied.
 *
 * Security note: anything in this asset is compiled into the APK and is recoverable
 * by anyone with the APK. Only use seeded builds for installs you control (your own
 * device); never publish a seeded APK to the public store.
 */
@Serializable
data class SeedConfig(
    val seedVersion: String? = null,
    val inference: InferenceConfig? = null,
    val remoteAgents: List<RemoteAgent>? = null,
    val telemetry: TelemetryConfig? = null,
    val permissions: PermissionOptIns? = null
)

private val SEEDED_VERSION_KEY = stringPreferencesKey("applied_seed_version")

/**
 * Apply `assets/config.seed.json` if present and its seedVersion differs from the
 * version already applied. Safe to call on every launch; no-op when the asset is
 * missing or already applied.
 */
suspend fun seedConfigIfPresent(context: Context) {
    val jsonStr = try {
        context.assets.open("config.seed.json").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        return // no seed asset (public build) — nothing to do
    }
    if (jsonStr.isBlank()) return

    val seed = try {
        Json { ignoreUnknownKeys = true }.decodeFromString<SeedConfig>(jsonStr)
    } catch (e: Exception) {
        return // malformed seed — leave defaults, retry next launch
    }

    val version = seed.seedVersion ?: "1"
    val applied = context.dataStore.data.first()[SEEDED_VERSION_KEY] ?: ""
    if (applied == version) return // already applied this version

    seed.inference?.let { saveInferenceConfig(context, it) }
    seed.remoteAgents?.let { saveRemoteAgents(context, it) }
    seed.telemetry?.let { saveTelemetryConfig(context, it) }
    seed.permissions?.let { savePermissionOptIns(context, it) }

    context.dataStore.edit { prefs ->
        prefs[SEEDED_VERSION_KEY] = version
    }
}

/**
 * Blocking helper for use from Application.onCreate (one-time, fast).
 */
fun seedConfigBlocking(context: Context) {
    runBlocking { seedConfigIfPresent(context) }
}
