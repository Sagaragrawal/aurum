package com.aurum.intelligence.data

import android.content.res.AssetManager
import androidx.room.withTransaction
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

object DesktopProductSeedParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): List<ProductEntity> = json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
        runCatching {
            val value = element as JsonObject
            val url = value.text("url") ?: return@runCatching null
            val address = ProductIdentity.derive(url)
            val price = value.number("price") ?: 0.0
            val purity = value.text("purity")
            ProductEntity(
                id = value.text("id") ?: UUID.randomUUID().toString(),
                store = address.store,
                retailerId = address.retailerId,
                canonicalUrl = address.canonicalUrl,
                name = ProductAvailability.displayName(value.text("name") ?: address.retailerId),
                brand = value.text("brand"),
                grams = value.number("grams")?.takeIf { it > 0 },
                karat = value.number("karat")?.takeIf { it > 0 } ?: purity?.karatFromPurity(),
                purity = purity,
                price = price.takeIf { it > 0 } ?: 0.0,
                couponPrice = value.number("couponPrice")?.takeIf { it > 0 },
                status = if (ProductAvailability.isUnavailableName(value.text("name"))) "unavailable" else value.text("status")?.takeIf(VALID_STATUSES::contains)
                    ?: if (price > 0) "stale" else "unverified",
                refreshMethod = value.text("refreshMethod") ?: "desktop-seed",
                checkedAt = value.timestamp("checkedAt"),
                lastLiveAt = value.timestamp("lastLiveAt"),
                manuallyEditedAt = value.timestampOrNull("manuallyEditedAt"),
            )
        }.getOrNull()
    }

    private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content
        ?.takeUnless { it == "null" || it.isBlank() }
    private fun JsonObject.number(key: String): Double? = get(key)?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.timestamp(key: String): Long = timestampOrNull(key) ?: 0L
    private fun JsonObject.timestampOrNull(key: String): Long? = text(key)?.let { value ->
        value.toLongOrNull() ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
    }
    private fun String.karatFromPurity(): Double? = when {
        contains("24", ignoreCase = true) || this in setOf("999", "999.9", "9999", "995") -> 24.0
        contains("22", ignoreCase = true) || this == "916" -> 22.0
        contains("18", ignoreCase = true) || this == "750" -> 18.0
        contains("14", ignoreCase = true) || this == "585" -> 14.0
        else -> null
    }

    private val VALID_STATUSES = setOf("live", "stale", "unverified", "unavailable", "failed")
}

class DesktopProductSeeder(
    private val database: AurumDatabase,
    private val assets: AssetManager,
) {
    suspend fun seedIfEmpty(): Int {
        val parsed = PRODUCT_FILES.flatMap { file ->
            assets.open("seed/products/$file").bufferedReader().use { reader ->
                DesktopProductSeedParser.parse(reader.readText())
            }
        }
        val products = dedupeDesktopSeed(parsed)
        return database.withTransaction {
            var inserted = 0
            products.forEach { product ->
                val retailerMatch = database.dao().productByRetailerId(product.store, product.retailerId)
                val urlMatch = database.dao().productByCanonicalUrl(product.canonicalUrl)
                if (retailerMatch == null && urlMatch == null) {
                    database.dao().upsertProduct(product)
                    inserted += 1
                }
            }
            inserted
        }
    }

    private companion object {
        val PRODUCT_FILES = listOf("ajio-com.json", "amazon-in.json", "flipkart-com.json", "myntra-com.json")
    }
}

internal fun dedupeDesktopSeed(products: List<ProductEntity>): List<ProductEntity> {
    fun rank(product: ProductEntity): Int = when {
        product.status == "live" -> 3
        product.price > 0 -> 2
        product.checkedAt > 0 -> 1
        else -> 0
    }
    fun dedupeBy(items: List<ProductEntity>, key: (ProductEntity) -> String): List<ProductEntity> {
        val selected = linkedMapOf<String, ProductEntity>()
        items.forEach { product ->
            val identity = key(product)
            val existing = selected[identity]
            if (existing == null || rank(product) > rank(existing)) selected[identity] = product
        }
        return selected.values.toList()
    }
    return dedupeBy(
        dedupeBy(products) { product -> "${product.store}|${product.retailerId}" },
    ) { product -> product.canonicalUrl }
}