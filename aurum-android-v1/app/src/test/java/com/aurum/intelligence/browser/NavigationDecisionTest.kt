package com.aurum.intelligence.browser

import com.aurum.intelligence.browser.NavigationDecision.Attempt
import com.aurum.intelligence.browser.NavigationDecision.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationDecisionTest {
    private val attempt = Attempt(urlIndex = 0, attemptNumber = 0, webViewGeneration = 1)

    @Test
    fun freshWebViewAndUrlNavigates() {
        val decision = NavigationDecision.decide(
            hasWebView = true,
            webViewIndex = 0,
            requestedIndex = 0,
            attempt = attempt,
            lastHandledKey = null,
            sessionCancelled = false,
            assetReady = true,
            assetError = null,
        )
        assertEquals(Decision.Navigate, decision)
    }

    @Test
    fun sameAttemptSameWebViewDoesNotDuplicateNavigation() {
        val decision = NavigationDecision.decide(
            hasWebView = true,
            webViewIndex = 0,
            requestedIndex = 0,
            attempt = attempt,
            lastHandledKey = attempt.key(),
            sessionCancelled = false,
            assetReady = true,
            assetError = null,
        )
        assertEquals(Decision.AlreadyHandled, decision)
    }

    @Test
    fun newRetryAttemptNavigates() {
        val retry = attempt.copy(attemptNumber = 1)
        val decision = NavigationDecision.decide(
            hasWebView = true,
            webViewIndex = 0,
            requestedIndex = 0,
            attempt = retry,
            lastHandledKey = attempt.key(),
            sessionCancelled = false,
            assetReady = true,
            assetError = null,
        )
        assertEquals(Decision.Navigate, decision)
    }

    @Test
    fun newWebViewGenerationAfterRotationNavigatesEvenWithSameIndexAndAttempt() {
        val recreated = attempt.copy(webViewGeneration = 2)
        val decision = NavigationDecision.decide(
            hasWebView = true,
            webViewIndex = 0,
            requestedIndex = 0,
            attempt = recreated,
            lastHandledKey = attempt.key(),
            sessionCancelled = false,
            assetReady = true,
            assetError = null,
        )
        assertEquals(Decision.Navigate, decision)
    }

    @Test
    fun oneWebViewNavigatesEachSequentialUrlIndexExactlyOnce() {
        // AJIO walks four URLs on ONE WebView (generation unchanged); each index must navigate once.
        var lastHandledKey: String? = null
        var navigations = 0
        repeat(4) { urlIndex ->
            val attemptForIndex = Attempt(urlIndex = urlIndex, attemptNumber = 0, webViewGeneration = 1)
            repeat(3) {
                val decision = NavigationDecision.decide(
                    hasWebView = true,
                    webViewIndex = urlIndex,
                    requestedIndex = urlIndex,
                    attempt = attemptForIndex,
                    lastHandledKey = lastHandledKey,
                    sessionCancelled = false,
                    assetReady = true,
                    assetError = null,
                )
                if (decision == Decision.Navigate) {
                    navigations += 1
                    lastHandledKey = attemptForIndex.key()
                } else {
                    assertEquals(Decision.AlreadyHandled, decision)
                }
            }
        }
        assertEquals(4, navigations)
    }

    @Test
    fun wrongIndexIsExplicitlyRejected() {
        val decision = NavigationDecision.decide(
            hasWebView = true,
            webViewIndex = 1,
            requestedIndex = 0,
            attempt = attempt,
            lastHandledKey = null,
            sessionCancelled = false,
            assetReady = true,
            assetError = null,
        )
        assertEquals(Decision.Reject("index_mismatch"), decision)
    }

    @Test
    fun cancelledSessionIsExplicitlyRejected() {
        val decision = NavigationDecision.decide(
            hasWebView = true,
            webViewIndex = 0,
            requestedIndex = 0,
            attempt = attempt,
            lastHandledKey = null,
            sessionCancelled = true,
            assetReady = true,
            assetError = null,
        )
        assertEquals(Decision.Reject("session_cancelled"), decision)
    }

    @Test
    fun missingWebViewIsExplicitlyRejected() {
        val decision = NavigationDecision.decide(
            hasWebView = false,
            webViewIndex = -1,
            requestedIndex = 0,
            attempt = attempt,
            lastHandledKey = null,
            sessionCancelled = false,
            assetReady = true,
            assetError = null,
        )
        assertEquals(Decision.Reject("no_webview"), decision)
    }

    @Test
    fun assetNotYetLoadedIsRejectedNotSilentlyDropped() {
        val decision = NavigationDecision.decide(
            hasWebView = true,
            webViewIndex = 0,
            requestedIndex = 0,
            attempt = attempt,
            lastHandledKey = null,
            sessionCancelled = false,
            assetReady = false,
            assetError = null,
        )
        assertEquals(Decision.Reject("asset_not_ready"), decision)
    }

    @Test
    fun assetLoadFailureIsRejectedWithReason() {
        val decision = NavigationDecision.decide(
            hasWebView = true,
            webViewIndex = 0,
            requestedIndex = 0,
            attempt = attempt,
            lastHandledKey = null,
            sessionCancelled = false,
            assetReady = false,
            assetError = "asset missing",
        )
        assertEquals(Decision.Reject("asset_missing"), decision)
    }

    // Regresses the 4.9.13 hang: a caller must only record `lastHandledKey` together with actually
    // invoking navigate() (no suspension in between). If a navigation attempt is interrupted before
    // that commit happens, re-evaluating with the same key must still Navigate, not AlreadyHandled.
    @Test
    fun interruptedAttemptBeforeCommitDoesNotPermanentlySuppressNavigation() {
        val first = NavigationDecision.decide(
            hasWebView = true,
            webViewIndex = 0,
            requestedIndex = 0,
            attempt = attempt,
            lastHandledKey = null,
            sessionCancelled = false,
            assetReady = true,
            assetError = null,
        )
        assertEquals(Decision.Navigate, first)

        // Simulate: the effect was cancelled/relaunched before the caller committed lastHandledKey.
        val retried = NavigationDecision.decide(
            hasWebView = true,
            webViewIndex = 0,
            requestedIndex = 0,
            attempt = attempt,
            lastHandledKey = null,
            sessionCancelled = false,
            assetReady = true,
            assetError = null,
        )
        assertEquals(Decision.Navigate, retried)
    }

    @Test
    fun allDecisionsHaveExplicitReasonNeverSilent() {
        val rejections = listOf(
            NavigationDecision.decide(false, -1, 0, attempt, null, false, true, null),
            NavigationDecision.decide(true, 1, 0, attempt, null, false, true, null),
            NavigationDecision.decide(true, 0, 0, attempt, null, true, true, null),
            NavigationDecision.decide(true, 0, 0, attempt, null, false, false, null),
            NavigationDecision.decide(true, 0, 0, attempt, null, false, false, "boom"),
        )
        rejections.forEach { decision ->
            check(decision is Decision.Reject) { "expected an explicit rejection, got $decision" }
            check(decision.reason.isNotBlank())
        }
    }
}
