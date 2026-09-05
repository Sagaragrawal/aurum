package com.aurum.intelligence.ui

/**
 * Pure retailer multi-select transition, single source of truth `Set<String>`.
 *
 * Tapping a retailer while every retailer is currently selected means "switch from All to only
 * this retailer" - not "everything except this retailer". `allSelected` is always derived from
 * the selection itself (`current == all`), never tracked as an independent boolean.
 */
object RetailerSelection {
    fun toggle(current: Set<String>, tapped: String, all: Set<String>): Set<String> = when {
        current == all -> setOf(tapped)
        tapped in current -> (current - tapped).ifEmpty { current }
        else -> current + tapped
    }
}
