package com.aurum.intelligence.parsers

import com.aurum.intelligence.data.BridgeRecord
import com.aurum.intelligence.data.CandidateParseResult
import com.aurum.intelligence.data.ProductCandidate
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
            val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "https://www.ajio.com$rawUrl"

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
                retailerId = code,
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
                is CandidateParseResult.Rejected -> { /* Skip filtered out items */ }
            }
        }

        return ParseResult(candidates, totalResults, totalPages, currentPage)
    }
}
