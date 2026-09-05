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

    private val nextDataRegex = Regex("""<script[^>]*id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    fun parse(html: String, store: String = "flipkart.com", bullionRate24: Double? = null): ParseResult {
        val totalMatch = reportedTotalRegex.find(html)
        val totalResults = totalMatch?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: 0
        val reportedTotal = totalMatch?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: 0

        val host = if (store == "shopsy.in") "https://www.shopsy.in" else "https://www.flipkart.com"
        val candidates = ArrayList<ProductCandidate>()
        val seenPids = HashSet<String>()

        // 1. Try Next.js __NEXT_DATA__ SSR hydration payload (e.g. Shopsy / Flipkart modern web)
        if (html.contains("__NEXT_DATA__")) {
            val jsonStr = nextDataRegex.find(html)?.groupValues?.get(1)?.trim()
            if (!jsonStr.isNullOrBlank()) {
                runCatching {
                    val root = org.json.JSONObject(jsonStr)
                    val slots = root.optJSONObject("props")
                        ?.optJSONObject("pageProps")
                        ?.optJSONObject("initialState")
                        ?.optJSONObject("pageData")
                        ?.optJSONObject("RESPONSE")
                        ?.optJSONArray("slots")

                    if (slots != null) {
                        for (i in 0 until slots.length()) {
                            val slot = slots.optJSONObject(i) ?: continue
                            val widget = slot.optJSONObject("widget") ?: continue
                            val widgetData = widget.optJSONObject("data") ?: continue
                            val products = widgetData.optJSONArray("products") ?: continue

                            for (j in 0 until products.length()) {
                                val prodWrapper = products.optJSONObject(j) ?: continue
                                val valObj = prodWrapper.optJSONObject("productInfo")?.optJSONObject("value")
                                    ?: prodWrapper.optJSONObject("value")
                                    ?: continue

                                val action = prodWrapper.optJSONObject("action")
                                val actionParams = action?.optJSONObject("params")
                                val pid = actionParams?.optString("productId")?.takeIf(String::isNotBlank)
                                    ?: valObj.optString("id").takeIf(String::isNotBlank)
                                    ?: continue

                                if (seenPids.contains(pid)) continue
                                seenPids.add(pid)

                                val titlesObj = valObj.optJSONObject("titles")
                                val title = titlesObj?.optString("title")?.takeIf(String::isNotBlank)
                                    ?: titlesObj?.optString("newTitle")?.takeIf(String::isNotBlank)
                                    ?: continue

                                val pricing = valObj.optJSONObject("pricing")
                                val price = pricing?.optJSONObject("finalPrice")?.optDouble("value")?.takeIf { it.isFinite() && it > 0 }
                                    ?: pricing?.optDouble("displayPrice")?.takeIf { it.isFinite() && it > 0 }
                                    ?: continue

                                val mrp = pricing?.optJSONObject("mrp")?.optDouble("value")?.takeIf { it.isFinite() && it > price }
                                val rawUrl = action?.optString("url")?.takeIf(String::isNotBlank)
                                    ?: valObj.optString("baseUrl").takeIf(String::isNotBlank)
                                    ?: "/dp/itm?pid=$pid"
                                val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "$host$rawUrl"
                                val cleanUrl = cleanProductUrl(fullUrl, pid, store)

                                val brand = titlesObj?.optString("superTitle")?.takeIf(String::isNotBlank)
                                    ?: valObj.optString("productBrand").takeIf(String::isNotBlank)
                                val unavailable = valObj.optBoolean("outOfStock", false)
                                    || valObj.optBoolean("unserviceable", false)
                                    || (valObj.has("isAvailable") && !valObj.optBoolean("isAvailable", true))
                                    || (valObj.has("deliverable") && !valObj.optBoolean("deliverable", true))

                                val record = BridgeRecord(
                                    retailerId = pid,
                                    url = cleanUrl,
                                    name = title,
                                    brand = brand,
                                    price = price,
                                    couponPrice = null,
                                    metal = "Gold",
                                    unavailable = unavailable,
                                )

                                when (val parsed = record.toProductCandidate(store, bullionRate24)) {
                                    is CandidateParseResult.Valid -> candidates.add(parsed.candidate)
                                    is CandidateParseResult.Rejected -> { /* Skip */ }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Fallback to HTML DOM parsing if candidates is empty
        if (candidates.isEmpty()) {
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
                        || cardHtml.contains("Cannot be delivered", ignoreCase = true)
                        || cardHtml.contains("unserviceable", ignoreCase = true)

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
                    is CandidateParseResult.Rejected -> { /* Skip */ }
                }
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