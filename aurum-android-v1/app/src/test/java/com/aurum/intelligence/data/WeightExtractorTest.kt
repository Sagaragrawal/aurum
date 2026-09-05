package com.aurum.intelligence.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WeightExtractorTest {

    @Test
    fun testSingleWeightParsing() {
        val w1 = WeightExtractor.parse("24K Gold Coin 1g")
        assertEquals(1.0, w1.unitWeightGrams!!, 0.001)
        assertEquals(1, w1.quantity)
        assertEquals(1.0, w1.totalWeightGrams!!, 0.001)
        assertEquals(WeightConfidence.High, w1.confidence)

        val w2 = WeightExtractor.parse("22K Yellow Gold Bar 2.5 grams")
        assertEquals(2.5, w2.unitWeightGrams!!, 0.001)
        assertEquals(2.5, w2.totalWeightGrams!!, 0.001)

        val w3 = WeightExtractor.parse("Gold Coin 500 mg")
        assertEquals(0.5, w3.unitWeightGrams!!, 0.001)
        assertEquals(0.5, w3.totalWeightGrams!!, 0.001)
    }

    @Test
    fun testMultiPackWeightParsing() {
        val w1 = WeightExtractor.parse("24K 999 Gold Coin 1g x 2")
        assertEquals(1.0, w1.unitWeightGrams!!, 0.001)
        assertEquals(2, w1.quantity)
        assertEquals(2.0, w1.totalWeightGrams!!, 0.001)

        val w2 = WeightExtractor.parse("2 x 0.5g Yellow Gold Bar")
        assertEquals(0.5, w2.unitWeightGrams!!, 0.001)
        assertEquals(2, w2.quantity)
        assertEquals(1.0, w2.totalWeightGrams!!, 0.001)

        val w3 = WeightExtractor.parse("Gold Coin 0.5g + 0.5g")
        assertEquals(0.5, w3.unitWeightGrams!!, 0.001)
        assertEquals(2, w3.quantity)
        assertEquals(1.0, w3.totalWeightGrams!!, 0.001)

        val w4 = WeightExtractor.parse("Pack of 5 Gold Coins (0.5g each)")
        assertEquals(0.5, w4.unitWeightGrams!!, 0.001)
        assertEquals(5, w4.quantity)
        assertEquals(2.5, w4.totalWeightGrams!!, 0.001)
    }

    @Test
    fun testPurityDisambiguation() {
        val w1 = WeightExtractor.parse("24K 999 Fineness 2024 Year Edition 5 Grams Gold Coin")
        assertEquals(5.0, w1.unitWeightGrams!!, 0.001)
        assertEquals(5.0, w1.totalWeightGrams!!, 0.001)

        val w2 = WeightExtractor.parse("22K 916 Gold Bar 10 GM")
        assertEquals(10.0, w2.unitWeightGrams!!, 0.001)
        assertEquals(10.0, w2.totalWeightGrams!!, 0.001)
    }

    @Test
    fun testSpecificationTableParsing() {
        val body = """
            {
                "productDetails": {
                    "net weight": "2.5 g",
                    "purity": "24K"
                }
            }
        """.trimIndent()
        val w = WeightExtractor.parse("Generic Gold Coin", body)
        assertEquals(2.5, w.unitWeightGrams!!, 0.001)
        assertEquals(WeightSource.SpecificationTable, w.source)
    }

    @Test
    fun testAmbiguousTitle() {
        val w = WeightExtractor.parse("Generic Gold Item No Weight")
        assertNull(w.totalWeightGrams)
        assertEquals(WeightConfidence.Ambiguous, w.confidence)
    }
}
