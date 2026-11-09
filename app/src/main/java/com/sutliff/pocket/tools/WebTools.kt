package com.sutliff.pocket.tools

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
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
 */
class WebSearchTool : Tool {
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
        val maxResults = args["max_results"]?.toIntOrNull() ?: 8
        val app = com.sutliff.pocket.AgentApp.instance ?: return "Error: no app context"

        val latch = CountDownLatch(1)
        val resultHolder = arrayOf<String?>(null)
        val errorHolder = arrayOf<String?>(null)

        Handler(Looper.getMainLooper()).post {
            var webView: WebView? = null
            try {
                webView = WebView(app).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.blockNetworkImage = true // faster, we only need text
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Give JS a moment to render results
                        Handler(Looper.getMainLooper()).postDelayed({
                            view?.evaluateJavascript(
                                """
                                (function() {
                                    var results = [];
                                    var items = document.querySelectorAll('div.g');
                                    for (var i = 0; i < Math.min(items.length, $maxResults); i++) {
                                        var a = items[i].querySelector('a');
                                        var h3 = items[i].querySelector('h3');
                                        var snippet = items[i].querySelector('.VwiC3b, .s3v9rd, [data-sncf], .st, .IsZvec');
                                        results.push(
                                            (i+1) + '. ' + (h3 ? h3.innerText.trim() : 'No title') + '\n' +
                                            '   URL: ' + (a ? a.href : 'N/A') + '\n' +
                                            '   ' + (snippet ? snippet.innerText.trim() : '')
                                        );
                                    }
                                    if (results.length === 0) {
                                        // Fallback: try broader selectors
                                        var links = document.querySelectorAll('a[href^="http"]');
                                        var seen = {};
                                        for (var j = 0; j < links.length && results.length < $maxResults; j++) {
                                            var href = links[j].href;
                                            if (href.indexOf('google.com') !== -1) continue;
                                            if (seen[href]) continue;
                                            seen[href] = true;
                                            var text = links[j].innerText.trim();
                                            if (text.length > 5) {
                                                results.push((results.length+1) + '. ' + text + '\n   URL: ' + href);
                                            }
                                        }
                                    }
                                    return results.join('\n\n');
                                })()
                                """.trimIndent()
                            ) { result ->
                                resultHolder[0] = result
                                latch.countDown()
                            }
                        }, 2000)
                    }
                }

                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                webView.loadUrl("https://www.google.com/search?q=$encodedQuery&hl=en")
            } catch (e: Exception) {
                errorHolder[0] = e.message
                latch.countDown()
            }
        }

        val completed = latch.await(15, TimeUnit.SECONDS)
        if (!completed) return "Error: search timed out after 15s"

        // Cleanup WebView on main thread
        Handler(Looper.getMainLooper()).post {
            // WebView cleanup handled by GC; explicit destroy not needed for ephemeral
        }

        if (errorHolder[0] != null) return "Error: ${errorHolder[0]}"

        val raw = resultHolder[0] ?: return "No results found"
        // evaluateJavascript wraps string results in quotes
        val cleaned = if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length > 2) {
            raw.substring(1, raw.length - 1)
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        } else {
            raw
        }
        return if (cleaned.isBlank() || cleaned == "null") "No results found for: $query" else cleaned
    }
}

/**
 * Ephemeral WebView-based page fetcher. Loads a URL, waits for render,
 * extracts readable text from the page body.
 */
class WebFetchTool : Tool {
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
        val maxChars = args["max_chars"]?.toIntOrNull() ?: 4000
        val app = com.sutliff.pocket.AgentApp.instance ?: return "Error: no app context"

        val latch = CountDownLatch(1)
        val resultHolder = arrayOf<String?>(null)
        val errorHolder = arrayOf<String?>(null)

        Handler(Looper.getMainLooper()).post {
            var webView: WebView? = null
            try {
                webView = WebView(app).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.blockNetworkImage = true
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            view?.evaluateJavascript(
                                """
                                (function() {
                                    var text = document.body ? document.body.innerText : '';
                                    // Collapse excessive whitespace
                                    text = text.replace(/\s{3,}/g, '\n\n');
                                    return text.substring(0, $maxChars);
                                })()
                                """.trimIndent()
                            ) { result ->
                                resultHolder[0] = result
                                latch.countDown()
                            }
                        }, 1500)
                    }
                }

                webView.loadUrl(url)
            } catch (e: Exception) {
                errorHolder[0] = e.message
                latch.countDown()
            }
        }

        val completed = latch.await(20, TimeUnit.SECONDS)
        if (!completed) return "Error: fetch timed out after 20s"

        if (errorHolder[0] != null) return "Error: ${errorHolder[0]}"

        val raw = resultHolder[0] ?: return "No content"
        val cleaned = if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length > 2) {
            raw.substring(1, raw.length - 1)
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        } else {
            raw
        }
        return if (cleaned.isBlank() || cleaned == "null") "No content extracted from: $url" else cleaned
    }
}
