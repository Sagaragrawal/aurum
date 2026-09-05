package com.aurum.intelligence.data

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
    private const val FAILURE_THRESHOLD = 3
    private const val COOLDOWN_DURATION_MS = 15 * 60 * 1000L // 15 minutes cooldown

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
                if (nowMillis - circuit.lastFailureAt >= COOLDOWN_DURATION_MS) {
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
        if (isAntiBotBlock || circuit.failureCount >= FAILURE_THRESHOLD) {
            circuit.state = CircuitState.Open
        }
    }

    fun status(storeName: String, nowMillis: Long = System.currentTimeMillis()): CircuitStatus {
        val storeKey = storeName.lowercase()
        val circuit = circuits[storeKey] ?: return CircuitStatus(CircuitState.Closed, 0, 0L, 0L, null)
        val remaining = (COOLDOWN_DURATION_MS - (nowMillis - circuit.lastFailureAt)).coerceAtLeast(0L)
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
        val markers = listOf(
            "access denied",
            "request blocked",
            "security check",
            "captcha",
            "cloudflare",
            "akamai",
            "perimeterx",
            "bot challenge",
            "http 403",
            "http 429",
            "too many requests",
            "blocked due to security",
        )
        return markers.any { text.contains(it, ignoreCase = true) }
    }

    fun resetAll() {
        circuits.clear()
    }
}
