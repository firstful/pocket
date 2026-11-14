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

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.util.concurrent.CountDownLatch

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


// ─── WebView renderer (JS-capable) + granular extraction ─────────────────────

object WebRenderManager {
    private var webView: WebView? = null
    private var overlay: FrameLayout? = null
    private val lock = Any()

    fun render(activity: Activity, url: String, timeoutMs: Long): String {
        val latch = CountDownLatch(1)
        var html: String? = null
        var error: String? = null

        activity.runOnUiThread {
            try {
                if (webView == null) {
                    val wv = WebView(activity)
                    wv.settings.javaScriptEnabled = true
                    wv.settings.domStorageEnabled = true
                    wv.settings.loadWithOverviewMode = true
                    wv.settings.useWideViewPort = true
                    wv.settings.userAgentString =
                        "Mozilla/5.0 (Linux; Android 14; Pixel 6a) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    val client = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            view?.postDelayed({
                                view.evaluateJavascript(
                                    "(function(){try{return document.documentElement.outerHTML;}catch(e){return '';}})();"
                                ) { value ->
                                    html = unquoteJs(value)
                                    latch.countDown()
                                }
                            }, 1500)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?
                        ) {
                            if (error == null) error = "WebView error $errorCode: $description"
                        }
                    }
                    wv.webViewClient = client
                    wv.webChromeClient = WebChromeClient()

                    val container = FrameLayout(activity)
                    val lp = FrameLayout.LayoutParams(dp(activity, 260), dp(activity, 180))
                    lp.gravity = Gravity.TOP or Gravity.END
                    lp.topMargin = dp(activity, 72)
                    lp.rightMargin = dp(activity, 12)
                    container.addView(wv, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                    // rounded-ish background handled by elevation; keep simple
                    container.elevation = 16f
                    container.visibility = View.VISIBLE

                    val root = activity.window.decorView as? ViewGroup
                    root?.addView(container, lp)
                    webView = wv
                    overlay = container
                } else {
                    overlay?.visibility = View.VISIBLE
                }
                webView?.loadUrl(url)
            } catch (e: Exception) {
                error = e.message
                latch.countDown()
            }
        }

        val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!ok && error == null) error = "Timed out waiting for page render (${timeoutMs}ms)"

        activity.runOnUiThread {
            overlay?.visibility = View.GONE
        }

        return when {
            !html.isNullOrBlank() -> html!!
            !error.isNullOrBlank() -> "Error: $error"
            else -> ""
        }
    }

    private fun unquoteJs(s: String?): String {
        if (s.isNullOrBlank() || s == "null") return ""
        var v = s.trim()
        if (v.startsWith("\"") && v.endsWith("\"") && v.length >= 2) {
            v = v.substring(1, v.length - 1)
        }
        return v
            .replace("\\\\", "\\")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\r", "\r")
    }

    private fun dp(activity: Activity, v: Int): Int =
        (v * activity.resources.displayMetrics.density).toInt()
}

class WebRenderTool : Tool {
    private val tag = "WebRenderTool"
    override val name = "web_render"
    override val description =
        "Render a web page in a WebView (executes JavaScript; works for SPAs) and return " +
            "readable text. Modes: full (default), outline (headings/TOC), section (one heading), " +
            "find (search text). Shows a small render preview (PIP) top-right while running."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("url") { put("type", "string"); put("description", "URL to render") }
            putJsonObject("mode") {
                put("type", "string")
                put("description", "full | outline | section | find (default full)")
            }
            putJsonObject("query") { put("type", "string"); put("description", "For find mode: text to search") }
            putJsonObject("section") { put("type", "string"); put("description", "For section mode: heading text") }
            putJsonObject("max_chars") { put("type", "integer"); put("description", "Max chars (default 4000)") }
            putJsonObject("timeout_ms") { put("type", "integer"); put("description", "Render timeout ms (default 12000)") }
            putJsonObject("max_matches") { put("type", "integer"); put("description", "For find mode (default 8)") }
            putJsonObject("context_chars") { put("type", "integer"); put("description", "For find mode (default 240)") }
        }
        putJsonArray("required") { add(JsonPrimitive("url")) }
    }

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private fun stripTags(s: String): String =
        android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_LEGACY).toString()

    private fun cleanHtml(html: String): String =
        html
            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("<noscript[^>]*>.*?</noscript>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")

    private fun htmlToText(html: String): String {
        val cleaned = cleanHtml(html)
        // Promote headings to markdown so structure survives Html.fromHtml
        val promoted = Regex("<h([1-6])[^>]*>(.*?)</h\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .replace(cleaned) { m ->
                "\n" + "#".repeat(m.groupValues[1].toInt()) + " " + stripTags(m.groupValues[2]).trim() + "\n"
            }
        val raw = android.text.Html.fromHtml(promoted, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        val lines = raw.split('\n').map { it.replace(Regex("\\s+"), " ").trim() }
        return lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private data class Heading(val level: Int, val text: String, val line: Int, val anchor: String)

    private fun headingsOf(text: String): List<Heading> {
        val out = mutableListOf<Heading>()
        text.lines().forEachIndexed { idx, line ->
            val m = Regex("^(#{1,6})\\s+(.+)$").matchEntire(line.trim())
            if (m != null) {
                val lvl = m.groupValues[1].length
                val t = m.groupValues[2].trim()
                val anchor = t.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                out.add(Heading(lvl, t, idx + 1, anchor))
            }
        }
        return out
    }

    override fun execute(args: Map<String, String>): String {
        val url = args["url"] ?: return "Error: no url"
        val mode = (args["mode"] ?: "full").lowercase()
        val maxChars = (args["max_chars"]?.toIntOrNull() ?: 4000).coerceIn(500, 50000)
        val timeoutMs = (args["timeout_ms"]?.toIntOrNull() ?: 12000).coerceIn(3000, 60000)
        val query = args["query"] ?: ""
        val section = args["section"] ?: ""
        val maxMatches = (args["max_matches"]?.toIntOrNull() ?: 8).coerceIn(1, 50)
        val contextChars = (args["context_chars"]?.toIntOrNull() ?: 240).coerceIn(40, 4000)

        val app = com.sutliff.pocket.AgentApp.instance
        val activity = com.sutliff.pocket.AgentApp.foregroundActivity?.get()

        val html: String = if (activity != null) {
            WebRenderManager.render(activity, url, timeoutMs.toLong())
        } else {
            // Fallback: static fetch (no JS)
            try {
                val req = okhttp3.Request.Builder().url(url).get()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 6a) AppleWebKit/537.36")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return "Error: HTTP ${resp.code} for $url"
                    resp.body?.string() ?: ""
                }
            } catch (e: Exception) {
                return "Error: ${e.message}"
            }
        }
        if (html.isBlank() || html.startsWith("Error:")) return html.ifBlank { "No content rendered from: $url" }

        val text = htmlToText(html)
        if (text.isBlank()) return "No readable text extracted from: $url"

        return when (mode) {
            "outline" -> {
                val hs = headingsOf(text)
                if (hs.isEmpty()) "No headings found in: $url"
                else buildString {
                    appendLine("# Outline: $url")
                    appendLine("Headings: ${hs.size}")
                    appendLine()
                    hs.forEach { h ->
                        appendLine("${"  ".repeat((h.level - 1).coerceAtLeast(0))}- [H${h.level}] ${h.text} (line ${h.line}, #${h.anchor})")
                    }
                }
            }
            "find" -> {
                if (query.isBlank()) return "Error: find mode requires 'query'"
                val lines = text.lines()
                val matches = mutableListOf<String>()
                var count = 0
                var truncated = false
                val re = Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
                re.findAll(text).forEach { m ->
                    if (count >= maxMatches) { truncated = true; return@forEach }
                    count++
                    val lineNo = text.substring(0, m.range.first).count { it == '\n' } + 1
                    val start = (m.range.first - contextChars).coerceAtLeast(0)
                    val end = (m.range.last + 1 + contextChars).coerceAtMost(text.length)
                    val snippet = text.substring(start, end).replace(Regex("\\s+"), " ").trim()
                    val prefix = if (start > 0) "…" else ""
                    val suffix = if (end < text.length) "…" else ""
                    // nearest preceding heading
                    val nearest = headingsOf(text).lastOrNull { it.line <= lineNo }
                    val sec = if (nearest != null) " | section: ${nearest.text} (#${nearest.anchor})" else ""
                    matches.add("## Match $count — line $lineNo$sec\n$prefix$snippet$suffix")
                }
                buildString {
                    appendLine("# Find: \"$query\"")
                    appendLine("URL: $url")
                    appendLine("Matches: ${matches.size}" + if (truncated) " (more available)" else "")
                    appendLine()
                    if (matches.isEmpty()) appendLine("No matches found.")
                    else matches.forEach { appendLine(it); appendLine() }
                }
            }
            "section" -> {
                val q = (if (section.isNotBlank()) section else query).trim()
                if (q.isBlank()) return "Error: section mode requires 'section'"
                val hs = headingsOf(text)
                val idx = hs.indexOfFirst { h ->
                    h.text.equals(q, ignoreCase = true) || h.anchor.equals(q.lowercase().replace(Regex("[^a-z0-9]+"), "-"), ignoreCase = true) ||
                        h.text.contains(q, ignoreCase = true)
                }
                if (idx < 0) {
                    return buildString {
                        appendLine("Section not found: \"$q\"")
                        appendLine("Available: ${hs.take(25).joinToString(", ") { it.text }}")
                    }
                }
                val h = hs[idx]
                // slice text lines from heading line to next heading of <= level
                val lines = text.lines()
                val startIdx = (h.line - 1).coerceIn(0, lines.size)
                val endIdx = hs.drop(idx + 1).firstOrNull { it.level <= h.level }?.line?.minus(1)?.coerceAtLeast(startIdx)
                    ?: lines.size
                val body = lines.subList(startIdx, endIdx.coerceAtMost(lines.size)).joinToString("\n").trim()
                val trimmed = if (body.length > maxChars) body.take(maxChars) + "\n[... section truncated ...]" else body
                buildString {
                    appendLine("# Section: ${h.text}")
                    appendLine("URL: $url  |  Anchor: #${h.anchor}  |  Level: H${h.level}")
                    appendLine()
                    appendLine(trimmed)
                }
            }
            else -> {
                if (text.length <= maxChars) text else text.take(maxChars) + "\n\n[... truncated at $maxChars of ${text.length} chars ...]"
            }
        }
    }
}
