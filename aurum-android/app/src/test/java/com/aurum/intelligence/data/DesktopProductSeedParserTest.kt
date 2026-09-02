package com.aurum.intelligence.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopProductSeedParserTest {
    @Test
    fun parsesDesktopProductAndDerivesIdentity() {
        val products = DesktopProductSeedParser.parse(
            """[{"id":"one","source":"amazon.in","name":"Gold Coin","grams":1,"price":10000,"purity":"999","url":"https://www.amazon.in/example/dp/B012345678/ref=x","checkedAt":"2026-08-31T14:27:54.218Z","status":"live"}]""",
        )

        assertEquals(1, products.size)
        assertEquals("B012345678", products.single().retailerId)
        assertEquals(24.0, products.single().karat ?: 0.0, 0.0)
        assertEquals("https://www.amazon.in/example/dp/B012345678/ref=x", products.single().canonicalUrl)
    }

    @Test
    fun duplicateRetailerIdentityKeepsLiveRecord() {
        val stale = product(id = "stale", status = "stale", price = 9000.0)
        val live = product(id = "live", status = "live", price = 10000.0)

        val result = dedupeDesktopSeed(listOf(stale, live))

        assertEquals(1, result.size)
        assertEquals("live", result.single().id)
    }

    private fun product(id: String, status: String, price: Double) = ProductEntity(
        id = id,
        store = "amazon.in",
        retailerId = "B012345678",
        canonicalUrl = "https://www.amazon.in/example/dp/B012345678",
        name = "Gold Coin",
        brand = null,
        grams = 1.0,
        karat = 24.0,
        purity = "999",
        price = price,
        couponPrice = null,
        status = status,
        refreshMethod = "desktop-seed",
        checkedAt = 1,
        lastLiveAt = 1,
    )
}