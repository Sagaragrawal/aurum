package com.aurum.intelligence.parsers
import com.aurum.intelligence.data.db.*
import com.aurum.intelligence.data.engine.*
import com.aurum.intelligence.data.model.*
import com.aurum.intelligence.data.repository.*
import com.aurum.intelligence.data.validation.*

import org.json.JSONObject

object AjioNativeParser {

    data class ParseResult(
        val candidates: List<ProductCandidate>,
        val totalResults: Int,
        val totalPages: Int,
        val currentPage: Int,
    )

    fun parse(jsonString: String, bullionRate24: Double? = null): ParseResult {
        val root = runCatching { JSONObject(jsonString) }.getOrNull()
            ?: return ParseResult(emptyList(), 0, 0, 0)

        val pagination = root.optJSONObject("pagination")
        val totalResults = pagination?.optInt("totalResults", 0) ?: 0
        val totalPages = pagination?.optInt("totalPages", 0) ?: 0
        val currentPage = pagination?.optInt("currentPage", 0) ?: 0

        val productsArray = root.optJSONArray("products")
            ?: return ParseResult(emptyList(), totalResults, totalPages, currentPage)

        val candidates = ArrayList<ProductCandidate>(productsArray.length())

        for (i in 0 until productsArray.length()) {
            val item = productsArray.optJSONObject(i) ?: continue
            val code = item.optString("code").takeIf(String::isNotBlank) ?: continue
            val name = item.optString("name").takeIf(String::isNotBlank) ?: continue
            val priceObj = item.optJSONObject("price")
            val price = priceObj?.optDouble("value")?.takeIf { it.isFinite() && it > 0 } ?: continue

            val rawUrl = item.optString("url")
            val derivedRetailerId = Regex("""/p/([^/?#]+)""", RegexOption.IGNORE_CASE).find(rawUrl)?.groupValues?.get(1)
                ?: item.optJSONObject("fnlColorVariantData")?.optString("colorGroup")?.takeIf(String::isNotBlank)
            val baseUrl = ScraperConfigProvider.get().stores["ajio"]?.webBaseUrl ?: "https://www.ajio.com"
            val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "$baseUrl$rawUrl"

            val brand = item.optJSONObject("fnlColorVariantData")?.optString("brandName")
                ?.takeIf(String::isNotBlank) ?: item.optString("brandName").takeIf(String::isNotBlank)
            val displayName = if (!brand.isNullOrBlank() && !name.startsWith(brand, ignoreCase = true)) {
                "$brand $name"
            } else name

            val stock = item.optJSONObject("stock")
            val isOutOfStock = stock?.optString("stockLevelStatus")?.equals("outOfStock", ignoreCase = true) == true
                    || item.optBoolean("purchasable", true).not()

            val offerPrice = item.optDouble("offerPrice").takeIf { it.isFinite() && it > 0 && it < price }
                ?: item.optDouble("promoDiscountedPrice").takeIf { it.isFinite() && it > 0 && it < price }
                ?: item.optDouble("discountedPrice").takeIf { it.isFinite() && it > 0 && it < price }

            val record = BridgeRecord(
                retailerId = derivedRetailerId,
                url = fullUrl,
                name = displayName,
                brand = brand,
                price = price,
                couponPrice = offerPrice,
                metal = "Gold",
                unavailable = isOutOfStock,
            )

            when (val parsed = record.toProductCandidate("ajio.com", bullionRate24)) {
                is CandidateParseResult.Valid -> candidates.add(parsed.candidate)
                is CandidateParseResult.Rejected -> {
                    if (isOutOfStock && derivedRetailerId != null) {
                        candidates.add(
                            ProductCandidate(
                                store = "ajio.com",
                                retailerId = derivedRetailerId,
                                canonicalUrl = ProductIdentity.canonicalUrl(fullUrl),
                                name = displayName,
                                brand = brand,
                                price = price,
                                couponPrice = offerPrice,
                                grams = null,
                                karat = 24.0,
                                purity = "999",
                                unavailable = true,
                            )
                        )
                    }
                }
            }
        }

        return ParseResult(candidates, totalResults, totalPages, currentPage)
    }

    fun parseStreamChunk(
        accumulatedJson: String,
        seenPids: MutableSet<String>,
        bullionRate24: Double? = null,
    ): List<ProductCandidate> {
        val parsed = parse(accumulatedJson, bullionRate24)
        if (parsed.candidates.isEmpty()) return emptyList()
        val newCandidates = ArrayList<ProductCandidate>()
        for (c in parsed.candidates) {
            if (seenPids.add(c.retailerId)) {
                newCandidates.add(c)
            }
        }
        return newCandidates
    }
}
