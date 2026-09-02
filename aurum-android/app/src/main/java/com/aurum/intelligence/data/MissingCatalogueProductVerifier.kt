package com.aurum.intelligence.data

import java.net.HttpURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
                    "${product.store}:${product.retailerId}" !in acceptedIdentityKeys)
        }
        return@withContext refreshCandidates(candidates, fetcher, onProgress)
    }

    private suspend fun refreshCandidates(
        candidates: List<ProductEntity>,
        fetcher: suspend (String) -> ProductFetchResponse?,
        onProgress: suspend (Int, Int, ProductEntity) -> Unit,
    ): MissingCatalogueProductResult {
        var updated = 0
        var unavailable = 0
        var unchanged = 0
        val details = mutableListOf<ProductRefreshDetail>()
        candidates.forEachIndexed { index, product ->
            if (index > 0) delay(REQUEST_DELAY_MS)
            onProgress(index + 1, candidates.size, product)
            when (val result = fetchWithRetry(product, fetcher)) {
                is ProductLookup.Available -> {
                    val now = System.currentTimeMillis()
                    database.dao().upsertProduct(product.copy(
                        name = result.name ?: product.name,
                        brand = result.brand ?: product.brand,
                        price = result.price,
                        grams = result.grams ?: product.grams,
                        couponPrice = null,
                        status = "live",
                        refreshMethod = result.refreshMethod,
                        checkedAt = now,
                        lastLiveAt = now,
                    ))
                    updated += 1
                    details += ProductRefreshDetail(product.canonicalUrl, result.price, result.grams ?: product.grams, product.karat, "live")
                }
                is ProductLookup.Unavailable -> {
                    val now = System.currentTimeMillis()
                    database.dao().upsertProduct(product.copy(
                        price = result.price ?: product.price,
                        couponPrice = null,
                        status = "unavailable",
                        refreshMethod = "${product.store}-product-api",
                        checkedAt = now,
                    ))
                    unavailable += 1
                    details += ProductRefreshDetail(product.canonicalUrl, result.price ?: product.price, product.grams, product.karat, "unavailable")
                }
                ProductLookup.Unknown -> {
                    unchanged += 1
                    details += ProductRefreshDetail(product.canonicalUrl, null, product.grams, product.karat, "unresolved")
                }
            }
        }
        return MissingCatalogueProductResult(candidates.size, updated, unavailable, unchanged, details)
    }

    suspend fun refreshProduct(productId: String, fetcher: suspend (String) -> ProductFetchResponse?): ProductLookup {
        val product = database.dao().productById(productId) ?: return ProductLookup.Unknown
        return fetchProduct(product, fetcher).also { result -> applyResult(product, result) }
    }

    private suspend fun fetchProduct(product: ProductEntity, fetcher: suspend (String) -> ProductFetchResponse?): ProductLookup =
        endpointFor(product)?.let { endpoint ->
            fetcher(endpoint)?.let { ProductLookup.parse(product.store, it.status, it.body, endpoint) }
        } ?: ProductLookup.Unknown

    private suspend fun fetchWithRetry(product: ProductEntity, fetcher: suspend (String) -> ProductFetchResponse?): ProductLookup {
        return fetchProduct(product, fetcher)
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
                    couponPrice = null,
                    status = "live",
                    refreshMethod = result.refreshMethod,
                    checkedAt = now,
                    lastLiveAt = now,
                ))
            }
            is ProductLookup.Unavailable -> database.dao().upsertProduct(product.copy(
                price = result.price ?: product.price,
                couponPrice = null,
                status = "unavailable",
                refreshMethod = "${product.store}-product-api",
                checkedAt = System.currentTimeMillis(),
            ))
            ProductLookup.Unknown -> Unit
        }
    }

    private fun endpointFor(product: ProductEntity): String? = when (product.store) {
            "ajio.com" -> "https://www.ajio.com/api/p/${product.retailerId}"
            "myntra.com" -> "https://www.myntra.com/gateway/v2/product/${product.retailerId}"
            "amazon.in", "flipkart.com" -> product.canonicalUrl
            else -> null
        }

    private companion object {
        const val REQUEST_DELAY_MS = 300L
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
data class ProductFetchResponse(val status: Int, val body: String)

sealed interface ProductLookup {
    data class Available(val price: Double, val name: String?, val brand: String?, val grams: Double?, val refreshMethod: String) : ProductLookup
    data class Unavailable(val price: Double? = null) : ProductLookup
    data object Unknown : ProductLookup

    companion object {
        private val unavailableTerms = listOf("out of stock", "sold out", "no longer available", "product is not available", "currently unavailable", "not deliverable at your location")

        fun parse(store: String, statusCode: Int, body: String, sourceUrl: String = ""): ProductLookup {
            if (statusCode == HttpURLConnection.HTTP_NOT_FOUND) return Unavailable()
            if (statusCode !in 200..299) return Unknown
            if (body.isBlank() || body.contains("access denied", ignoreCase = true) ||
                body.contains("request blocked", ignoreCase = true) ||
                body.contains("captcha", ignoreCase = true)
            ) return Unknown
            val unavailable = unavailableTerms.any { body.contains(it, ignoreCase = true) } ||
                (store == "myntra.com" && Regex("\\\"outOfStock\\\"\\s*:\\s*true", RegexOption.IGNORE_CASE).containsMatchIn(body)) ||
                (store == "ajio.com" && (body.contains("\"purchasable\":false") || body.contains("\"stockLevelStatus\":\"outOfStock\"", ignoreCase = true)))
            val price = when (store) {
                "amazon.in" -> amazonPrice(body)
                "flipkart.com" -> flipkartPrice(body)
                else -> priceKeys(store).firstNotNullOfOrNull { key -> priceFor(body, key) }
            }?.takeIf { it.isFinite() && it > 0 }
            if (unavailable) return Unavailable(price)
            val grams = extractGrams("$body $sourceUrl")
            if (price == null) return Unknown
            return Available(
                price = price,
                name = stringFor(body, "name"),
                brand = stringFor(body, "brand"),
                grams = grams,
                refreshMethod = if (store in setOf("ajio.com", "myntra.com")) "$store-product-api" else "$store-product-page",
            )
        }

        private fun extractGrams(body: String): Double? {
            val text = body
                .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
            val explicit = Regex("(\\d+(?:\\.\\d+)?)\\s*(mg|gms|gm|grams|gram|g)\\b", RegexOption.IGNORE_CASE)
                .findAll(text)
                .mapNotNull { match ->
                    val amount = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
                    val unit = match.groupValues[2].lowercase()
                    val grams = if (unit == "mg") amount / 1000.0 else amount
                    grams.takeIf { it > 0 && it <= 1000 }
                }
                .toList()
            if (explicit.isNotEmpty()) return explicit.maxOrNull()
            val slug = Regex("(?:^|[-_/])(\\d+(?:\\.\\d+)?)\\s*-?\\s*(mg|gms|gm|grams|gram|g)(?=[-_/\\d]|$)", RegexOption.IGNORE_CASE)
                .find(body)
            val amount = slug?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
            return if (slug.groupValues[2].equals("mg", ignoreCase = true)) amount / 1000.0 else amount
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