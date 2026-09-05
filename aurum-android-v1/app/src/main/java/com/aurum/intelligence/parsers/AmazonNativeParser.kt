package com.aurum.intelligence.parsers

import com.aurum.intelligence.data.BridgeRecord
import com.aurum.intelligence.data.CandidateParseResult
import com.aurum.intelligence.data.ProductCandidate

object AmazonNativeParser {

    data class ParseResult(
        val candidates: List<ProductCandidate>,
        val totalResults: Int,
    )

    private val cardRegex = Regex("""<div[^>]*\bdata-asin=["']([A-Z0-9]{10})["']([\s\S]*?)(?=<div[^>]*\bdata-asin=|\z)""", RegexOption.IGNORE_CASE)
    private val titleRegex = Regex("""<h2[^>]*>[\s\S]*?<span[^>]*>([^<]{6,})</span>[\s\S]*?</h2>""", RegexOption.IGNORE_CASE)
    private val fallbackTitleRegex = Regex("""<span[^>]*class=["'][^"']*a-text-normal[^"']*["'][^>]*>([^<]{6,})</span>""", RegexOption.IGNORE_CASE)
    private val priceWholeRegex = Regex("""<span[^>]*class=["'][^"']*a-price-whole[^"']*["'][^>]*>([\d,]+)""", RegexOption.IGNORE_CASE)
    private val offscreenPriceRegex = Regex("""<span[^>]*class=["'][^"']*a-offscreen[^"']*["'][^>]*>(?:₹|Rs\.?)\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val couponRegex = Regex("""(?:Save|Get)\s*₹\s*([\d,]+)\s*(?:with|coupon)""", RegexOption.IGNORE_CASE)
    private val reportedTotalRegex = Regex("""(?:of\s+(?:over\s+)?([\d,]+)\s+results?|\b\d+\s*[-–]\s*\d+\s+of\s+(?:over\s+)?([\d,]+)\s+results?)""", RegexOption.IGNORE_CASE)

    fun parse(html: String, bullionRate24: Double? = null): ParseResult {
        val totalMatch = reportedTotalRegex.find(html)
        val totalResults = totalMatch?.groupValues?.get(1)?.ifEmpty { totalMatch.groupValues.getOrNull(2) }
            ?.replace(",", "")?.toIntOrNull() ?: 0

        val candidates = ArrayList<ProductCandidate>()
        val seenAsins = HashSet<String>()

        val matches = cardRegex.findAll(html)
        for (m in matches) {
            val asin = m.groupValues[1].trim()
            if (asin.isBlank() || seenAsins.contains(asin)) continue
            seenAsins.add(asin)

            val cardHtml = m.groupValues[2]

            // 1. Extract Title
            val rawTitle = titleRegex.find(cardHtml)?.groupValues?.get(1)?.trim()
                ?: fallbackTitleRegex.find(cardHtml)?.groupValues?.get(1)?.trim()
                ?: continue
            val title = cleanHtmlEntities(rawTitle)
            if (title.length < 5) continue

            // 2. Extract Price
            val priceStr = priceWholeRegex.find(cardHtml)?.groupValues?.get(1)
                ?: offscreenPriceRegex.find(cardHtml)?.groupValues?.get(1)
                ?: continue
            val price = priceStr.replace(",", "").toDoubleOrNull() ?: continue
            if (price <= 0) continue

            // 3. Extract Coupon Discount
            val couponMatch = couponRegex.find(cardHtml)
            val couponDiscount = couponMatch?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
            val couponPrice = if (couponDiscount != null && couponDiscount > 0 && couponDiscount < price) price - couponDiscount else null

            // 4. URL
            val fullUrl = "https://www.amazon.in/dp/$asin"

            // 5. Stock status
            val unavailable = cardHtml.contains("Currently unavailable", ignoreCase = true)
                    || cardHtml.contains("Out of Stock", ignoreCase = true)
                    || cardHtml.contains("Currently sold out", ignoreCase = true)

            val record = BridgeRecord(
                retailerId = asin,
                url = fullUrl,
                name = title,
                brand = null,
                price = price,
                couponPrice = couponPrice,
                metal = "Gold",
                unavailable = unavailable,
            )

            when (val candidate = record.toProductCandidate("amazon.in", bullionRate24)) {
                is CandidateParseResult.Valid -> candidates.add(candidate.candidate)
                is CandidateParseResult.Rejected -> { /* Skip filtered items */ }
            }
        }

        return ParseResult(candidates, if (totalResults > 0) totalResults else candidates.size)
    }

    private fun cleanHtmlEntities(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}