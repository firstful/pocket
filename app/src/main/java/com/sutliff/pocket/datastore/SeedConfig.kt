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
 * Version key for passphrase-imported config. Deliberately SEPARATE from
 * [SEEDED_VERSION_KEY]: the build-time seed path writes applied_seed_version,
 * so reusing it would let either path silence the other.
 */
val IMPORTED_VERSION_KEY = stringPreferencesKey("applied_import_version")

/** Version of the most recently applied imported config, or null if never. */
suspend fun appliedImportVersion(context: Context): String? =
    context.dataStore.data.first()[IMPORTED_VERSION_KEY]

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

/**
 * Apply a passphrase-imported [SeedConfig] to DataStore.
 *
 * - Validates that at least one config block is present BEFORE writing
 *   anything. Validation failure throws [IllegalArgumentException] and never
 *   marks the version as applied.
 * - Applies each present block via the normal `save*` helpers, which wrap
 *   secrets through KeystoreWrap before persisting.
 * - Writes [IMPORTED_VERSION_KEY] LAST, so a partial failure (e.g. Keystore
 *   unavailable mid-way) leaves the previously applied version intact and
 *   the import can be retried.
 * - Skips re-apply when [SeedConfig.seedVersion] equals the already-applied
 *   import version, unless [force] is true. Force is the escape hatch after a
 *   device security change invalidated the hardware key: re-import the same
 *   version to re-wrap its secrets.
 *
 * @return true if config was applied, false if skipped (same version).
 */
suspend fun applyImportedConfig(
    context: Context,
    seed: SeedConfig,
    force: Boolean = false
): Boolean {
    // Validation first — no writes, no version key on failure.
    val hasAnyBlock = seed.inference != null || seed.remoteAgents != null ||
        seed.telemetry != null || seed.permissions != null
    if (!hasAnyBlock) {
        throw IllegalArgumentException("Imported config contains no config blocks to apply")
    }

    val version = seed.seedVersion ?: "1"
    val applied = context.dataStore.data.first()[IMPORTED_VERSION_KEY] ?: ""
    if (!force && applied == version) {
        return false // already applied this imported version — no-op
    }

    seed.inference?.let { saveInferenceConfig(context, it) }
    seed.remoteAgents?.let { saveRemoteAgents(context, it) }
    seed.telemetry?.let { saveTelemetryConfig(context, it) }
    seed.permissions?.let { savePermissionOptIns(context, it) }

    // Mark applied LAST — any exception above leaves the old version intact.
    context.dataStore.edit { prefs ->
        prefs[IMPORTED_VERSION_KEY] = version
    }
    return true
}
