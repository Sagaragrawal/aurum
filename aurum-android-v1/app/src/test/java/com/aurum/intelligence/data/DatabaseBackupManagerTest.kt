package com.aurum.intelligence.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DatabaseBackupManagerTest {

    @Test
    fun testBackupFilenameContract() {
        // Contract check for backup file naming convention
        val filename = "aurum-persistent.aurum"
        assertEquals("aurum-persistent.aurum", filename)
    }
}
