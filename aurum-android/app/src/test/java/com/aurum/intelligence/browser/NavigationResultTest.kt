package com.aurum.intelligence.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationResultTest {
    @Test
    fun tokenIdentifiesUrlAndNavigationStartInstant() {
        val token = NavigationResult.token("https://www.ajio.com/x", 1_234L)
        assertEquals("https://www.ajio.com/x:1234", token)
        assertTrue(token.startsWith("https://www.ajio.com/x"))
    }

    @Test
    fun repeatedNavigationsToSameUrlProduceDistinctTokens() {
        assertNotEquals(
            NavigationResult.token("https://www.ajio.com/x", 1L),
            NavigationResult.token("https://www.ajio.com/x", 2L),
        )
    }

    @Test
    fun loadStartedCarriesTheTokenTheWatchdogMustObserve() {
        val token = NavigationResult.token("https://www.myntra.com/x", 99L)
        val result: NavigationResult = NavigationResult.LoadStarted(token)
        assertEquals(token, (result as NavigationResult.LoadStarted).token)
    }

    @Test
    fun loadFailedCarriesAReasonAndNoToken() {
        val result: NavigationResult = NavigationResult.LoadFailed("boom")
        assertEquals("boom", (result as NavigationResult.LoadFailed).reason)
    }
}
