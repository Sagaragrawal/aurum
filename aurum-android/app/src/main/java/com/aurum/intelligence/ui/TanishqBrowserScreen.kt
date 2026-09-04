package com.aurum.intelligence.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aurum.intelligence.browser.BrowserViewport
import com.aurum.intelligence.browser.NavigationResult
import com.aurum.intelligence.browser.RetailerWebView
import com.aurum.intelligence.data.RefreshLogSeverity
import java.net.URI
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

@Composable
fun TanishqBrowserScreen(
    showBrowser: Boolean,
    onResult: (Double, Double?) -> Unit,
    onLog: (RefreshLogSeverity, String?, String) -> Unit = { _, _, _ -> },
    onClose: () -> Unit,
) {
    var status by remember { mutableStateOf("Opening Tanishq gold rate") }
    var error by remember { mutableStateOf<String?>(null) }
    var readyView by remember { mutableStateOf<WebView?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var injected by remember { mutableStateOf(false) }
    var terminal by remember { mutableStateOf(false) }
    var loadUrlCalledToken by remember { mutableStateOf<String?>(null) }
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current.density
    val backgroundViewport = remember(windowInfo.containerSize, density) {
        BrowserViewport.derive(windowInfo.containerSize.width, windowInfo.containerSize.height, density)
    }

    fun fail(message: String) {
        if (terminal) return
        terminal = true
        error = message
        onLog(RefreshLogSeverity.Error, "tanishq", message)
        onClose()
    }

    LaunchedEffect(Unit) {
        onLog(
            RefreshLogSeverity.Info,
            "tanishq",
            "Opening rendered Tanishq gold-rate page in background; Browser tab is optional",
        )
    }
    // Navigation-start watchdog on the same authoritative token RetailerWebView.navigate() returned.
    LaunchedEffect(Unit) {
        delay(8_000)
        if (!terminal && loadUrlCalledToken == null) {
            fail("Tanishq navigation never started (loadUrl was not invoked). Existing data was preserved.")
        }
    }
    LaunchedEffect(readyView) {
        val view = readyView ?: return@LaunchedEffect
        if (injected) return@LaunchedEffect
        injected = true
        status = "Selecting 24 Karat and reading rendered rate"
        view.evaluateJavascript(TANISHQ_EXTRACTOR, null)
        repeat(45) {
            delay(1_000)
            val raw = view.evaluate("JSON.stringify(window.__aurumTanishqRate || null)")
            val decoded = decodeJavascriptString(raw)
            if (decoded != null && decoded != "null") {
                val result = JSONObject(decoded)
                val price24 = result.optDouble("price24", 0.0)
                if (price24 > 0) {
                    val price22 = result.optDouble("price22", Double.NaN).takeIf(Double::isFinite)
                    onLog(RefreshLogSeverity.Info, "tanishq", "Rendered rates received: 24K=$price24, 22K=${price22 ?: "derived"}")
                    onResult(price24, price22)
                    return@LaunchedEffect
                }
            }
        }
        fail("Tanishq did not expose a rendered 24K rate within 45 seconds. Existing data was preserved.")
    }

    // ONE Column, ONE AndroidView call site, ONE Tanishq WebView. Toggling Browser visibility only
    // changes Modifiers; it never enters a second AndroidView branch, so
    // no second WebView is created and navigate() is never called again. Extraction/polling/parsing
    // below is unchanged.
    Column(if (showBrowser) Modifier.fillMaxSize() else Modifier.size(1.dp)) {
        if (showBrowser && error != null) {
            Button(onClick = onClose, modifier = Modifier.padding(16.dp)) { Text("Return to Aurum") }
        }
        // Same "fills width, gets remaining weighted height" viewport contract as the product Browser
        // (BrowserRefreshScreen) when visible; 1dp off-screen clipped host when backgrounded.
        Box(if (showBrowser) Modifier.fillMaxWidth().weight(1f) else Modifier.size(1.dp)) {
            AndroidView(
                modifier = (if (showBrowser) Modifier.fillMaxSize() else Modifier.size(1.dp).offset(x = (-1000).dp, y = (-1000).dp))
                    .clip(RectangleShape),
                factory = { context ->
                    android.widget.FrameLayout(context).apply {
                        clipChildren = true
                        clipToPadding = true
                        layoutParams = android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        )
                        val created = RetailerWebView.create(
                            context,
                            onPageReady = { view, url ->
                                if (runCatching { URI(url).host == "www.tanishq.co.in" }.getOrDefault(false)) readyView = view
                                else fail("Tanishq redirected to an unexpected page.")
                            },
                            onError = ::fail,
                        )
                        webView = created
                        addView(
                            created,
                            android.widget.FrameLayout.LayoutParams(backgroundViewport.widthPx, backgroundViewport.heightPx),
                        )
                        when (val result = RetailerWebView.navigate(created, TANISHQ_URL)) {
                            is NavigationResult.LoadStarted -> {
                                loadUrlCalledToken = result.token
                            }
                            is NavigationResult.LoadFailed ->
                                fail("Tanishq navigation could not start: ${result.reason}. Existing data was preserved.")
                        }
                    }
                },
                update = { host ->
                    val view = webView ?: return@AndroidView
                    val targetWidth = if (showBrowser) android.widget.FrameLayout.LayoutParams.MATCH_PARENT else backgroundViewport.widthPx
                    val targetHeight = if (showBrowser) android.widget.FrameLayout.LayoutParams.MATCH_PARENT else backgroundViewport.heightPx
                    val params = view.layoutParams as android.widget.FrameLayout.LayoutParams
                    if (params.width != targetWidth || params.height != targetHeight) {
                        params.width = targetWidth
                        params.height = targetHeight
                        view.layoutParams = params
                    }
                    if (view.parent !== host) {
                        (view.parent as? android.view.ViewGroup)?.removeView(view)
                        host.addView(view, params)
                    }
                },
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
        }
    }
}

private suspend fun WebView.evaluate(script: String): String? = suspendCancellableCoroutine { continuation ->
    evaluateJavascript(script) { result -> if (continuation.isActive) continuation.resume(result) }
}

private fun decodeJavascriptString(value: String?): String? {
    if (value == null || value == "null") return null
    return runCatching { JSONObject("{\"value\":$value}").getString("value") }.getOrNull()
}

private const val TANISHQ_URL = "https://www.tanishq.co.in/gold-rate.html"
private val TANISHQ_EXTRACTOR = """
    (function () {
      window.__aurumTanishqRate = null;
      const number = value => {
        const parsed = Number(String(value || '').replaceAll(',', '').replace(/[^0-9.]/g, ''));
        return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
      };
      const read = () => {
        document.querySelector('#pge-close-x')?.click();
        const overlay = document.querySelector('#pge-modal-overlay');
        if (overlay) { overlay.style.display = 'none'; overlay.setAttribute('aria-hidden', 'true'); }
        const selected = document.querySelector('.select-gold-purity-menu .sBtn-text')?.textContent || '';
        if (!/24\s*karat/i.test(selected)) {
          document.querySelector('.select-gold-purity-menu .select-btn')?.click();
          const option = document.querySelector('.select-gold-purity-menu .option[data-value="24 Karat"]');
          option?.dispatchEvent(new MouseEvent('click', {bubbles: true}));
        }
        const row = document.querySelector('.goldrate-history-table tbody tr:first-child');
        const rate24 = number(row?.querySelector('[data-goldrate24kt]')?.getAttribute('data-goldrate24kt')) ||
          number(document.querySelector('[data-goldrate24kt]')?.getAttribute('data-goldrate24kt'));
                const rate22 = number(row?.querySelector('[data-goldrate22kt]')?.getAttribute('data-goldrate22kt')) ||
                    number(document.querySelector('[data-goldrate22kt]')?.getAttribute('data-goldrate22kt'));
        const text = document.body?.innerText || '';
        const match22 = text.match(/22\s*(?:Karat|Kt|K)[\s\S]{0,220}(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)/i);
                const candidate22 = rate22 || number(match22?.[1]);
                const price22 = candidate22 && rate24 && candidate22 >= rate24 * 0.72 && candidate22 <= rate24 * 1.02
                    ? candidate22 : null;
                if (rate24) window.__aurumTanishqRate = {price24: rate24, price22};
      };
      read();
      const timer = setInterval(() => {
        read();
        if (window.__aurumTanishqRate) clearInterval(timer);
      }, 500);
      setTimeout(() => clearInterval(timer), 45000);
    })();
"""