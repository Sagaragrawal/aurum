package com.aurum.intelligence.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyntraReadinessTest {
    @Test
    fun acceptsPopulatedGoldCoinCatalogue() {
        assertTrue(
            MyntraReadiness.isReady(
                host = "www.myntra.com",
                path = "/gold-coin",
                readyState = "complete",
                bodyText = "Home Gold Coin Gold Coin - 337 items FILTERS Men Women BRAND",
            ),
        )
    }

    @Test
    fun rejectsEmptyOrUnrelatedPages() {
        assertFalse(MyntraReadiness.isReady("www.myntra.com", "/gold-coin", "complete", "Gold Coin - 0 items"))
        assertFalse(MyntraReadiness.isReady("www.myntra.com", "/men-tshirts", "complete", "Gold Coin - 337 items"))
    }
}