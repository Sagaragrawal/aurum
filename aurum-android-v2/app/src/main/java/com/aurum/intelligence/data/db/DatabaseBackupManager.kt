package com.aurum.intelligence.data.db
import com.aurum.intelligence.data.db.*
import com.aurum.intelligence.data.engine.*
import com.aurum.intelligence.data.model.*
import com.aurum.intelligence.data.repository.*
import com.aurum.intelligence.data.validation.*

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DatabaseBackupManager {
    private val backupFileName: String get() = ScraperConfigProvider.get().storage.backupDbFileName
    private val internalDbFileName: String get() = ScraperConfigProvider.get().storage.internalDbFileName
    private val rawPagesDirName: String get() = ScraperConfigProvider.get().storage.rawPagesDirName

    fun getAurumDir(): File {
        val root = File(ScraperConfigProvider.get().storage.externalDirPath)
        if (!root.exists()) {
            root.mkdirs()
        }
        return root
    }

    fun getBackupFile(context: Context): File {
        val aurumDir = getAurumDir()
        return File(aurumDir, backupFileName)
    }

    fun shouldSaveRawPage(store: String): Boolean {
        val config = ScraperConfigProvider.get()
        return config.debug.saveRawPages[store] != false
    }

    fun saveRawPage(store: String, pageName: String, content: String, extension: String = "txt") {
        if (!shouldSaveRawPage(store)) return
        try {
            val rawDir = File(getAurumDir(), rawPagesDirName).apply { if (!exists()) mkdirs() }
            val sanitizedStore = store.replace(Regex("[^a-zA-Z0-9.-]"), "_")
            val sanitizedPage = pageName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val file = File(rawDir, "${sanitizedStore}_${sanitizedPage}.${extension}")
            file.writeText(content, Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.w("DatabaseBackupManager", "Failed to save raw page: ${e.message}")
        }
    }

    fun clearAllRawPages() {
        runCatching {
            val rawDir = File(getAurumDir(), rawPagesDirName)
            if (rawDir.exists()) {
                rawDir.listFiles()?.forEach { it.delete() }
            }
        }
    }

    fun clearStoreRawPages(store: String) {
        runCatching {
            val rawDir = File(getAurumDir(), rawPagesDirName)
            if (rawDir.exists()) {
                val sanitizedStore = store.replace(Regex("[^a-zA-Z0-9.-]"), "_")
                rawDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith(sanitizedStore)) {
                        file.delete()
                    }
                }
            }
        }
    }

    suspend fun createBackup(repository: BridgeRepository, context: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val aurumDir = getAurumDir()
            val dbFile = context.getDatabasePath(backupFileName)
            val backupFile = File(aurumDir, backupFileName)
            if (dbFile.exists() && dbFile.length() > 0) {
                dbFile.copyTo(backupFile, overwrite = true)
                // Also copy WAL and SHM if they exist
                val wal = File(dbFile.parentFile, "$backupFileName-wal")
                if (wal.exists()) wal.copyTo(File(aurumDir, "$backupFileName-wal"), overwrite = true)
                val shm = File(dbFile.parentFile, "$backupFileName-shm")
                if (shm.exists()) shm.copyTo(File(aurumDir, "$backupFileName-shm"), overwrite = true)
            } else {
                val tempFile = File(aurumDir, "$backupFileName.tmp")
                FileOutputStream(tempFile).use { output ->
                    repository.exportArchive(output)
                }
                if (tempFile.exists() && tempFile.length() > 0) {
                    if (backupFile.exists()) backupFile.delete()
                    tempFile.renameTo(backupFile)
                }
            }

            val internalDbFile = context.getDatabasePath(internalDbFileName)
            if (internalDbFile.exists() && internalDbFile.length() > 0) {
                val internalBackupFile = File(aurumDir, internalDbFileName)
                internalDbFile.copyTo(internalBackupFile, overwrite = true)
                val intWal = File(internalDbFile.parentFile, "$internalDbFileName-wal")
                if (intWal.exists()) intWal.copyTo(File(aurumDir, "$internalDbFileName-wal"), overwrite = true)
                val intShm = File(internalDbFile.parentFile, "$internalDbFileName-shm")
                if (intShm.exists()) intShm.copyTo(File(aurumDir, "$internalDbFileName-shm"), overwrite = true)
            }
            true
        }.getOrDefault(false)
    }

    fun syncDatabasesToExternal(context: Context, database: AurumDatabase? = null, internalDatabase: AurumInternalDatabase? = null) {
        runCatching {
            val aurumDir = getAurumDir()
            database?.runCatching {
                openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
            }
            internalDatabase?.runCatching {
                openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
            }

            val dbFile = context.getDatabasePath(backupFileName)
            if (dbFile.exists() && dbFile.length() > 0) {
                dbFile.copyTo(File(aurumDir, backupFileName), overwrite = true)
                val wal = File(dbFile.parentFile, "$backupFileName-wal")
                if (wal.exists()) wal.copyTo(File(aurumDir, "$backupFileName-wal"), overwrite = true)
                val shm = File(dbFile.parentFile, "$backupFileName-shm")
                if (shm.exists()) shm.copyTo(File(aurumDir, "$backupFileName-shm"), overwrite = true)
            }

            val internalFile = context.getDatabasePath(internalDbFileName)
            if (internalFile.exists() && internalFile.length() > 0) {
                internalFile.copyTo(File(aurumDir, internalDbFileName), overwrite = true)
                val intWal = File(internalFile.parentFile, "$internalDbFileName-wal")
                if (intWal.exists()) intWal.copyTo(File(aurumDir, "$internalDbFileName-wal"), overwrite = true)
                val intShm = File(internalFile.parentFile, "$internalDbFileName-shm")
                if (intShm.exists()) intShm.copyTo(File(aurumDir, "$internalDbFileName-shm"), overwrite = true)
            }
            android.util.Log.i("DatabaseBackupManager", "Successfully synced databases to ${aurumDir.absolutePath}")
        }.onFailure { e ->
            android.util.Log.e("DatabaseBackupManager", "Database sync to external failed: ${e.message}", e)
        }
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
