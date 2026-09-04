package com.aurum.intelligence.ui

import com.aurum.intelligence.browser.MasterScripts
import com.aurum.intelligence.browser.Retailer
import com.aurum.intelligence.data.RefreshLogSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AjioBridgeWrapperTest {
    private val ajioMaster = MasterScripts.all.first { it.retailer == Retailer.Ajio }

    @Test
    fun generatedAjioWrapperEmbedsSessionLiteralAndBridgeHeader() {
        val js = generateMasterWrapper(
            master = ajioMaster,
            source = "window.ajioGold=[];window.ajioDone=Promise.resolve();",
            sessionId = "test-session-123",
        )

        assertTrue(js.contains("\"test-session-123\""))
        assertTrue(js.contains("headersObj['X-Aurum-Refresh-Session'] = \"test-session-123\""))
        assertTrue(js.contains("'X-Aurum-Refresh-Session': \"test-session-123\""))
        assertTrue(js.contains("const aurumBridgePath = 'http://localhost:8788/api/browser-bridge/products'"))
        assertTrue(js.contains("fetch('http://localhost:8788/api/browser-bridge/products'"))
        assertFalse(js.contains("aurumSession"))
    }

    @Test
    fun generatedAjioWrapperSafelyEscapesSessionLiteral() {
        val js = generateMasterWrapper(
            master = ajioMaster,
            source = "window.ajioGold=[];window.ajioDone=Promise.resolve();",
            sessionId = "test-session-'\\\n\r\t-123",
        )

        assertTrue(js.contains("\"test-session-'\\\\\\n\\r\\t-123\""))
        assertTrue(js.contains("headersObj['X-Aurum-Refresh-Session'] = \"test-session-'\\\\\\n\\r\\t-123\""))
        assertTrue(js.contains("'X-Aurum-Refresh-Session': \"test-session-'\\\\\\n\\r\\t-123\""))
        assertFalse(js.contains("aurumSession"))
    }

    @Test
    fun normalAjioMasterProgressIsInfoButFailuresStayError() {
        assertEquals(
            RefreshLogSeverity.Info,
            diagnosticSeverity("error", "Retailer console page:1: [Aurum AJIO Master] MASTER_RESOLVE count=8"),
        )
        assertEquals(
            RefreshLogSeverity.Info,
            diagnosticSeverity("warning", "Retailer console page:1: [Aurum AJIO] BRIDGE_POST_ATTEMPT records=8 session=test-ses"),
        )
        assertEquals(
            RefreshLogSeverity.Error,
            diagnosticSeverity("error", "Retailer console page:1: [Aurum AJIO] BRIDGE_POST_FAILED network down"),
        )
        assertEquals(
            RefreshLogSeverity.Error,
            diagnosticSeverity("error", "Retailer console page:1: [Aurum AJIO Master] FETCH_TIMEOUT stage=search page=0"),
        )
    }

    @Test
    fun ajioHeartbeatErrorsRemainSeparated() {
        val heartbeat = """
            {"doneSettled":true,"doneRejected":null,"goldLength":8,"bridgeAttempted":true,"bridgeCompleted":false,"bridgeError":"network down"}
        """.trimIndent()
        assertEquals("network down", extractAjioBridgeError(heartbeat))
        assertEquals(null, extractAjioMasterError(heartbeat))

        val masterHeartbeat = """
            {"doneSettled":false,"doneRejected":"AJIO master failed at page0: 403","goldLength":-1,"bridgeAttempted":false,"bridgeCompleted":false,"bridgeError":null}
        """.trimIndent()
        assertEquals(null, extractAjioBridgeError(masterHeartbeat))
        assertEquals("AJIO master failed at page0: 403", extractAjioMasterError(masterHeartbeat))
    }
}