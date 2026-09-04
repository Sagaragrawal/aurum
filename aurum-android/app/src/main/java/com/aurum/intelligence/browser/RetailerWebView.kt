package com.aurum.intelligence.browser

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebSettings
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.net.http.SslError
import com.aurum.intelligence.data.RetailerUrlPolicy

object RetailerWebView {
    fun clearBrowserData(context: Context, onComplete: () -> Unit = {}) {
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            context.cacheDir.deleteRecursively()
            context.codeCacheDir.deleteRecursively()
            context.externalCacheDir?.deleteRecursively()
            onComplete()
        }
    }
    private data class NavigationTrace(
        val startedAt: Long,
        val onDiagnostic: (String, String) -> Unit,
        var lastProgress: Int = -1,
        var lastBoundsSummary: String? = null,
        var loadUrlCalledToken: String? = null,
    )
    private val localHosts = setOf(
        "localhost",
        "127.0.0.1",
    )

    @SuppressLint("SetJavaScriptEnabled")
    fun create(
        context: Context,
        retailer: Retailer? = null,
        onPageReady: (WebView, String) -> Unit,
        onError: (String) -> Unit,
        onDiagnostic: (severity: String, message: String) -> Unit = { _, _ -> },
        onPageLifecycle: (stage: String, view: WebView, url: String) -> Unit = { _, _, _ -> },
        onGeolocationPermissionRequest: ((String, GeolocationPermissions.Callback) -> Unit)? = null,
        deferMainFrameHttpErrors: Boolean = false,
    ): WebView = WebView(context).apply {
        val reportedConsoleDiagnostics = mutableSetOf<String>()
        setTag(NavigationTrace(0, onDiagnostic))
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        if (!RetailerBrowserPolicy.usesNativeUserAgent(retailer)) {
            settings.userAgentString = CHROME_DESKTOP_USER_AGENT
        }
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        setInitialScale(0)
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        // Temporary black-overlay diagnostics (item 3/9 of the 4.9.13 follow-up): log measured bounds,
        // visibility and attachment only when they actually change, so a covered-Compose regression is
        // diagnosable from logs alone before redesigning the host.
        addOnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
            reportBoundsIfChanged(view as WebView, left, top, right, bottom)
        }
        addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: android.view.View) = reportBoundsIfChanged(view as WebView, view.left, view.top, view.right, view.bottom)
            override fun onViewDetachedFromWindow(view: android.view.View) {
                val trace = view.tag as? NavigationTrace ?: return
                trace.onDiagnostic("info", stage(trace, "WEBVIEW_ATTACHED false"))
            }
        })
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                val text = message.message()
                if (isKnownRetailerNoise(text)) return true
                val bridgeDiagnostic = text.startsWith("[Aurum Bridge]")
                val ajioDiagnostic = text.startsWith("[Aurum AJIO]") || text.startsWith("[Aurum AJIO Master]")
                if (bridgeDiagnostic || ajioDiagnostic || message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    val diagnosticKey = "${message.sourceId()}:${message.lineNumber()}:$text"
                    if (!reportedConsoleDiagnostics.add(diagnosticKey)) return true
                    onDiagnostic(
                        if (bridgeDiagnostic || message.messageLevel() != ConsoleMessage.MessageLevel.ERROR) "info" else "error",
                        "Console [${retailer?.name ?: "browser"}]: $text",
                    )
                }
                return true
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                val host = runCatching { java.net.URI(origin).host }.getOrNull()
                if (RetailerUrlPolicy.isAllowedRetailerHost(host)) {
                    onGeolocationPermissionRequest?.invoke(origin, callback) ?: callback.invoke(origin, true, false)
                } else {
                    callback.invoke(origin, false, false)
                }
            }
        }
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                reportedConsoleDiagnostics.clear()
                onPageLifecycle("onPageStarted", view, url)
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                onPageLifecycle("onPageCommitVisible", view, url)
                super.onPageCommitVisible(view, url)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                val blocked = !isAllowedHost(request.url.host)
                if (blocked && request.isForMainFrame) onError("Blocked navigation to ${request.url.host ?: "unknown host"}")
                return blocked
            }

            override fun onPageFinished(view: WebView, url: String) {
                (view.tag as? NavigationTrace)?.let { trace ->
                    trace.onDiagnostic("info", "Page loaded: ${displayUrl(android.net.Uri.parse(url))}")
                    reportReadOnlyPageDiagnostics(view, trace, "finish")
                }
                onPageLifecycle("onPageFinished", view, url)
                if (runCatching { isAllowedHost(java.net.URI(url).host) }.getOrDefault(false)) {
                    onPageReady(view, url)
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val cleartextHost = request.url.host
                if (request.url.scheme == "http" && cleartextHost !in setOf("localhost", "127.0.0.1")) {
                    return WebResourceResponse("text/plain", "utf-8", 403, "Cleartext blocked", emptyMap(), null)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    val trace = view.tag as? NavigationTrace
                    val message = "Main frame failed ${displayUrl(request.url)}: ${error.description} (code ${error.errorCode})"
                    onDiagnostic("error", message)
                    onError(message)
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) {
                    val message = "Main frame HTTP ${response.statusCode} ${response.reasonPhrase}: ${displayUrl(request.url)}"
                    onDiagnostic("error", message)
                    if (!deferMainFrameHttpErrors) onError(message)
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                val message = "SSL error ${error.primaryError} for ${error.url}; navigation cancelled"
                onDiagnostic("error", message)
                onError(message)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                val text = if (detail.didCrash()) {
                    "Retailer renderer crashed (priority ${detail.rendererPriorityAtExit()}); data was preserved"
                } else {
                    "Retailer renderer was reclaimed (priority ${detail.rendererPriorityAtExit()}); data was preserved"
                }
                onDiagnostic("error", text)
                onError(text)
                view.destroy()
                return true
            }
        }
    }

    private fun reportBoundsIfChanged(view: WebView, left: Int, top: Int, right: Int, bottom: Int) {
        // Suppress bounds logging to prevent spam in logcat.
    }

    /**
     * Starts a navigation and returns the single authoritative result.
     */
    fun navigate(webView: WebView, url: String): NavigationResult {
        val diagnostic = (webView.tag as? NavigationTrace)?.onDiagnostic ?: { _: String, _: String -> }
        val newTrace = NavigationTrace(android.os.SystemClock.elapsedRealtime(), diagnostic)
        webView.tag = newTrace
        diagnostic("info", "Navigating to ${displayUrl(android.net.Uri.parse(url))}")
        return try {
            webView.loadUrl(url)
            val token = NavigationResult.token(url, System.nanoTime())
            newTrace.loadUrlCalledToken = token
            NavigationResult.LoadStarted(token)
        } catch (e: Exception) {
            val reason = e.message ?: "unknown error"
            diagnostic("error", "Navigation failed: $reason")
            NavigationResult.LoadFailed(reason)
        }
    }

    /** Token recorded on the WebView itself, for watchdog cross-checks against the caller's copy. */
    fun currentNavigationToken(webView: WebView): String? = (webView.tag as? NavigationTrace)?.loadUrlCalledToken

    private fun reportReadOnlyPageDiagnostics(webView: WebView, trace: NavigationTrace, lifecycle: String) {
        if (lifecycle != "finish") return
        webView.evaluateJavascript(
            """
            (function () {
                var body = String(document.body && document.body.innerText || '').replace(/\s+/g, ' ').trim();
                var blocked = /access denied|request blocked|blocked due to security reasons|captcha|you don.t have permission/i.test(body);
                return JSON.stringify({
                    title: document.title || '',
                    blocked: blocked
                });
            })()
            """.trimIndent(),
        ) { value ->
            runCatching {
                val decoded = org.json.JSONTokener(value).nextValue()
                val json = if (decoded is org.json.JSONObject) decoded else org.json.JSONObject(value.toString())
                val title = json.optString("title")
                val blocked = json.optBoolean("blocked")
                if (blocked) {
                    trace.onDiagnostic("warning", "Page blocked (access denied): \"$title\"")
                }
            }
        }
    }

    private const val CHROME_DESKTOP_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"

    private fun displayUrl(url: android.net.Uri): String = "${url.host ?: "unknown"}${url.path.orEmpty()}"

    private fun displaySource(source: String): String = runCatching {
        java.net.URI(source).let { "${it.host ?: "page"}${it.path.orEmpty()}" }
    }.getOrDefault(source.take(160))

    private fun isKnownRetailerNoise(message: String): Boolean =
        message.contains("pushData is not defined", ignoreCase = true) ||
            message.contains("$ is not defined", ignoreCase = true) ||
            message.contains("jQuery is not defined", ignoreCase = true) ||
            message.contains("reading 'config'", ignoreCase = true) ||
            message.contains("reading 'val'", ignoreCase = true) ||
            message.contains("setting 'processingQueue'", ignoreCase = true) ||
            message.contains("Failed to fetch", ignoreCase = true) ||
            message.contains("DhPixel", ignoreCase = true) ||
            message.contains("Handing cache handler", ignoreCase = true)

    private fun elapsed(trace: NavigationTrace): Long = android.os.SystemClock.elapsedRealtime() - trace.startedAt

    private fun stage(trace: NavigationTrace, label: String): String = "+${elapsed(trace)}ms $label"

    private fun isAllowedHost(host: String?): Boolean = host in localHosts ||
        RetailerUrlPolicy.isAllowedRetailerHost(host) ||
        host == "tanishq.co.in" || host?.endsWith(".tanishq.co.in") == true

}