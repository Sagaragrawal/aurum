package com.aurum.intelligence.browser

/** Conservative, deterministic AJIO pacing. No jitter or fingerprint behavior. */
internal object AjioRequestPacing {
    const val MASTER_SETTLE_MS = 2_000L
    const val CATEGORY_COOLDOWN_MS = 3_000L
}
