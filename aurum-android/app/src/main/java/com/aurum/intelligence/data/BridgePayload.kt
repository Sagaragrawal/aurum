package com.aurum.intelligence.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class BridgePayload(
    val store: String,
    val records: List<BridgeRecord>,
)

data class BridgeRecord(
    val retailerId: String?,
    val url: String?,
    val name: String? = null,
    val brand: String? = null,
    val price: Double? = null,
    val couponPrice: Double? = null,
    val metal: String? = null,
    val grams: Double? = null,
    val karat: Double? = null,
    val purity: String? = null,
    val unavailable: Boolean? = null,
) {
    fun toProductCandidate(store: String): CandidateParseResult {
        val acceptedPrice = price?.takeIf { it.isFinite() && it > 0 }
            ?: return CandidateParseResult.Rejected("invalid_price")
        if (!metal.equals("gold", ignoreCase = true)) return CandidateParseResult.Rejected("non_gold")
        val acceptedUrl = url?.takeIf { RetailerUrlPolicy.isAllowedProductUrl(store, it) }
            ?: return CandidateParseResult.Rejected("invalid_retailer_url")
        val acceptedRetailerId = retailerId?.trim()?.takeIf(String::isNotEmpty)
            ?: return CandidateParseResult.Rejected("invalid_identity")
        if (couponPrice != null && (!couponPrice.isFinite() || couponPrice <= 0)) {
            return CandidateParseResult.Rejected("invalid_coupon")
        }
        val acceptedCouponPrice = couponPrice?.takeIf { it < acceptedPrice }
        if (grams != null && (!grams.isFinite() || grams <= 0)) return CandidateParseResult.Rejected("invalid_weight")
        if (karat != null && (!karat.isFinite() || karat !in 1.0..24.0)) return CandidateParseResult.Rejected("invalid_karat")
        val normalizedName = name?.trim()?.takeIf(String::isNotEmpty)?.let(ProductAvailability::displayName)
        return CandidateParseResult.Valid(ProductCandidate(
            store = store,
            retailerId = acceptedRetailerId,
            canonicalUrl = ProductIdentity.canonicalUrl(acceptedUrl),
            name = normalizedName,
            brand = brand?.trim()?.takeIf(String::isNotEmpty),
            price = acceptedPrice,
            couponPrice = acceptedCouponPrice,
            grams = grams,
            karat = karat,
            purity = purity?.trim()?.takeIf(String::isNotEmpty),
            unavailable = unavailable == true || ProductAvailability.isUnavailableName(name),
        ))
    }
}

sealed interface CandidateParseResult {
    data class Valid(val candidate: ProductCandidate) : CandidateParseResult
    data class Rejected(val reason: String) : CandidateParseResult
}

data class ProductCandidate(
    val store: String,
    val retailerId: String,
    val canonicalUrl: String,
    val name: String?,
    val brand: String?,
    val price: Double,
    val couponPrice: Double?,
    val grams: Double?,
    val karat: Double?,
    val purity: String?,
    val unavailable: Boolean,
)

object ProductAvailability {
    private val unavailableMarker = Regex("\\s*[-|:]?\\s*(?:not\\s+deliverable|unavailable|out\\s+of\\s+stock)\\s*", RegexOption.IGNORE_CASE)

    fun isUnavailableName(name: String?): Boolean = name?.let(unavailableMarker::containsMatchIn) == true

    fun displayName(name: String): String = name.replace(unavailableMarker, " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
}

object RetailerUrlPolicy {
    private val allowedHosts = setOf("ajio.com", "amazon.in", "flipkart.com", "myntra.com", "shopsy.in")

    fun isAllowedProductUrl(store: String, value: String): Boolean = runCatching {
        val uri = java.net.URI(value)
        uri.scheme.equals("https", ignoreCase = true) &&
            store in allowedHosts &&
            isAllowedRetailerHost(uri.host, store)
    }.getOrDefault(false)

    fun isAllowedRetailerHost(host: String?, store: String? = null): Boolean {
        val normalizedHost = host?.lowercase() ?: return false
        return allowedHosts.any { allowed ->
            (store == null || store == allowed) && (normalizedHost == allowed || normalizedHost.endsWith(".$allowed"))
        }
    }
}

object BridgePayloadParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val stores = setOf("ajio.com", "amazon.in", "flipkart.com", "myntra.com", "shopsy.in")

    fun parse(raw: String): BridgePayload {
        val root = json.parseToJsonElement(raw).jsonObject
        val store = root.string("store")?.lowercase()
            ?.takeIf(stores::contains)
            ?: throw IllegalArgumentException("Unsupported bridge store")
        val records = root["records"]?.jsonArray
            ?: throw IllegalArgumentException("Bridge records must be an array")
        require(records.size <= 10_000) { "Bridge payload exceeds record limit" }
        return BridgePayload(store, records.map { parseRecord(store, it.jsonObject) })
    }

    private fun parseRecord(store: String, value: JsonObject): BridgeRecord = BridgeRecord(
        retailerId = when (store) {
            "ajio.com" -> value.firstString("code", "id", "productCode")
            "amazon.in" -> value.firstString("asin", "id")
            else -> value.firstString("productId", "pid", "id")
        },
        url = value.firstString("url", "link", "landingPageUrl"),
        name = value.firstString("name", "productName"),
        brand = value.string("brand"),
        price = value.number("price"),
        couponPrice = value.firstNumber("couponPrice", "offerPrice"),
        metal = value.string("metal"),
        grams = value.firstNumber("grams", "weightGrams", "totalWeightGrams"),
        karat = value.number("karat"),
        purity = value["purity"]?.primitiveText(),
        unavailable = value.firstBoolean("unavailable", "outOfStock"),
    )

    private fun JsonObject.string(key: String): String? = get(key)?.primitiveText()
    private fun JsonObject.firstString(vararg keys: String): String? = keys.firstNotNullOfOrNull { key -> string(key) }
    private fun JsonObject.number(key: String): Double? = get(key)?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.firstNumber(vararg keys: String): Double? = keys.firstNotNullOfOrNull { key -> number(key) }
    private fun JsonObject.boolean(key: String): Boolean? = get(key)?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.firstBoolean(vararg keys: String): Boolean? = keys.firstNotNullOfOrNull { key -> boolean(key) }
    private fun JsonElement.primitiveText(): String? = runCatching { jsonPrimitive.content }.getOrNull()
}