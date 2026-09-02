package com.aurum.intelligence.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BullionDomainTest {
    @Test
    fun blendsRatesAfterRemovingValuesBeyondSixPercentOfMedian() {
        val rates = listOf(15_690.0, 15_740.0, 15_950.0, 30_000.0)

        assertEquals(listOf(15_690.0, 15_740.0, 15_950.0), BullionBenchmark.cleanRates(rates))
        assertEquals(15_793.33, BullionBenchmark.blend(rates)!!, 0.01)
    }

    @Test
    fun keepsSmallSourceSetsAndFallsBackWhenFilteringWouldLeaveOneRate() {
        assertEquals(listOf(10_000.0, 12_000.0), BullionBenchmark.cleanRates(listOf(10_000.0, 12_000.0)))
        assertEquals(listOf(10_000.0, 20_000.0, 40_000.0), BullionBenchmark.cleanRates(listOf(10_000.0, 20_000.0, 40_000.0)))
    }

    @Test
    fun parsesMalabarGraphQlRates() {
        val payload = """
            {"data":{"getMetalRate":{"items":[
              {"purity":"24K","rate":"15693"},
              {"purity":"916","rate":"14385"}
            ]}}}
        """.trimIndent()

        assertEquals(BullionRates(15_693.0, 14_385.0), BullionRateParser.parse(payload, "malabar"))
    }

    @Test
    fun parsesMmtcQuoteWithoutMistakingFinenessFor22KPrice() {
        assertEquals(
            BullionRates(15_954.61, null),
            BullionRateParser.parse("{\"preTaxAmount\":15954.61}", "mmtc"),
        )
        val page = "24k Gold Rate (Exc. GST) 1 gm INR 15,954.61 22k Gold Rate (Exc. GST) 1 gm INR 14,625.06"
        assertEquals(BullionRates(15_954.61, 14_625.06), BullionRateParser.parse(page, "mmtc"))
    }

    @Test
    fun parsesKalyanAndTanishqEmbeddedRates() {
        val kalyan = """
            "karat_24(999)":{"price_per_gram":15693},
            "karat_22(916)":{"price_per_gram":14385.25}
        """.trimIndent()
        assertEquals(BullionRates(15_693.0, 14_385.25), BullionRateParser.parse(kalyan, "kalyan"))

        val tanishq = "<span data-goldrate24kt=\"15,742\"></span>"
        assertEquals(15_742.0, BullionRateParser.parse(tanishq, "tan").price24!!, 0.0)
        assertNull(BullionRateParser.parse(tanishq, "tan").price22)
    }

    @Test
    fun rejectsInflatedRenderedAndImportedRates() {
        val rates = BullionRateParser.parse("<span data-goldrate24kt=\"15,595\"></span> 22 Karat Rs 14,295,000", "tan")

        assertEquals(15_595.0, rates.price24!!, 0.0)
        assertNull(rates.price22)
        assertEquals(false, BullionRatePolicy.isPlausible24(1_443_000.0))
    }
}