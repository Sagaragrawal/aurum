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
    val isBlinkDeal: Boolean? = null,
    val blinkDealPrice: Double? = null,
) {
    fun toProductCandidate(store: String, bullionRate24: Double? = null): CandidateParseResult {
        val acceptedPrice = price?.takeIf { it.isFinite() && it > 0 }
            ?: return CandidateParseResult.Rejected("invalid_price")

        // Permissive gold check: accept if metal is missing/null, contains "gold", or is "au"
        val isGold = metal == null || metal.isBlank() ||
            metal.contains("gold", ignoreCase = true) ||
            metal.contains("yellow", ignoreCase = true) ||
            metal.contains("au", ignoreCase = true)
        if (!isGold) return CandidateParseResult.Rejected("non_gold")

        val acceptedUrl = url?.takeIf { RetailerUrlPolicy.isAllowedProductUrl(store, it) }
            ?: return CandidateParseResult.Rejected("invalid_retailer_url")
        val acceptedRetailerId = retailerId?.trim()?.takeIf(String::isNotEmpty)
            ?: return CandidateParseResult.Rejected("invalid_identity")

        if (couponPrice != null && (!couponPrice.isFinite() || couponPrice <= 0)) {
            return CandidateParseResult.Rejected("invalid_coupon")
        }
        val acceptedCouponPrice = couponPrice?.takeIf { it < acceptedPrice }
        if (karat != null && (!karat.isFinite() || karat !in 1.0..24.0)) return CandidateParseResult.Rejected("invalid_karat")

        val rawNormalizedName = name?.trim()?.takeIf(String::isNotEmpty)?.let(ProductAvailability::displayName)
        val normalizedName = rawNormalizedName?.let { DatabaseSanitizerEngine.cleanTitle(it) }

        // Strict non-gold filter: reject silver, platinum, brass, copper, imitation, or gold-plated
        if (DatabaseSanitizerEngine.isNonGold(normalizedName.orEmpty(), acceptedUrl)) {
            return CandidateParseResult.Rejected("non_gold_product")
        }

        // Infer karat and purity if missing
        val resolvedKarat = DatabaseSanitizerEngine.resolveKarat(normalizedName.orEmpty(), karat)
        val resolvedPurity = DatabaseSanitizerEngine.resolvePurity(normalizedName.orEmpty(), purity)

        // Focus strictly on 24K pure gold (anything 995 and above)
        if (resolvedKarat != null && resolvedKarat < 24.0) {
            return CandidateParseResult.Rejected("non_24k_gold")
        }
        val purityNum = resolvedPurity?.toDoubleOrNull()
        if (purityNum != null && ((purityNum >= 1.0 && purityNum < 995.0) || (purityNum < 1.0 && purityNum < 0.995))) {
            return CandidateParseResult.Rejected("non_24k_purity")
        }
        if (Regex("\\b(?:22\\s*[kK]|22\\s*Kt|22Kt|22\\s*Karat|916|18\\s*[kK]|14\\s*[kK]|750|585)\\b", RegexOption.IGNORE_CASE).containsMatchIn(normalizedName.orEmpty())) {
            return CandidateParseResult.Rejected("non_24k_title")
        }

        // Parse weight using WeightExtractor with fallback to supplied grams
        val extractedWeight = (normalizedName ?: "").let { WeightExtractor.parse(it) }
        val rawUnitGrams = extractedWeight.unitWeightGrams ?: grams
        val qty = extractedWeight.quantity
        val rawTotalGrams = (extractedWeight.totalWeightGrams ?: grams)?.takeIf { it in 0.01..500.0 }
        val totalGrams = DatabaseSanitizerEngine.normalizeVendorWeight(rawTotalGrams, acceptedPrice)
        val unitGrams = if (qty > 1 && totalGrams != null) totalGrams / qty else (totalGrams ?: rawUnitGrams)
        
        if (!DatabaseSanitizerEngine.validatePricePlausibility(acceptedPrice, totalGrams, resolvedKarat, bullionRate24)) {
            return CandidateParseResult.Rejected("implausible_price")
        }
        
        val isMicro = DatabaseSanitizerEngine.isMicroCoin(totalGrams)

        return CandidateParseResult.Valid(ProductCandidate(
            store = store,
            retailerId = acceptedRetailerId,
            canonicalUrl = ProductIdentity.canonicalUrl(acceptedUrl),
            name = normalizedName,
            brand = brand?.trim()?.takeIf(String::isNotEmpty),
            price = acceptedPrice,
            couponPrice = acceptedCouponPrice,
            grams = totalGrams,
            karat = resolvedKarat,
            purity = resolvedPurity?.trim()?.takeIf(String::isNotEmpty),
            unavailable = unavailable == true || ProductAvailability.isUnavailableName(name),
            unitWeightGrams = unitGrams,
            quantity = qty,
            totalWeightGrams = totalGrams,
            weightConfidence = extractedWeight.confidence.name,
            isBlinkDeal = isBlinkDeal == true,
            blinkDealPrice = blinkDealPrice?.takeIf { it.isFinite() && it > 0 && it < acceptedPrice },
            isMicroCoin = isMicro,
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
    val unitWeightGrams: Double? = null,
    val quantity: Int = 1,
    val totalWeightGrams: Double? = null,
    val weightConfidence: String = "High",
    val isBlinkDeal: Boolean = false,
    val blinkDealPrice: Double? = null,
    val isMicroCoin: Boolean = false,
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
        metal = value.string("metal") ?: "gold",
        grams = value.firstNumber("grams", "weightGrams", "totalWeightGrams"),
        karat = value.number("karat"),
        purity = value["purity"]?.primitiveText(),
        unavailable = value.firstBoolean("unavailable", "outOfStock"),
        isBlinkDeal = value.firstBoolean("isBlinkDeal", "blinkDeal"),
        blinkDealPrice = value.firstNumber("blinkDealPrice", "specialPrice"),
    )

    private fun JsonObject.string(key: String): String? = get(key)?.primitiveText()
    private fun JsonObject.firstString(vararg keys: String): String? = keys.firstNotNullOfOrNull { key -> string(key) }
    private fun JsonObject.number(key: String): Double? = get(key)?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.firstNumber(vararg keys: String): Double? = keys.firstNotNullOfOrNull { key -> number(key) }
    private fun JsonObject.boolean(key: String): Boolean? = get(key)?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.firstBoolean(vararg keys: String): Boolean? = keys.firstNotNullOfOrNull { key -> boolean(key) }
    private fun JsonElement.primitiveText(): String? = runCatching { jsonPrimitive.content }.getOrNull()
}
