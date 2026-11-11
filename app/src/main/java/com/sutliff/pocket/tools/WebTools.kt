package com.sutliff.pocket.tools

import android.content.Context
import android.text.Html
import android.util.Log
import com.sutliff.pocket.AgentApp
import com.sutliff.pocket.telemetry.Telemetry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Web search + fetch tools.
 *
 * web_search uses an OkHttp POST to DuckDuckGo's HTML endpoint
 * (https://html.duckduckgo.com/html/) instead of an ephemeral WebView.
 * The WebView approach never fired onPageFinished in this environment
 * (even attached to the Activity content view), causing a 30s hang.
 * The HTML endpoint returns server-rendered result links/snippets that are
 * parsed with regex — fast, deterministic, no rendering dependency.
 *
 * web_fetch similarly fetches the page with OkHttp and extracts readable text.
 */
class WebSearchTool : Tool {
    private val tag = "WebSearchTool"
    override val name = "web_search"
    override val description =
        "Search the web and return result titles, snippets, and URLs. " +
            "Use web_fetch to read a specific result page."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string")
                put("description", "Search query")
            }
            putJsonObject("max_results") {
                put("type", "integer")
                put("description", "Max results to return (default 8)")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("query")) }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override fun execute(args: Map<String, String>): String {
        val query = args["query"] ?: return "Error: no query"
        val maxResults = (args["max_results"]?.toIntOrNull() ?: 8).coerceIn(1, 20)
        val app = AgentApp.instance ?: run {
            Log.e(tag, "execute failed: no app context")
            return "Error: no app context"
        }
        val startMs = System.currentTimeMillis()
        Log.d(tag, "start query_len=${query.length} max_results=$maxResults")
        Telemetry.log(app, "web_search_start", mapOf("query_length" to query.length.toString(), "max_results" to maxResults.toString()))

        return try {
            val body = FormBody.Builder().add("q", query).build()
            val request = Request.Builder()
                .url("https://html.duckduckgo.com/html/")
                .post(body)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 6a) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            client.newCall(request).execute().use { response ->
                val durationMs = System.currentTimeMillis() - startMs
                if (!response.isSuccessful) {
                    val msg = "Error: search HTTP ${response.code} for: $query"
                    Log.e(tag, msg)
                    Telemetry.log(app, "web_search_error", mapOf("error" to "http_${response.code}", "duration_ms" to durationMs.toString()))
                    return msg
                }
                val html = response.body?.string() ?: ""
                Log.d(tag, "response code=${response.code} html_len=${html.length} duration_ms=$durationMs")

                val titleRegex = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
                val snippetRegex = Regex("""<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

                val titles = titleRegex.findAll(html).toList()
                val snippets = snippetRegex.findAll(html).map { it.groupValues[1] }.toList()
                Log.d(tag, "parsed titles=${titles.size} snippets=${snippets.size}")

                val results = mutableListOf<String>()
                for (i in titles.indices) {
                    if (results.size >= maxResults) break
                    val rawUrl = titles[i].groupValues[1]
                    val url = unwrapDdgUrl(rawUrl)
                    val title = stripTags(titles[i].groupValues[2])
                    val snippet = if (i < snippets.size) stripTags(snippets[i]) else ""
                    if (title.isBlank() && url.isBlank()) continue
                    results.add("${results.size + 1}. $title\n   URL: $url\n   $snippet".trimEnd())
                }

                Log.d(tag, "done result_count=${results.size} duration_ms=$durationMs")
                Telemetry.log(app, "web_search_done", mapOf("tier" to "ddg", "result_count" to results.size.toString(), "duration_ms" to durationMs.toString(), "query_length" to query.length.toString()))
                if (results.isEmpty()) {
                    Telemetry.log(app, "web_search_error", mapOf("error" to "no_results", "duration_ms" to durationMs.toString()))
                    return "No results found for: $query"
                }
                "TIER:ddg\n" + results.joinToString("\n\n")
            }
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startMs
            Log.e(tag, "exception: ${e.message}", e)
            Telemetry.log(app, "web_search_error", mapOf("error" to (e.message ?: "unknown"), "duration_ms" to durationMs.toString()))
            "Error: ${e.message}"
        }
    }

    private fun unwrapDdgUrl(raw: String): String {
        return try {
            if (raw.contains("uddg=")) {
                val u = java.net.URI(raw)
                val q = u.query?.split("&")?.firstOrNull { it.startsWith("uddg=") }?.substringAfter("=")
                if (!q.isNullOrBlank()) java.net.URLDecoder.decode(q, "UTF-8") else raw
            } else {
                raw
            }
        } catch (e: Exception) {
            raw
        }
    }

    private fun stripTags(s: String): String =
        Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()
}

/**
 * Page fetcher: fetches a URL with OkHttp and returns readable text.
 */
class WebFetchTool : Tool {
    private val tag = "WebFetchTool"
    override val name = "web_fetch"
    override val description =
        "Fetch a web page and return its readable text content. " +
            "Use after web_search to read a specific result."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("url") {
                put("type", "string")
                put("description", "URL to fetch")
            }
            putJsonObject("max_chars") {
                put("type", "integer")
                put("description", "Max characters to return (default 4000)")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("url")) }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override fun execute(args: Map<String, String>): String {
        val url = args["url"] ?: return "Error: no url"
        val maxChars = (args["max_chars"]?.toIntOrNull() ?: 4000).coerceIn(500, 20000)
        val app = AgentApp.instance ?: run {
            Log.e(tag, "execute failed: no app context")
            return "Error: no app context"
        }
        val startMs = System.currentTimeMillis()
        Log.d(tag, "start url=$url max_chars=$maxChars")
        Telemetry.log(app, "web_fetch_start", mapOf("url_length" to url.length.toString(), "max_chars" to maxChars.toString()))

        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 6a) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            client.newCall(request).execute().use { response ->
                val durationMs = System.currentTimeMillis() - startMs
                if (!response.isSuccessful) {
                    val msg = "Error: fetch HTTP ${response.code} for: $url"
                    Log.e(tag, msg)
                    Telemetry.log(app, "web_fetch_error", mapOf("error" to "http_${response.code}", "duration_ms" to durationMs.toString()))
                    return msg
                }
                val html = response.body?.string() ?: ""
                Log.d(tag, "response code=${response.code} html_len=${html.length} duration_ms=$durationMs")
                val cleanedHtml = html
                    .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
                    .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
                val text = Html.fromHtml(cleanedHtml, Html.FROM_HTML_MODE_LEGACY).toString()
                    .replace(Regex("\\s+"), " ").trim()
                Log.d(tag, "done cleaned_len=${text.length} duration_ms=$durationMs")
                Telemetry.log(app, "web_fetch_done", mapOf("result_length" to text.length.toString(), "duration_ms" to durationMs.toString()))
                if (text.isBlank()) "No content extracted from: $url" else text.take(maxChars)
            }
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startMs
            Log.e(tag, "exception: ${e.message}", e)
            Telemetry.log(app, "web_fetch_error", mapOf("error" to (e.message ?: "unknown"), "duration_ms" to durationMs.toString()))
            "Error: ${e.message}"
        }
    }
}
