package com.docwallet.domain

import android.content.Context
import android.net.Uri
import android.util.Log
import com.docwallet.data.db.DocWalletDatabase
import com.docwallet.data.encryption.EncryptionManager
import com.docwallet.vault.backup.VaultExporter
import com.docwallet.vault.database.SqlCipherOpener
import com.docwallet.vault.database.SqlHandle
import com.docwallet.vault.database.SqlHandleSupportAndroid
import com.docwallet.vault.database.VaultDatabaseMerger
import com.docwallet.vault.backup.VaultImporter
import com.docwallet.vault.crypto.Argon2Hasher
import com.docwallet.vault.crypto.Argon2HasherImpl
import com.docwallet.vault.crypto.FileEncryptor
import com.docwallet.vault.crypto.KdfParams
import com.docwallet.vault.crypto.KeyDerivation
import com.docwallet.vault.crypto.KeyWrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

data class BackupProgress(
    val phase: String,
    val fraction: Float,
    val detail: String = "",
)

class BackupManager(
    private val context: Context,
    private val encryptionManager: EncryptionManager,
    private val getDatabase: () -> DocWalletDatabase? = { null },
    private val hasher: Argon2Hasher = Argon2HasherImpl(),
) {
    private val keyDerivation = KeyDerivation(hasher)
    private val kdfParams = KdfParams()
    private val vaultExporter = VaultExporter(keyDerivation, kdfParams, FileEncryptor())
    private val vaultImporter = VaultImporter(keyDerivation, kdfParams, FileEncryptor())

    suspend fun exportBackup(
        destination: File,
        vaultPassword: String,
        onProgress: (BackupProgress) -> Unit = {},
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val encryptionDir = File(context.filesDir, "encryption")
                val fileEntries = mutableMapOf<String, ByteArray>()

                onProgress(BackupProgress("Reading key files", 0.0f))
                val wrappedKey = File(encryptionDir, "wrapped_master_key")
                if (wrappedKey.exists()) {
                    fileEntries["wrapped_master_key"] = wrappedKey.readBytes()
                }
                onProgress(BackupProgress("Reading key files", 0.05f))

                val saltFile = File(encryptionDir, "salt")
                if (saltFile.exists()) {
                    fileEntries["salt"] = saltFile.readBytes()
                }
                onProgress(BackupProgress("Reading key files", 0.1f))

                val dbFile = context.getDatabasePath("docwallet.db")
                val dbData = if (dbFile.exists()) {
                    getDatabase()?.openHelper?.writableDatabase?.let { writableDb ->
                        writableDb.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                            cursor.moveToFirst()
                        }
                    }
                    dbFile.readBytes()
                } else null
                onProgress(BackupProgress("Reading database", 0.15f))

                val filesDir = File(context.filesDir, "files")
                val allFiles = if (filesDir.exists()) {
                    filesDir.walkTopDown().filter { it.isFile }.toList()
                } else emptyList()
                val total = allFiles.size.coerceAtLeast(1)
                val files = mutableMapOf<String, ByteArray>()
                allFiles.forEachIndexed { i, file ->
                    val name = file.relativeTo(filesDir).path
                    files[name] = file.readBytes()
                    onProgress(
                        BackupProgress(
                            "Reading files",
                            0.15f + 0.45f * ((i + 1).toFloat() / total),
                            detail = "$i of $total",
                        )
                    )
                }

                onProgress(BackupProgress("Encrypting backup", 0.6f))
                val vaultBytes = vaultExporter.export(files, dbData, vaultPassword, fileEntries)
                onProgress(BackupProgress("Encrypting backup", 0.8f))

                destination.writeBytes(vaultBytes)
                onProgress(BackupProgress("Writing output", 1.0f))

                Log.d(TAG, "Export complete: ${vaultBytes.size} bytes, ${files.size} documents")
                true
            } catch (e: Exception) {
                Log.e(TAG, "exportBackup failed", e)
                false
            }
        }
    }

    suspend fun importBackup(
        source: File,
        vaultPassword: String,
        onProgress: (BackupProgress) -> Unit = {},
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                onProgress(BackupProgress("Decrypting backup", 0.10f))
                val vaultBytes = source.readBytes()
                val contents = vaultImporter.`import`(vaultBytes, vaultPassword)
                    ?: return@withContext false
                onProgress(BackupProgress("Decrypting backup", 0.30f))

                restoreContents(contents, vaultPassword, onProgress)

                onProgress(BackupProgress("Restore complete", 1.0f))
                Log.d(TAG, "Import complete from vault format")
                true
            } catch (e: Exception) {
                Log.e(TAG, "importBackup failed", e)
                false
            }
        }
    }

    private suspend fun restoreContents(
        contents: com.docwallet.vault.backup.BackupContents,
        vaultPassword: String,
        onProgress: (BackupProgress) -> Unit = {},
    ) {
        val encryptionDir = File(context.filesDir, "encryption").also { it.mkdirs() }
        val currentDb = getDatabase()

        val backupMasterKey: ByteArray? = deriveBackupMasterKey(contents, vaultPassword)

        if (backupMasterKey == null && currentDb == null) {
            Log.e(TAG, "No valid master key for backup restoration")
            return
        }

        onProgress(BackupProgress("Restoring keys", 0.30f))
        contents.dbFile?.let { dbData ->
            val tempDb = File(context.cacheDir, "restore_db_${System.currentTimeMillis()}.db")
            try {
                tempDb.writeBytes(dbData)

                onProgress(BackupProgress("Merging database", 0.35f))
                if (currentDb != null && backupMasterKey != null) {
                    val backupHandle = SqlCipherOpener(context, backupMasterKey).open(tempDb.absolutePath)
                    val currentSqlHandle = getDatabase()?.openHelper?.writableDatabase
                        ?.let { SqlHandleSupportAndroid(it) } ?: return
                    val localKey = encryptionManager.getMasterKeyForSession()
                    val filesDir = File(context.filesDir, "files")
                    try {
                        val merger = VaultDatabaseMerger()
                        if (localKey != null) {
                            merger.mergeWithFileReencryption(
                                backupDb = backupHandle,
                                currentDb = currentSqlHandle,
                                files = contents.files,
                                backupKey = backupMasterKey,
                                localKey = localKey,
                                filesDirPath = filesDir.absolutePath,
                            )
                        } else {
                            merger.merge(backupHandle, currentSqlHandle)
                        }
                    } finally {
                        backupHandle.close()
                    }
                } else if (currentDb == null && backupMasterKey != null) {
                    val origWrappedKey = File(encryptionDir, "wrapped_master_key").let { f ->
                        if (f.exists()) f.readBytes() else null
                    }
                    val origSalt = File(encryptionDir, "salt").let { f ->
                        if (f.exists()) f.readBytes() else null
                    }

                    contents.keys["wrapped_master_key"]?.let {
                        File(encryptionDir, "wrapped_master_key").writeBytes(it)
                    }
                    contents.keys["salt"]?.let {
                        File(encryptionDir, "salt").writeBytes(it)
                    }

                    val passwordVerified = encryptionManager.verifyPassword(vaultPassword)
                    if (!passwordVerified) {
                        Log.e(TAG, "Password verification failed — restoring original key material")
                        if (origWrappedKey != null) {
                            File(encryptionDir, "wrapped_master_key").writeBytes(origWrappedKey)
                        }
                        if (origSalt != null) {
                            File(encryptionDir, "salt").writeBytes(origSalt)
                        }
                        return
                    }

                    val deviceKeySetup = encryptionManager.setupDeviceKeyForDailyUnlock()
                    if (!deviceKeySetup) {
                        Log.e(TAG, "Device key setup failed — restoring original key material and aborting")
                        if (origWrappedKey != null) {
                            File(encryptionDir, "wrapped_master_key").writeBytes(origWrappedKey)
                        } else {
                            File(encryptionDir, "wrapped_master_key").delete()
                        }
                        if (origSalt != null) {
                            File(encryptionDir, "salt").writeBytes(origSalt)
                        } else {
                            File(encryptionDir, "salt").delete()
                        }
                        return
                    }

                    val dbFile = context.getDatabasePath("docwallet.db")
                    dbFile.parentFile?.mkdirs()
                    tempDb.copyTo(dbFile, overwrite = true)
                } else if (currentDb != null && backupMasterKey == null) {
                    Log.w(TAG, "Legacy backup without key material — merging with current key")
                    val mk = encryptionManager.getMasterKeyForSession()
                    if (mk != null) {
                        val legacyOpener: (String) -> SqlHandle = { path ->
                            SqlCipherOpener(context, mk).open(path)
                        }
                        val currentHandle: () -> SqlHandle? = {
                            getDatabase()?.openHelper?.writableDatabase?.let { SqlHandleSupportAndroid(it) }
                        }
                        DatabaseMerger(legacyOpener, currentHandle).merge(tempDb.absolutePath)
                    } else {
                        Log.w(TAG, "No master key available for legacy merge")
                    }
                } else {
                    Log.w(TAG, "Unhandled restore branch — skipping DB restore")
                }
            } finally {
                tempDb.delete()
            }
        }

        val filesDir = File(context.filesDir, "files").also { it.mkdirs() }
        val fileList = contents.files.entries.toList()
        val fileTotal = fileList.size.coerceAtLeast(1)
        fileList.forEachIndexed { i, (entryName, data) ->
            val targetFile = File(filesDir, entryName)
            targetFile.parentFile?.mkdirs()
            if (!targetFile.exists()) {
                targetFile.writeBytes(data)
            }
            onProgress(
                BackupProgress(
                    "Restoring files",
                    0.70f + 0.30f * ((i + 1).toFloat() / fileTotal),
                    detail = "$i of $fileTotal",
                )
            )
        }
    }

    private fun deriveBackupMasterKey(
        contents: com.docwallet.vault.backup.BackupContents,
        vaultPassword: String,
    ): ByteArray? {
        if (!contents.keys.containsKey("wrapped_master_key") ||
            !contents.keys.containsKey("salt")) return null

        return try {
            val salt = contents.keys["salt"]!!
            val wrappedKey = contents.keys["wrapped_master_key"]!!
            val userKey = keyDerivation.deriveAndZero(vaultPassword, salt, kdfParams)
            try {
                KeyWrap.unwrap(wrappedKey, userKey).copyOf()
            } finally {
                userKey.fill(0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to derive backup master key", e)
            null
        }
    }

    suspend fun exportBackupToUri(
        uri: Uri,
        vaultPassword: String,
        onProgress: (BackupProgress) -> Unit = {},
    ): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        try {
            val tempFile = File(context.cacheDir, "backup_export_${System.currentTimeMillis()}.vault")
            val success = exportBackup(tempFile, vaultPassword, onProgress)
            if (!success) return@withContext false

            onProgress(BackupProgress("Writing to file", 0.80f))
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                val totalBytes = tempFile.length()
                val buffer = ByteArray(8192)
                var bytesWritten = 0L
                tempFile.inputStream().use { input ->
                    var read = input.read(buffer)
                    while (read >= 0) {
                        outputStream.write(buffer, 0, read)
                        bytesWritten += read
                        if (totalBytes > 0) {
                            onProgress(
                                BackupProgress(
                                    "Writing to file",
                                    0.80f + 0.20f * (bytesWritten.toFloat() / totalBytes),
                                )
                            )
                        }
                        read = input.read(buffer)
                    }
                }
            } ?: return@withContext false
            tempFile.delete()
            onProgress(BackupProgress("Export complete", 1.0f))
            true
        } catch (e: Exception) {
            Log.e(TAG, "exportBackupToUri failed", e)
            false
        }
    }

    suspend fun importBackupFromUri(
        uri: Uri,
        vaultPassword: String,
        onProgress: (BackupProgress) -> Unit = {},
    ): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        try {
            val tempFile = File(context.cacheDir, "backup_import_${System.currentTimeMillis()}.vault")
            onProgress(BackupProgress("Reading backup file", 0.0f))
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                tempFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return@withContext false
            onProgress(BackupProgress("Reading backup file", 0.10f))
            val success = importBackup(tempFile, vaultPassword, onProgress)
            tempFile.delete()
            success
        } catch (e: Exception) {
            Log.e(TAG, "importBackupFromUri failed", e)
            false
        }
    }

    companion object {
        private const val TAG = "BackupManager"
    }
}
