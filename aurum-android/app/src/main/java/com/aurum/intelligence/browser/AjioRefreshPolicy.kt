package com.aurum.intelligence.browser

object AjioRefreshPolicy {
    const val Blocked = "blocked"

    fun terminalState(readiness: RetailerReadiness): String? = when (readiness) {
        is RetailerReadiness.Blocked -> Blocked
        else -> null
    }

    fun mayScheduleNextUrl(state: String): Boolean = state != Blocked

    fun mayRetryWithinRefreshSession(state: String): Boolean = state != Blocked

    fun summaryLabel(state: String): String = when (state) {
        "complete" -> "updated"
        "partial" -> "partial"
        Blocked -> "blocked"
        "cancelled" -> "cancelled"
        else -> "failed"
    }

    fun externalViewingUrl(url: String): String = url
}