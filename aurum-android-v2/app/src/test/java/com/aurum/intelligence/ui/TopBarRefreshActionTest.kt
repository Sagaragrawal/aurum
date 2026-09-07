package com.aurum.intelligence.ui
import com.aurum.intelligence.data.db.*
import com.aurum.intelligence.data.engine.*
import com.aurum.intelligence.data.model.*
import com.aurum.intelligence.data.repository.*
import com.aurum.intelligence.data.validation.*

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
