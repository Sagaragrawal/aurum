package com.aurum.intelligence.ui

import com.aurum.intelligence.data.ProductEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductCalculationsTest {
    private val now = 1_750_000_000_000L
    @Test
    fun appliesBaseFiltersBeforeQuickCountsAndPurity() {
        val products = listOf(
            product("a", store = "ajio.com", karat = 24.0, grams = 1.0, price = 9_000.0, status = "live"),
            product("b", store = "ajio.com", karat = 22.0, grams = 2.0, price = 22_000.0, status = "stale"),
            product("c", store = "amazon.in", karat = 24.0, grams = 1.0, price = 11_000.0, status = "failed"),
        )
        val query = WatchlistQuery(
            purity = PurityFilter.K24,
            minimumGrams = 1.5,
            stores = setOf("ajio.com"),
        )

        val counts = ProductCalculations.quickCounts(products, query, benchmark24 = 10_000.0, benchmark22 = 10_000.0)

        assertEquals(1, counts.getValue(QuickFilter.All))
        assertEquals(1, counts.getValue(QuickFilter.Stale))
        assertEquals(1, counts.getValue(QuickFilter.NotLive))
        assertTrue(ProductCalculations.filteredAndSorted(products, query, 10_000.0, 10_000.0).isEmpty())
    }

    @Test
    fun sortsLiveFirstThenBySelectedDirectionWithStableIdTieBreak() {
        val products = listOf(
            product("stale", name = "A", price = 5_000.0, status = "stale"),
            product("live-b", name = "B", price = 8_000.0),
            product("live-a", name = "A", price = 8_000.0),
        )
        val query = WatchlistQuery(
            sort = ProductSort.Price,
            direction = SortDirection.Descending,
        )

        assertEquals(
            listOf("live-a", "live-b", "stale"),
            ProductCalculations.filteredAndSorted(products, query, 10_000.0, 9_000.0).map { it.id },
        )
    }

    @Test
    fun effectiveValuesUseOnlyARealLowerCoupon() {
        val discounted = product("discounted", price = 12_000.0, couponPrice = 9_000.0, grams = 2.0)
        val invalidCoupon = product("invalid", price = 12_000.0, couponPrice = 13_000.0, grams = 2.0)

        assertEquals(9_000.0, ProductCalculations.effectiveTotal(discounted), 0.0)
        assertEquals(4_500.0, ProductCalculations.effectivePerGram(discounted)!!, 0.0)
        assertEquals(12_000.0, ProductCalculations.effectiveTotal(invalidCoupon), 0.0)
    }

    @Test
    fun dealRadarIsCouponAwareClosestFirstAndLimitedToSixLiveProducts() {
        val products = (1..8).map { index ->
            product(
                id = "p$index",
                price = 10_000.0 + index * 10,
                couponPrice = if (index == 8) 10_001.0 else null,
                status = if (index == 7) "stale" else "live",
            )
        }

        val deals = ProductCalculations.deals(
            products = products,
            benchmark24 = 10_000.0,
            benchmark22 = 9_000.0,
            mode = DealMode.RupeesPerGram,
            threshold = 100.0,
            nowMillis = now,
        )

        assertEquals(6, deals.size)
        assertEquals("p8", deals.first().product.id)
        assertEquals(1.0, deals.first().rupeesDelta, 0.0)
        assertTrue(deals.none { it.product.id == "p7" })
    }

    @Test
    fun belowBullionUsesKaratSpecificBenchmark() {
        val products = listOf(
            product("under-22", karat = 22.0, price = 8_500.0),
            product("over-24", karat = 24.0, price = 10_500.0),
            product("stale-under-22", karat = 22.0, price = 8_500.0, status = "stale", lastLiveAt = now - 31 * 60 * 1_000L),
        )
        val query = WatchlistQuery(purity = PurityFilter.K22, quickFilter = QuickFilter.BelowBullion)

        assertEquals(
            listOf("under-22"),
            ProductCalculations.filteredAndSorted(products, query, benchmark24 = 10_000.0, benchmark22 = 9_000.0, nowMillis = now).map { it.id },
        )
    }

    @Test
    fun dealRadarRejectsStaleAndImplausiblyCheapPerGramObservations() {
        val stale = product("stale", price = 9_000.0, status = "stale", lastLiveAt = now - 31 * 60 * 1_000L)
        val implausible = product("bad-weight", price = 100.0, grams = 1.0, lastLiveAt = now)
        val valid = product("valid", price = 9_000.0, grams = 1.0, lastLiveAt = now)

        val deals = ProductCalculations.deals(
            products = listOf(stale, implausible, valid),
            benchmark24 = 10_000.0,
            benchmark22 = 9_000.0,
            mode = DealMode.RupeesPerGram,
            threshold = 2_000.0,
            nowMillis = now,
        )

        assertEquals(listOf("valid"), deals.map { it.product.id })
    }

    @Test
    fun notDeliverableProductIsExcludedFromLiveAndRanksAfterPurchasableProducts() {
        val unavailable = product(
            "unavailable",
            name = "Mia Lotus Coin - Not Deliverable",
            price = 100.0,
            grams = 1.0,
            status = "live",
        )
        val live = product("live", name = "Mia Lotus Coin", price = 10_000.0, grams = 1.0)
        val allQuery = WatchlistQuery(quickFilter = QuickFilter.All, sort = ProductSort.PricePerGram)

        assertEquals(
            listOf("live", "unavailable"),
            ProductCalculations.filteredAndSorted(listOf(unavailable, live), allQuery, 10_000.0, 9_000.0).map { it.id },
        )
        assertEquals(
            listOf("live"),
            ProductCalculations.filteredAndSorted(
                listOf(unavailable, live),
                WatchlistQuery(quickFilter = QuickFilter.Live),
                10_000.0,
                9_000.0,
                nowMillis = now,
            ).map { it.id },
        )
        assertEquals("Mia Lotus Coin", ProductCalculations.displayName(unavailable))
        assertEquals(
            emptyList<String>(),
            ProductCalculations.filteredAndSorted(
                listOf(unavailable, live),
                WatchlistQuery(quickFilter = QuickFilter.BelowBullion),
                10_000.0,
                9_000.0,
                nowMillis = now,
            ).map { it.id },
        )
        assertEquals(
            0,
            ProductCalculations.quickCounts(
                listOf(unavailable, live),
                WatchlistQuery(),
                10_000.0,
                9_000.0,
                nowMillis = now,
            ).getValue(QuickFilter.BelowBullion),
        )
    }

    @Test
    fun liveQuickFilterIncludesOnlyARecentlyLiveObservation() {
        val recentlyLive = product("recent", status = "live", lastLiveAt = now - 5 * 60 * 1_000L)

        assertEquals(
            listOf("recent"),
            ProductCalculations.filteredAndSorted(
                listOf(recentlyLive),
                WatchlistQuery(quickFilter = QuickFilter.Live),
                10_000.0,
                9_000.0,
                nowMillis = now,
            ).map { it.id },
        )
    }

    @Test
    fun liveQuickFilterExcludesStaleTimestampUnavailableWrongStatusAndInvalidTimestamps() {
        val tooOldTimestamp = product("too-old", status = "live", lastLiveAt = now - 25 * 60 * 60 * 1_000L)
        val unavailableButRecent = product("unavailable-recent", name = "Coin - Not Deliverable", status = "live", lastLiveAt = now)
        val staleStatusRecentTimestamp = product("stale-status", status = "stale", lastLiveAt = now)
        val futureTimestamp = product("future", status = "live", lastLiveAt = now + 60 * 60 * 1_000L)
        val products = listOf(tooOldTimestamp, unavailableButRecent, staleStatusRecentTimestamp, futureTimestamp)

        assertTrue(
            ProductCalculations.filteredAndSorted(
                products,
                WatchlistQuery(quickFilter = QuickFilter.Live),
                10_000.0,
                9_000.0,
                nowMillis = now,
            ).isEmpty(),
        )
    }

    @Test
    fun notLiveQuickFilterExcludesUnavailableAndNotDeliverableProducts() {
        val unavailable = product("unavailable", name = "Coin - Not Deliverable", status = "unavailable")
        val stale = product("stale", status = "stale")
        val products = listOf(unavailable, stale)

        val notLive = ProductCalculations.filteredAndSorted(
            products,
            WatchlistQuery(quickFilter = QuickFilter.NotLive),
            10_000.0,
            9_000.0,
            nowMillis = now,
        )

        assertEquals(listOf("stale"), notLive.map { it.id })
    }

    @Test
    fun unavailableProductNeverAppearsInDealRadar() {
        val unavailable = product("unavailable", name = "Mia Coin Unavailable", price = 100.0, grams = 1.0)
        val live = product("live", price = 9_900.0, grams = 1.0)

        val deals = ProductCalculations.deals(
            products = listOf(unavailable, live),
            benchmark24 = 10_000.0,
            benchmark22 = 9_000.0,
            mode = DealMode.RupeesPerGram,
            threshold = 20_000.0,
            nowMillis = now,
        )

        assertEquals(listOf("live"), deals.map { it.product.id })
    }

    private fun product(
        id: String,
        store: String = "ajio.com",
        name: String = id,
        grams: Double = 1.0,
        karat: Double = 24.0,
        price: Double = 10_000.0,
        couponPrice: Double? = null,
        status: String = "live",
        lastLiveAt: Long = now,
    ) = ProductEntity(
        id = id,
        store = store,
        retailerId = id,
        canonicalUrl = "https://$store/$id",
        name = name,
        brand = null,
        grams = grams,
        karat = karat,
        purity = "${karat.toInt()}K",
        price = price,
        couponPrice = couponPrice,
        status = status,
        refreshMethod = "test",
        checkedAt = lastLiveAt,
        lastLiveAt = lastLiveAt,
    )
}