package com.aurum.intelligence.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StoreCircuitBreakerTest {

    @Before
    fun setUp() {
        StoreCircuitBreaker.resetAll()
    }

    @Test
    fun testNormalOperation() {
        assertTrue(StoreCircuitBreaker.canExecute("ajio.com"))
        StoreCircuitBreaker.recordSuccess("ajio.com")
        assertEquals(CircuitState.Closed, StoreCircuitBreaker.status("ajio.com").state)
    }

    @Test
    fun testAntiBotChallengeTripsCircuit() {
        assertTrue(StoreCircuitBreaker.canExecute("myntra.com"))
        StoreCircuitBreaker.recordFailure("myntra.com", "Access denied by security check")
        assertEquals(CircuitState.Open, StoreCircuitBreaker.status("myntra.com").state)
        assertFalse(StoreCircuitBreaker.canExecute("myntra.com"))
    }

    @Test
    fun testRepeatedFailuresTripCircuit() {
        val store = "flipkart.com"
        assertTrue(StoreCircuitBreaker.canExecute(store))
        StoreCircuitBreaker.recordFailure(store, "Timeout 1")
        assertTrue(StoreCircuitBreaker.canExecute(store))
        StoreCircuitBreaker.recordFailure(store, "Timeout 2")
        assertTrue(StoreCircuitBreaker.canExecute(store))
        StoreCircuitBreaker.recordFailure(store, "Timeout 3")
        assertEquals(CircuitState.Open, StoreCircuitBreaker.status(store).state)
        assertFalse(StoreCircuitBreaker.canExecute(store))
    }

    @Test
    fun testCooldownExpirationHalfOpen() {
        val store = "amazon.in"
        val now = System.currentTimeMillis()
        StoreCircuitBreaker.recordFailure(store, "Access denied", now)
        assertFalse(StoreCircuitBreaker.canExecute(store, now + 1000))

        // Fast forward 16 minutes (beyond 15-min cooldown)
        val future = now + (16 * 60 * 1000L)
        assertTrue(StoreCircuitBreaker.canExecute(store, future))
        assertEquals(CircuitState.HalfOpen, StoreCircuitBreaker.status(store, future).state)
    }
}
