package com.aurum.intelligence.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

data class BullionRates(val price24: Double?, val price22: Double?)

object BullionRatePolicy {
    fun isPlausible24(value: Double?): Boolean = value?.isFinite() == true && value in 3_000.0..50_000.0

    fun isPlausible22(value: Double?, price24: Double?): Boolean {
        val rate24 = price24 ?: return false
        return value?.isFinite() == true && isPlausible24(rate24) && value in (rate24 * 0.72)..(rate24 * 1.02)
    }
}

object BullionBenchmark {
    fun cleanRates(values: List<Double>): List<Double> {
        val rates = values.filter(BullionRatePolicy::isPlausible24)
        if (rates.size < 3) return rates
        val sorted = rates.sorted()
        val middle = sorted.size / 2
        val median = if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
        val filtered = rates.filter { abs(it - median) <= median * 0.06 }
        return if (filtered.size >= 2) filtered else rates
    }

    fun blend(values: List<Double>): Double? = cleanRates(values).takeIf { it.isNotEmpty() }?.average()
}

object BullionRateParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String, sourceId: String): BullionRates {
        val jsonRates = parseJson(text, sourceId)
        val price24 = jsonRates?.price24 ?: parse24(text, sourceId)
        val raw22 = jsonRates?.price22 ?: parse22(text, sourceId)
        val plausible24 = price24?.takeIf(BullionRatePolicy::isPlausible24)
        return BullionRates(plausible24, raw22?.takeIf { BullionRatePolicy.isPlausible22(it, plausible24) })
    }

    private fun parseJson(text: String, sourceId: String): BullionRates? {
        if (!text.trimStart().startsWith('{') && !text.trimStart().startsWith('[')) return null
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        if (sourceId == "mmtc") {
            val price24 = root.number("preTaxAmount") ?: root.number("totalAmount")
            return BullionRates(price24, null)
        }
        if (sourceId != "malabar") return null
        val items = runCatching {
            root.getValue("data").jsonObject.getValue("getMetalRate").jsonObject
                .getValue("items").jsonArray.map { it.jsonObject }
        }.getOrNull() ?: return null
        fun rateFor(vararg purities: String): Double? = items.firstOrNull { item ->
            item["purity"]?.jsonPrimitive?.contentOrNull?.lowercase() in purities
        }?.number("rate")
        return BullionRates(rateFor("24k", "99.99", "999"), rateFor("22k", "916"))
    }

    private fun parse24(text: String, sourceId: String): Double? = when (sourceId) {
        "malabar" -> capture(text, """([\d,]+(?:\.\d+)?)\s*INR\s*/\s*gms?[\s\S]{0,160}24k\s*\(999\)""")
            ?: allCaptures(text, """([\d,]+(?:\.\d+)?)\s*INR\s*/\s*gms?""").maxOrNull()
        "mmtc" -> capture(text, """24k\s*Gold\s*Rate\s*\(Exc\.\s*GST\)[\s\S]{0,280}?(?:1\s*gm|1gm)\s*(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)""")
            ?: capture(text, """24k\s*Gold\s*Rate\s*Today[\s\S]{0,240}(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)""")
        "kalyan" -> capture(text, """"karat_24\(999\)"\s*:\s*\{[\s\S]*?"price_per_gram"\s*:\s*(\d+(?:\.\d+)?)""")
            ?: capture(text, """Gold\s*Rate\s*in\s*India\s*for\s*1\s*gram\s*is\s*(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)""")
            ?: capture(text, """10g\s*of\s*24K\s*Gold[\s\S]{0,120}(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)""")?.div(10)
        "tan" -> capture(text, """data-goldrate24kt\s*=\s*["']\s*([\d,]+(?:\.\d+)?)""")
            ?: capture(text, """24\s*Karat[\s\S]{0,180}(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)""")
        else -> null
    }

    private fun parse22(text: String, sourceId: String): Double? = when (sourceId) {
        "tan" -> capture(text, """22\s*(?:Karat|Kt|K)[\s\S]{0,220}(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)""")
        "malabar" -> capture(text, """([\d,]+(?:\.\d+)?)\s*INR\s*/\s*gms?[\s\S]{0,200}22\s*k\s*\(\s*916\s*\)""")
        "mmtc" -> capture(text, """22k\s*Gold\s*Rate\s*\(Exc\.\s*GST\)[\s\S]{0,260}?(?:1\s*gm|1gm)?\s*(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)""")
        "kalyan" -> capture(text, """"karat_22\(916\)"\s*:\s*\{[\s\S]*?"price_per_gram"\s*:\s*(\d+(?:\.\d+)?)""")
        else -> null
    }

    private fun capture(text: String, pattern: String): Double? = Regex(pattern, RegexOption.IGNORE_CASE)
        .find(text)?.groupValues?.getOrNull(1)?.replace(",", "")?.toDoubleOrNull()

    private fun allCaptures(text: String, pattern: String): List<Double> = Regex(pattern, RegexOption.IGNORE_CASE)
        .findAll(text).mapNotNull { it.groupValues.getOrNull(1)?.replace(",", "")?.toDoubleOrNull() }.toList()

    private fun JsonObject.number(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull
}