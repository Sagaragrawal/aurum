package com.aurum.intelligence.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshOutcomeSummaryTest {
    @Test
    fun blockedIsCountedSeparatelyFromUpdatedAndPartial() {
        val summary = formatRefreshSummary(
            listOf(
                StoreCompletionSummary("amazon.in", "complete", 1, "done"),
                StoreCompletionSummary("ajio.com", "blocked", 0, "blocked"),
                StoreCompletionSummary("flipkart.com", "partial", 1, "partial"),
                StoreCompletionSummary("myntra.com", "failed", 0, "failed"),
            ),
            cancelledCount = 0,
        )

        assertEquals("Refresh finished: 1 updated · 1 partial · 1 failed · 1 blocked", summary)
    }

    @Test
    fun cancelledIsAlsoASeparateTerminalOutcome() {
        assertEquals("Refresh finished: 1 cancelled", formatRefreshSummary(emptyList(), cancelledCount = 1))
    }
}