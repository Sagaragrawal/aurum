package com.aurum.intelligence.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserViewportTest {
    @Test
    fun usesMeasuredWindowSizeWhenAvailable() {
        val viewport = BrowserViewport.derive(windowWidthPx = 1080, windowHeightPx = 2340, density = 3f)
        assertTrue(viewport.derivedFromWindow)
        assertEquals(1080, viewport.widthPx)
        assertEquals(2340, viewport.heightPx)
        assertEquals(360, viewport.widthDp)
        assertEquals(780, viewport.heightDp)
    }

    @Test
    fun landscapeAndTabletWindowsAreNotForcedToPhoneDimensions() {
        val landscape = BrowserViewport.derive(2340, 1080, 3f)
        assertEquals(780, landscape.widthDp)
        assertEquals(360, landscape.heightDp)
        val tablet = BrowserViewport.derive(1600, 2560, 2f)
        assertEquals(800, tablet.widthDp)
        assertEquals(1280, tablet.heightDp)
    }

    @Test
    fun fallsBackToConstantsOnlyWhenWindowIsUnmeasured() {
        val viewport = BrowserViewport.derive(0, 0, 2f)
        assertFalse(viewport.derivedFromWindow)
        assertEquals(BrowserViewport.FALLBACK_WIDTH_DP, viewport.widthDp)
        assertEquals(BrowserViewport.FALLBACK_HEIGHT_DP, viewport.heightDp)
        assertEquals(828, viewport.widthPx)
        assertTrue(viewport.describe().contains("source=fallback"))
    }

    @Test
    fun invalidDensityDoesNotProduceDivisionByZero() {
        val viewport = BrowserViewport.derive(1080, 2340, 0f)
        assertEquals(1080, viewport.widthDp)
        assertTrue(viewport.describe().contains("source=window"))
    }
}
