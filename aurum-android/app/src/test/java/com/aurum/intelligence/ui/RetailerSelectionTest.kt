package com.aurum.intelligence.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RetailerSelectionTest {
    private val all = setOf("ajio.com", "amazon.in", "flipkart.com", "myntra.com", "shopsy.in")

    @Test
    fun allSelectedTapAmazonIsolatesAmazon() {
        assertEquals(setOf("amazon.in"), RetailerSelection.toggle(all, "amazon.in", all))
    }

    @Test
    fun allSelectedTapAjioIsolatesAjio() {
        assertEquals(setOf("ajio.com"), RetailerSelection.toggle(all, "ajio.com", all))
    }

    @Test
    fun allSelectedTapFlipkartIsolatesFlipkart() {
        assertEquals(setOf("flipkart.com"), RetailerSelection.toggle(all, "flipkart.com", all))
    }

    @Test
    fun allSelectedTapMyntraIsolatesMyntra() {
        assertEquals(setOf("myntra.com"), RetailerSelection.toggle(all, "myntra.com", all))
    }

    @Test
    fun amazonOnlyTapAjioAddsAjio() {
        val current = setOf("amazon.in")
        assertEquals(setOf("amazon.in", "ajio.com"), RetailerSelection.toggle(current, "ajio.com", all))
    }

    @Test
    fun amazonAndAjioTapAmazonLeavesAjioOnly() {
        val current = setOf("amazon.in", "ajio.com")
        assertEquals(setOf("ajio.com"), RetailerSelection.toggle(current, "amazon.in", all))
    }

    @Test
    fun threeSelectedTapFourthSelectsAll() {
        val current = setOf("amazon.in", "ajio.com", "flipkart.com")
        assertEquals(all, RetailerSelection.toggle(current, "myntra.com", all))
    }

    @Test
    fun oneSelectedTapSameRetailerStaysSelected() {
        val current = setOf("amazon.in")
        assertEquals(setOf("amazon.in"), RetailerSelection.toggle(current, "amazon.in", all))
    }

    @Test
    fun explicitAllActionSelectsAllFour() {
        assertEquals(all, all.toSet())
    }

    @Test
    fun selectionNeverContainsDuplicateIdentities() {
        val result = RetailerSelection.toggle(setOf("amazon.in"), "amazon.in", all)
        assertEquals(result.size, result.toSet().size)
    }
}
