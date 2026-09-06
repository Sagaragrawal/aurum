package com.aurum.intelligence.data

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DatabaseBackupManager {
    private const val BACKUP_FILENAME = "aurum.db"

    fun getBackupFile(context: Context): File {
        val rootSdcard = Environment.getExternalStorageDirectory()
        val aurumDbDir = File(rootSdcard, "Aurum/database").apply { if (!exists()) mkdirs() }
        val targetFile = File(aurumDbDir, BACKUP_FILENAME)

        // Migrate from lowercase aurum if present
        runCatching {
            val legacyFile = File(rootSdcard, "aurum/aurum.db")
            if (legacyFile.exists() && (!targetFile.exists() || targetFile.length() == 0L)) {
                legacyFile.copyTo(targetFile, overwrite = true)
            }
            val dcimAurum = File(File(rootSdcard, "DCIM"), "Aurum")
            if (dcimAurum.exists() && dcimAurum.isDirectory) {
                dcimAurum.deleteRecursively()
            }
        }
        return targetFile
    }

    suspend fun createBackup(repository: BridgeRepository, context: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val dbFile = context.getDatabasePath("aurum.db")
            val backupFile = getBackupFile(context)
            if (dbFile.exists() && dbFile.length() > 0) {
                dbFile.copyTo(backupFile, overwrite = true)
                true
            } else {
                val tempFile = File(backupFile.parentFile, "$BACKUP_FILENAME.tmp")
                FileOutputStream(tempFile).use { output ->
                    repository.exportArchive(output)
                }
                if (tempFile.exists() && tempFile.length() > 0) {
                    if (backupFile.exists()) backupFile.delete()
                    tempFile.renameTo(backupFile)
                    true
                } else false
            }
        }.getOrDefault(false)
    }

    suspend fun checkAndRestoreIfNeeded(database: AurumDatabase, repository: BridgeRepository, context: Context): ArchiveImportResult? = withContext(Dispatchers.IO) {
        runCatching {
            val productCount = database.dao().productCount()
            if (productCount > 0) return@runCatching null // Database has existing data; no restore needed

            val backupFile = getBackupFile(context)
            if (!backupFile.exists() || backupFile.length() == 0L) return@runCatching null

            FileInputStream(backupFile).use { input ->
                repository.importArchive(input)
            }
        }.getOrNull()
    }
}
