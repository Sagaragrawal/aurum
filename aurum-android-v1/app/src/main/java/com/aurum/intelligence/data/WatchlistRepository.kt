package com.aurum.intelligence.data

import androidx.room.withTransaction
import java.util.UUID

data class ProductEdits(
    val name: String,
    val brand: String?,
    val grams: Double?,
    val karat: Double?,
    val purity: String?,
    val price: Double,
    val couponPrice: Double?,
)

interface WatchlistDataSource {
    suspend fun productById(id: String): ProductEntity?
    suspend fun productByRetailerId(store: String, retailerId: String): ProductEntity?
    suspend fun productByCanonicalUrl(canonicalUrl: String): ProductEntity?
    suspend fun upsertProduct(product: ProductEntity)
    suspend fun insertPriceHistory(history: ProductPriceHistoryEntity)
    suspend fun deleteProduct(id: String)
}

class WatchlistRepository(
    private val dataSource: WatchlistDataSource,
    private val transaction: suspend (suspend () -> Unit) -> Unit = { operation -> operation() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun addProduct(url: String, name: String): ProductEntity {
        require(name.isNotBlank()) { "Name is required" }
        val address = ProductIdentity.derive(url)
        require(dataSource.productByRetailerId(address.store, address.retailerId) == null) {
            "This product is already on the watchlist"
        }
        require(dataSource.productByCanonicalUrl(address.canonicalUrl) == null) {
            "This product is already on the watchlist"
        }
        return ProductEntity(
            id = newId(),
            store = address.store,
            retailerId = address.retailerId,
            canonicalUrl = address.canonicalUrl,
            name = name.trim(),
            brand = null,
            grams = null,
            karat = null,
            purity = null,
            price = 0.0,
            couponPrice = null,
            status = "unverified",
            refreshMethod = "manual-add",
            checkedAt = 0,
            lastLiveAt = 0,
        ).also { dataSource.upsertProduct(it) }
    }

    suspend fun editProduct(id: String, edits: ProductEdits): ProductEntity {
        validate(edits)
        val existing = requireNotNull(dataSource.productById(id)) { "Product no longer exists" }
        val now = clock()
        val updated = existing.copy(
            name = edits.name.trim(),
            brand = edits.brand.normalized(),
            grams = edits.grams,
            karat = edits.karat,
            purity = edits.purity.normalized(),
            price = edits.price,
            couponPrice = edits.couponPrice,
            manuallyEditedAt = now,
        )
        transaction {
            dataSource.upsertProduct(updated)
            if (existing.price != updated.price || existing.couponPrice != updated.couponPrice) {
                dataSource.insertPriceHistory(
                    ProductPriceHistoryEntity(
                        productId = id,
                        price = updated.price,
                        couponPrice = updated.couponPrice,
                        checkedAt = now,
                    ),
                )
            }
        }
        return updated
    }

    suspend fun deleteProduct(id: String) = transaction { dataSource.deleteProduct(id) }

    private fun validate(edits: ProductEdits) {
        require(edits.name.isNotBlank()) { "Name is required" }
        require(edits.price >= 0) { "Price cannot be negative" }
        require(edits.couponPrice?.let { it >= 0 } != false) { "Coupon price cannot be negative" }
        require(edits.grams?.let { it > 0 } != false) { "Grams must be positive" }
        require(edits.karat?.let { it in 1.0..24.0 } != false) { "Karat must be between 1 and 24" }
    }

    private fun String?.normalized() = this?.trim()?.takeIf(String::isNotEmpty)
}

private class RoomWatchlistDataSource(private val dao: AurumDao) : WatchlistDataSource {
    override suspend fun productById(id: String) = dao.productById(id)
    override suspend fun productByRetailerId(store: String, retailerId: String) =
        dao.productByRetailerId(store, retailerId)
    override suspend fun productByCanonicalUrl(canonicalUrl: String) = dao.productByCanonicalUrl(canonicalUrl)
    override suspend fun upsertProduct(product: ProductEntity) = dao.upsertProduct(product)
    override suspend fun insertPriceHistory(history: ProductPriceHistoryEntity) = dao.insertPriceHistory(history)
    override suspend fun deleteProduct(id: String) {
        dao.deleteProductHistory(id)
        dao.deleteProduct(id)
    }
}

fun AurumDatabase.createWatchlistRepository() = WatchlistRepository(
    dataSource = RoomWatchlistDataSource(dao()),
    transaction = { operation -> withTransaction { operation() } },
)