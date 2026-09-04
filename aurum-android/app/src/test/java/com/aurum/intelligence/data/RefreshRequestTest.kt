package com.aurum.intelligence.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshRequestTest {
    @Test
    fun `all resets scope to every available store`() {
        val stores = setOf("ajio.com", "amazon.in", "flipkart.com", "myntra.com", "shopsy.in")

        assertEquals(stores, RefreshRequest.all().targetStores(stores))
    }

    @Test
    fun `selection derives only visible product ids and stores`() {
        val visible = listOf(
            product("a", "ajio.com", "live"),
            product("m", "myntra.com", "stale"),
        )

        val request = RefreshRequest.selection(visible)

        assertEquals(setOf("a", "m"), request.productIds)
        assertEquals(setOf("ajio.com", "myntra.com"), request.targetStores(allStores))
    }

    @Test
    fun `stale only derives retryable visible products`() {
        val visible = listOf(
            product("live", "ajio.com", "live"),
            product("stale", "myntra.com", "stale"),
            product("unknown", "flipkart.com", "unverified"),
            product("failed", "amazon.in", "failed"),
            product("gone", "amazon.in", "unavailable"),
        )

        val request = RefreshRequest.staleOnly(visible)

        assertEquals(setOf("stale", "unknown", "failed", "gone"), request.productIds)
        assertEquals(setOf("myntra.com", "flipkart.com", "amazon.in"), request.targetStores(allStores))
    }

    @Test
    fun `product card retry is explicitly one store`() {
        val request = RefreshRequest.storeRetry(product("m", "myntra.com", "failed"))

        assertEquals(RefreshScope.StoreRetry, request.scope)
        assertEquals(setOf("m"), request.productIds)
        assertEquals(setOf("myntra.com"), request.targetStores(allStores))
    }

    @Test
    fun `myntra only selection never falls back to all stores`() {
        val request = RefreshRequest.selection(listOf(product("m", "myntra.com", "live")))

        assertEquals(setOf("myntra.com"), request.targetStores(allStores))
    }

    @Test
    fun `empty visible selection targets no stores`() {
        assertEquals(emptySet<String>(), RefreshRequest.selection(emptyList()).targetStores(allStores))
    }

    private fun product(id: String, store: String, status: String) = ProductEntity(
        id = id,
        store = store,
        retailerId = id,
        canonicalUrl = "https://www.$store/product/$id",
        name = id,
        brand = null,
        grams = 1.0,
        karat = 24.0,
        purity = "999",
        price = 1.0,
        couponPrice = null,
        status = status,
        refreshMethod = "test",
        checkedAt = 0,
        lastLiveAt = 0,
    )

    private companion object {
        val allStores = setOf("ajio.com", "amazon.in", "flipkart.com", "myntra.com", "shopsy.in")
    }
}