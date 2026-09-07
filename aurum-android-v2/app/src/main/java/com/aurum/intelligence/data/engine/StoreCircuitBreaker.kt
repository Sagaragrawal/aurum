package com.aurum.intelligence.data.engine
import com.aurum.intelligence.data.db.*
import com.aurum.intelligence.data.engine.*
import com.aurum.intelligence.data.model.*
import com.aurum.intelligence.data.repository.*
import com.aurum.intelligence.data.validation.*

import java.util.concurrent.ConcurrentHashMap

enum class CircuitState { Closed, Open, HalfOpen }

data class CircuitStatus(
    val state: CircuitState,
    val failureCount: Int,
    val lastFailureAt: Long,
    val cooldownRemainingMs: Long,
    val reason: String?,
)

object StoreCircuitBreaker {
    private val failureThreshold: Int get() = ScraperConfigProvider.get().circuitBreaker.failureThreshold
    private val cooldownDurationMs: Long get() = ScraperConfigProvider.get().circuitBreaker.cooldownDurationMs

    private data class StoreCircuit(
        var state: CircuitState = CircuitState.Closed,
        var failureCount: Int = 0,
        var lastFailureAt: Long = 0L,
        var reason: String? = null,
    )

    private val circuits = ConcurrentHashMap<String, StoreCircuit>()

    fun canExecute(storeName: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val storeKey = storeName.lowercase()
        val circuit = circuits[storeKey] ?: return true
        return when (circuit.state) {
            CircuitState.Closed -> true
            CircuitState.HalfOpen -> true
            CircuitState.Open -> {
                if (nowMillis - circuit.lastFailureAt >= cooldownDurationMs) {
                    circuit.state = CircuitState.HalfOpen
                    true
                } else {
                    false
                }
            }
        }
    }

    fun recordSuccess(storeName: String) {
        val storeKey = storeName.lowercase()
        circuits[storeKey] = StoreCircuit(
            state = CircuitState.Closed,
            failureCount = 0,
            lastFailureAt = 0L,
            reason = null,
        )
    }

    fun recordFailure(storeName: String, reason: String, nowMillis: Long = System.currentTimeMillis()) {
        val storeKey = storeName.lowercase()
        val circuit = circuits.computeIfAbsent(storeKey) { StoreCircuit() }
        circuit.failureCount += 1
        circuit.lastFailureAt = nowMillis
        circuit.reason = reason

        val isAntiBotBlock = isAntiBotChallenge(reason)
        if (isAntiBotBlock || circuit.failureCount >= failureThreshold) {
            circuit.state = CircuitState.Open
        }
    }

    fun status(storeName: String, nowMillis: Long = System.currentTimeMillis()): CircuitStatus {
        val storeKey = storeName.lowercase()
        val circuit = circuits[storeKey] ?: return CircuitStatus(CircuitState.Closed, 0, 0L, 0L, null)
        val remaining = (cooldownDurationMs - (nowMillis - circuit.lastFailureAt)).coerceAtLeast(0L)
        return CircuitStatus(
            state = circuit.state,
            failureCount = circuit.failureCount,
            lastFailureAt = circuit.lastFailureAt,
            cooldownRemainingMs = if (circuit.state == CircuitState.Open) remaining else 0L,
            reason = circuit.reason,
        )
    }

    fun isAntiBotChallenge(text: String): Boolean {
        if (text.isBlank()) return false
        val markers = ScraperConfigProvider.get().circuitBreaker.antiBotMarkers
        return markers.any { text.contains(it, ignoreCase = true) }
    }

    fun resetAll() {
        circuits.clear()
    }
}
