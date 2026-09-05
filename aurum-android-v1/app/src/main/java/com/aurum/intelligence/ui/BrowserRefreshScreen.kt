package com.aurum.intelligence.ui

import com.aurum.intelligence.browser.AjioRequestPacing
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aurum.intelligence.browser.AjioRefreshPolicy
import com.aurum.intelligence.browser.AjioReadiness
import com.aurum.intelligence.browser.BrowserViewport
import com.aurum.intelligence.browser.MasterScript
import com.aurum.intelligence.browser.MasterScriptAssetLoader
import com.aurum.intelligence.browser.MasterScripts
import com.aurum.intelligence.browser.MyntraReadiness
import com.aurum.intelligence.browser.NavigationDecision
import com.aurum.intelligence.browser.NavigationResult
import com.aurum.intelligence.browser.RetailerWebView
import com.aurum.intelligence.browser.RetailerReadiness
import com.aurum.intelligence.data.RefreshActivityLogEntity
import com.aurum.intelligence.data.RefreshLogSeverity
import com.aurum.intelligence.data.RefreshRequest
import com.aurum.intelligence.data.MissingCatalogueProductResult
import com.aurum.intelligence.data.ProductFetchResponse
import com.aurum.intelligence.data.ProductEntity
import com.aurum.intelligence.data.ProductLookup
import java.net.URI
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun BrowserRefreshScreen(
    mergeEvents: Flow<com.aurum.intelligence.data.BridgeMergeEvent>,
    request: RefreshRequest = RefreshRequest.all(),
    products: List<ProductEntity>,
    showBrowser: Boolean,
    pincode: String = "560048",
    latitude: Double? = null,
    longitude: Double? = null,
    logs: List<RefreshActivityLogEntity> = emptyList(),
    onLog: (severity: RefreshLogSeverity, store: String?, message: String) -> Unit = { _, _, _ -> },
    onClearLogs: () -> Unit = {},
    onSessionStart: suspend (String, Set<String>, Set<String>) -> Unit,
    onSessionEnd: (String) -> Unit,
    onCatalogueMerged: suspend (String, Set<String>, Set<String>, suspend (String) -> ProductFetchResponse?, suspend (Int, Int, ProductEntity) -> Unit) -> MissingCatalogueProductResult = { _, _, _, _, _ -> MissingCatalogueProductResult(0, 0, 0, 0) },
    onProductRefresh: suspend (String, suspend (String) -> ProductFetchResponse?) -> ProductLookup = { _, _ -> ProductLookup.Unknown },
    onFinished: () -> Unit = {},
) {
    val context = LocalContext.current
    var pendingGeolocationOrigin by remember { mutableStateOf<String?>(null) }
    var pendingGeolocationCallback by remember { mutableStateOf<GeolocationPermissions.Callback?>(null) }
    val geolocationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        pendingGeolocationCallback?.invoke(pendingGeolocationOrigin.orEmpty(), granted, false)
        pendingGeolocationOrigin = null
        pendingGeolocationCallback = null
    }
    val onFlipkartGeolocationRequest: (String, GeolocationPermissions.Callback) -> Unit = { origin, callback ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            callback.invoke(origin, true, false)
        } else {
            pendingGeolocationCallback?.invoke(pendingGeolocationOrigin.orEmpty(), false, false)
            pendingGeolocationOrigin = origin
            pendingGeolocationCallback = callback
            geolocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val targetScripts = remember(request) {
        val availableStores = MasterScripts.all.map(MasterScript::storeName).toSet()
        val targetStores = request.targetStores(availableStores)
        MasterScripts.all.filter { it.storeName in targetStores }.map { master ->
            if (request.scope == com.aurum.intelligence.data.RefreshScope.StoreRetry) {
                val product = products.firstOrNull { it.id in request.productIds && it.store == master.storeName }
                master.copy(urls = listOfNotNull(product?.canonicalUrl))
            } else {
                master
            }
        }.filter { it.urls.isNotEmpty() }
    }
    val sessionId = rememberSaveable(request) { UUID.randomUUID().toString() }
    var sessionReady by remember(sessionId) { mutableStateOf(false) }
    var summaries by remember(request) { mutableStateOf(emptyMap<String, StoreCompletionSummary>()) }
    val activeScripts = if (sessionReady) targetScripts.filterNot { it.storeName in summaries }.take(MAX_CONCURRENT_STORES) else emptyList()
    var selectedBrowser by remember(request) { mutableStateOf(targetScripts.firstOrNull()?.storeName) }
    var finishedReported by remember(request) { mutableStateOf(false) }
    val refreshStartedAtElapsed = remember(sessionId) { android.os.SystemClock.elapsedRealtime() }
    val completed = summaries.size
    val succeeded = summaries.values.count { it.state == "complete" }
    val needsAttention = summaries.size - succeeded

    LaunchedEffect(request) {
        val targets = targetScripts.joinToString { it.storeName }
        onLog(
            if (targetScripts.isEmpty()) RefreshLogSeverity.Warning else RefreshLogSeverity.Info,
            null,
            if (targetScripts.isEmpty()) "No visible products matched ${request.scope.name}" else "${request.scope.name} refresh targets: $targets",
        )
    }
    LaunchedEffect(sessionId, targetScripts) {
        onSessionStart(sessionId, targetScripts.map(MasterScript::storeName).toSet(), request.productIds)
        sessionReady = true
        onLog(
            RefreshLogSeverity.Info,
            null,
            "Product refresh session=${sessionId.take(8)} registered for ${targetScripts.size} stores; running in background, Browser tab is optional",
        )
        targetScripts.forEach { script ->
            if (!com.aurum.intelligence.data.StoreCircuitBreaker.canExecute(script.storeName)) {
                val status = com.aurum.intelligence.data.StoreCircuitBreaker.status(script.storeName)
                onLog(
                    RefreshLogSeverity.Warning,
                    script.storeName,
                    "Circuit breaker OPEN: ${status.reason ?: "Bot blocked"}. Cooldown remaining: ${status.cooldownRemainingMs / 1000}s",
                )
                summaries = summaries + (script.storeName to StoreCompletionSummary(script.storeName, "blocked", 0, "Circuit breaker OPEN: ${status.reason ?: "Bot blocked"}"))
            }
        }
        if (targetScripts.isNotEmpty()) {
            onLog(
                RefreshLogSeverity.Info,
                null,
                "Starting sub-10s Direct Parallel API Refresh across ${targetScripts.size} stores...",
            )
            // Trigger Direct Headless Coroutine Refresh Engine across all stores concurrently in parallel
            val app = context.applicationContext as? com.aurum.intelligence.AurumApplication
            if (app != null) {
                val directCoordinator = com.aurum.intelligence.data.DirectApiRefreshCoordinator(app.database)
                val directResult = directCoordinator.refreshAllStoresParallel(pincode = pincode, latitude = latitude, longitude = longitude) { storeSummary ->
                    if (storeSummary.success && storeSummary.validCount > 0) {
                        summaries = summaries + (storeSummary.store to StoreCompletionSummary(
                            store = storeSummary.store,
                            state = "completed",
                            received = storeSummary.validCount,
                            message = storeSummary.error ?: "Completed",
                        ))
                    }
                    onLog(
                        if (storeSummary.success) RefreshLogSeverity.Info else RefreshLogSeverity.Warning,
                        storeSummary.store,
                        "Direct API Store Refresh [${storeSummary.store}]: Discovered ${storeSummary.totalDiscovered}, Valid ${storeSummary.validCount} in ${storeSummary.durationMs}ms",
                    )
                }
                onLog(
                    RefreshLogSeverity.Info,
                    null,
                    "Direct API Parallel Refresh Completed in ${directResult.durationMs}ms: ${directResult.totalProductsValid}/${directResult.totalProductsDiscovered} products verified",
                )
            }
        }
        if (targetScripts.isEmpty()) {
            onSessionEnd(sessionId)
            onFinished()
        }
    }
    LaunchedEffect(activeScripts, selectedBrowser) {
        if (selectedBrowser !in activeScripts.map(MasterScript::storeName)) {
            selectedBrowser = activeScripts.firstOrNull()?.storeName
        }
    }
    // Explicit visibility/selection diagnostics required for B1/B2 phone verification.
    LaunchedEffect(showBrowser) {
        onLog(RefreshLogSeverity.Info, null, "Browser visibility changed: ${if (showBrowser) "visible" else "background"}")
    }
    LaunchedEffect(selectedBrowser, showBrowser) {
        if (showBrowser && selectedBrowser != null) {
            onLog(RefreshLogSeverity.Info, null, "Browser selected retailer: $selectedBrowser")
        }
    }
    // Periodic structured progress so a multi-minute background refresh does not look frozen; relaunches
    // (and logs immediately) whenever completed/active stores change, then heartbeats every 15s.
    LaunchedEffect(sessionId, completed, activeScripts) {
        if (targetScripts.isEmpty() || completed >= targetScripts.size) return@LaunchedEffect
        val activeNames = activeScripts.joinToString { it.storeName }.ifEmpty { "none" }
        while (true) {
            val elapsedSeconds = (android.os.SystemClock.elapsedRealtime() - refreshStartedAtElapsed) / 1_000
            onLog(
                RefreshLogSeverity.Info,
                null,
                "Progress: $completed/${targetScripts.size} stores finished | active=$activeNames | elapsed=${elapsedSeconds}s",
            )
            delay(15_000)
        }
    }
    LaunchedEffect(completed, targetScripts.size, summaries) {
        if (targetScripts.isNotEmpty() && completed == targetScripts.size && !finishedReported) {
            finishedReported = true
            onSessionEnd(sessionId)
            val outcomes = summaries.values.sortedBy(StoreCompletionSummary::store)
                .joinToString { "${it.store}=${it.state}(${it.received} unique)" }
            onLog(
                if (needsAttention == 0) RefreshLogSeverity.Info else RefreshLogSeverity.Warning,
                null,
                formatRefreshSummary(summaries.values, cancelledCount = 0),
            )
            onLog(
                if (needsAttention == 0) RefreshLogSeverity.Info else RefreshLogSeverity.Warning,
                null,
                "Product refresh session=${sessionId.take(8)} finished: updated=$succeeded, attention=$needsAttention, outcomes=$outcomes",
            )
            onFinished()
        }
    }

    Column(if (showBrowser) Modifier.fillMaxSize() else Modifier.size(1.dp)) {
        if (showBrowser) {
        LinearProgressIndicator(
            progress = { if (targetScripts.isEmpty()) 0f else completed.toFloat() / targetScripts.size },
            modifier = Modifier.fillMaxWidth(),
        )
        if (activeScripts.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                activeScripts.forEach { script ->
                    FilterChip(
                        selected = selectedBrowser == script.storeName,
                        onClick = { selectedBrowser = script.storeName },
                        label = { Text(script.retailer.name) },
                    )
                }
            }
        }
        }
        // Browser is the primary area: fills width, gets the remaining vertical space. Toggling
        // showBrowser/selectedBrowser only changes this Modifier and never appears in a
        // navigation-triggering key elsewhere in this file, so opening/switching Browser cannot
        // restart extraction.
        val forceAjioForegroundHost = AJIO_FOREGROUND_HOST_DIAGNOSTIC && activeScripts.any {
            it.retailer == com.aurum.intelligence.browser.Retailer.Ajio
        }
        Box(if ((showBrowser || forceAjioForegroundHost) && activeScripts.isNotEmpty()) Modifier.fillMaxWidth().weight(1f) else Modifier.size(1.dp)) {
            activeScripts.forEach { script ->
                key(script.storeName) {
                    StoreRefreshRunner(
                        master = script,
                        mergeEvents = mergeEvents,
                        sessionId = sessionId,
                        pincode = pincode,
                        latitude = latitude,
                        longitude = longitude,
                        visibleModifier = if (
                            (showBrowser && selectedBrowser == script.storeName) ||
                            (AJIO_FOREGROUND_HOST_DIAGNOSTIC && script.retailer == com.aurum.intelligence.browser.Retailer.Ajio)
                        ) Modifier.fillMaxSize() else null,
                        onLog = onLog,
                        onStatus = {},
                        onGeolocationPermissionRequest = onFlipkartGeolocationRequest,
                        onCatalogueMerged = onCatalogueMerged,
                        onProductRefresh = onProductRefresh,
                        directProduct = request.scope == com.aurum.intelligence.data.RefreshScope.StoreRetry,
                        directProductId = request.productIds.firstOrNull(),
                        productIds = if (request.scope == com.aurum.intelligence.data.RefreshScope.All) {
                            products.filter { it.store == script.storeName }.mapTo(linkedSetOf(), ProductEntity::id)
                        } else {
                            request.productIds
                        },
                        onComplete = { summary -> summaries = summaries + (summary.store to summary) },
                    )
                }
            }
        }
        if (showBrowser) {
        if (activeScripts.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No active retailer browser", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        RefreshActivityPanel(logs = logs, onClear = onClearLogs, initiallyExpanded = false)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(summaries.values.toList(), key = StoreCompletionSummary::store) { summary ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "${summary.store}: ${outcomeLabel(summary.state).replaceFirstChar(Char::uppercase)} | ${summary.received} received",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            if (summary.state == AjioRefreshPolicy.Blocked) {
                                "AJIO did not allow the embedded browser. Existing prices are preserved."
                            } else if (summary.received > 0 || summary.state == "complete" || summary.state == "partial") {
                                summary.message
                            } else {
                                "Could not update. Last known prices are still shown."
                            },
                            color = if (summary.received > 0 || summary.state == "complete") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (summary.state == AjioRefreshPolicy.Blocked) {
                            androidx.compose.material3.TextButton(onClick = {
                                val url = AjioRefreshPolicy.externalViewingUrl(
                                    targetScripts.first { it.storeName == summary.store }.urls.first(),
                                )
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }) { Text("Open AJIO in Chrome") }
                        }
                    }
            }
        }
        }
    }

    DisposableEffect(sessionId) {
        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        val wakeLock = powerManager?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Aurum:StoreRefresh")?.apply {
            runCatching { acquire(10 * 60 * 1000L) }
        }
        onDispose {
            if (wakeLock?.isHeld == true) {
                runCatching { wakeLock.release() }
            }
            onSessionEnd(sessionId)
            if (!finishedReported && targetScripts.isNotEmpty()) {
                val cancelledCount = targetScripts.size - summaries.size
                onLog(RefreshLogSeverity.Warning, null, formatRefreshSummary(summaries.values, cancelledCount))
            }
        }
    }
}

@Composable
private fun StoreRefreshRunner(
    master: MasterScript,
    mergeEvents: Flow<com.aurum.intelligence.data.BridgeMergeEvent>,
    sessionId: String,
    pincode: String,
    latitude: Double? = null,
    longitude: Double? = null,
    visibleModifier: Modifier?,
    onLog: (RefreshLogSeverity, String?, String) -> Unit,
    onStatus: (String) -> Unit,
    onGeolocationPermissionRequest: (String, GeolocationPermissions.Callback) -> Unit,
    onCatalogueMerged: suspend (String, Set<String>, Set<String>, suspend (String) -> ProductFetchResponse?, suspend (Int, Int, ProductEntity) -> Unit) -> MissingCatalogueProductResult,
    onProductRefresh: suspend (String, suspend (String) -> ProductFetchResponse?) -> ProductLookup,
    directProduct: Boolean,
    directProductId: String?,
    productIds: Set<String>,
    onComplete: (StoreCompletionSummary) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current.density
    // Background viewport is derived from the current window/container size, so rotation, tablets and
    // foldables all get a realistic logical viewport; the constant is only a measurement fallback.
    val backgroundViewport = remember(windowInfo.containerSize, density) {
        BrowserViewport.derive(windowInfo.containerSize.width, windowInfo.containerSize.height, density)
    }
    var index by rememberSaveable(master.storeName, sessionId) { mutableIntStateOf(0) }
    var attempt by rememberSaveable(master.storeName, sessionId) { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var webViewIndex by remember { mutableIntStateOf(-1) }
    // Monotonic id for the physical WebView instance (not object identity): a WebView recreated after
    // rotation must always navigate again even if index/attempt are unchanged (4.9.13 follow-up).
    var webViewGeneration by remember { mutableIntStateOf(0) }
    var readyWebView by remember { mutableStateOf<WebView?>(null) }
    var loadUrlCalledToken by remember { mutableStateOf<String?>(null) }
    var scriptExecuted by remember { mutableStateOf(false) }
    // Preloaded independently of any navigation attempt: the navigation effect below must never
    // suspend between committing the idempotency guard and calling RetailerWebView.navigate, which
    // was the root cause of NAV_REQUEST never reaching LOAD_URL_CALLED.
    var preloadedScript by remember(master.storeName) { mutableStateOf<String?>(null) }
    var preloadError by remember(master.storeName) { mutableStateOf<String?>(null) }
    // Not saveable: the underlying WebView is recreated (never reloaded) across configuration changes,
    // so a restored guard value must not suppress navigation on the new instance.
    var startedNavigationKey by remember(master.storeName, sessionId) { mutableStateOf<String?>(null) }
    var receivedTotal by rememberSaveable(master.storeName, sessionId) { mutableIntStateOf(0) }
    var acceptedIdentities by remember(master.storeName, sessionId) { mutableStateOf(emptySet<String>()) }
    var retryCount by rememberSaveable(master.storeName, sessionId) { mutableIntStateOf(0) }
    var finished by remember(master.storeName, sessionId) { mutableStateOf(false) }
    var navStartedAtElapsed by remember(master.storeName, sessionId) { mutableLongStateOf(0L) }
    var ajioPageReadySignal by remember { mutableIntStateOf(0) }
    var readinessEffectSignal by remember { mutableIntStateOf(0) }
    var ajioPageReadyStage by remember { mutableStateOf<String?>(null) }
    var ajioPageReadyUrl by remember { mutableStateOf<String?>(null) }
    var ajioPageReadyViewIdentity by remember { mutableStateOf<Int?>(null) }
    var ajioMasterSubmittedSignal by remember { mutableIntStateOf(0) }
    var ajioBridgeEventReceived by remember { mutableStateOf(false) }
    val currentUrl = master.urls.getOrNull(index)
    // The WebView is created once per runner, so its long-lived callbacks must read the URL of the
    // index currently being loaded, not the one captured when the factory ran.
    val currentUrlState = rememberUpdatedState(currentUrl)
    val logState = rememberUpdatedState(onLog)
    val indexState = rememberUpdatedState(index)
    val attemptState = rememberUpdatedState(attempt)
    val generationState = rememberUpdatedState(webViewGeneration)
    val readyViewState = rememberUpdatedState(readyWebView)
    val finishedState = rememberUpdatedState(finished)

    // Compact elapsed-time-since-navigation-start prefix for the major lifecycle stages (item 3/10 of
    // the 4.9.13 pass), so a phone-test hang can be located without manually diffing timestamps.
    fun stage(label: String): String = "+${android.os.SystemClock.elapsedRealtime() - navStartedAtElapsed}ms $label"

    fun complete(state: String, message: String) {
        if (finished) return
        finished = true
        onLog(if (state == "complete") RefreshLogSeverity.Info else RefreshLogSeverity.Warning, master.storeName, "${stage("terminal $state")}: $message")
        onComplete(StoreCompletionSummary(master.storeName, state, receivedTotal, message))
    }

    fun failCurrent(message: String) {
        if (finished) return
        val failedIndex = index
        onStatus(message)
        val willRetry = retryCount < MAX_TRANSIENT_HTTP_RETRIES
        if (willRetry) {
            retryCount += 1
            onLog(RefreshLogSeverity.Warning, master.storeName, "PHASE retrying-navigation attempt=$retryCount/$MAX_TRANSIENT_HTTP_RETRIES reason=$message")
        } else {
            onLog(RefreshLogSeverity.Error, master.storeName, message)
        }
        scope.launch {
            delay(if (willRetry) TRANSIENT_HTTP_RETRY_DELAY_MILLIS else 1_200)
            if (index != failedIndex || finished) return@launch
            if (willRetry) {
                readyWebView = null
                scriptExecuted = false
                startedNavigationKey = null
                attempt += 1
            } else if (index < master.urls.lastIndex) {
                retryCount = 0
                attempt = 0
                index += 1
            } else if (receivedTotal > 0) {
                complete("complete", "Refreshed $receivedTotal ${master.storeName} products")
            } else {
                complete("failed", "Existing prices preserved after: $message")
            }
        }
    }

    fun failInternalPipeline(message: String) {
        if (finished) return
        onStatus(message)
        onLog(RefreshLogSeverity.Error, master.storeName, message)
        complete("failed", "Existing prices preserved after: $message")
    }

    // Preloads/caches the packaged master-script asset outside the navigation-critical path so the
    // one-time IO suspension can never delay or get cancelled mid-navigation-attempt.
    LaunchedEffect(master.storeName) {
        MasterScriptAssetLoader.load(context, master.assetName)
            .onSuccess { source ->
                preloadedScript = source
            }
            .onFailure { error ->
                preloadError = error.message ?: "asset error"
                onLog(RefreshLogSeverity.Error, master.storeName, "Failed to load master script ${master.assetName}: ${error.message}")
            }
    }

    // One WebView walks the whole URL list, so the host tracks the index it is currently serving.
    LaunchedEffect(index, webView, webViewGeneration) {
        if (webView != null) webViewIndex = index
    }

    LaunchedEffect(index, attempt, webView, webViewIndex, webViewGeneration, preloadedScript, preloadError) {
        val url = currentUrl ?: return@LaunchedEffect
        val decision = NavigationDecision.decide(
            hasWebView = webView != null,
            webViewIndex = webViewIndex,
            requestedIndex = index,
            attempt = NavigationDecision.Attempt(index, attempt, webViewGeneration),
            lastHandledKey = startedNavigationKey,
            sessionCancelled = finished,
            assetReady = preloadedScript != null,
            assetError = preloadError,
        )
        when (decision) {
            NavigationDecision.Decision.AlreadyHandled -> Unit
            is NavigationDecision.Decision.Reject -> {
                if (decision.reason != "no_webview") {
                    onLog(RefreshLogSeverity.Warning, master.storeName, "Navigation skipped: ${decision.reason}")
                }
                if (decision.reason == "asset_missing") {
                    failCurrent("Failed to load script ${master.assetName}: $preloadError")
                }
            }
            NavigationDecision.Decision.Navigate -> {
                startedNavigationKey = NavigationDecision.Attempt(index, attempt, webViewGeneration).key()
                readyWebView = null
                scriptExecuted = false
                loadUrlCalledToken = null
                navStartedAtElapsed = android.os.SystemClock.elapsedRealtime()
                onStatus("Opening ${master.retailer.name} ${index + 1}/${master.urls.size}")
                onLog(
                    RefreshLogSeverity.Info,
                    master.storeName,
                    "Opening ${master.storeName} page ${index + 1}/${master.urls.size}: ${displayUrl(url)}",
                )
                when (val result = RetailerWebView.navigate(webView!!, url)) {
                    is NavigationResult.LoadStarted -> {
                        loadUrlCalledToken = result.token
                    }
                    is NavigationResult.LoadFailed -> {
                        onLog(RefreshLogSeverity.Error, master.storeName, "Navigation failed: ${result.reason}")
                        failCurrent("Internal navigation failed: ${result.reason}")
                        return@LaunchedEffect
                    }
                }
                delay(master.hardTimeoutMillis)
                if (!finished && index == master.urls.indexOf(url)) {
                    failCurrent("Timeout loading URL ${index + 1} for ${master.retailer.name}")
                }
            }
        }
    }
    // Internal navigation-start watchdog (distinct from master.hardTimeoutMillis, the retailer page
    // timeout): diagnoses, then fails, a case where NAV_ATTEMPT fired but loadUrl was never invoked,
    // instead of waiting minutes for a retailer timeout that was never going to fire.
    LaunchedEffect(navStartedAtElapsed) {
        val startedToken = navStartedAtElapsed
        if (startedToken == 0L || finished) return@LaunchedEffect
        delay(3_000)
        if (finished || navStartedAtElapsed != startedToken) return@LaunchedEffect
        if (loadUrlCalledToken == null) {
            onLog(
                RefreshLogSeverity.Warning,
                master.storeName,
                "${stage("INTERNAL_NAVIGATION_STALL")} preloaded=${preloadedScript != null} preloadError=$preloadError " +
                    "webViewPresent=${webView != null} webViewIndex=$webViewIndex index=$index attempt=$attempt generation=$webViewGeneration " +
                    "loadUrlCalledToken=null webViewToken=${webView?.let(RetailerWebView::currentNavigationToken)}",
            )
        }
        delay(5_000)
        if (finished || navStartedAtElapsed != startedToken) return@LaunchedEffect
        if (loadUrlCalledToken == null) {
            failCurrent("Failed \u2014 internal browser navigation: ${stage("NAV_TIMEOUT")}: loadUrl was never invoked for ${master.retailer.name}")
        }
    }
    LaunchedEffect(readyWebView, preloadedScript, index, attempt) {
        val view = readyWebView
        val source = preloadedScript
        if (master.retailer == com.aurum.intelligence.browser.Retailer.Ajio && ajioPageReadySignal > readinessEffectSignal) {
            readinessEffectSignal = ajioPageReadySignal
        }
        if (view != null && source != null && !scriptExecuted && !finished) {
            scriptExecuted = true
            when (val readiness = waitForRetailerReady(view, master, pincode)) {
                RetailerReadiness.Ready -> {
                    onLog(RefreshLogSeverity.Info, master.storeName, "Page loaded: ${displayUrl(currentUrl.orEmpty())}. Executing script...")
                    if (directProduct && directProductId != null) {
                        val directUrl = currentUrl ?: return@LaunchedEffect
                        val directView = readyWebView ?: return@LaunchedEffect
                        val result = onProductRefresh(directProductId, { url ->
                            directView.fetchProductResponse(url).also { response ->
                                onLog(
                                    if (response == null) RefreshLogSeverity.Error else RefreshLogSeverity.Info,
                                    master.storeName,
                                    "Direct product fetch ${if (response == null) "failed" else "done"} for ${displayUrl(url)} (status=${response?.status ?: 0})",
                                )
                            }
                        })
                        when (result) {
                            is ProductLookup.Available -> complete("complete", "Direct product refresh updated ${displayUrl(directUrl)}")
                            is ProductLookup.Unavailable -> complete("complete", "Direct product refresh kept unavailable ${displayUrl(directUrl)}")
                            ProductLookup.Unknown -> complete("failed", "Direct product refresh returned no usable data for ${displayUrl(directUrl)}")
                        }
                        return@LaunchedEffect
                    }
                    if (master.retailer == com.aurum.intelligence.browser.Retailer.Ajio) {
                        // Removed hardcoded wait as per user request
                    }
                    executeMaster(view, master, source, sessionId)
                    if (master.retailer == com.aurum.intelligence.browser.Retailer.Ajio) ajioMasterSubmittedSignal += 1
                }
                is RetailerReadiness.Blocked -> complete(
                    AjioRefreshPolicy.terminalState(readiness)!!,
                    "Access blocked on ${master.storeName} (${readiness.reason}). Existing prices preserved.",
                )
                RetailerReadiness.Timeout, RetailerReadiness.Waiting -> failCurrent(
                    "Page readiness timed out for URL ${index + 1}",
                )
            }
        }
    }
    LaunchedEffect(ajioPageReadySignal) {
        val signal = ajioPageReadySignal
        if (master.retailer != com.aurum.intelligence.browser.Retailer.Ajio || signal == 0 || finished) return@LaunchedEffect
        delay(3_000)
        if (!finished && ajioPageReadySignal == signal && readinessEffectSignal < signal) {
            failInternalPipeline(
                "AJIO page callback completed but readiness did not start (view=${ajioPageReadyViewIdentity})",
            )
        }
    }
    LaunchedEffect(ajioMasterSubmittedSignal) {
        val signal = ajioMasterSubmittedSignal
        if (master.retailer != com.aurum.intelligence.browser.Retailer.Ajio || signal == 0) return@LaunchedEffect
        val submittedView = readyWebView ?: return@LaunchedEffect
        var lastHeartbeat = submittedView.evaluateAjioMasterState()
        var stagnantMillis = 0L
        while (!finished && ajioMasterSubmittedSignal == signal && !ajioBridgeEventReceived) {
            delay(5_000)
            val heartbeat = submittedView.evaluateAjioMasterState()
            if (heartbeat != lastHeartbeat) {
                lastHeartbeat = heartbeat
                stagnantMillis = 0L
                onLog(RefreshLogSeverity.Info, master.storeName, "Extraction progress: $heartbeat")
            } else {
                stagnantMillis += 5_000
            }
            if (heartbeat.contains("\"bridgeAttempted\":true") &&
                heartbeat.contains("\"bridgeCompleted\":false") &&
                heartbeat.contains("\"bridgeError\":") &&
                !heartbeat.contains("\"bridgeError\":null")
            ) {
                val bridgeError = extractAjioBridgeError(heartbeat) ?: heartbeat
                failInternalPipeline("AJIO bridge failed: $bridgeError")
                break
            }
            if (heartbeat.contains("\"doneRejected\":") &&
                !heartbeat.contains("\"doneRejected\":null")
            ) {
                val masterError = extractAjioMasterError(heartbeat) ?: heartbeat
                failInternalPipeline("AJIO master failed: $masterError")
                break
            }
            if (stagnantMillis < 30_000) continue
            failInternalPipeline(
                "AJIO script stalled for ${stagnantMillis / 1_000}s: $heartbeat",
            )
        }
    }
    LaunchedEffect(master.storeName) {
        mergeEvents.collectLatest { event ->
            if (event.sessionId != sessionId || event.store != master.storeName || finished) return@collectLatest
            if (master.retailer == com.aurum.intelligence.browser.Retailer.Ajio) ajioBridgeEventReceived = true
            acceptedIdentities = acceptedIdentities + event.result.acceptedIdentityKeys
            receivedTotal = acceptedIdentities.size
            onLog(
                RefreshLogSeverity.Info,
                master.storeName,
                "Merged ${event.result.accepted} products (${event.result.updated} updated, ${event.result.skipped} skipped) for session ${sessionId.take(8)}",
            )
            val lastUrl = index == master.urls.lastIndex
            if (lastUrl) {
                onLog(
                    RefreshLogSeverity.Info,
                    master.storeName,
                    "PHASE master-complete pages=${master.urls.size} accepted=$receivedTotal skipped=${event.result.skipped}",
                )
                val fallback = run {
                    onLog(RefreshLogSeverity.Info, master.storeName, "PHASE product-fallback-start masterAccepted=$receivedTotal scopeProducts=${productIds.size}")
                    val fallbackResult = withTimeoutOrNull(PRODUCT_QUEUE_TIMEOUT_MILLIS) {
                        onCatalogueMerged(master.storeName, acceptedIdentities, productIds, { productUrl ->
                        onLog(RefreshLogSeverity.Info, master.storeName, "PRODUCT_URL_OPEN ${displayUrl(productUrl)}")
                        onLog(RefreshLogSeverity.Info, master.storeName, "PRODUCT_FETCH_START ${displayUrl(productUrl)}")
                        readyWebView!!.fetchProductResponse(productUrl).also { response ->
                            onLog(
                                if (response == null) RefreshLogSeverity.Error else RefreshLogSeverity.Info,
                                master.storeName,
                                "Product fetch ${if (response == null) "failed" else "done"}: ${displayUrl(productUrl)} (status=${response?.status ?: 0})",
                            )
                        }
                        }, { current, total, product ->
                        onLog(RefreshLogSeverity.Info, master.storeName, "Product check $current/$total: ${displayUrl(product.canonicalUrl)}")
                        })
                    }
                    fallbackResult ?: MissingCatalogueProductResult(0, 0, 0, 1).also {
                        onLog(RefreshLogSeverity.Error, master.storeName, "Product check queue timed out")
                    }.also {
                        onLog(RefreshLogSeverity.Info, master.storeName, "Catalogue checks complete: ${it.checked} checked, ${it.updated} updated, ${it.unavailable} unavailable")
                    }
                }
                val refreshed = fallback.updated + fallback.unavailable
                if (fallback.checked == 0) {
                    complete("complete", "Refreshed $receivedTotal ${master.storeName} products from live catalogue")
                } else if (fallback.unchanged == 0) {
                    complete("complete", "Refreshed $receivedTotal ${master.storeName} products ($refreshed/${fallback.checked} verified)")
                } else {
                    complete("partial", "Refreshed $receivedTotal ${master.storeName} products ($refreshed/${fallback.checked} verified)")
                }
            } else {
                retryCount = 0
                attempt = 0
                if (master.retailer == com.aurum.intelligence.browser.Retailer.Ajio) {
                    // Removed cooldown delay as per user request
                }
                index += 1
            }
        }
    }

    // ONE AndroidView and ONE WebView per StoreRefreshRunner.
    //
    // The host is keyed on nothing: not on `index` (so the same WebView, its cookies, DOM and JS
    // environment are reused while the runner walks its URL list) and not on visibility (so opening
    // Browser or switching retailer chips only updates the Modifier). The factory therefore runs once
    // per composition lifetime. Activity/Compose recreation does destroy this AndroidView and its
    // WebView; the factory then creates a new WebView generation and NavigationDecision navigates the
    // current URL exactly once on it. No WebView instance is claimed to survive Activity recreation.
    //
    // Layout: while backgrounded the host is 1dp and pushed off-screen with clipping, and the WebView
    // child keeps the derived window-sized viewport so retailer layout/JS behaves realistically. While
    // visible the same child switches to MATCH_PARENT and fills the Browser viewport. Only
    // LayoutParams change; the WebView is never recreated or reparented.
    val hostVisible = visibleModifier != null &&
        (readyWebView != null || (AJIO_FOREGROUND_HOST_DIAGNOSTIC && master.retailer == com.aurum.intelligence.browser.Retailer.Ajio))
    AndroidView(
        modifier = (visibleModifier?.takeIf { readyWebView != null }
            ?: Modifier.size(1.dp).offset(x = (-1000).dp, y = (-1000).dp)).clip(RectangleShape),
        factory = { viewContext ->
            android.widget.FrameLayout(viewContext).apply {
                clipChildren = true
                clipToPadding = true
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                )
                val created = RetailerWebView.create(
                    context = viewContext,
                    retailer = master.retailer,
                    pincode = pincode,
                    latitude = latitude,
                    longitude = longitude,
                    onPageReady = { view, loadedUrl ->
                        val requestedUrl = currentUrlState.value.orEmpty()
                        val isSamePage = runCatching {
                            samePage(loadedUrl, requestedUrl)
                        }.onFailure { error ->
                            logState.value(RefreshLogSeverity.Error, master.storeName, "Page match check error: ${error.message}")
                        }.getOrDefault(false)

                        if (isSamePage) {
                            logState.value(RefreshLogSeverity.Info, master.storeName, "Page ready [${indexState.value + 1}/${master.urls.size}]: ${displayUrl(loadedUrl)}")
                            readyWebView = view
                            if (master.retailer == com.aurum.intelligence.browser.Retailer.Ajio) {
                                ajioPageReadyStage = "onPageFinished"
                                ajioPageReadyUrl = loadedUrl
                                ajioPageReadyViewIdentity = view.hashCode()
                                ajioPageReadySignal += 1
                            }
                        } else {
                            logState.value(RefreshLogSeverity.Warning, master.storeName, "Redirected away from expected page to ${displayUrl(loadedUrl)}")
                            failCurrent("Retailer redirected away from required URL to ${displayUrl(loadedUrl)}")
                        }
                    },
                    onError = ::failCurrent,
                    onGeolocationPermissionRequest = onGeolocationPermissionRequest,
                    onPageLifecycle = { callbackStage, view, callbackUrl ->
                        if (master.retailer == com.aurum.intelligence.browser.Retailer.Ajio) {
                            logState.value(
                                RefreshLogSeverity.Info,
                                master.storeName,
                                "Lifecycle: $callbackStage -> ${displayUrl(callbackUrl)}",
                            )
                        }
                    },
                    onDiagnostic = { severity, message ->
                        logState.value(
                            diagnosticSeverity(severity, message),
                            master.storeName,
                            message,
                        )
                    },
                    deferMainFrameHttpErrors = master.retailer == com.aurum.intelligence.browser.Retailer.Ajio,
                )
                webView = created
                webViewIndex = index
                webViewGeneration += 1
                addView(
                    created,
                    android.widget.FrameLayout.LayoutParams(backgroundViewport.widthPx, backgroundViewport.heightPx),
                )
                logState.value(
                    RefreshLogSeverity.Info,
                    master.storeName,
                    "WEBVIEW_STATE index=$index webViewIndex=$webViewIndex generation=$webViewGeneration attached=${created.isAttachedToWindow}",
                )
                logState.value(
                    RefreshLogSeverity.Info,
                    master.storeName,
                    "WEBVIEW_VIEWPORT background ${backgroundViewport.describe()} host=FrameLayout clipped=yes",
                )
            }
        },
        update = { host ->
            val view = webView ?: return@AndroidView
            if (view.parent !== host) {
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                host.addView(
                    view,
                    android.widget.FrameLayout.LayoutParams(backgroundViewport.widthPx, backgroundViewport.heightPx),
                )
            }
            val targetWidth = if (hostVisible) android.widget.FrameLayout.LayoutParams.MATCH_PARENT else backgroundViewport.widthPx
            val targetHeight = if (hostVisible) android.widget.FrameLayout.LayoutParams.MATCH_PARENT else backgroundViewport.heightPx
            val params = view.layoutParams as android.widget.FrameLayout.LayoutParams
            if (params.width != targetWidth || params.height != targetHeight) {
                params.width = targetWidth
                params.height = targetHeight
                view.layoutParams = params
                logState.value(
                    RefreshLogSeverity.Info,
                    master.storeName,
                    if (hostVisible) {
                        "WEBVIEW_VIEWPORT visible match_parent within Browser viewport ${backgroundViewport.describe()}"
                    } else {
                        "WEBVIEW_VIEWPORT background ${backgroundViewport.describe()}"
                    },
                )
            }
        },
    )

    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> webView?.onResume()
                Lifecycle.Event.ON_PAUSE -> webView?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            RetailerWebView.safeDestroy(webView)
            webView = null
        }
    }
}

internal data class StoreCompletionSummary(
    val store: String,
    val state: String,
    val received: Int,
    val message: String,
)

// One terminal label per store: Updated/Partial/Failed/Blocked/Cancelled - never "Updated" merely because a
// task terminated (item 11 of the 4.9.13 pass).
internal fun outcomeLabel(state: String): String = when (state) {
    else -> AjioRefreshPolicy.summaryLabel(state)
}

internal fun formatRefreshSummary(summaries: Collection<StoreCompletionSummary>, cancelledCount: Int): String {
    val counts = linkedMapOf("updated" to 0, "partial" to 0, "failed" to 0, "blocked" to 0, "cancelled" to cancelledCount)
    summaries.forEach { summary -> counts[outcomeLabel(summary.state)] = (counts[outcomeLabel(summary.state)] ?: 0) + 1 }
    val parts = counts.filterValues { it > 0 }.entries.joinToString(" \u00b7 ") { "${it.value} ${it.key}" }
    return "Refresh finished: ${parts.ifEmpty { "no stores targeted" }}"
}

private const val MAX_CONCURRENT_STORES = 5
private const val MAX_TRANSIENT_HTTP_RETRIES = 2
private const val TRANSIENT_HTTP_RETRY_DELAY_MILLIS = 3_000L
private const val PRODUCT_QUEUE_TIMEOUT_MILLIS = 10 * 60_000L
// A/B diagnostic only: Test A keeps this false; Test B sets true to lay out the same persistent AJIO WebView.
private const val AJIO_FOREGROUND_HOST_DIAGNOSTIC = false

private val MasterScript.storeName: String
    get() = when (retailer) {
        com.aurum.intelligence.browser.Retailer.Ajio -> "ajio.com"
        com.aurum.intelligence.browser.Retailer.Amazon -> "amazon.in"
        com.aurum.intelligence.browser.Retailer.Flipkart -> "flipkart.com"
        com.aurum.intelligence.browser.Retailer.Myntra -> "myntra.com"
        com.aurum.intelligence.browser.Retailer.Shopsy -> "shopsy.in"
    }

private fun patchSourceForSpeed(source: String): String {
    return source
        .replace(Regex("searchDelayMs:\\s*\\d+"), "searchDelayMs: 0")
        .replace(Regex("listingDelayMs:\\s*\\d+"), "listingDelayMs: 0")
        .replace(Regex("pdpDelayMs:\\s*\\d+"), "pdpDelayMs: 0")
        .replace("const PDP_TARGETS =", "const PDP_TARGETS = []; const DUMMY_TARGETS =")
        .replace("let PDP_TARGETS =", "let PDP_TARGETS = []; let DUMMY_TARGETS =")
        .replace(Regex("enablePdpEnrichment:\\s*true"), "enablePdpEnrichment: false")
}

private fun executeMaster(
    webView: WebView,
    master: MasterScript,
    source: String,
    sessionId: String,
) {
    val fastSource = patchSourceForSpeed(source)
    val combinedSource = generateMasterWrapper(master, fastSource, sessionId)
    webView.evaluateJavascript(combinedSource) { _ -> }
}

internal fun generateMasterWrapper(master: MasterScript, source: String, sessionId: String): String {
    val prefix = if (master.requiresRunnerFlag) "globalThis.__aurumMasterRunner=true;\n" else ""
    val ajioDiagnosticPrefix = if (master.retailer == com.aurum.intelligence.browser.Retailer.Ajio) {
        "globalThis.__AURUM_AJIO_DIAGNOSTIC__=true;\n"
    } else {
        ""
    }
    val doneVarName = "${master.retailer.name.lowercase()}Done"
    val bridgeHeaders = bridgeHeaderWrapper(sessionId)
    val bridgePost = retailerBridgeWrapper(master, sessionId)
    val wrappedSource = "window.$doneVarName = (function() {\nreturn $source\n})();\n"
    return bridgeHeaders + prefix + ajioDiagnosticPrefix + wrappedSource + bridgePost
}

private fun bridgeHeaderWrapper(sessionId: String): String {
    val sessionLiteral = jsonString(sessionId)
    return """
        ;(function () {
            const aurumBridgePath = 'http://localhost:8788/api/browser-bridge/products';
            const nativeFetch = window.fetch.bind(window);
            window.fetch = function (input, init) {
                const requestUrl = typeof input === 'string' ? input : input && input.url;
                if (requestUrl === aurumBridgePath) {
                    init = init || {};
                    let headersObj = {};
                    if (init.headers) {
                        if (typeof init.headers.forEach === 'function') {
                            init.headers.forEach(function (v, k) { headersObj[k] = v; });
                        } else {
                            Object.assign(headersObj, init.headers);
                        }
                    }
                    headersObj['X-Aurum-Refresh-Session'] = $sessionLiteral;
                    init.headers = headersObj;
                    return nativeFetch(input, init)
                        .then(function (response) {
                            return response;
                        })
                        .catch(function (error) {
                            console.error('[Aurum Bridge] Network failure=' + (error && error.message ? error.message : error));
                            throw error;
                        });
                }
                return nativeFetch(input, init);
            };
        })();
    """.trimIndent()
}

private fun retailerBridgeWrapper(master: MasterScript, sessionId: String): String {
    val sessionLiteral = jsonString(sessionId)
    val storeName = master.storeName
    val retailerName = master.retailer.name
    val doneVarName = "${retailerName.lowercase()}Done"
    val productBinding = master.productBinding

    return """
        ;(function () {
            const masterDone = window.$doneVarName;
            console.warn('[Aurum $retailerName] Master execution started (session=' + $sessionLiteral.slice(0, 8) + ')');
            Promise.resolve(masterDone).then(function () {
                const records = Array.isArray(window.$productBinding) ? window.$productBinding :
                    Array.isArray(window.${retailerName.lowercase()}Products) ? window.${retailerName.lowercase()}Products : [];
                console.warn('[Aurum $retailerName] Extraction complete: ' + records.length + ' products found');
                if (records.length === 0) return;
                return fetch('http://localhost:8788/api/browser-bridge/products', {
                    method: 'POST',
                    headers: {'content-type': 'application/json', 'X-Aurum-Refresh-Session': $sessionLiteral},
                    body: JSON.stringify({
                        store: '$storeName',
                        records: records.map(function (product) {
                            return {
                                bridgeSnapshot: true,
                                code: String(product.code || product.asin || product.productId || product.pid || product.id || ''),
                                url: product.link || product.url || product.landingPageUrl,
                                name: product.name || product.title || product.productName,
                                brand: product.brand || product.brandName,
                                price: product.price,
                                couponPrice: product.offerPrice || product.couponPrice,
                                metal: product.metal || 'gold',
                                grams: product.weightGrams || product.grams || product.totalWeightGrams,
                                karat: product.karat,
                                purity: product.purity,
                                unavailable: Boolean(product.unavailable || product.outOfStock || product.purchasable === false)
                            };
                        })
                    })
                }).then(function (response) {
                    console.warn('[Aurum $retailerName] Bridge POST status=' + response.status);
                    return response;
                }).catch(function (error) {
                    console.error('[Aurum $retailerName] Bridge POST failed: ' + (error && error.message ? error.message : error));
                });
            }).catch(function (error) {
                console.error('[Aurum $retailerName] Master script failed: ' + (error && error.message ? error.message : error));
            });
        })();
    """.trimIndent()
}

    private suspend fun waitForRetailerReady(webView: WebView, master: MasterScript, pincode: String): RetailerReadiness {
        webView.evaluateJavascript(
            """
            (function() {
                try {
                    document.cookie = "pincode=$pincode; path=/; max-age=31536000; SameSite=Lax";
                    document.cookie = "ajio_pincode=$pincode; path=/; max-age=31536000; SameSite=Lax";
                    document.cookie = "mynt-ulc=pincode:$pincode; path=/; Secure; SameSite=Lax";
                    document.cookie = "fk_pincode=$pincode; path=/; max-age=31536000; SameSite=Lax";
                    document.querySelectorAll('.ic-close, [data-testid="close-button"], .close-button, .modal-close, button.close, #pge-close-x, .pincode-modal-close').forEach(function(el) { el.click(); });
                } catch (_) {}
            })();
            """.trimIndent(),
            null,
        )
        repeat(80) {
            if (master.retailer == com.aurum.intelligence.browser.Retailer.Ajio) {
                when (val state = webView.evaluateAjioReadiness()) {
                    RetailerReadiness.Ready -> {
                        delay(300)
                        return RetailerReadiness.Ready
                    }
                    is RetailerReadiness.Blocked -> return state
                    RetailerReadiness.Waiting, RetailerReadiness.Timeout -> Unit
                }
                delay(200)
                return@repeat
            }
            if (master.retailer == com.aurum.intelligence.browser.Retailer.Myntra && webView.evaluateMyntraReadiness()) {
                delay(300)
                return RetailerReadiness.Ready
            }
            val expression = when (master.retailer) {
                com.aurum.intelligence.browser.Retailer.Ajio -> "false"
                com.aurum.intelligence.browser.Retailer.Amazon ->
                    "document.querySelectorAll('[data-component-type=\"s-search-result\"][data-asin]').length>0"
                com.aurum.intelligence.browser.Retailer.Flipkart, com.aurum.intelligence.browser.Retailer.Shopsy ->
                    "Array.from(document.querySelectorAll('a[href]')).some(a=>/[?&]pid=/.test(a.href)||/\\/p\\//.test(a.pathname))||document.readyState==='complete'"
                com.aurum.intelligence.browser.Retailer.Myntra -> "false"
            }
            if (webView.evaluateBoolean(expression)) {
                delay(300)
                return RetailerReadiness.Ready
            }
            delay(200)
        }
        return RetailerReadiness.Timeout
    }

private suspend fun WebView.evaluateAjioReadiness(): RetailerReadiness =
    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        evaluateJavascript(
            "JSON.stringify({host:location.hostname,path:location.pathname,readyState:document.readyState,body:String(document.body?.innerText||'').slice(0,5000)})",
        ) { value ->
            val state = runCatching {
                val decoded = org.json.JSONTokener(value).nextValue() as String
                val json = org.json.JSONObject(decoded)
                AjioReadiness.classify(json.getString("host"), json.getString("path"), json.getString("readyState"), json.getString("body"))
            }.getOrDefault(RetailerReadiness.Waiting)
            if (continuation.isActive) continuation.resume(state)
        }
    }

private suspend fun WebView.evaluateMyntraReadiness(): Boolean =
    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        evaluateJavascript(
            "JSON.stringify({host:location.hostname,path:location.pathname,readyState:document.readyState,body:String(document.body?.innerText||'').slice(0,20000)})",
        ) { value ->
            val ready = runCatching {
                val decoded = org.json.JSONTokener(value).nextValue() as String
                val json = org.json.JSONObject(decoded)
                MyntraReadiness.isReady(
                    json.getString("host"),
                    json.getString("path"),
                    json.getString("readyState"),
                    json.getString("body"),
                )
            }.getOrDefault(false)
            if (continuation.isActive) continuation.resume(ready)
        }
    }

private suspend fun WebView.evaluateAjioMasterState(): String =
    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        evaluateJavascript(
            """(function(){var done=window.ajioDone;var gold=window.ajioGold;var progress=window.ajioProgress||null;return JSON.stringify({stage:window.__aurumAjioMasterStage||null,page:window.__aurumAjioMasterPage??null,progress:progress&&{stage:progress.stage,completed:progress.completed,total:progress.total},doneType:typeof done,doneThenable:Boolean(done&&typeof done.then==='function'),goldArray:Array.isArray(gold),goldLength:Array.isArray(gold)?gold.length:-1,doneSettled:Boolean(window.__aurumAjioDoneSettled),doneRejected:window.__aurumAjioDoneRejected||null,bridgeAttempted:Boolean(window.__aurumAjioBridgeAttempted),bridgeCompleted:Boolean(window.__aurumAjioBridgeCompleted),bridgeError:window.__aurumAjioBridgeError||null});})()""",
        ) { value ->
            val state = runCatching {
                val decoded = org.json.JSONTokener(value).nextValue() as String
                decoded.take(768)
            }.getOrDefault("unavailable")
            if (continuation.isActive) continuation.resume(state)
        }
    }

internal fun extractAjioBridgeError(heartbeat: String): String? = runCatching {
    heartbeatStringField(heartbeat, "bridgeError")
}.getOrNull()

internal fun extractAjioMasterError(heartbeat: String): String? = runCatching {
    heartbeatStringField(heartbeat, "doneRejected")
}.getOrNull()

private fun heartbeatStringField(heartbeat: String, name: String): String? {
    val element = Json.parseToJsonElement(heartbeat).jsonObject[name] ?: return null
    if (element is JsonNull) return null
    return element.jsonPrimitive.content.takeIf(String::isNotBlank)
}

private suspend fun WebView.evaluateBoolean(expression: String): Boolean =
    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        evaluateJavascript("Boolean($expression)") { value ->
            if (continuation.isActive) continuation.resume(value == "true")
        }
    }

private suspend fun WebView.fetchProductResponse(url: String): ProductFetchResponse? {
    return kotlinx.coroutines.withTimeoutOrNull(PRODUCT_FETCH_TIMEOUT_MILLIS) {
        val quotedUrl = org.json.JSONObject.quote(url)
        val started = evaluateJavascriptValue(
            """(function(){window.__aurumProductFetchResult={pending:true};fetch($quotedUrl,{credentials:'include',redirect:'follow'}).then(async function(response){window.__aurumProductFetchResult={pending:false,status:response.status,body:(await response.text()).slice(0,200000)};}).catch(function(error){window.__aurumProductFetchResult={pending:false,status:0,body:String(error&&error.message||error)};});return true;})()""",
        ) == "true"
        if (!started) return@withTimeoutOrNull null
        repeat(60) {
            delay(250)
            val result = runCatching {
                val value = evaluateJavascriptValue("JSON.stringify(window.__aurumProductFetchResult || null)") ?: return@runCatching null
                val decoded = org.json.JSONTokener(value).nextValue() as String
                val json = org.json.JSONObject(decoded)
                if (json.optBoolean("pending", true)) return@runCatching null
                ProductFetchResponse(json.optInt("status"), json.optString("body"))
            }.getOrNull()
            if (result != null) return@withTimeoutOrNull result
        }
        null
    }
}

private const val PRODUCT_FETCH_TIMEOUT_MILLIS = 20_000L

private suspend fun WebView.evaluateJavascriptValue(script: String): String? =
    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        if (!post { evaluateJavascript(script) { value -> if (continuation.isActive) continuation.resume(value) } }) {
            continuation.resume(null)
        }
    }

private fun samePage(left: String?, right: String): Boolean = runCatching {
    val a = URI(left ?: return false)
    val b = URI(right)
    a.host == b.host && a.path == b.path && queryParameters(b).all { (key, expected) ->
        queryParameters(a)[key] == expected
    }
}.getOrDefault(false)

private fun queryParameters(uri: URI): Map<String, String> = uri.rawQuery.orEmpty()
    .split('&')
    .filter(String::isNotBlank)
    .associate { part ->
        val pieces = part.split('=', limit = 2)
        java.net.URLDecoder.decode(pieces[0], Charsets.UTF_8.name()) to
            java.net.URLDecoder.decode(pieces.getOrElse(1) { "" }, Charsets.UTF_8.name())
    }

internal fun diagnosticSeverity(severity: String, message: String): RefreshLogSeverity = when {
    isNormalAjioProgress(message) -> RefreshLogSeverity.Info
    severity == "error" -> RefreshLogSeverity.Error
    severity == "info" -> RefreshLogSeverity.Info
    else -> RefreshLogSeverity.Warning
}

private fun isNormalAjioProgress(message: String): Boolean {
    if (!message.contains("[Aurum AJIO]") && !message.contains("[Aurum AJIO Master]")) return false
    return listOf(
        "START",
        "CONFIG_READY",
        "FETCH_BEGIN",
        "PARSE_BEGIN",
        "PARSE_DONE",
        "NORMALIZE_BEGIN",
        "NORMALIZE_DONE",
        "RESULT_ASSIGN",
        "MASTER_RESOLVE",
        "MASTER_BEGIN",
        "MASTER_SOURCE_RETURNED",
        "AJIO_DONE_CAPTURED",
        "AJIO_DONE_RESOLVED",
        "BRIDGE_POST_ATTEMPT",
        "BRIDGE_POST_RESPONSE",
    ).any(message::contains) ||
        (message.contains("FETCH_RESPONSE") && Regex("status=2\\d\\d").containsMatchIn(message))
}

private fun jsonString(value: String): String = JsonPrimitive(value).toString()

    private fun displayUrl(url: String): String = runCatching {
        URI(url).let { "${it.host}${it.path}" }
    }.getOrDefault(url)
