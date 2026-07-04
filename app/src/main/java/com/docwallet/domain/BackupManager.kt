package com.docwallet.domain

import android.content.Context
import android.net.Uri
import android.util.Log
import com.docwallet.data.db.DocWalletDatabase
import com.docwallet.data.encryption.EncryptionManager
import com.docwallet.data.model.Collection
import com.docwallet.data.model.Document
import com.docwallet.data.model.DocumentTag
import com.docwallet.data.model.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.sqlcipher.database.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupManager(
    private val context: Context,
    private val encryptionManager: EncryptionManager,
    private val getDatabase: () -> DocWalletDatabase? = { null },
) {

    suspend fun exportBackup(destination: File): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val tempDir = File(context.cacheDir, "backup_${System.currentTimeMillis()}")
                tempDir.mkdirs()

                val encryptionDir = File(context.filesDir, "encryption")

                // wrapped_master_key — always copied as-is (AES-key-wrapped)
                copyToDir(File(encryptionDir, "wrapped_master_key"), tempDir)

                // device_key — may need to decrypt from KeyStore-wrapped form
                val deviceKeyFile = File(encryptionDir, "device_key")
                if (deviceKeyFile.exists()) {
                    deviceKeyFile.copyTo(File(tempDir, "device_key"), overwrite = true)
                } else {
                    val encryptedKeyFile = File(encryptionDir, "encrypted_device_key")
                    if (encryptedKeyFile.exists()) {
                        val data = encryptedKeyFile.readBytes()
                        val iv = data.copyOfRange(0, 12)
                        val ciphertext = data.copyOfRange(12, data.size)
                        val deviceKey = encryptionManager.keyStoreCryptographer.decrypt(iv, ciphertext)
                        File(tempDir, "device_key").writeBytes(deviceKey)
                    }
                }

                val saltFile = File(encryptionDir, "salt")
                if (saltFile.exists()) {
                    saltFile.copyTo(File(tempDir, "salt"), overwrite = true)
                }

                val dbFile = context.getDatabasePath("docwallet.db")
                if (dbFile.exists()) {
                    getDatabase()?.openHelper?.writableDatabase?.let { writableDb ->
                        writableDb.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                            cursor.moveToFirst()
                        }
                    }
                    dbFile.copyTo(File(tempDir, "docwallet.db"), overwrite = true)
                }

                copyDirectory(context.filesDir, File(tempDir, "files"))

                // Write plain ZIP directly to destination — no outer encryption.
                ZipOutputStream(FileOutputStream(destination)).use { zos ->
                    tempDir.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            val entryName = file.relativeTo(tempDir).path
                            zos.putNextEntry(ZipEntry(entryName))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }

                tempDir.deleteRecursively()
                true
            } catch (e: Exception) {
                Log.e("BackupManager", "exportBackup failed", e)
                false
            }
        }
    }

    suspend fun importBackup(source: File, currentPassword: String?): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (encryptionManager.isPasswordSet()) {
                    if (currentPassword == null || !encryptionManager.verifyPassword(currentPassword)) {
                        return@withContext false
                    }
                }

                val tempDir = File(context.cacheDir, "restore_${System.currentTimeMillis()}")
                tempDir.mkdirs()

                // Open the plain ZIP directly — no GCM decryption.
                ZipInputStream(FileInputStream(source)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outputFile = File(tempDir, entry.name)
                        if (entry.isDirectory) {
                            outputFile.mkdirs()
                        } else {
                            outputFile.parentFile?.mkdirs()
                            FileOutputStream(outputFile).use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                var masterKey = encryptionManager.getMasterKeyForSession()
                if (masterKey == null) {
                    // Fresh install — restore key files from the backup first.
                    restoreKeyFiles(tempDir)
                    masterKey = encryptionManager.getMasterKeyForSession()
                }

                masterKey ?: return@withContext false

                val tempDb = File(tempDir, "docwallet.db")
                if (tempDb.exists()) {
                    val currentDb = getDatabase()
                    if (currentDb != null) {
                        // Existing install — merge records into the current database.
                        mergeDatabase(tempDb, masterKey)
                    } else {
                        // Fresh install — no database exists yet. Copy the backup
                        // database directly; keys have been restored so it will be
                        // decryptable when the app opens it.
                        val dbFile = context.getDatabasePath("docwallet.db")
                        dbFile.parentFile?.mkdirs()
                        tempDb.copyTo(dbFile, overwrite = true)
                    }
                }

                val tempFiles = File(tempDir, "files")
                if (tempFiles.exists()) {
                    mergeFiles(tempFiles)
                }

                tempDir.deleteRecursively()
                true
            } catch (e: Exception) {
                Log.e("BackupManager", "importBackup failed", e)
                false
            }
        }
    }

    private fun restoreKeyFiles(tempDir: File) {
        val encryptionDir = File(context.filesDir, "encryption").also { it.mkdirs() }
        copyFromDir(tempDir, "wrapped_master_key", encryptionDir)
        // Only restore the plaintext device_key. On a cross-device restore the
        // old KeyStore-wrapped key is undecryptable — the legacy path will
        // succeed and trigger migrateDeviceKeyToKeyStore on first access.
        copyFromDir(tempDir, "device_key", encryptionDir)
        val tempSalt = File(tempDir, "salt")
        if (tempSalt.exists()) {
            tempSalt.copyTo(File(encryptionDir, "salt"), overwrite = true)
        }
    }

    private suspend fun mergeDatabase(backupDbFile: File, masterKey: ByteArray) {
        val currentDb = getDatabase() ?: return
        val backupDb = withContext(Dispatchers.IO) {
            SQLiteDatabase.openOrCreateDatabase(
                backupDbFile.absolutePath, masterKey, null
            )
        }

        try {
            val collections = mutableListOf<Collection>()
            backupDb.rawQuery("SELECT * FROM collections", null).use { cursor ->
                while (cursor.moveToNext()) {
                    collections.add(
                        Collection(
                            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                            name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                            icon = cursor.getString(cursor.getColumnIndexOrThrow("icon")),
                            sortOrder = cursor.getInt(cursor.getColumnIndexOrThrow("sort_order")),
                            parentId = getStringOrNull(cursor, "parent_id"),
                        )
                    )
                }
            }
            for (item in collections) {
                runCatching { currentDb.collectionDao().insertOrIgnore(item) }
            }

            val tags = mutableListOf<Tag>()
            backupDb.rawQuery("SELECT * FROM tags", null).use { cursor ->
                while (cursor.moveToNext()) {
                    tags.add(
                        Tag(
                            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                            name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                            color = cursor.getLong(cursor.getColumnIndexOrThrow("color")),
                        )
                    )
                }
            }
            for (item in tags) {
                runCatching { currentDb.tagDao().insertOrIgnore(item) }
            }

            val documents = mutableListOf<Document>()
            backupDb.rawQuery("SELECT * FROM documents", null).use { cursor ->
                while (cursor.moveToNext()) {
                    documents.add(
                        Document(
                            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                            fileName = cursor.getString(cursor.getColumnIndexOrThrow("file_name")),
                            mimeType = cursor.getString(cursor.getColumnIndexOrThrow("mime_type")),
                            filePath = cursor.getString(cursor.getColumnIndexOrThrow("file_path")),
                            fileSize = cursor.getLong(cursor.getColumnIndexOrThrow("file_size")),
                            pageCount = cursor.getInt(cursor.getColumnIndexOrThrow("page_count")),
                            author = getStringOrNull(cursor, "author") ?: "",
                            description = getStringOrNull(cursor, "description") ?: "",
                            thumbnailPath = getStringOrNull(cursor, "thumbnail_path"),
                            importedAt = cursor.getLong(cursor.getColumnIndexOrThrow("imported_at")),
                            lastOpenedAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_opened_at")),
                            isFavorite = cursor.getInt(cursor.getColumnIndexOrThrow("is_favorite")) != 0,
                            collectionId = getStringOrNull(cursor, "collection_id"),
                            encryptionIv = cursor.getBlob(cursor.getColumnIndexOrThrow("encryption_iv")),
                            textContent = getStringOrNull(cursor, "text_content"),
                            barcodeFormat = getStringOrNull(cursor, "barcode_format"),
                            barcodeValue = getStringOrNull(cursor, "barcode_value"),
                            currentPage = cursor.getInt(cursor.getColumnIndexOrThrow("current_page")),
                            readingPosition = getStringOrNull(cursor, "reading_position"),
                        )
                    )
                }
            }
            for (item in documents) {
                runCatching { currentDb.documentDao().insertOrIgnore(item) }
            }

            backupDb.rawQuery("SELECT * FROM document_tags", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val dt = DocumentTag(
                        documentId = cursor.getString(cursor.getColumnIndexOrThrow("document_id")),
                        tagId = cursor.getString(cursor.getColumnIndexOrThrow("tag_id")),
                    )
                    runCatching { currentDb.documentTagDao().insertOrIgnore(dt) }
                }
            }
        } finally {
            backupDb.close()
        }
    }

    private fun mergeFiles(sourceDir: File) {
        val filesDir = File(context.filesDir, "files")
        filesDir.mkdirs()
        sourceDir.listFiles()?.forEach { file ->
            val dest = File(filesDir, file.name)
            if (file.isDirectory) {
                mergeFiles(file)
            } else if (!dest.exists()) {
                file.copyTo(dest)
            }
        }
    }

    private fun getStringOrNull(cursor: net.sqlcipher.Cursor, columnName: String): String? {
        val index = cursor.getColumnIndexOrThrow(columnName)
        return if (cursor.isNull(index)) null else cursor.getString(index)
    }

    private fun copyToDir(source: File, targetDir: File) {
        if (source.exists()) {
            source.copyTo(File(targetDir, source.name), overwrite = true)
        }
    }

    private fun copyFromDir(sourceDir: File, fileName: String, targetDir: File) {
        val source = File(sourceDir, fileName)
        if (source.exists()) {
            source.copyTo(File(targetDir, fileName), overwrite = true)
        }
    }

    private fun copyDirectory(source: File, target: File) {
        target.mkdirs()
        source.listFiles()?.forEach { file ->
            val dest = File(target, file.name)
            if (file.isDirectory) {
                copyDirectory(file, dest)
            } else {
                file.copyTo(dest, overwrite = true)
            }
        }
    }

    suspend fun exportBackupToUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(context.cacheDir, "backup_export_${System.currentTimeMillis()}.backup")
            val success = exportBackup(tempFile)
            if (!success) return@withContext false
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                tempFile.inputStream().use { it.copyTo(outputStream) }
            } ?: return@withContext false
            tempFile.delete()
            true
        } catch (e: Exception) {
            Log.e("BackupManager", "exportBackupToUri failed", e)
            false
        }
    }

    suspend fun importBackupFromUri(uri: Uri, currentPassword: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(context.cacheDir, "backup_import_${System.currentTimeMillis()}.backup")
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                tempFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return@withContext false
            val success = importBackup(tempFile, currentPassword)
            tempFile.delete()
            success
        } catch (e: Exception) {
            Log.e("BackupManager", "importBackupFromUri failed", e)
            false
        }
    }
}
