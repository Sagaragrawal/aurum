package com.aurum.intelligence.background

import android.content.Context
import android.util.Log
import android.webkit.WebView
import com.aurum.intelligence.browser.RetailerWebView
import com.aurum.intelligence.data.AurumDatabase
import com.aurum.intelligence.data.StoreRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.json.JSONObject

object ProductDetailScraper {
    private const val TAG = "ProductDetailScraper"

    suspend fun scrapePdp(
        context: Context,
        database: AurumDatabase,
        pincode: String,
        latitude: Double?,
        longitude: Double?
    ) = withContext(Dispatchers.Main) {
        val now = System.currentTimeMillis()
        val staleThreshold = now - (12 * 60 * 60 * 1000) // 12 hours
        
        // Find top 10 oldest live products
        val productsToUpdate = withContext(Dispatchers.IO) {
            database.dao().allProducts()
                .filter { it.status == "live" && it.checkedAt < staleThreshold }
                .sortedBy { it.checkedAt }
                .take(10)
        }

        if (productsToUpdate.isEmpty()) {
            Log.i(TAG, "No products need PDP refresh right now.")
            return@withContext
        }

        val webView = RetailerWebView.create(
            context = context,
            retailer = null,
            onPageReady = { _, _ -> },
            onError = { _ -> },
            latitude = latitude,
            longitude = longitude
        )

        try {
            for (product in productsToUpdate) {
                val adapter = StoreRegistry.getAll().find { it.storeName == product.store } ?: continue
                val jsExtractor = adapter.getPdpJsExtractor() ?: continue

                Log.i(TAG, "Navigating to PDP: ")
                webView.loadUrl(product.canonicalUrl)
                
                // Wait for basic navigation
                delay(3500)

                val resultJson = evaluateJavascriptValue(webView, jsExtractor)
                
                if (resultJson != null && resultJson != "null") {
                    try {
                        val json = JSONObject(resultJson)
                        val price = if (json.isNull("price")) null else json.getDouble("price")
                        val available = json.getBoolean("available")
                        
                        Log.i(TAG, "PDP Scraped []: price=, available=")
                        
                        withContext(Dispatchers.IO) {
                            database.dao().upsertProduct(
                                product.copy(
                                    price = price ?: product.price,
                                    status = if (available) "live" else "unavailable",
                                    checkedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse PDP JSON for ", e)
                    }
                } else {
                    Log.w(TAG, "Failed to evaluate PDP extractor on ")
                }
                
                delay(1000)
            }
        } finally {
            webView.destroy()
        }
    }

    private suspend fun evaluateJavascriptValue(webView: WebView, script: String): String? =
        suspendCancellableCoroutine { continuation ->
            webView.evaluateJavascript(script) { value ->
                if (continuation.isActive) {
                    continuation.resume(if (value == "null") null else org.json.JSONTokener(value).nextValue() as? String)
                }
            }
        }
}
