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

    suspend fun exportBackup(destination: File, vaultPassword: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val encryptionDir = File(context.filesDir, "encryption")
                val fileEntries = mutableMapOf<String, ByteArray>()

                val wrappedKey = File(encryptionDir, "wrapped_master_key")
                if (wrappedKey.exists()) {
                    fileEntries["wrapped_master_key"] = wrappedKey.readBytes()
                }

                val saltFile = File(encryptionDir, "salt")
                if (saltFile.exists()) {
                    fileEntries["salt"] = saltFile.readBytes()
                }

                val dbFile = context.getDatabasePath("docwallet.db")
                val dbData = if (dbFile.exists()) {
                    getDatabase()?.openHelper?.writableDatabase?.let { writableDb ->
                        writableDb.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                            cursor.moveToFirst()
                        }
                    }
                    dbFile.readBytes()
                } else null

                val filesDir = File(context.filesDir, "files")
                val files = mutableMapOf<String, ByteArray>()
                if (filesDir.exists()) {
                    filesDir.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            val name = file.relativeTo(filesDir).path
                            files[name] = file.readBytes()
                        }
                    }
                }

                val vaultBytes = vaultExporter.export(files, dbData, vaultPassword, fileEntries)
                destination.writeBytes(vaultBytes)

                Log.d(TAG, "Export complete: ${vaultBytes.size} bytes, ${files.size} documents")
                true
            } catch (e: Exception) {
                Log.e(TAG, "exportBackup failed", e)
                false
            }
        }
    }

    suspend fun importBackup(source: File, vaultPassword: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val vaultBytes = source.readBytes()
                val contents = vaultImporter.`import`(vaultBytes, vaultPassword)
                    ?: return@withContext false

                restoreContents(contents, vaultPassword)

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
    ) {
        val encryptionDir = File(context.filesDir, "encryption").also { it.mkdirs() }
        val currentDb = getDatabase()

        val backupMasterKey: ByteArray? = deriveBackupMasterKey(contents, vaultPassword)

        if (backupMasterKey == null && currentDb == null) {
            Log.e(TAG, "No valid master key for backup restoration")
            return
        }

        contents.dbFile?.let { dbData ->
            val tempDb = File(context.cacheDir, "restore_db_${System.currentTimeMillis()}.db")
            try {
                tempDb.writeBytes(dbData)

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
                    contents.keys["wrapped_master_key"]?.let {
                        File(encryptionDir, "wrapped_master_key").writeBytes(it)
                    }
                    contents.keys["salt"]?.let {
                        File(encryptionDir, "salt").writeBytes(it)
                    }

                    encryptionManager.verifyPassword(vaultPassword)
                    encryptionManager.setupDeviceKeyForDailyUnlock()

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
        for ((entryName, data) in contents.files) {
            val targetFile = File(filesDir, entryName)
            targetFile.parentFile?.mkdirs()
            if (!targetFile.exists()) {
                targetFile.writeBytes(data)
            }
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

    suspend fun exportBackupToUri(uri: Uri, vaultPassword: String): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        try {
            val tempFile = File(context.cacheDir, "backup_export_${System.currentTimeMillis()}.vault")
            val success = exportBackup(tempFile, vaultPassword)
            if (!success) return@withContext false
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                tempFile.inputStream().use { it.copyTo(outputStream) }
            } ?: return@withContext false
            tempFile.delete()
            true
        } catch (e: Exception) {
            Log.e(TAG, "exportBackupToUri failed", e)
            false
        }
    }

    suspend fun importBackupFromUri(uri: Uri, vaultPassword: String): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        try {
            val tempFile = File(context.cacheDir, "backup_import_${System.currentTimeMillis()}.vault")
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                tempFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return@withContext false
            val success = importBackup(tempFile, vaultPassword)
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
