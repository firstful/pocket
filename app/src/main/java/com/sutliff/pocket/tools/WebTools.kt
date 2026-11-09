package com.sutliff.pocket.tools

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.sutliff.pocket.telemetry.Telemetry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Ephemeral WebView-based web search. Opens Google search results in an
 * off-screen WebView, waits for render, extracts result titles/snippets/URLs
 * via evaluateJavascript. Appears as a normal mobile Chrome browser.
 *
 * Reliability notes (2026-11-09):
 * - WebView is attached to a hidden 1x1 FrameLayout, onResume()+resumeTimers()
 *   are called, and destroy() is always invoked on completion.
 * - SERP extraction is multi-tier (div.g -> article/[data-hveid] -> generic
 *   links -> innerText) because Google class names rot without notice.
 * - Every stage is Log.d + Telemetry logged so silent failures are diagnosable.
 */
class WebSearchTool : Tool {
    private val tag = "WebSearchTool"
    override val name = "web_search"
    override val description =
        "Search the web (Google) and return result titles, snippets, and URLs. " +
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

    override fun execute(args: Map<String, String>): String {
        val query = args["query"] ?: return "Error: no query"
        val maxResults = (args["max_results"]?.toIntOrNull() ?: 8).coerceIn(1, 20)
        val app = com.sutliff.pocket.AgentApp.instance ?: run {
            Log.e(tag, "execute failed: no app context")
            return "Error: no app context"
        }
        val startMs = System.currentTimeMillis()
        val searchUrl = "https://www.google.com/search?q=${URLEncoder.encode(query, "UTF-8")}&hl=en&num=10"
        Log.d(tag, "start query_len=${query.length} max_results=$maxResults url=$searchUrl")
        Telemetry.log(app, "web_search_start", mapOf("query_length" to query.length.toString(), "max_results" to maxResults.toString()))

        val latch = CountDownLatch(1)
        val resultHolder = arrayOf<String?>(null)
        val errorHolder = arrayOf<String?>(null)
        val webViewHolder = arrayOf<WebView?>(null)
        val containerHolder = arrayOf<FrameLayout?>(null)
        val pageFinished = arrayOf(false)

        Handler(Looper.getMainLooper()).post {
            var webView: WebView? = null
            var container: FrameLayout? = null
            try {
                container = FrameLayout(app).apply {
                    layoutParams = FrameLayout.LayoutParams(1, 1)
                    visibility = android.view.View.VISIBLE
                }
                containerHolder[0] = container
                webView = WebView(app).apply {
                    layoutParams = ViewGroup.LayoutParams(1, 1)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.blockNetworkImage = true
                    settings.userAgentString = settings.userAgentString // keep real mobile UA
                    onResume()
                    resumeTimers()
                }
                webViewHolder[0] = webView
                container.addView(webView)
                // Attach container so the renderer is not fully detached
                try {
                    val wm = app.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                    val params = android.view.WindowManager.LayoutParams(
                        1, 1,
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        android.graphics.PixelFormat.TRANSLUCENT
                    )
                    wm.addView(container, params)
                    Log.d(tag, "WebView attached to WindowManager (1x1)")
                } catch (e: Exception) {
                    Log.d(tag, "WindowManager attach failed (using detached container): ${e.message}")
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        Log.d(tag, "onPageFinished url=$url")
                        if (pageFinished[0]) return
                        // Google consent redirect: click Accept/Reject and wait for reload
                        if (url != null && url.contains("consent.google.com")) {
                            Log.d(tag, "consent page detected, attempting to click accept/reject")
                            view?.evaluateJavascript(
                                "(function(){var b=document.querySelectorAll('button,input[type=submit],div[role=button]');" +
                                "for(var i=0;i<b.length;i++){var t=(b[i].innerText||b[i].value||'').toLowerCase();" +
                                "if(t.indexOf('reject')>=0||t.indexOf('accept')>=0||t.indexOf('agree')>=0||t.indexOf('got it')>=0||t.indexOf('i agree')>=0){b[i].click();return 'clicked:'+t;}}" +
                                "if(b.length){b[0].click();return 'clicked-first';}return 'no-button';})()",
                                null
                            )
                            // Reload search after a moment so consent cookie takes effect
                            Handler(Looper.getMainLooper()).postDelayed({
                                Log.d(tag, "reloading search URL after consent click")
                                view?.loadUrl(searchUrl)
                            }, 1500)
                            return
                        }
                        if (url != null && url.contains("www.google.com/search")) {
                            pageFinished[0] = true
                        } else if (!pageFinished[0]) {
                            // Still extract on first finished page even if URL differs
                            pageFinished[0] = true
                        }
                        Handler(Looper.getMainLooper()).postDelayed({
                            Log.d(tag, "running SERP extraction JS (post-page delay 3000ms)")
                            view?.evaluateJavascript(
                                """
                                (function() {
                                    function unwrap(href){
                                        try {
                                            var u = new URL(href);
                                            var q = u.searchParams.get('q');
                                            if ((u.hostname.indexOf('google.')>=0) && q && q.indexOf('http')===0) return q;
                                        } catch(e){}
                                        return href;
                                    }
                                    function clean(t){ return (t||'').replace(/\s+/g,' ').trim(); }

                                    // Tier 1: div.g
                                    var results = [];
                                    var items = document.querySelectorAll('div.g');
                                    for (var i = 0; i < items.length && results.length < $maxResults; i++) {
                                        var a = items[i].querySelector('a');
                                        var h3 = items[i].querySelector('h3');
                                        var snippet = items[i].querySelector('.VwiC3b, .s3v9rd, [data-sncf], .st, .IsZvec, span');
                                        var href = a ? unwrap(a.href) : '';
                                        var title = clean(h3 ? h3.innerText : (a ? a.innerText : ''));
                                        if (!title && !href) continue;
                                        if (href.indexOf('google.com')>=0 && href.indexOf('/url')<0) continue;
                                        results.push((results.length+1) + '. ' + title + '\n   URL: ' + href + '\n   ' + clean(snippet ? snippet.innerText : ''));
                                    }
                                    if (results.length > 0) return 'TIER:1\n' + results.join('\n\n');

                                    // Tier 2: article / [data-hveid] organic blocks
                                    results = [];
                                    var items2 = document.querySelectorAll('article, div[data-hveid], div[data-text-ad]');
                                    for (var i2 = 0; i2 < items2.length && results.length < $maxResults; i2++) {
                                        var a2 = items2[i2].querySelector('a[href^="http"]');
                                        if (!a2) continue;
                                        var href2 = unwrap(a2.href);
                                        if (href2.indexOf('google.com')>=0) continue;
                                        var title2 = clean(a2.innerText) || clean(items2[i2].querySelector('h3') ? items2[i2].querySelector('h3').innerText : '');
                                        if (title2.length < 5) continue;
                                        results.push((results.length+1) + '. ' + title2 + '\n   URL: ' + href2);
                                    }
                                    if (results.length > 0) return 'TIER:2\n' + results.join('\n\n');

                                    // Tier 3: all http links with visible text >10, deduped
                                    results = [];
                                    var links = document.querySelectorAll('a[href^="http"]');
                                    var seen = {};
                                    for (var j = 0; j < links.length && results.length < $maxResults; j++) {
                                        var href3 = unwrap(links[j].href);
                                        if (href3.indexOf('google.com')>=0) continue;
                                        if (seen[href3]) continue;
                                        seen[href3] = true;
                                        var text3 = clean(links[j].innerText);
                                        if (text3.length > 10) {
                                            results.push((results.length+1) + '. ' + text3 + '\n   URL: ' + href3);
                                        }
                                    }
                                    if (results.length > 0) return 'TIER:3\n' + results.join('\n\n');

                                    // Ultimate fallback: innerText so caller always gets something
                                    var bodyText = document.body ? document.body.innerText : '';
                                    bodyText = bodyText.replace(/\s{3,}/g, '\n\n').trim();
                                    return 'TIER:innerText\n' + bodyText.substring(0, 4000);
                                })()
                                """.trimIndent()
                            ) { result ->
                                Log.d(tag, "JS extraction complete result_len=${result?.length ?: 0}")
                                resultHolder[0] = result
                                latch.countDown()
                            }
                        }, 3000)
                    }

                    override fun onReceivedError(
                        view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?
                    ) {
                        Log.d(tag, "onReceivedError url=${request?.url} code=${error?.errorCode} desc=${error?.description}")
                        super.onReceivedError(view, request, error)
                    }
                }

                Log.d(tag, "loading search URL")
                webView.loadUrl(searchUrl)
            } catch (e: Exception) {
                Log.e(tag, "exception during WebView setup: ${e.message}", e)
                errorHolder[0] = e.message
                latch.countDown()
            }
        }

        val completed = latch.await(30, TimeUnit.SECONDS)
        val durationMs = System.currentTimeMillis() - startMs
        Log.d(tag, "latch completed=$completed duration_ms=$durationMs pageFinished=${pageFinished[0]}")
        // Cleanup WebView on main thread (destroy always)
        Handler(Looper.getMainLooper()).post {
            try {
                val wv = webViewHolder[0]
                val cont = containerHolder[0]
                try {
                    val wm = app.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                    if (cont != null && cont.parent != null) wm.removeView(cont)
                } catch (e: Exception) { Log.d(tag, "removeView failed: ${e.message}") }
                try {
                    wv?.let { v -> (v.parent as? ViewGroup)?.removeView(v); v.removeAllViews(); v.destroy() }
                    Log.d(tag, "WebView destroyed")
                } catch (e: Exception) { Log.d(tag, "destroy failed: ${e.message}") }
            } catch (e: Exception) {
                Log.d(tag, "cleanup exception: ${e.message}")
            }
        }

        if (!completed) {
            val msg = "Error: search timed out after 30s (query_len=${query.length})"
            Log.e(tag, msg)
            Telemetry.log(app, "web_search_error", mapOf("error" to "timeout_30s", "duration_ms" to durationMs.toString()))
            return msg
        }
        if (errorHolder[0] != null) {
            Log.e(tag, "errorHolder=${errorHolder[0]}")
            Telemetry.log(app, "web_search_error", mapOf("error" to (errorHolder[0] ?: "unknown"), "duration_ms" to durationMs.toString()))
            return "Error: ${errorHolder[0]}"
        }

        val raw = resultHolder[0] ?: run {
            Telemetry.log(app, "web_search_error", mapOf("error" to "null_result", "duration_ms" to durationMs.toString()))
            return "No results found"
        }
        val cleaned = decodeJsString(raw)
        val tier = cleaned.lineSequence().firstOrNull()?.takeIf { it.startsWith("TIER:") } ?: "unknown"
        val count = if (cleaned.contains("URL:")) cleaned.split("URL:").size - 1 else 0
        Log.d(tag, "done tier=$tier result_count=$count cleaned_len=${cleaned.length} duration_ms=$durationMs")
        Telemetry.log(app, "web_search_done", mapOf("tier" to tier, "result_count" to count.toString(), "duration_ms" to durationMs.toString(), "query_length" to query.length.toString()))
        if (cleaned.isBlank() || cleaned == "null") {
            Telemetry.log(app, "web_search_error", mapOf("error" to "empty_result", "duration_ms" to durationMs.toString()))
            return "No results found for: $query"
        }
        return cleaned
    }

    private fun decodeJsString(raw: String): String {
        return if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length > 2) {
            raw.substring(1, raw.length - 1)
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\u003D", "=")
                .replace("\\\\", "\\")
        } else {
            raw
        }
    }
}

/**
 * Ephemeral WebView-based page fetcher. Loads a URL, waits for render,
 * extracts readable text from the page body.
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

    override fun execute(args: Map<String, String>): String {
        val url = args["url"] ?: return "Error: no url"
        val maxChars = (args["max_chars"]?.toIntOrNull() ?: 4000).coerceIn(500, 20000)
        val app = com.sutliff.pocket.AgentApp.instance ?: run {
            Log.e(tag, "execute failed: no app context")
            return "Error: no app context"
        }
        val startMs = System.currentTimeMillis()
        Log.d(tag, "start url=$url max_chars=$maxChars")
        Telemetry.log(app, "web_fetch_start", mapOf("url_length" to url.length.toString(), "max_chars" to maxChars.toString()))

        val latch = CountDownLatch(1)
        val resultHolder = arrayOf<String?>(null)
        val errorHolder = arrayOf<String?>(null)
        val webViewHolder = arrayOf<WebView?>(null)
        val containerHolder = arrayOf<FrameLayout?>(null)

        Handler(Looper.getMainLooper()).post {
            var webView: WebView? = null
            var container: FrameLayout? = null
            try {
                container = FrameLayout(app).apply {
                    layoutParams = FrameLayout.LayoutParams(1, 1)
                    visibility = android.view.View.VISIBLE
                }
                containerHolder[0] = container
                webView = WebView(app).apply {
                    layoutParams = ViewGroup.LayoutParams(1, 1)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.blockNetworkImage = true
                    onResume()
                    resumeTimers()
                }
                webViewHolder[0] = webView
                container.addView(webView)
                try {
                    val wm = app.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                    val params = android.view.WindowManager.LayoutParams(
                        1, 1,
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        android.graphics.PixelFormat.TRANSLUCENT
                    )
                    wm.addView(container, params)
                    Log.d(tag, "WebView attached to WindowManager (1x1)")
                } catch (e: Exception) {
                    Log.d(tag, "WindowManager attach failed (detached container): ${e.message}")
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        Log.d(tag, "onPageFinished url=$finishedUrl")
                        Handler(Looper.getMainLooper()).postDelayed({
                            Log.d(tag, "running fetch extraction JS (post-page delay 2000ms)")
                            view?.evaluateJavascript(
                                """
                                (function() {
                                    var text = document.body ? document.body.innerText : '';
                                    if (!text || text.trim().length === 0) {
                                        text = document.documentElement ? document.documentElement.innerText : '';
                                    }
                                    text = text.replace(/\s{3,}/g, '\n\n');
                                    return text.substring(0, $maxChars);
                                })()
                                """.trimIndent()
                            ) { result ->
                                Log.d(tag, "JS extraction complete result_len=${result?.length ?: 0}")
                                resultHolder[0] = result
                                latch.countDown()
                            }
                        }, 2000)
                    }

                    override fun onReceivedError(
                        view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?
                    ) {
                        Log.d(tag, "onReceivedError url=${request?.url} code=${error?.errorCode} desc=${error?.description}")
                        super.onReceivedError(view, request, error)
                    }
                }

                Log.d(tag, "loading URL")
                webView.loadUrl(url)
            } catch (e: Exception) {
                Log.e(tag, "exception during WebView setup: ${e.message}", e)
                errorHolder[0] = e.message
                latch.countDown()
            }
        }

        val completed = latch.await(30, TimeUnit.SECONDS)
        val durationMs = System.currentTimeMillis() - startMs
        Log.d(tag, "latch completed=$completed duration_ms=$durationMs")
        Handler(Looper.getMainLooper()).post {
            try {
                val wv = webViewHolder[0]
                val cont = containerHolder[0]
                try {
                    val wm = app.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                    if (cont != null && cont.parent != null) wm.removeView(cont)
                } catch (e: Exception) { Log.d(tag, "removeView failed: ${e.message}") }
                try {
                    wv?.let { v -> (v.parent as? ViewGroup)?.removeView(v); v.removeAllViews(); v.destroy() }
                    Log.d(tag, "WebView destroyed")
                } catch (e: Exception) { Log.d(tag, "destroy failed: ${e.message}") }
            } catch (e: Exception) {
                Log.d(tag, "cleanup exception: ${e.message}")
            }
        }

        if (!completed) {
            val msg = "Error: fetch timed out after 30s for: $url"
            Log.e(tag, msg)
            Telemetry.log(app, "web_fetch_error", mapOf("error" to "timeout_30s", "duration_ms" to durationMs.toString()))
            return msg
        }
        if (errorHolder[0] != null) {
            Log.e(tag, "errorHolder=${errorHolder[0]}")
            Telemetry.log(app, "web_fetch_error", mapOf("error" to (errorHolder[0] ?: "unknown"), "duration_ms" to durationMs.toString()))
            return "Error: ${errorHolder[0]}"
        }

        val raw = resultHolder[0] ?: run {
            Telemetry.log(app, "web_fetch_error", mapOf("error" to "null_result", "duration_ms" to durationMs.toString()))
            return "No content"
        }
        val cleaned = if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length > 2) {
            raw.substring(1, raw.length - 1)
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\\\", "\\")
        } else {
            raw
        }
        Log.d(tag, "done cleaned_len=${cleaned.length} duration_ms=$durationMs")
        Telemetry.log(app, "web_fetch_done", mapOf("result_length" to cleaned.length.toString(), "duration_ms" to durationMs.toString()))
        return if (cleaned.isBlank() || cleaned == "null") "No content extracted from: $url" else cleaned
    }
}
