package com.aurum.intelligence.browser

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AjioRequestPacingTest {
    private val masterSource: String by lazy {
        File("../../aurum-desktop/manual_js/ajio_gold_master.js").readText()
    }

    @Test
    fun initialSettleAndCategoryCooldownAreConservative() {
        assertEquals(5_000L, AjioRequestPacing.MASTER_SETTLE_MS)
        assertEquals(10_000L, AjioRequestPacing.CATEGORY_COOLDOWN_MS)
    }

    @Test
    fun searchAndPdpAreSerialAndPaced() {
        assertTrue(masterSource.contains("searchConcurrency: 1"))
        assertTrue(masterSource.contains("searchDelayMs: 1000"))
        assertTrue(masterSource.contains("pdpConcurrency: 1"))
        assertTrue(masterSource.contains("pdpDelayMs: 1000"))
    }

    @Test
    fun accessDenialsAreNotRetriedAndPdpTripsCircuitBreaker() {
        assertTrue(masterSource.contains("if (r.status === 401 || r.status === 403) {\n          return r;"))
        assertTrue(masterSource.contains("pdpAccessDenied = true"))
        assertTrue(masterSource.contains("if (accessDenied || pdpAccessDenied || isCancelled()) return;"))
        assertTrue(masterSource.contains("if (pdpAccessDenied || isCancelled()) return;"))
    }

    @Test
    fun transientFailuresUseBoundedDeterministicBackoff() {
        assertTrue(masterSource.contains("transientBackoffMs: [1000, 2000, 4000]"))
        assertTrue(masterSource.contains("await sleep(waitMs)"))
    }
}
