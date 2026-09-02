package com.aurum.intelligence.browser

/**
 * Single authoritative outcome of [RetailerWebView.navigate].
 *
 * The token is generated only after the real `WebView.loadUrl(url)` call returns without throwing,
 * and it is the same value stored on the WebView's navigation trace. Callers must store the token
 * returned here instead of inventing their own placeholder, so the navigation-start watchdog
 * observes the actual navigation rather than its own bookkeeping.
 */
sealed class NavigationResult {
    data class LoadStarted(val token: String) : NavigationResult()

    data class LoadFailed(val reason: String) : NavigationResult()

    companion object {
        fun token(url: String, nanoTime: Long): String = "$url:$nanoTime"
    }
}
