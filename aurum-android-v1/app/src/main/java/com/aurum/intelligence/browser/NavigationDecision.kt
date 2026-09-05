package com.aurum.intelligence.browser

/**
 * Pure navigation-start decision, extracted from [com.aurum.intelligence.ui.BrowserRefreshScreen]
 * so the guard/idempotency logic that sits between NAV_REQUEST and LOAD_URL_CALLED can be unit
 * tested without a real WebView/Compose runtime.
 *
 * Navigation identity is (urlIndex, attempt, webViewGeneration): a new WebView created after
 * rotation/recreation must be navigated even if urlIndex/attempt are unchanged, but a recomposition
 * of the same WebView for the same attempt must not duplicate navigation.
 */
object NavigationDecision {
    data class Attempt(val urlIndex: Int, val attemptNumber: Int, val webViewGeneration: Int) {
        fun key(): String = "$urlIndex:$attemptNumber:$webViewGeneration"
    }

    sealed class Decision {
        object Navigate : Decision()
        object AlreadyHandled : Decision()
        data class Reject(val reason: String) : Decision()
    }

    fun decide(
        hasWebView: Boolean,
        webViewIndex: Int,
        requestedIndex: Int,
        attempt: Attempt,
        lastHandledKey: String?,
        sessionCancelled: Boolean,
        assetReady: Boolean,
        assetError: String?,
    ): Decision {
        if (sessionCancelled) return Decision.Reject("session_cancelled")
        if (!hasWebView) return Decision.Reject("no_webview")
        if (webViewIndex != requestedIndex) return Decision.Reject("index_mismatch")
        if (lastHandledKey == attempt.key()) return Decision.AlreadyHandled
        if (!assetReady) return Decision.Reject(if (assetError != null) "asset_missing" else "asset_not_ready")
        return Decision.Navigate
    }
}
