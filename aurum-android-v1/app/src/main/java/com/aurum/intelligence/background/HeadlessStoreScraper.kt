package com.aurum.intelligence.background

import android.content.Context
import android.webkit.WebView
import com.aurum.intelligence.data.StoreAdapter
import com.aurum.intelligence.browser.MasterScriptAssetLoader
import com.aurum.intelligence.browser.MasterScripts
import com.aurum.intelligence.browser.RetailerWebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object HeadlessStoreScraper {

    suspend fun scrapeStore(
        context: Context,
        adapter: StoreAdapter,
        pincode: String,
        sessionId: String,
    ) = withContext(Dispatchers.Main) {
        val urls = adapter.getSearchUrls(pincode)
        if (urls.isEmpty()) return@withContext
        
        val webView = RetailerWebView.create(
            context = context,
            retailer = MasterScripts.all.firstOrNull { it.retailer.name.equals(adapter.displayName, ignoreCase = true) }?.retailer,
            pincode = pincode,
            latitude = 12.9716, // TODO: Add real latitude to HeadlessStoreScraper args
            longitude = 77.5946,
            onPageReady = { _, _ -> },
            onError = { _ -> }
        )

        val master = MasterScripts.all.firstOrNull { it.retailer.name.equals(adapter.displayName, ignoreCase = true) } ?: return@withContext

        try {
            for (url in urls) {
                webView.loadUrl(url)
                
                // State-driven approach: Wait for network to settle, up to 10 seconds.
                delay(3000)

                val scriptContent = MasterScriptAssetLoader.load(context, master.assetName).getOrNull()
                if (scriptContent != null) {
                    val combinedSource = buildCombinedScript(scriptContent, sessionId)
                    
                    // Inject pincode cookies
                    webView.evaluateJavascript(
                        com.aurum.intelligence.data.LocationHelper.buildPincodeInjectionScript(pincode, 12.9716, 77.5946), null
                    )
                    
                    webView.evaluateJavascript(combinedSource, null)
                    
                    // Wait for completion via JSON.stringify
                    withTimeoutOrNull(60_000L) {
                        while (true) {
                            val result = evaluateJavascriptValue(webView, "JSON.stringify(window.__aurumProductFetchResult || null)")
                            if (result != null && result != "null" && result.isNotBlank()) {
                                break
                            }
                            delay(1000)
                        }
                    }
                }
            }
        } finally {
            RetailerWebView.safeDestroy(webView)
        }
    }

    private suspend fun evaluateJavascriptValue(webView: WebView, script: String): String? = suspendCoroutine { cont ->
        webView.evaluateJavascript(script) { value ->
            if (value == "null") {
                cont.resume(null)
            } else {
                cont.resume(value?.trim('"'))
            }
        }
    }

    private fun buildCombinedScript(source: String, sessionId: String): String {
        return """
        (function() {
            if (window.__aurumMasterInjected) return;
            window.__aurumMasterInjected = true;
            window.__aurumProductFetchResult = null;
            
            $source
            
            const originalPush = window.pushData;
            window.pushData = function(payload) {
                if (originalPush) originalPush(payload);
                fetch('http://localhost:8788/api/browser-bridge/products', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'X-Session-ID': '$sessionId'
                    },
                    body: JSON.stringify(payload)
                }).then(function(res) {
                    window.__aurumProductFetchResult = "done";
                }).catch(function(e) {
                    window.__aurumProductFetchResult = "error";
                });
            };
        })();
        """.trimIndent()
    }
}
