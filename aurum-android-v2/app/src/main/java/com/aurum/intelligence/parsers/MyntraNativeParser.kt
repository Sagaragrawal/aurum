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

    private fun isIgnoredFirstUserCoupon(code: String?): Boolean {
        if (code.isNullOrBlank()) return false
        val upper = code.uppercase()
        return upper.contains("MYNTRA300") ||
            upper.contains("MYNTRA200") ||
            upper.contains("MYNTRA100") ||
            upper.contains("NEWUSER") ||
            upper.contains("FIRST")
    }

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
                ?: priceObj?.optDouble("price")?.takeIf { it.isFinite() && it > 0 }
                ?: priceObj?.optDouble("mrp")?.takeIf { it.isFinite() && it > 0 }
                ?: return ParseResult(emptyList(), 0)

            val pdpCouponData = pdpData.optJSONObject("couponData")
            val pdpCouponCode = pdpCouponData?.optJSONObject("couponDescription")?.optString("couponCode")
                ?: pdpCouponData?.optString("couponCode")
                ?: pdpData.optString("couponCode")

            val isIgnoredCoupon = isIgnoredFirstUserCoupon(pdpCouponCode)

            val pdpBestPrice = pdpCouponData?.optJSONObject("couponDescription")?.optDouble("bestPrice")?.takeIf { it.isFinite() && it > 0 && it < price }
                ?: pdpCouponData?.optDouble("bestPrice")?.takeIf { it.isFinite() && it > 0 && it < price }
                ?: pdpData.optDouble("bestPrice").takeIf { it.isFinite() && it > 0 && it < price }

            val pdpCouponDiscount = pdpCouponData?.optDouble("couponDiscount")?.takeIf { it.isFinite() && it > 0 }
                ?: pdpCouponData?.optJSONObject("couponDescription")?.optDouble("couponDiscount")?.takeIf { it.isFinite() && it > 0 }
                ?: pdpData.optDouble("personalizedCouponValue").takeIf { it.isFinite() && it > 0 }
                ?: pdpData.optJSONObject("personalizedCoupon")?.optDouble("value")?.takeIf { it.isFinite() && it > 0 }

            val pdpCouponPrice = when {
                isIgnoredCoupon -> null
                pdpBestPrice != null -> pdpBestPrice
                pdpCouponDiscount != null && pdpCouponDiscount < price -> price - pdpCouponDiscount
                else -> null
            }

            val pdpIsBlink = !isIgnoredCoupon && ((pdpCouponCode != null && pdpCouponCode.contains("BLINK", ignoreCase = true)) ||
                pdpData.optString("discountLabel").contains("BLINK", ignoreCase = true) ||
                pdpData.optString("discountDisplayLabel").contains("BLINK", ignoreCase = true))

            val flags = pdpData.optJSONObject("flags")
            val availabilityStr = pdpData.optString("availability")
            val normAvail = availabilityStr.lowercase().replace("_", "").replace("-", "").replace(" ", "")
            val sizes = pdpData.optJSONArray("sizes")
            val allSizesUnavailable = if (sizes != null && sizes.length() > 0) {
                var anyAvail = false
                for (sIdx in 0 until sizes.length()) {
                    val sObj = sizes.optJSONObject(sIdx)
                    if (sObj?.optBoolean("available", false) == true) {
                        anyAvail = true
                        break
                    }
                }
                !anyAvail
            } else false

            val isOutOfStock = flags?.optBoolean("outOfStock", false) == true ||
                pdpData.optBoolean("outOfStock", false) ||
                flags?.optBoolean("disableBuyButton", false) == true ||
                allSizesUnavailable ||
                normAvail.contains("outofstock") ||
                normAvail.contains("soldout") ||
                normAvail.contains("notavailable") ||
                normAvail.contains("unserviceable")

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

            val articleAttrs = pdpData.optJSONObject("articleAttributes")
            val goldPurityAttr = articleAttrs?.optString("Gold Purity")?.takeIf(String::isNotBlank)
                ?: articleAttrs?.optString("Purity")?.takeIf(String::isNotBlank)
                ?: articleAttrs?.optString("metal_purity_article_attr")?.takeIf(String::isNotBlank)

            val fullDescText = descBuilder.toString()
            val combinedPdpText = "$displayName $goldPurityAttr $fullDescText"

            val isExplicit22K = Regex("\\b(?:22\\s*k|22\\s*kt|22kt|22-kt|916|18\\s*k|18\\s*kt|14\\s*k)\\b", RegexOption.IGNORE_CASE).containsMatchIn(combinedPdpText)
            val isExplicit24K = Regex("\\b(?:24\\s*k|24\\s*kt|24kt|24-kt|24\\s*karat|999|999\\.9|995)\\b", RegexOption.IGNORE_CASE).containsMatchIn(combinedPdpText)

            val resolvedKarat = when {
                isExplicit22K -> 22.0
                isExplicit24K -> 24.0
                else -> null
            }
            val resolvedPurity = when {
                isExplicit22K -> "916"
                goldPurityAttr != null -> goldPurityAttr
                isExplicit24K -> "999"
                else -> null
            }

            val record = BridgeRecord(
                retailerId = pid,
                url = fullUrl,
                name = displayName,
                brand = brand,
                price = price,
                couponPrice = pdpCouponPrice,
                metal = "Gold",
                karat = resolvedKarat,
                purity = resolvedPurity,
                grams = weight.totalWeightGrams,
                unavailable = isOutOfStock,
                isBlinkDeal = pdpIsBlink,
                blinkDealPrice = if (pdpIsBlink) pdpCouponPrice else null,
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
                            couponPrice = pdpCouponPrice,
                            grams = weight.totalWeightGrams,
                            karat = 24.0,
                            purity = "999",
                            unavailable = true,
                            isBlinkDeal = pdpIsBlink,
                            blinkDealPrice = if (pdpIsBlink) pdpCouponPrice else null,
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
                val parsedPrice = item.optDouble("discountedPrice").takeIf { it.isFinite() && it > 0 }
                    ?: item.optDouble("price").takeIf { it.isFinite() && it > 0 }
                    ?: item.optDouble("mrp").takeIf { it.isFinite() && it > 0 }

                // Check inventory & availability
                val availabilityStr = item.optString("availability")
                val normAvail = availabilityStr.lowercase().replace("_", "").replace("-", "").replace(" ", "")
                val flags = item.optJSONObject("flags")
                val isExplicitOutOfStock = normAvail.contains("outofstock") ||
                    normAvail.contains("soldout") ||
                    normAvail.contains("notavailable") ||
                    normAvail.contains("unserviceable") ||
                    flags?.optBoolean("outOfStock", false) == true ||
                    flags?.optBoolean("disableBuyButton", false) == true ||
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

                if (parsedPrice == null && !isProductUnavailable) continue
                val price = parsedPrice ?: 0.0

                // Check coupon discount, bestPrice, and BlinkDeal
                val couponData = item.optJSONObject("couponData")
                val couponCode = couponData?.optJSONObject("couponDescription")?.optString("couponCode")
                    ?: couponData?.optString("couponCode")
                    ?: item.optString("couponCode")

                val isIgnoredCoupon = isIgnoredFirstUserCoupon(couponCode)

                val bestPrice = couponData?.optJSONObject("couponDescription")?.optDouble("bestPrice")?.takeIf { it.isFinite() && it > 0 && it < price }
                    ?: couponData?.optDouble("bestPrice")?.takeIf { it.isFinite() && it > 0 && it < price }
                    ?: item.optDouble("bestPrice").takeIf { it.isFinite() && it > 0 && it < price }

                val couponDiscount = couponData?.optDouble("couponDiscount")?.takeIf { it.isFinite() && it > 0 }
                    ?: couponData?.optJSONObject("couponDescription")?.optDouble("couponDiscount")?.takeIf { it.isFinite() && it > 0 }
                    ?: item.optDouble("personalizedCouponValue").takeIf { it.isFinite() && it > 0 }
                    ?: item.optJSONObject("personalizedCoupon")?.optDouble("value")?.takeIf { it.isFinite() && it > 0 }

                val couponPrice = when {
                    isIgnoredCoupon -> null
                    bestPrice != null -> bestPrice
                    couponDiscount != null && couponDiscount < price -> price - couponDiscount
                    else -> null
                }

                val isBlink = !isIgnoredCoupon && ((couponCode != null && couponCode.contains("BLINK", ignoreCase = true)) ||
                    item.optString("discountLabel").contains("BLINK", ignoreCase = true) ||
                    item.optString("discountDisplayLabel").contains("BLINK", ignoreCase = true))

                val landingPage = item.optString("landingPageUrl").trimStart('/')
                val baseUrl = ScraperConfigProvider.get().stores["myntra"]?.webBaseUrl ?: "https://www.myntra.com"
                val fullUrl = if (landingPage.startsWith("http")) landingPage else "$baseUrl/$landingPage"

                val displayName = if (!brand.isNullOrBlank() && !name.startsWith(brand, ignoreCase = true)) {
                    "$brand $name"
                } else name

                val additionalInfo = item.optString("additionalInfo")
                val imagesArray = item.optJSONArray("images")
                val imageSrcBuilder = StringBuilder()
                if (imagesArray != null) {
                    for (imgIdx in 0 until imagesArray.length()) {
                        val imgObj = imagesArray.optJSONObject(imgIdx)
                        val src = imgObj?.optString("src")
                        if (!src.isNullOrBlank()) {
                            imageSrcBuilder.append(" ").append(src)
                        }
                    }
                }

                val articleAttrs = item.optJSONObject("articleAttributes")
                val metalAttr = articleAttrs?.optString("metal_article_attr")?.takeIf(String::isNotBlank)
                    ?: articleAttrs?.optString("Metal")?.takeIf(String::isNotBlank)
                    ?: if (displayName.contains("silver", ignoreCase = true)) "Silver" else "Gold"

                val purityAttr = articleAttrs?.optString("metal_purity_article_attr")?.takeIf(String::isNotBlank)
                    ?: articleAttrs?.optString("Purity")?.takeIf(String::isNotBlank)

                val plpText = "$displayName $additionalInfo $landingPage $purityAttr $imageSrcBuilder"

                val isExplicit22K = Regex("\\b(?:22\\s*k|22\\s*kt|22kt|22-kt|916|18\\s*k|18\\s*kt|14\\s*k)\\b", RegexOption.IGNORE_CASE).containsMatchIn(plpText)
                val isExplicit24K = Regex("\\b(?:24\\s*k|24\\s*kt|24kt|24-kt|24\\s*karat|999|999\\.9|995)\\b", RegexOption.IGNORE_CASE).containsMatchIn(plpText)

                val resolvedKarat = when {
                    isExplicit22K -> 22.0
                    isExplicit24K -> 24.0
                    else -> null
                }
                val resolvedPurity = when {
                    isExplicit22K -> "916"
                    purityAttr != null -> purityAttr
                    isExplicit24K -> "999"
                    else -> null
                }

                val weight = WeightExtractor.parse(displayName, plpText)

                val record = BridgeRecord(
                    retailerId = pid,
                    url = fullUrl,
                    name = displayName,
                    brand = brand,
                    price = price,
                    couponPrice = couponPrice,
                    metal = metalAttr,
                    karat = resolvedKarat,
                    purity = resolvedPurity,
                    grams = weight.totalWeightGrams,
                    unavailable = isProductUnavailable,
                    isBlinkDeal = isBlink,
                    blinkDealPrice = if (isBlink) couponPrice else null,
                )

                when (val candidate = record.toProductCandidate("myntra.com", bullionRate24)) {
                    is CandidateParseResult.Valid -> candidates.add(candidate.candidate)
                    is CandidateParseResult.Rejected -> {
                        if (isProductUnavailable && !isExplicit22K) {
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
                                    isBlinkDeal = isBlink,
                                    blinkDealPrice = if (isBlink) couponPrice else null,
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