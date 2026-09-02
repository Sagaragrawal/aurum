package com.aurum.intelligence.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AjioIntegrationPolicyTest {
    @Test
    fun onlyAjioUsesNativeWebViewUserAgent() {
        assertTrue(RetailerBrowserPolicy.usesNativeUserAgent(Retailer.Ajio))
        assertFalse(RetailerBrowserPolicy.usesNativeUserAgent(Retailer.Amazon))
        assertFalse(RetailerBrowserPolicy.usesNativeUserAgent(null))
    }

    @Test
    fun acceptsExpectedCompleteAjioPathsWithoutLocalStorage() {
        assertEquals(RetailerReadiness.Ready, AjioReadiness.classify("www.ajio.com", "/s/boys-169373", "complete", "Products"))
        assertEquals(RetailerReadiness.Ready, AjioReadiness.classify("www.ajio.com", "/women/c/8303", "complete", "Products"))
        assertEquals(RetailerReadiness.Waiting, AjioReadiness.classify("www.ajio.com", "/help", "complete", "Help"))
    }

    @Test
    fun classifiesKnownAjioBlockPages() {
        assertEquals(RetailerReadiness.Blocked("access_denied"), AjioReadiness.classify("www.ajio.com", "/s/boys", "complete", "Access Denied"))
        assertEquals(RetailerReadiness.Blocked("captcha"), AjioReadiness.classify("www.ajio.com", "/s/boys", "complete", "Complete CAPTCHA"))
    }

    @Test
    fun missingPageRequestStillPermitsMasterUrlFallback() {
        assertEquals("", AjioPageRequestParser.injectionPrefix(null))
    }

    @Test
    fun capturedPageRequestIsInjectedUnchanged() {
        val request = AjioPageRequest("{\"pathname\":\"/api/curated/boys-169373\",\"query\":{}}", "/api/curated/boys-169373")
        assertTrue(AjioPageRequestParser.injectionPrefix(request).contains(request.json))
    }
}