package com.aurum.intelligence.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WatchlistRepositoryTest {
    @Test
    fun addEditAndDeleteProduct() {
        runBlocking {
        val dataSource = FakeWatchlistDataSource()
        val repository = WatchlistRepository(dataSource, clock = { 1_234L }, newId = { "local-id" })

        val added = repository.addProduct(
            url = "https://www.amazon.in/dp/B0CSNFCVPX?tag=noise",
            name = "Gold coin",
        )

        assertEquals("local-id", added.id)
        assertEquals("amazon.in", added.store)
        assertEquals("B0CSNFCVPX", added.retailerId)
        assertEquals("unverified", added.status)
        assertEquals(0.0, added.price, 0.0)
        assertNull(added.manuallyEditedAt)

        val edited = repository.editProduct(
            id = added.id,
            edits = ProductEdits(
                name = "24K Gold coin",
                brand = "Aurum",
                grams = 1.0,
                karat = 24.0,
                purity = "999",
                price = 9_000.0,
                couponPrice = 8_750.0,
            ),
        )

        assertEquals(1_234L, edited.manuallyEditedAt)
        assertEquals(8_750.0, edited.couponPrice!!, 0.0)
        assertEquals(1, dataSource.history.size)
        assertEquals(9_000.0, dataSource.history.single().price, 0.0)

        repository.deleteProduct(added.id)
        assertNull(dataSource.products[added.id])
        }
    }

    @Test
    fun duplicateRetailerIdentityIsRejected() {
        runBlocking {
        val dataSource = FakeWatchlistDataSource()
        val repository = WatchlistRepository(dataSource, newId = { "first" })
        repository.addProduct("https://www.flipkart.com/item?pid=ABC123", "Coin")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.addProduct("https://www.flipkart.com/other?pid=ABC123&iid=noise", "Duplicate")
            }
        }
        assertEquals(1, dataSource.products.size)
        }
    }

    @Test
    fun editRequiresExistingProductAndValidValues() {
        runBlocking {
        val repository = WatchlistRepository(FakeWatchlistDataSource())
        val valid = ProductEdits("Coin", null, 1.0, 24.0, "999", 1.0, null)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.editProduct("missing", valid) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.addProduct("https://example.com/product", "Coin") }
        }
        }
    }
}

private class FakeWatchlistDataSource : WatchlistDataSource {
    val products = linkedMapOf<String, ProductEntity>()
    val history = mutableListOf<ProductPriceHistoryEntity>()

    override suspend fun productById(id: String): ProductEntity? = products[id]

    override suspend fun productByRetailerId(store: String, retailerId: String): ProductEntity? =
        products.values.firstOrNull { it.store == store && it.retailerId == retailerId }

    override suspend fun productByCanonicalUrl(canonicalUrl: String): ProductEntity? =
        products.values.firstOrNull { it.canonicalUrl == canonicalUrl }

    override suspend fun upsertProduct(product: ProductEntity) {
        products[product.id] = product
    }

    override suspend fun insertPriceHistory(history: ProductPriceHistoryEntity) {
        this.history += history
    }

    override suspend fun deleteProduct(id: String) {
        assertNotNull(products.remove(id))
    }
}