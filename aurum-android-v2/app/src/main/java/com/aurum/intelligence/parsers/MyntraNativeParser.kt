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

    private val myxRegex = Regex("""<script[^>]*>\s*window\.__myx\s*=\s*(.+?)</script>""", RegexOption.DOT_MATCHES_ALL)

    fun parse(content: String, bullionRate24: Double? = null): ParseResult {
        val jsonString = if (content.contains("window.__myx")) {
            myxRegex.find(content)?.groupValues?.get(1)?.trim() ?: content
        } else content

        val root = runCatching { JSONObject(jsonString) }.getOrNull()
            ?: return ParseResult(emptyList(), 0)

        val searchData = root.optJSONObject("searchData")
        val results = searchData?.optJSONObject("results")

        val totalCount = results?.optInt("totalCount", 0)
            ?: searchData?.optInt("totalCount", 0)
            ?: root.optInt("totalCount", 0)

        // Combine organic products and PLA products
        val productsArray = results?.optJSONArray("products")
            ?: searchData?.optJSONArray("products")
            ?: root.optJSONArray("products")
        val plaArray = results?.optJSONArray("plaProducts")
            ?: searchData?.optJSONArray("plaProducts")
            ?: root.optJSONArray("plaProducts")

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

                // In Myntra gateway search: 'price' is the discounted selling price, 'mrp' is the original price
                val price = item.optDouble("price").takeIf { it.isFinite() && it > 0 } ?: continue

                // Check coupon discount
                val couponData = item.optJSONObject("couponData")
                val couponDiscount = couponData?.optDouble("couponDiscount")?.takeIf { it.isFinite() && it > 0 } ?: 0.0
                val couponPrice = if (couponDiscount > 0 && couponDiscount < price) price - couponDiscount else null

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