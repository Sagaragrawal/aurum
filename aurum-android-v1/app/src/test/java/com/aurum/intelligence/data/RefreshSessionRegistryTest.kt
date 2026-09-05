package com.aurum.intelligence.data

import org.junit.Assert.assertThrows
import org.junit.Test

class RefreshSessionRegistryTest {
    @Test
    fun beginAuthorizesThenEndRejectsImmediately() {
        val registry = RefreshSessionRegistry()
        registry.begin("session-1", setOf("amazon.in"))

        registry.requireAllowed("session-1", "amazon.in")

        registry.end("session-1")

        assertThrows(IllegalArgumentException::class.java) {
            registry.requireAllowed("session-1", "amazon.in")
        }
    }

    @Test
    fun requireAllowedRejectsStoreNotIncludedInSession() {
        val registry = RefreshSessionRegistry()
        registry.begin("session-1", setOf("amazon.in"))

        assertThrows(IllegalArgumentException::class.java) {
            registry.requireAllowed("session-1", "flipkart.com")
        }
    }

    @Test
    fun requireAllowedRejectsUnknownSession() {
        val registry = RefreshSessionRegistry()

        assertThrows(IllegalArgumentException::class.java) {
            registry.requireAllowed("never-begun", "amazon.in")
        }
    }

    @Test
    fun endIsIdempotentAndSafeWithoutAPriorBegin() {
        val registry = RefreshSessionRegistry()

        registry.end("never-begun")

        assertThrows(IllegalArgumentException::class.java) {
            registry.requireAllowed("never-begun", "amazon.in")
        }
    }
}
