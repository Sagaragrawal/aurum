package com.aurum.intelligence.parsers
import com.aurum.intelligence.data.db.*
import com.aurum.intelligence.data.engine.*
import com.aurum.intelligence.data.model.*
import com.aurum.intelligence.data.repository.*
import com.aurum.intelligence.data.validation.*


object AmazonNativeParser {

    data class ParseResult(
        val candidates: List<ProductCandidate>,
        val totalResults: Int,
    )

    private val cardRegex = Regex("""<div[^>]*\bdata-asin=["']([A-Z0-9]{10})["']([\s\S]*?)(?=<div[^>]*\bdata-asin=|\z)""", RegexOption.IGNORE_CASE)
    private val brandRegex = Regex("""<h2[^>]*class=["'][^"']*a-size-mini[^"']*["'][^>]*>[\s\S]*?<span[^>]*>([^<]+)</span>""", RegexOption.IGNORE_CASE)
    private val titleH2Regex = Regex("""<h2[^>]*>[\s\S]*?<span[^>]*>([^<]{5,})</span>""", RegexOption.IGNORE_CASE)
    private val spanTitleRegex = Regex("""<span[^>]*class=["'][^"']*(?:a-size-medium|a-size-base-plus|a-size-base|a-text-normal)[^"']*["'][^>]*>([^<]{5,})</span>""", RegexOption.IGNORE_CASE)
    private val altRegex = Regex("""<img[^>]*class=["'][^"']*s-image[^"']*["'][^>]*alt=["']([^"']{5,})["']""", RegexOption.IGNORE_CASE)
    private val fallbackTitleRegex = Regex("""<span[^>]*class=["'][^"']*a-text-normal[^"']*["'][^>]*>([^<]{5,})</span>""", RegexOption.IGNORE_CASE)
    private val priceWholeRegex = Regex("""<span[^>]*class=["'][^"']*a-price-whole[^"']*["'][^>]*>([\d,]+)""", RegexOption.IGNORE_CASE)
    private val offscreenPriceRegex = Regex("""<span[^>]*class=["'][^"']*a-offscreen[^"']*["'][^>]*>(?:₹|Rs\.?)\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val genericPriceRegex = Regex("""(?:₹|Rs\.?)\s*([\d,]+(?:\.\d+)?)""")
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

            // 1. Extract Title (combining brand + title if separate, or from alt attribute)
            val brand = brandRegex.find(cardHtml)?.groupValues?.get(1)?.trim()
            val titleH2 = titleH2Regex.find(cardHtml)?.groupValues?.get(1)?.trim()
            val spanTitle = spanTitleRegex.find(cardHtml)?.groupValues?.get(1)?.trim()
            val altTitle = altRegex.find(cardHtml)?.groupValues?.get(1)?.trim()

            val rawTitle = when {
                !titleH2.isNullOrBlank() && !brand.isNullOrBlank() && !titleH2.startsWith(brand, ignoreCase = true) -> "$brand $titleH2"
                !titleH2.isNullOrBlank() -> titleH2
                !spanTitle.isNullOrBlank() -> spanTitle
                !altTitle.isNullOrBlank() -> altTitle
                else -> fallbackTitleRegex.find(cardHtml)?.groupValues?.get(1)?.trim()
            } ?: continue

            val title = cleanHtmlEntities(rawTitle)
            if (title.length < 5) continue

            // 2. Extract Price
            val priceStr = priceWholeRegex.find(cardHtml)?.groupValues?.get(1)
                ?: offscreenPriceRegex.find(cardHtml)?.groupValues?.get(1)
                ?: genericPriceRegex.find(cardHtml)?.groupValues?.get(1)
                ?: continue
            val price = priceStr.replace(",", "").toDoubleOrNull() ?: continue
            if (price <= 0) continue

            // 3. Extract Coupon Discount
            val couponMatch = couponRegex.find(cardHtml)
            val couponDiscount = couponMatch?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
            val couponPrice = if (couponDiscount != null && couponDiscount > 0 && couponDiscount < price) price - couponDiscount else null

            // 4. URL
            val pattern = ScraperConfigProvider.get().stores["amazon"]?.pdpUrlPattern?.takeIf { it.isNotBlank() } ?: "https://www.amazon.in/dp/%s"
            val fullUrl = if (pattern.contains("{id}")) pattern.replace("{id}", asin) else String.format(pattern, asin)

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

    fun parseStreamChunk(
        accumulatedHtml: String,
        seenAsins: MutableSet<String>,
        bullionRate24: Double? = null,
    ): List<ProductCandidate> {
        val parsed = parse(accumulatedHtml, bullionRate24)
        if (parsed.candidates.isEmpty()) return emptyList()
        val newCandidates = ArrayList<ProductCandidate>()
        for (c in parsed.candidates) {
            if (seenAsins.add(c.retailerId)) {
                newCandidates.add(c)
            }
        }
        return newCandidates
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