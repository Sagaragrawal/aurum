package com.aurum.intelligence.data.engine

/**
 * Common contract for isolated store scraper engines.
 * Each store implementation (AJIO, Amazon, Flipkart, Shopsy, Myntra) handles its own
 * discovery, session state, PLP pagination, PDP verification, and database persistence.
 */
interface StoreScraperEngine {
    val storeKey: String

    suspend fun refreshStore(
        pincode: String,
        bullionRate24: Double?,
        maxPages: Int,
        onProgress: (StoreRefreshProgress) -> Unit,
    ): StoreRefreshProgress
}
