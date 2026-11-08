package com.sutliff.pocket.telemetry

import android.content.Context
import android.util.Log
import com.sutliff.pocket.BuildConfig
import com.sutliff.pocket.data.TelemetryConfig
import com.sutliff.pocket.datastore.getTelemetryConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Generic OpenTelemetry (OTEL) Logs exporter (todo #423 follow-up).
 *
 * - [TelemetryConfig.endpoint] empty or [TelemetryConfig.format] != "otlp_json" => OFF (no-op).
 * - When configured, events are POSTed as an OTEL Logs JSON envelope:
 *     { resourceLogs: [ { resource: { attributes: [ {key:"service.name", value:{stringValue:"pocket"}} ] },
 *                        scopeLogs: [ { scope: {name:"com.sutliff.pocket"}, logRecords: [
 *                          { timeUnixNano, severityText, body:{stringValue: event},
 *                            attributes: [ {key, value:{stringValue}} ... ] } ] } ] } ] }
 * - Fire-and-forget, one in-flight POST, bounded queue (128, drop-oldest), no retries.
 * - No PII: never device id, model, or build fingerprint. Attribute values are
 *   sanitized to a single token (no whitespace) and truncated.
 * - Config is cached in memory and refreshed from DataStore on every log() call
 *   (read is cheap; DataStore caches the file). The cached value is volatile.
 */
object Telemetry {
    private const val TAG = "Telemetry"
    private const val MAX_BODY_BYTES = 4096
    private const val MAX_QUEUE = 128

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val queue = ConcurrentLinkedQueue<String>()
    private val inFlight = AtomicBoolean(false)

    @Volatile
    private var cachedConfig: TelemetryConfig = TelemetryConfig()

    @Volatile
    private var cachedContext: Context? = null

    /** Refresh the cached config from DataStore. Safe to call from any thread. */
    private fun refreshConfig(context: Context) {
        cachedContext = context.applicationContext
        try {
            val cfg = runBlocking { getTelemetryConfig(context) }
            cachedConfig = cfg
        } catch (e: Exception) {
            Log.d(TAG, "telemetry config read failed: ${e.message}")
        }
    }

    fun log(context: Context, event: String, extras: Map<String, String> = emptyMap()) {
        refreshConfig(context)
        val cfg = cachedConfig
        if (!isEnabled(cfg)) return // empty endpoint OR unknown format = OFF (no-op)

        val version = try { BuildConfig.VERSION_NAME } catch (_: Throwable) { "unknown" }
        val tsNanos = System.currentTimeMillis() * 1_000_000L
        val bodyJson = buildOtlpJson(version, tsNanos, event, extras)
        enqueue(capToBytes(bodyJson, MAX_BODY_BYTES))
    }

    fun logError(context: Context, tag: String, throwable: Throwable) {
        log(
            context,
            "error",
            mapOf(
                "tag" to tag,
                "type" to (throwable::class.simpleName ?: "Throwable"),
                "msg" to (throwable.message ?: "").take(120)
            )
        )
    }

    /** Exposed for Settings/tests; also used by [log] internally. */
    fun isEnabled(cfg: TelemetryConfig = cachedConfig): Boolean =
        cfg.endpoint.isNotBlank() && cfg.format.trim().lowercase() == "otlp_json"

    /** Build the OTEL Logs JSON envelope string for a single log record. */
    fun buildOtlpJson(version: String, tsNanos: Long, event: String, extras: Map<String, String>): String {
        val attrs = mutableListOf<Pair<String, String>>()
        attrs += "version" to sanitize(version)
        attrs += "event" to sanitize(event)
        for ((k, v) in extras) {
            attrs += sanitize(k) to sanitize(v)
        }
        val attrElements = attrs.map { (k, v) ->
            buildJsonObject {
                put("key", k)
                putJsonObject("value") { put("stringValue", v) }
            }
        }
        val record = buildJsonObject {
            put("timeUnixNano", tsNanos.toString())
            // severity: INFO for normal events, ERROR for the error event name.
            put("severityText", if (event == "error") "ERROR" else "INFO")
            putJsonObject("body") { put("stringValue", sanitize(event)) }
            putJsonArray("attributes") { attrElements.forEach { add(it) } }
        }
        val envelope = buildJsonObject {
            putJsonArray("resourceLogs") {
                add(
                    buildJsonObject {
                        putJsonObject("resource") {
                            putJsonArray("attributes") {
                                add(
                                    buildJsonObject {
                                        put("key", "service.name")
                                        putJsonObject("value") { put("stringValue", "pocket") }
                                    }
                                )
                            }
                        }
                        putJsonArray("scopeLogs") {
                            add(
                                buildJsonObject {
                                    putJsonObject("scope") { put("name", "com.sutliff.pocket") }
                                    putJsonArray("logRecords") { add(record) }
                                }
                            )
                        }
                    }
                )
            }
        }
        return envelope.toString()
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("\\s+"), "_").take(200)

    private fun enqueue(body: String) {
        if (queue.size >= MAX_QUEUE) {
            queue.poll() // drop oldest to bound memory
        }
        queue.offer(body)
        drain()
    }

    private fun drain() {
        if (!inFlight.compareAndSet(false, true)) return
        val body = queue.poll()
        if (body == null) {
            inFlight.set(false)
            return
        }
        val cfg = cachedConfig
        if (!isEnabled(cfg)) {
            inFlight.set(false)
            if (!queue.isEmpty()) drain()
            return
        }
        scope.launch {
            try {
                val request = Request.Builder()
                    .url(cfg.endpoint.trim())
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    response.body?.string()
                }
            } catch (e: Throwable) {
                Log.d(TAG, "telemetry post failed: ${e.message}")
            } finally {
                inFlight.set(false)
                if (!queue.isEmpty()) drain()
            }
        }
    }

    private fun capToBytes(s: String, maxBytes: Int): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return s
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8)
    }
}
