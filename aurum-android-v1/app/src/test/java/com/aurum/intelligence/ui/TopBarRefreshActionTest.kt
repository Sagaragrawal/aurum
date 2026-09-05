package com.aurum.intelligence.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TopBarRefreshActionTest {
    @Test
    fun marketMapsToBullionOnly() {
        assertEquals(TopBarRefreshAction.BullionOnly, topBarRefreshAction(AppSection.Market))
    }

    @Test
    fun watchlistMapsToProductsOnly() {
        assertEquals(TopBarRefreshAction.ProductsOnly, topBarRefreshAction(AppSection.Watchlist))
    }

    @Test
    fun browserMapsToCombined() {
        assertEquals(TopBarRefreshAction.Combined, topBarRefreshAction(AppSection.Browser))
    }
}
