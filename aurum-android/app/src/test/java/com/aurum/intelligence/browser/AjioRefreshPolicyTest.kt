package com.aurum.intelligence.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AjioRefreshPolicyTest {
    @Test
    fun confirmedBlockIsTerminalForTheEntireAjioStoreRun() {
        val outcome = AjioRefreshPolicy.terminalState(RetailerReadiness.Blocked("access_denied"))

        assertEquals(AjioRefreshPolicy.Blocked, outcome)
        assertFalse(AjioRefreshPolicy.mayScheduleNextUrl(outcome!!))
        assertFalse(AjioRefreshPolicy.mayRetryWithinRefreshSession(outcome))
    }

    @Test
    fun nonBlockedReadinessDoesNotPreventFutureUrlScheduling() {
        assertNull(AjioRefreshPolicy.terminalState(RetailerReadiness.Ready))
        assertTrue(AjioRefreshPolicy.mayScheduleNextUrl("partial"))
        assertTrue(AjioRefreshPolicy.mayRetryWithinRefreshSession("complete"))
    }

    @Test
    fun externalViewingKeepsTheExactConfiguredAjioUrl() {
        val url = "https://www.ajio.com/s/boys-169373"

        assertEquals(url, AjioRefreshPolicy.externalViewingUrl(url))
    }

    @Test
    fun blockedHasItsOwnSummaryOutcomeAndIsNotUpdatedOrPartial() {
        assertEquals("blocked", AjioRefreshPolicy.summaryLabel(AjioRefreshPolicy.Blocked))
        assertEquals("updated", AjioRefreshPolicy.summaryLabel("complete"))
        assertEquals("partial", AjioRefreshPolicy.summaryLabel("partial"))
        assertEquals("failed", AjioRefreshPolicy.summaryLabel("failed"))
        assertEquals("cancelled", AjioRefreshPolicy.summaryLabel("cancelled"))
    }
}