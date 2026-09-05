package com.aurum.intelligence.ui

import com.aurum.intelligence.data.BullionHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class BullionTrendSeriesTest {
    @Test
    fun blendsSourcesAtEachTimestamp() {
        val history = listOf(
            row(1, "a", 15_000.0, 14_000.0),
            row(1, "b", 15_200.0, 14_200.0),
            row(2, "a", 15_500.0, 14_500.0),
        )

        val points = BullionTrendSeries.build(history)

        assertEquals(2, points.size)
        assertEquals(15_100.0, points.first().price24, 0.0)
        assertEquals(14_100.0, points.first().price22, 0.0)
    }

    private fun row(at: Long, source: String, price24: Double, price22: Double) = BullionHistoryEntity(
        sourceId = source,
        price24 = price24,
        price22 = price22,
        price22Derived = false,
        fetchedAt = at,
    )
}