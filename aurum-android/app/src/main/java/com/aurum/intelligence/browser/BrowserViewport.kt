package com.aurum.intelligence.browser

/**
 * Retailer WebView viewport derived from the current window/container size rather than a hard-coded
 * phone size, so landscape, tablets and foldables all get a realistic logical viewport. The fallback
 * constants are used only when the window has not been measured yet and are reported as such.
 */
data class BrowserViewport(
    val widthPx: Int,
    val heightPx: Int,
    val widthDp: Int,
    val heightDp: Int,
    val derivedFromWindow: Boolean,
) {
    fun describe(): String =
        "width=${widthPx}px height=${heightPx}px (${widthDp}dp x ${heightDp}dp) " +
            "source=${if (derivedFromWindow) "window" else "fallback"}"

    companion object {
        const val FALLBACK_WIDTH_DP = 414
        const val FALLBACK_HEIGHT_DP = 896

        fun derive(windowWidthPx: Int, windowHeightPx: Int, density: Float): BrowserViewport {
            val safeDensity = if (density > 0f) density else 1f
            val measured = windowWidthPx > 0 && windowHeightPx > 0
            val widthPx = if (measured) windowWidthPx else (FALLBACK_WIDTH_DP * safeDensity).toInt()
            val heightPx = if (measured) windowHeightPx else (FALLBACK_HEIGHT_DP * safeDensity).toInt()
            return BrowserViewport(
                widthPx = widthPx,
                heightPx = heightPx,
                widthDp = (widthPx / safeDensity).toInt(),
                heightDp = (heightPx / safeDensity).toInt(),
                derivedFromWindow = measured,
            )
        }
    }
}
