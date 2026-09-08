package com.aurum.intelligence.parsers

import com.aurum.intelligence.data.BridgeRecord
import com.aurum.intelligence.data.CandidateParseResult
import com.aurum.intelligence.data.ProductCandidate
import org.json.JSONObject

object MyntraNativeParser {

    data class ParseResult(
        val candidates: List<ProductCandidate>,
        val totalCount: Int,
    )

    private fun isIgnoredFirstUserCoupon(code: String?): Boolean {
        if (code.isNullOrBlank()) return false
        val upper = code.uppercase()
        return upper.contains("MYNTRA300") ||
            upper.contains("MYNTRA200") ||
            upper.contains("MYNTRA100") ||
            upper.contains("NEWUSER") ||
            upper.contains("FIRST")
    }

    fun parse(jsonString: String, bullionRate24: Double? = null): ParseResult {
        val root = runCatching { JSONObject(jsonString) }.getOrNull()
            ?: return ParseResult(emptyList(), 0)

        val totalCount = root.optInt("totalCount", 0)

        // Combine organic products and PLA products
        val productsArray = root.optJSONArray("products")
        val plaArray = root.optJSONArray("plaProducts")

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
                    ?: continue
                val price = parsedPrice

                // Check coupon discount, bestPrice, and BlinkDeal
                val couponData = item.optJSONObject("couponData")
                val couponCode = couponData?.optJSONObject("couponDescription")?.optString("couponCode")
                    ?: couponData?.optString("couponCode")
                    ?: item.optString("couponCode")

                val isIgnoredCoupon = isIgnoredFirstUserCoupon(couponCode)

                val bestPrice = couponData?.optJSONObject("couponDescription")?.optDouble("bestPrice")?.takeIf { it.isFinite() && it > 0 && it < price }
                    ?: couponData?.optDouble("bestPrice")?.takeIf { it.isFinite() && it > 0 && it < price }
                    ?: item.optDouble("bestPrice")?.takeIf { it.isFinite() && it > 0 && it < price }

                val couponDiscount = couponData?.optDouble("couponDiscount")?.takeIf { it.isFinite() && it > 0 }
                    ?: couponData?.optJSONObject("couponDescription")?.optDouble("couponDiscount")?.takeIf { it.isFinite() && it > 0 }
                    ?: item.optDouble("personalizedCouponValue")?.takeIf { it.isFinite() && it > 0 }
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
                val fullUrl = if (landingPage.startsWith("http")) landingPage else "https://www.myntra.com/$landingPage"

                // Check inventory
                val inventoryArray = item.optJSONArray("inventoryInfo")
                var hasStock = false
                if (inventoryArray != null && inventoryArray.length() > 0) {
                    for (j in 0 until inventoryArray.length()) {
                        val inv = inventoryArray.optJSONObject(j)
                        if (inv?.optBoolean("available", true) == true) {
                            hasStock = true
                            break
                        }
                    }
                } else {
                    hasStock = true
                }

                val flags = item.optJSONObject("flags")
                if (flags?.optBoolean("outOfStock", false) == true) {
                    hasStock = false
                }

                val displayName = if (!brand.isNullOrBlank() && !name.startsWith(brand, ignoreCase = true)) {
                    "$brand $name"
                } else name

                val record = BridgeRecord(
                    retailerId = pid,
                    url = fullUrl,
                    name = displayName,
                    brand = brand,
                    price = price,
                    couponPrice = couponPrice,
                    metal = "Gold",
                    unavailable = !hasStock,
                    isBlinkDeal = isBlink,
                    blinkDealPrice = if (isBlink) couponPrice else null,
                )

                when (val candidate = record.toProductCandidate("myntra.com", bullionRate24)) {
                    is CandidateParseResult.Valid -> candidates.add(candidate.candidate)
                    is CandidateParseResult.Rejected -> { /* Skip filtered non-gold / implausible items */ }
                }
            }
        }

        processArray(productsArray)
        processArray(plaArray)

        return ParseResult(candidates, if (totalCount > 0) totalCount else candidates.size)
    }
}