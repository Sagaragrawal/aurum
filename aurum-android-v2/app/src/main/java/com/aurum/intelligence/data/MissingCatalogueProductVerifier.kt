package com.aurum.intelligence.data

import java.net.HttpURLConnection
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MissingCatalogueProductVerifier(private val database: AurumDatabase) {
    suspend fun refreshProducts(
        store: String,
        productIds: Set<String>,
        fetcher: suspend (String) -> ProductFetchResponse?,
    ): MissingCatalogueProductResult = withContext(Dispatchers.IO) {
        val candidates = database.dao().allProducts().filter { product ->
            product.store == store && product.id in productIds
        }
        return@withContext refreshCandidates(candidates, fetcher) { _, _, _ -> }
    }

    suspend fun refreshMissingProducts(
        store: String,
        acceptedIdentityKeys: Set<String>,
        targetProductIds: Set<String>,
        fetcher: suspend (String) -> ProductFetchResponse?,
        onProgress: suspend (Int, Int, ProductEntity) -> Unit = { _, _, _ -> },
    ): MissingCatalogueProductResult = withContext(Dispatchers.IO) {
        val candidates = database.dao().allProducts().filter { product ->
            product.store == store &&
                (targetProductIds.isEmpty() || product.id in targetProductIds) &&
                (product.status != "live" ||
                    "${product.store}:${product.retailerId}" !in acceptedIdentityKeys ||
                    product.weightConfidence != "High" || product.totalWeightGrams == null)
        }
        return@withContext refreshCandidates(candidates, fetcher, onProgress)
    }

    private suspend fun refreshCandidates(
        candidates: List<ProductEntity>,
        fetcher: suspend (String) -> ProductFetchResponse?,
        onProgress: suspend (Int, Int, ProductEntity) -> Unit,
    ): MissingCatalogueProductResult = coroutineScope {
        var updated = 0
        var unavailable = 0
        var unchanged = 0
        val details = java.util.Collections.synchronizedList(mutableListOf<ProductRefreshDetail>())

        val total = candidates.size
        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val semaphore = Semaphore(PARALLEL_CONCURRENCY)

        candidates.map { product ->
            async {
                semaphore.withPermit {
                    val currentCount = completedCount.incrementAndGet()
                    onProgress(currentCount, total, product)
                    when (val result = fetchWithRetry(product, fetcher)) {
                        is ProductLookup.Available -> {
                            val now = System.currentTimeMillis()
                            database.dao().upsertProduct(product.copy(
                                name = result.name ?: product.name,
                                brand = result.brand ?: product.brand,
                                price = result.price,
                                grams = result.grams ?: product.grams,
                                couponPrice = result.couponPrice,
                                status = "live",
                                refreshMethod = result.refreshMethod,
                                checkedAt = now,
                                lastLiveAt = now,
                                weightConfidence = result.weightConfidence,
                                isBlinkDeal = result.isBlinkDeal,
                                blinkDealPrice = result.blinkDealPrice,
                                deliverable = true,
                            ))
                            synchronized(details) {
                                updated += 1
                                details += ProductRefreshDetail(product.canonicalUrl, result.price, result.grams ?: product.grams, product.karat, "live")
                            }
                        }
                        is ProductLookup.Unavailable -> {
                            val now = System.currentTimeMillis()
                            database.dao().upsertProduct(product.copy(
                                price = result.price ?: product.price,
                                couponPrice = null,
                                status = "unavailable",
                                refreshMethod = "${product.store}-product-api",
                                checkedAt = now,
                                deliverable = false,
                            ))
                            synchronized(details) {
                                unavailable += 1
                                details += ProductRefreshDetail(product.canonicalUrl, result.price ?: product.price, product.grams, product.karat, "unavailable")
                            }
                        }
                        ProductLookup.Unknown -> {
                            val now = System.currentTimeMillis()
                            database.dao().upsertProduct(product.copy(
                                status = "unavailable",
                                refreshMethod = "${product.store}-missing-catalogue",
                                checkedAt = now,
                                deliverable = false,
                            ))
                            synchronized(details) {
                                unavailable += 1
                                details += ProductRefreshDetail(product.canonicalUrl, product.price, product.grams, product.karat, "unavailable")
                            }
                        }
                    }
                }
            }
        }.awaitAll()

        MissingCatalogueProductResult(candidates.size, updated, unavailable, unchanged, details)
    }

    suspend fun refreshProduct(productId: String, fetcher: suspend (String) -> ProductFetchResponse?): ProductLookup {
        val product = database.dao().productById(productId) ?: return ProductLookup.Unknown
        return fetchProduct(product, fetcher).also { result -> applyResult(product, result) }
    }

    private suspend fun fetchProduct(product: ProductEntity, fetcher: suspend (String) -> ProductFetchResponse?): ProductLookup =
        withTimeoutOrNull(5000L) {
            endpointFor(product)?.let { endpoint ->
                fetcher(endpoint)?.let { ProductLookup.parse(product.store, it.status, it.body, endpoint) }
            }
        } ?: ProductLookup.Unknown

    private suspend fun fetchWithRetry(product: ProductEntity, fetcher: suspend (String) -> ProductFetchResponse?): ProductLookup {
        var attempt = 0
        val maxAttempts = 2
        var delayMs = 300L

        while (attempt < maxAttempts) {
            attempt++
            val result = fetchProduct(product, fetcher)
            if (result !is ProductLookup.Unknown) return result

            if (attempt < maxAttempts) {
                val jitter = Random.nextLong(50L, 200L)
                delay(delayMs + jitter)
                delayMs = (delayMs * 1.5).toLong().coerceAtMost(2000L)
            }
        }
        return ProductLookup.Unknown
    }

    private suspend fun applyResult(product: ProductEntity, result: ProductLookup) {
        when (result) {
            is ProductLookup.Available -> {
                val now = System.currentTimeMillis()
                database.dao().upsertProduct(product.copy(
                    name = result.name ?: product.name,
                    brand = result.brand ?: product.brand,
                    price = result.price,
                    grams = result.grams ?: product.grams,
                    couponPrice = result.couponPrice,
                    status = "live",
                    refreshMethod = result.refreshMethod,
                    checkedAt = now,
                    lastLiveAt = now,
                    weightConfidence = result.weightConfidence,
                    isBlinkDeal = result.isBlinkDeal,
                    blinkDealPrice = result.blinkDealPrice,
                    deliverable = true,
                ))
            }
            is ProductLookup.Unavailable -> database.dao().upsertProduct(product.copy(
                price = result.price ?: product.price,
                couponPrice = null,
                status = "unavailable",
                refreshMethod = "${product.store}-product-api",
                checkedAt = System.currentTimeMillis(),
                deliverable = false,
            ))
            ProductLookup.Unknown -> Unit
        }
    }

    private fun endpointFor(product: ProductEntity): String? = when (product.store) {
            "ajio.com" -> "https://www.ajio.com/api/p/${product.retailerId}"
            "myntra.com", "amazon.in", "flipkart.com", "shopsy.in" -> product.canonicalUrl
            else -> null
        }

    private companion object {
        const val PARALLEL_CONCURRENCY = 30
    }
}

data class ProductRefreshDetail(
    val url: String,
    val price: Double?,
    val grams: Double?,
    val karat: Double?,
    val status: String,
)

data class MissingCatalogueProductResult(
    val checked: Int,
    val updated: Int,
    val unavailable: Int,
    val unchanged: Int,
    val details: List<ProductRefreshDetail> = emptyList(),
)
data class ProductFetchResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
    val protocol: String = "",
    val durationMs: Long = 0L,
)

sealed interface ProductLookup {
    data class Available(
        val price: Double,
        val couponPrice: Double? = null,
        val name: String?,
        val brand: String?,
        val grams: Double?,
        val weightConfidence: String,
        val refreshMethod: String,
        val isBlinkDeal: Boolean = false,
        val blinkDealPrice: Double? = null,
    ) : ProductLookup

    data class Unavailable(val price: Double? = null) : ProductLookup
    data object Unknown : ProductLookup

    companion object {
        private val unavailableTerms = listOf(
            "out of stock",
            "sold out",
            "no longer available",
            "product is not available",
            "currently unavailable",
            "not deliverable at your location",
            "not deliverable",
            "change address",
            "check deliverability",
            "cannot be delivered",
            "item is unavailable",
            "not available at",
            "enter pincode",
            "please enter pincode",
            "servicable: false",
            "deliverable: false",
            "isAvailable\":false",
            "unserviceable",
            "pincode not serviceable",
            "out of stock at pincode"
        )

        fun parse(store: String, statusCode: Int, body: String, sourceUrl: String = ""): ProductLookup {
            if (statusCode == HttpURLConnection.HTTP_NOT_FOUND) return Unavailable()
            if (statusCode !in 200..299) return Unknown
            if (body.isBlank() || body.contains("access denied", ignoreCase = true) ||
                body.contains("request blocked", ignoreCase = true) ||
                body.contains("captcha", ignoreCase = true)
            ) return Unknown

            val unavailable = unavailableTerms.any { body.contains(it, ignoreCase = true) } ||
                (store == "myntra.com" && (
                    body.contains("\"outOfStock\":true", ignoreCase = true) ||
                    body.contains("\"outOfStock\": true", ignoreCase = true) ||
                    body.contains("\"buyNowEnabled\":false", ignoreCase = true) ||
                    body.contains("\"buyNowEnabled\": false", ignoreCase = true)
                )) ||
                (store == "ajio.com" && (
                    body.contains("\"purchasable\":false", ignoreCase = true) ||
                    body.contains("\"stockLevelStatus\":\"outOfStock\"", ignoreCase = true) ||
                    body.contains("\"outOfStock\":true", ignoreCase = true) ||
                    body.contains("\"fnlColorVariantData\":null", ignoreCase = true)
                ))

            val price = when (store) {
                "amazon.in" -> amazonPrice(body)
                "flipkart.com", "shopsy.in" -> flipkartPrice(body)
                else -> priceKeys(store).firstNotNullOfOrNull { key -> priceFor(body, key) }
            }?.takeIf { it.isFinite() && it > 0 }

            if (unavailable) return Unavailable(price)

            val parsedName = stringFor(body, "name") ?: stringFor(body, "productDisplayName") ?: stringFor(body, "title")
            if (parsedName != null && DatabaseSanitizerEngine.isNonGold(parsedName, "$body $sourceUrl")) {
                return Unavailable(price)
            }
            val extractedWeight = (parsedName ?: "$body $sourceUrl").let { WeightExtractor.parse(it, body) }
            val grams = extractedWeight.totalWeightGrams

            if (price == null) return Unknown

            val isBlink = body.contains("blink", ignoreCase = true) || body.contains("flash", ignoreCase = true)
            val blinkPrice = if (isBlink) priceFor(body, "blinkDealPrice") ?: priceFor(body, "specialPrice") else null

            val couponPrice = priceFor(body, "couponDiscount")?.let { discount -> (price - discount).coerceAtLeast(0.0) }
                ?: priceFor(body, "offerPrice")?.takeIf { it < price }

            return Available(
                price = price,
                couponPrice = couponPrice,
                name = parsedName,
                brand = stringFor(body, "brand"),
                grams = grams,
                weightConfidence = extractedWeight.confidence.name,
                refreshMethod = if (store in setOf("ajio.com", "myntra.com")) "$store-product-api" else "$store-product-page",
                isBlinkDeal = isBlink,
                blinkDealPrice = blinkPrice?.takeIf { it < price },
            )
        }

        private fun priceKeys(store: String): List<String> = when (store) {
            "ajio.com" -> listOf("promoDiscountedPrice", "offerPrice", "value", "mrp")
            "myntra.com" -> listOf("discountedPrice", "discounted", "mrp")
            else -> emptyList()
        }

        private fun amazonPrice(body: String): Double? = listOf(
            Regex("id=[\\\"']corePriceDisplay_desktop_feature_div[\\\"'][\\s\\S]{0,3000}?class=[\\\"'][^\\\"']*a-price-whole[^\\\"']*[\\\"'][^>]*>([\\d,]+)", RegexOption.IGNORE_CASE),
            Regex("id=[\\\"']priceblock_(?:ourprice|dealprice|saleprice)[\\\"'][^>]*>\\s*[₹\\s]*([\\d,]+)", RegexOption.IGNORE_CASE),
            Regex("[\\\"']priceToPay[\\\"'][\\s\\S]{0,800}?[\\\"']priceAmount[\\\"']\\s*:\\s*([\\d.]+)", RegexOption.IGNORE_CASE),
            Regex("[\\\"'](?:priceAmount|currentPrice|price|amount)[\\\"']\\s*:\\s*\\\"?([\\d,]+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE),
            Regex("(?:₹|Rs\\.?)\\s*([\\d,]+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE),
            Regex("<meta[^>]+(?:property|name)=[\\\"'](?:product:price:amount|og:price:amount)[\\\"'][^>]+content=[\\\"']([\\d,]+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE),
        ).firstNotNullOfOrNull { expression -> expression.find(body)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() }

        private fun flipkartPrice(body: String): Double? = listOf(
            Regex("\\?\\\"(?:sellingPrice|selling_price|currentPrice|current_price|priceAmount|price|amount)\\?\\\"\\s*:\\s*\\?\\\"?([\\d,]+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE),
            Regex("[\\\"'](?:sellingPrice|selling_price|currentPrice|current_price|priceAmount|price|amount)[\\\"']\\s*:\\s*\\\"?([\\d,]+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE),
            Regex("[\\\"']price[\\\"']\\s*:\\s*\\{[\\s\\S]{0,160}?[\\\"'](?:value|amount)[\\\"']\\s*:\\s*\\\"?([\\d,]+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE),
            Regex("(?:₹|Rs\\.?)\\s*([\\d,]+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE),
            Regex("<meta[^>]+(?:property|name)=[\\\"'](?:product:price:amount|og:price:amount)[\\\"'][^>]+content=[\\\"']([\\d,]+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE),
        ).firstNotNullOfOrNull { expression -> expression.find(body)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() }

        private fun priceFor(body: String, key: String): Double? = listOf(
            Regex("\\\"$key\\\"\\s*[:>]\\s*\\\"?([\\d,]+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE),
            Regex("\\\"$key\\\"\\s*:\\s*\\{[\\s\\S]{0,160}?[\\\"'](?:value|amount)[\\\"']\\s*:\\s*\\\"?([\\d,]+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE),
        ).firstNotNullOfOrNull { expression -> expression.find(body)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() }

        private fun stringFor(body: String, key: String): String? =
            Regex("\\\"$key\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(body)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty)
    }
}
