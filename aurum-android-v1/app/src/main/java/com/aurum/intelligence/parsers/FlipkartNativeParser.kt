package com.aurum.intelligence.parsers

import com.aurum.intelligence.data.BridgeRecord
import com.aurum.intelligence.data.CandidateParseResult
import com.aurum.intelligence.data.ProductCandidate

object FlipkartNativeParser {

    data class ParseResult(
        val candidates: List<ProductCandidate>,
        val totalResults: Int,
    )

    private val cardRegex = Regex("""<div[^>]*\bdata-id=["']([^"']+)["']([\s\S]*?)(?=<div[^>]*\bdata-id=|\z)""", RegexOption.IGNORE_CASE)
    private val titleAttrRegex = Regex("""\btitle=["']([^"']{6,})["']""", RegexOption.IGNORE_CASE)
    private val classTitleRegex = Regex("""<div[^>]*class=["'][^"']*(?:KzDlHZ|_4rR01T)[^"']*["'][^>]*>([^<]+)</div>""", RegexOption.IGNORE_CASE)
    private val linkTitleRegex = Regex("""<a[^>]*class=["'][^"']*(?:wjcEIp|IRpwTa)[^"']*["'][^>]*>([^<]+)</a>""", RegexOption.IGNORE_CASE)
    private val priceRegex = Regex("""(?:₹|Rs\.?)\s*([\d,]+(?:\.\d+)?)""")
    private val classPriceRegex = Regex("""<div[^>]*class=["'][^"']*(?:Nx9bqj|_30jeq3)[^"']*["'][^>]*>(?:₹|Rs\.?)\s*([\d,]+)""", RegexOption.IGNORE_CASE)
    private val classMrpRegex = Regex("""<div[^>]*class=["'][^"']*(?:yRaY8j|_3I9_wc)[^"']*["'][^>]*>(?:₹|Rs\.?)\s*([\d,]+)""", RegexOption.IGNORE_CASE)
    private val hrefRegex = Regex("""<a[^>]*\bhref=["']([^"']*\/p\/[^"']*)["']""", RegexOption.IGNORE_CASE)
    private val reportedTotalRegex = Regex("""(?:Showing\s+[\d,]+\s*[–—\-]\s*[\d,]+\s+(?:products?\s+)?of\s+|\bof\s+)([\d,]+)\s*products?""", RegexOption.IGNORE_CASE)

    fun parse(html: String, store: String = "flipkart.com", bullionRate24: Double? = null): ParseResult {
        val totalMatch = reportedTotalRegex.find(html)
        val totalResults = totalMatch?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: 0

        val host = if (store == "shopsy.in") "https://www.shopsy.in" else "https://www.flipkart.com"
        val candidates = ArrayList<ProductCandidate>()
        val seenPids = HashSet<String>()

        val matches = cardRegex.findAll(html)
        for (m in matches) {
            val pid = m.groupValues[1].trim()
            if (pid.isBlank() || seenPids.contains(pid)) continue
            seenPids.add(pid)

            val cardHtml = m.groupValues[2]

            // 1. Extract Title
            val title = titleAttrRegex.find(cardHtml)?.groupValues?.get(1)?.trim()
                ?: classTitleRegex.find(cardHtml)?.groupValues?.get(1)?.trim()
                ?: linkTitleRegex.find(cardHtml)?.groupValues?.get(1)?.trim()
                ?: continue
            if (title.length < 5) continue

            // 2. Extract Price
            val priceStr = classPriceRegex.find(cardHtml)?.groupValues?.get(1)
                ?: priceRegex.find(cardHtml)?.groupValues?.get(1)
                ?: continue
            val price = priceStr.replace(",", "").toDoubleOrNull() ?: continue
            if (price <= 0) continue

            // 3. Extract MRP / Was-price
            val mrpStr = classMrpRegex.find(cardHtml)?.groupValues?.get(1)
            val mrp = mrpStr?.replace(",", "")?.toDoubleOrNull()?.takeIf { it > price }

            // 4. Extract URL
            val relativeHref = hrefRegex.find(cardHtml)?.groupValues?.get(1)
            val cleanUrl = if (relativeHref != null) {
                val full = if (relativeHref.startsWith("http")) relativeHref else "$host$relativeHref"
                cleanProductUrl(full, pid, store)
            } else {
                "$host/dp/itm?pid=$pid"
            }

            // 5. Stock status
            val unavailable = cardHtml.contains("Currently Unavailable", ignoreCase = true)
                    || cardHtml.contains("Out of Stock", ignoreCase = true)
                    || cardHtml.contains("Not Deliverable", ignoreCase = true)

            val record = BridgeRecord(
                retailerId = pid,
                url = cleanUrl,
                name = title,
                brand = null,
                price = price,
                couponPrice = null,
                metal = "Gold",
                unavailable = unavailable,
            )

            when (val candidate = record.toProductCandidate(store, bullionRate24)) {
                is CandidateParseResult.Valid -> candidates.add(candidate.candidate)
                is CandidateParseResult.Rejected -> { /* Skip filtered non-gold / implausible items */ }
            }
        }

        return ParseResult(candidates, if (totalResults > 0) totalResults else candidates.size)
    }

    private fun cleanProductUrl(rawUrl: String, pid: String, store: String): String {
        return try {
            val uri = java.net.URI(rawUrl)
            val base = "${uri.scheme}://${uri.host}${uri.path}"
            val marketplace = if (store == "shopsy.in") "FLIPKART" else "FLIPKART"
            "$base?pid=$pid&marketplace=$marketplace"
        } catch (_: Exception) {
            rawUrl
        }
    }
}