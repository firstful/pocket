package com.sutliff.pocket.telemetry

import android.content.Context
import android.util.Log
import com.sutliff.pocket.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Best-effort, fire-and-forget raw-string telemetry.
 *
 * - Body is a minimal plain string capped at 2KB (server allows 4KB).
 * - One in-flight POST at a time; callers enqueue and never block.
 * - No retries beyond the initial attempt; failures are dropped.
 * - No PII: never device id, model, or build fingerprint.
 */
object Telemetry {
    private const val TAG = "Telemetry"
    private const val MAX_BODY_BYTES = 2048
    private const val MAX_QUEUE = 128

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val queue = ConcurrentLinkedQueue<String>()
    private val inFlight = AtomicBoolean(false)

    fun log(context: Context, event: String, extras: Map<String, String> = emptyMap()) {
        // Build body without any PII (no device id / model / build).
        val version = try { BuildConfig.VERSION_NAME } catch (_: Throwable) { "unknown" }
        val ts = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.append("pocket ").append(sanitize(version))
        sb.append(" event=").append(sanitize(event))
        sb.append(" ts=").append(ts)
        for ((k, v) in extras) {
            sb.append(' ').append(sanitize(k)).append('=').append(sanitize(v))
        }
        enqueue(capToBytes(sb.toString(), MAX_BODY_BYTES))
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
        val url = try { BuildConfig.TELEMETRY_URL } catch (_: Throwable) { "https://telemetry.ffilt.us/" }
        scope.launch {
            try {
                val request = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody("text/plain; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    // best-effort: ignore response body
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
        // walk back to a UTF-8 char boundary
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8)
    }
}
