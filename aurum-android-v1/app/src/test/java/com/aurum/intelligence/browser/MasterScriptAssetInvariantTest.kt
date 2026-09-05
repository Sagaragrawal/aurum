package com.aurum.intelligence.browser

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every MasterScript.assetName must resolve to a non-empty packaged source file. A missing/empty
 * asset should be caught here, not discovered halfway through a multi-minute device refresh.
 */
class MasterScriptAssetInvariantTest {
    private val manualJsDir = File("../../aurum-desktop/manual_js")

    @Test
    fun everyMasterScriptAssetExistsAndIsNonEmpty() {
        MasterScripts.all.forEach { master ->
            val fileName = master.assetName.substringAfterLast('/')
            val file = File(manualJsDir, fileName)
            assertTrue("Missing packaged master script for ${master.storeName()}: ${file.path}", file.exists())
            assertTrue("Master script is unreadable for ${master.storeName()}: ${file.path}", file.canRead())
            assertTrue("Master script is empty for ${master.storeName()}: ${file.path}", file.length() > 0)
        }
    }

    private fun MasterScript.storeName(): String = retailer.name
}
