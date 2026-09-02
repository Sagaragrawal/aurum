package com.aurum.intelligence.browser

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads packaged retailer master-script assets independently of any navigation attempt.
 *
 * This exists so the navigation decision (guard + [RetailerWebView.navigate]) never has a
 * suspension point between deciding to navigate and actually calling loadUrl: asset bytes are
 * fetched ahead of time and cached here, so the in-composition navigation effect can read them
 * synchronously.
 */
object MasterScriptAssetLoader {
    private val cache = ConcurrentHashMap<String, Result<String>>()

    suspend fun load(context: Context, assetName: String): Result<String> {
        cache[assetName]?.let { return it }
        val result = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(assetName).bufferedReader().use { it.readText() } }
        }
        if (result.isSuccess) cache[assetName] = result
        return result
    }
}
