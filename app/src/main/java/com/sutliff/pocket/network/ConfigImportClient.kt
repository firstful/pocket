package com.sutliff.pocket.network

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.sutliff.pocket.BuildConfig
import java.io.IOException
import java.net.URL
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Thrown before any request is sent when the supplied config URL is not HTTPS. */
class InsecureConfigUrlException(message: String) : IllegalArgumentException(message)

/** Outcome of a one-shot config-import fetch. */
sealed class ConfigImportResult {
    data class Success(val configJson: String) : ConfigImportResult()
    object WrongPassphrase : ConfigImportResult()
    object RateLimited : ConfigImportResult()
    data class NetworkError(val error: Throwable) : ConfigImportResult()
    object InvalidResponse : ConfigImportResult()
}

/**
 * One-shot HTTPS POST of `{"passphrase": "..."}` to a user-supplied config
 * endpoint. The passphrase lives ONLY in the JSON body — never in the URL or
 * query string — and is never logged.
 *
 * HTTPS is enforced independently of the app-wide `usesCleartextTraffic=true`
 * manifest flag: [fetchConfig] validates the scheme BEFORE building/sending the
 * request. An `http://` URL is rejected with [InsecureConfigUrlException].
 *
 * This client is intentionally blocking (`execute()`); call it off the main
 * thread (e.g. `withContext(Dispatchers.IO)`).
 */
object ConfigImportClient {

    private val client: OkHttpClient by lazy { buildClient() }

    /**
     * @throws InsecureConfigUrlException if [url] is malformed or not HTTPS.
     */
    fun fetchConfig(url: String, passphrase: String): ConfigImportResult {
        val httpsUrl = requireHttps(url)

        val body = buildJsonObject { put("passphrase", passphrase) }.toString()
        val request = Request.Builder()
            .url(httpsUrl)
            .header("X-Pocket-Version", BuildConfig.VERSION_NAME)
            .header("X-Pocket-Version-Code", BuildConfig.VERSION_CODE.toString())
            .header("X-Pocket-Git-Hash", BuildConfig.GIT_HASH)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    401 -> ConfigImportResult.WrongPassphrase
                    429 -> ConfigImportResult.RateLimited
                    in 200..299 -> {
                        val payload = response.body?.string()?.trim().orEmpty()
                        // Guard against a 200 carrying an HTML error page. A
                        // SeedConfig payload is always a JSON object.
                        if (payload.startsWith("{") && payload.endsWith("}")) {
                            ConfigImportResult.Success(payload)
                        } else {
                            ConfigImportResult.InvalidResponse
                        }
                    }
                    in 500..599 -> ConfigImportResult.NetworkError(
                        IOException("Config endpoint server error: HTTP ${response.code}")
                    )
                    else -> ConfigImportResult.InvalidResponse
                }
            }
        } catch (e: IOException) {
            ConfigImportResult.NetworkError(e)
        } catch (e: Exception) {
            ConfigImportResult.NetworkError(e)
        }
    }

    /** Validate and normalize the URL; reject cleartext before any I/O. */
    private fun requireHttps(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val parsed = try {
            URL(trimmed)
        } catch (e: Exception) {
            throw InsecureConfigUrlException("Invalid config import URL: ${e.message}")
        }
        if (!parsed.protocol.equals("https", ignoreCase = true)) {
            throw InsecureConfigUrlException(
                "Config import URL must use HTTPS (got '${parsed.protocol}'); http:// is not allowed."
            )
        }
        return trimmed
    }

    private fun buildClient(): OkHttpClient {
        // Trust store: the platform default KeyStore includes system CAs
        // (Tailscale's public-CA certs) AND user-installed CAs (a homelab
        // internal CA installed under Settings > Security > Install). That is
        // what lets this dedicated client verify pocket-cfg.homelab.internal.
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?) // null => platform default trust store (system + user CAs)
        val delegates = tmf.trustManagers.filterIsInstance<X509TrustManager>()
        require(delegates.isNotEmpty()) { "No X509 trust manager available" }
        val trustManager: X509TrustManager = if (delegates.size == 1) {
            delegates.first()
        } else {
            CompositeX509TrustManager(delegates)
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), null)

        val builder = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            // Hostname verification stays at OkHttp's default (ON) — do NOT
            // disable it; the internal cert's CN/SAN must match the host.

        // ── CERT PINNING HOOK ────────────────────────────────────────────────
        // To pin the internal CA / Tailscale cert, add sha256 pins here, e.g.:
        //   pinner.add("pocket-cfg.homelab.internal", "sha256/BASE64PIN=")
        // Left empty by default so a Tailscale cert rotation cannot brick
        // imports. Callers can opt into pinning once pins are provisioned.
        val pinner = CertificatePinner.Builder().build()
        builder.certificatePinner(pinner)
        // ────────────────────────────────────────────────────────────────────

        return builder.build()
    }

    /** Delegates to every platform trust manager so any of them can trust a chain. */
    private class CompositeX509TrustManager(
        private val delegates: List<X509TrustManager>
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {
            delegates.firstOrNull { it.safeCheckClient(chain, authType) }
                ?: throw IllegalArgumentException("Client certificate not trusted by any trust manager")
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
            delegates.firstOrNull { it.safeCheckServer(chain, authType) }
                ?: throw IllegalArgumentException("Server certificate not trusted by any trust manager")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> {
            val all = mutableListOf<X509Certificate>()
            delegates.forEach { all.addAll(it.acceptedIssuers) }
            return all.toTypedArray()
        }

        private fun X509TrustManager.safeCheckClient(
            chain: Array<X509Certificate>?, authType: String?
        ): Boolean = try {
            checkClientTrusted(chain, authType); true
        } catch (e: Exception) {
            false
        }

        private fun X509TrustManager.safeCheckServer(
            chain: Array<X509Certificate>?, authType: String?
        ): Boolean = try {
            checkServerTrusted(chain, authType); true
        } catch (e: Exception) {
            false
        }
    }
}
