package com.aurum.intelligence.parsers

import org.json.JSONObject

data class BullionParseResult(
    val price24: Double?,
    val price22: Double?,
    val price22Derived: Boolean,
)

object BullionNativeParser {

    fun parse(sourceId: String, content: String): BullionParseResult {
        return when (sourceId.lowercase()) {
            "malabar" -> parseMalabar(content)
            "mmtc" -> parseMMTC(content)
            "kalyan" -> parseKalyan(content)
            "tan", "tanishq" -> parseTanishq(content)
            else -> BullionParseResult(null, null, false)
        }
    }

    fun parseMalabar(content: String): BullionParseResult {
        // 1. Try GraphQL JSON response
        if (content.trimStart().startsWith("{")) {
            val root = runCatching { JSONObject(content) }.getOrNull()
            val items = root?.optJSONObject("data")?.optJSONObject("getMetalRate")?.optJSONArray("items")
            if (items != null) {
                var p24: Double? = null
                var p22: Double? = null
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val purity = item.optString("purity").lowercase()
                    val rate = item.optDouble("rate").takeIf { it.isFinite() && it > 1000 } ?: continue
                    if (purity in listOf("24k", "999", "99.99", "99.9")) {
                        p24 = rate
                    } else if (purity in listOf("22k", "916")) {
                        p22 = rate
                    }
                }
                if (p24 != null) {
                    val final22 = p22 ?: (p24 * 22.0 / 24.0)
                    return BullionParseResult(p24, final22, p22 == null)
                }
            }
        }

        // 2. Try HTML regex
        val p24Match = Regex("""([\d,]+(?:\.\d+)?)\s*INR\s*/\s*gms?[\s\S]{0,160}24k\s*\(999\)""", RegexOption.IGNORE_CASE).find(content)
            ?: Regex("""24k\s*\(999\)[\s\S]{0,160}(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(content)
        val p24 = p24Match?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()

        val p22Match = Regex("""([\d,]+(?:\.\d+)?)\s*INR\s*/\s*gms?[\s\S]{0,200}22\s*k\s*\(\s*916\s*\)""", RegexOption.IGNORE_CASE).find(content)
        val p22 = p22Match?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()

        if (p24 != null && p24 > 1000) {
            val final22 = p22 ?: (p24 * 22.0 / 24.0)
            return BullionParseResult(p24, final22, p22 == null)
        }

        return BullionParseResult(null, null, false)
    }

    fun parseMMTC(content: String): BullionParseResult {
        // 1. Try Quote API JSON response
        if (content.trimStart().startsWith("{")) {
            val root = runCatching { JSONObject(content) }.getOrNull()
            val preTax = root?.optDouble("preTaxAmount")?.takeIf { it.isFinite() && it > 1000 }
                ?: root?.optDouble("totalAmount")?.takeIf { it.isFinite() && it > 1000 }
            if (preTax != null) {
                return BullionParseResult(preTax, preTax * 22.0 / 24.0, true)
            }
        }

        // 2. Try HTML pattern
        val p24Match = Regex("""24k\s*Gold\s*Rate\s*\(Exc\.\s*GST\)[\s\S]*?(?:1\s*gm|1gm)\s*₹\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(content)
            ?: Regex("""24k\s*Gold\s*Rate\s*Today[\s\S]{0,240}(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(content)
            ?: Regex("""24k[\s\S]{0,180}(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(content)
        val p24 = p24Match?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()

        val p22Match = Regex("""22k\s*Gold\s*Rate\s*\(Exc\.\s*GST\)[\s\S]{0,260}(?:1\s*gm|1gm)?\s*(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(content)
        val p22 = p22Match?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()

        if (p24 != null && p24 > 1000) {
            val final22 = p22 ?: (p24 * 22.0 / 24.0)
            return BullionParseResult(p24, final22, p22 == null)
        }

        return BullionParseResult(null, null, false)
    }

    fun parseKalyan(content: String): BullionParseResult {
        val p24Match = Regex(""""karat_24\(999\)"\s*:\s*\{[\s\S]*?"price_per_gram"\s*:\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(content)
            ?: Regex("""Gold\s*Rate\s*in\s*India\s*for\s*1\s*gram\s*is\s*(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(content)
            ?: Regex("""10g\s*of\s*24K\s*Gold[\s\S]{0,120}(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(content)
        var p24 = p24Match?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
        if (p24 != null && p24 > 100000) p24 /= 10.0 // 10g rate converted to 1g

        val p22Match = Regex(""""karat_22\(916\)"\s*:\s*\{[\s\S]*?"price_per_gram"\s*:\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(content)
        val p22 = p22Match?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()

        if (p24 != null && p24 > 1000) {
            val final22 = p22 ?: (p24 * 22.0 / 24.0)
            return BullionParseResult(p24, final22, p22 == null)
        }

        return BullionParseResult(null, null, false)
    }

    fun parseTanishq(content: String): BullionParseResult {
        // 1. Try id="goldRateValues" value="{...}" JSON
        val goldRateValuesMatch = Regex("""id=["']goldRateValues["'][^>]*\bvalue=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(content)
        if (goldRateValuesMatch != null) {
            val rawJson = goldRateValuesMatch.groupValues[1]
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
            val root = runCatching { JSONObject(rawJson) }.getOrNull()
            val list = root?.optJSONArray("GetDailyMetalRates")
            if (list != null && list.length() > 0) {
                val item = list.optJSONObject(0)
                val p24 = item?.optDouble("GoldRate24KT")?.takeIf { it.isFinite() && it > 1000 }
                val p22 = item?.optDouble("GoldRate22KT")?.takeIf { it.isFinite() && it > 1000 }
                if (p24 != null) {
                    val final22 = p22 ?: (p24 * 22.0 / 24.0)
                    return BullionParseResult(p24, final22, p22 == null)
                }
            }
        }

        // 2. Try data-goldrate24kt attribute
        val dataAttrMatch = Regex("""data-goldrate24kt\s*=\s*["']\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(content)
        val p24 = dataAttrMatch?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()

        val data22AttrMatch = Regex("""data-goldrate22kt\s*=\s*["']\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(content)
        val p22 = data22AttrMatch?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()

        if (p24 != null && p24 > 1000) {
            val final22 = p22 ?: (p24 * 22.0 / 24.0)
            return BullionParseResult(p24, final22, p22 == null)
        }

        // 3. Try table text fallback
        val textMatch = Regex("""24\s*Karat[\s\S]{0,180}(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(content)
            ?: Regex("""Gold\s*Rate\s*History\s*24\s*Karat[\s\S]*?Date\s+Rate\s+\d{1,2}-\d{1,2}-\d{4}\s*₹\s*([\d,]+)""", RegexOption.IGNORE_CASE).find(content)
        val textP24 = textMatch?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()

        if (textP24 != null && textP24 > 1000) {
            return BullionParseResult(textP24, textP24 * 22.0 / 24.0, true)
        }

        return BullionParseResult(null, null, false)
    }
}
