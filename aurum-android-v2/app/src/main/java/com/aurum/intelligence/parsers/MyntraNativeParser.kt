package com.aurum.intelligence.parsers
import com.aurum.intelligence.data.db.*
import com.aurum.intelligence.data.engine.*
import com.aurum.intelligence.data.model.*
import com.aurum.intelligence.data.repository.*
import com.aurum.intelligence.data.validation.*

import org.json.JSONObject

object MyntraNativeParser {

    data class ParseResult(
        val candidates: List<ProductCandidate>,
        val totalCount: Int,
    )

    private val myxRegex = Regex("""<script[^>]*>\s*window\.__myx\s*=\s*(.+?)</script>""", RegexOption.DOT_MATCHES_ALL)

    fun parse(content: String, bullionRate24: Double? = null): ParseResult {
        val jsonString = if (content.contains("window.__myx")) {
            val raw = myxRegex.find(content)?.groupValues?.get(1)?.trim()
                ?: if (content.contains("window.__myx = ")) {
                    content.substringAfter("window.__myx = ").substringBefore("</script>").trim()
                } else content
            raw.removeSuffix(";").trim()
        } else content

        val root = runCatching { JSONObject(jsonString) }.getOrNull()
            ?: return ParseResult(emptyList(), 0)

        val pdpData = root.optJSONObject("pdpData")
        if (pdpData != null) {
            val pid = pdpData.optString("id").takeIf(String::isNotBlank) ?: return ParseResult(emptyList(), 0)
            val name = pdpData.optString("name").takeIf(String::isNotBlank) ?: return ParseResult(emptyList(), 0)
            val brandObj = pdpData.optJSONObject("brand")
            val brand = brandObj?.optString("name")?.takeIf(String::isNotBlank)
            val priceObj = pdpData.optJSONObject("price")
            val price = priceObj?.optDouble("discounted")?.takeIf { it.isFinite() && it > 0 }
                ?: priceObj?.optDouble("mrp")?.takeIf { it.isFinite() && it > 0 }
                ?: return ParseResult(emptyList(), 0)

            val flags = pdpData.optJSONObject("flags")
            val availabilityStr = pdpData.optString("availability")
            val isOutOfStock = flags?.optBoolean("outOfStock", false) == true ||
                pdpData.optBoolean("outOfStock", false) ||
                availabilityStr.contains("outofstock", ignoreCase = true) ||
                availabilityStr.contains("out of stock", ignoreCase = true) ||
                availabilityStr.contains("not_available", ignoreCase = true)

            val landingPage = pdpData.optString("landingPageUrl").trimStart('/')
            val baseUrl = ScraperConfigProvider.get().stores["myntra"]?.webBaseUrl ?: "https://www.myntra.com"
            val fullUrl = if (landingPage.startsWith("http")) landingPage else "$baseUrl/$landingPage"

            val displayName = if (!brand.isNullOrBlank() && !name.startsWith(brand, ignoreCase = true)) {
                "$brand $name"
            } else name

            val productDetails = pdpData.optJSONArray("productDetails")
            val descriptors = pdpData.optJSONArray("descriptors")
            val descBuilder = StringBuilder()
            if (productDetails != null) {
                for (k in 0 until productDetails.length()) {
                    val d = productDetails.optJSONObject(k)
                    val desc = d?.optString("description")
                    if (!desc.isNullOrBlank()) descBuilder.append(" ").append(desc)
                }
            }
            if (descriptors != null) {
                for (k in 0 until descriptors.length()) {
                    val d = descriptors.optJSONObject(k)
                    val desc = d?.optString("description")
                    if (!desc.isNullOrBlank()) descBuilder.append(" ").append(desc)
                }
            }

            val fullTextForWeight = "$displayName ${descBuilder.toString()}"
            val weight = WeightExtractor.parse(displayName, fullTextForWeight)

            val record = BridgeRecord(
                retailerId = pid,
                url = fullUrl,
                name = displayName,
                brand = brand,
                price = price,
                couponPrice = null,
                metal = "Gold",
                grams = weight.totalWeightGrams,
                unavailable = isOutOfStock,
            )

            val candidates = ArrayList<ProductCandidate>()
            when (val candidate = record.toProductCandidate("myntra.com", bullionRate24)) {
                is CandidateParseResult.Valid -> candidates.add(candidate.candidate)
                is CandidateParseResult.Rejected -> {
                    if (isOutOfStock) {
                        candidates.add(ProductCandidate(
                            store = "myntra.com",
                            retailerId = pid,
                            canonicalUrl = ProductIdentity.canonicalUrl(fullUrl),
                            name = displayName,
                            brand = brand,
                            price = price,
                            couponPrice = null,
                            grams = weight.totalWeightGrams,
                            karat = 24.0,
                            purity = "999",
                            unavailable = true,
                        ))
                    }
                }
            }
            return ParseResult(candidates, 1)
        }

        val searchData = root.optJSONObject("searchData")
        val results = searchData?.optJSONObject("results")

        val totalCount = root.optInt("totalCount", 0).takeIf { it > 0 }
            ?: results?.optInt("totalCount", 0)
            ?: searchData?.optInt("totalCount", 0)
            ?: 0

        // Combine organic products and PLA products (check root level first for Gateway API, then nested searchData for SSR)
        val productsArray = root.optJSONArray("products")
            ?: results?.optJSONArray("products")
            ?: searchData?.optJSONArray("products")
        val plaArray = root.optJSONArray("plaProducts")
            ?: results?.optJSONArray("plaProducts")
            ?: searchData?.optJSONArray("plaProducts")

        val candidates = ArrayList<ProductCandidate>()
        val seenIds = HashSet<String>()

        fun processArray(arr: org.json.JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val pid = item.optString("productId").takeIf(String::isNotBlank) ?: continue
                if (seenIds.contains(pid)) continue
                seenIds.add(pid)

                val name = item.optString("productName").takeIf(String::isNotBlank)
                    ?: item.optString("product").takeIf(String::isNotBlank)
                    ?: continue

                val brand = item.optString("brand").takeIf(String::isNotBlank)

                // Price resolution: discountedPrice -> price (selling price) -> mrp (full price)
                val price = item.optDouble("discountedPrice").takeIf { it.isFinite() && it > 0 }
                    ?: item.optDouble("price").takeIf { it.isFinite() && it > 0 }
                    ?: item.optDouble("mrp").takeIf { it.isFinite() && it > 0 }
                    ?: continue

                // Check coupon discount and bestPrice
                val couponData = item.optJSONObject("couponData")
                val couponDiscount = couponData?.optDouble("couponDiscount")?.takeIf { it.isFinite() && it > 0 }
                val bestPrice = couponData?.optJSONObject("couponDescription")?.optDouble("bestPrice")?.takeIf { it.isFinite() && it > 0 && it < price }
                val couponPrice = when {
                    bestPrice != null -> bestPrice
                    couponDiscount != null && couponDiscount < price -> price - couponDiscount
                    else -> null
                }

                val landingPage = item.optString("landingPageUrl").trimStart('/')
                val baseUrl = ScraperConfigProvider.get().stores["myntra"]?.webBaseUrl ?: "https://www.myntra.com"
                val fullUrl = if (landingPage.startsWith("http")) landingPage else "$baseUrl/$landingPage"

                // Check inventory & availability
                val availabilityStr = item.optString("availability")
                val flags = item.optJSONObject("flags")
                val isExplicitOutOfStock = availabilityStr.contains("outofstock", ignoreCase = true) ||
                    availabilityStr.contains("out of stock", ignoreCase = true) ||
                    availabilityStr.contains("not_available", ignoreCase = true) ||
                    flags?.optBoolean("outOfStock", false) == true ||
                    item.optBoolean("outOfStock", false)

                val inventoryArray = item.optJSONArray("inventoryInfo")
                val hasStock = if (inventoryArray != null && inventoryArray.length() > 0) {
                    var inStock = false
                    for (j in 0 until inventoryArray.length()) {
                        val inv = inventoryArray.optJSONObject(j)
                        val isAvail = inv?.optBoolean("available", true) == true
                        val count = inv?.optInt("inventory", 0) ?: 0
                        if (isAvail && count > 0) {
                            inStock = true
                            break
                        }
                    }
                    inStock && !isExplicitOutOfStock
                } else {
                    !isExplicitOutOfStock
                }

                val isProductUnavailable = !hasStock || isExplicitOutOfStock

                val displayName = if (!brand.isNullOrBlank() && !name.startsWith(brand, ignoreCase = true)) {
                    "$brand $name"
                } else name

                val articleAttrs = item.optJSONObject("articleAttributes")
                val metalAttr = articleAttrs?.optString("metal_article_attr")?.takeIf(String::isNotBlank)
                    ?: articleAttrs?.optString("Metal")?.takeIf(String::isNotBlank)
                    ?: if (displayName.contains("silver", ignoreCase = true)) "Silver" else "Gold"

                val purityAttr = articleAttrs?.optString("metal_purity_article_attr")?.takeIf(String::isNotBlank)
                    ?: articleAttrs?.optString("Purity")?.takeIf(String::isNotBlank)

                val record = BridgeRecord(
                    retailerId = pid,
                    url = fullUrl,
                    name = displayName,
                    brand = brand,
                    price = price,
                    couponPrice = couponPrice,
                    metal = metalAttr,
                    purity = purityAttr,
                    unavailable = isProductUnavailable,
                )

                when (val candidate = record.toProductCandidate("myntra.com", bullionRate24)) {
                    is CandidateParseResult.Valid -> candidates.add(candidate.candidate)
                    is CandidateParseResult.Rejected -> {
                        if (isProductUnavailable) {
                            candidates.add(
                                ProductCandidate(
                                    store = "myntra.com",
                                    retailerId = pid,
                                    canonicalUrl = ProductIdentity.canonicalUrl(fullUrl),
                                    name = displayName,
                                    brand = brand,
                                    price = price,
                                    couponPrice = couponPrice,
                                    grams = null,
                                    karat = 24.0,
                                    purity = purityAttr ?: "999",
                                    unavailable = true,
                                )
                            )
                        }
                    }
                }
            }
        }

        processArray(productsArray)
        processArray(plaArray)

        return ParseResult(candidates, if (totalCount > 0) totalCount else candidates.size)
    }

    fun parseStreamChunk(
        accumulatedContent: String,
        seenPids: MutableSet<String>,
        bullionRate24: Double? = null,
    ): List<ProductCandidate> {
        val parsed = parse(accumulatedContent, bullionRate24)
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