package com.docwallet.domain

import android.content.Context
import android.net.Uri
import android.util.Log
import com.docwallet.data.db.DocWalletDatabase
import com.docwallet.data.encryption.EncryptionManager
import com.docwallet.vault.backup.VaultExporter
import com.docwallet.vault.database.SqlCipherOpener
import com.docwallet.vault.database.SqlHandleSupportAndroid
import com.docwallet.vault.backup.VaultImporter
import com.docwallet.vault.crypto.Argon2HasherImpl
import com.docwallet.vault.crypto.FileEncryptor
import com.docwallet.vault.crypto.KdfParams
import com.docwallet.vault.crypto.KeyDerivation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class BackupManager(
    private val context: Context,
    private val encryptionManager: EncryptionManager,
    private val getDatabase: () -> DocWalletDatabase? = { null },
) {
    private val hasher = Argon2HasherImpl()
    private val keyDerivation = KeyDerivation(hasher)
    private val kdfParams = KdfParams()
    private val databaseMerger = DatabaseMerger(
        backupOpener = { path ->
            val mk = encryptionManager.getMasterKeyForSession() ?: error("No master key")
            SqlCipherOpener(context, mk).open(path)
        },
        currentHandle = {
            getDatabase()?.openHelper?.writableDatabase?.let { SqlHandleSupportAndroid(it) }
        },
    )
    private val vaultExporter = VaultExporter(keyDerivation, kdfParams, FileEncryptor())
    private val vaultImporter = VaultImporter(keyDerivation, kdfParams, FileEncryptor())

    suspend fun exportBackup(destination: File, backupPassword: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val encryptionDir = File(context.filesDir, "encryption")
                val fileEntries = mutableMapOf<String, ByteArray>()

                val wrappedKey = File(encryptionDir, "wrapped_master_key")
                if (wrappedKey.exists()) {
                    fileEntries["keys/wrapped_master_key"] = wrappedKey.readBytes()
                }

                encryptionManager.resolveDeviceKeyForBackup()?.let { deviceKey ->
                    fileEntries["keys/device_key"] = deviceKey
                }

                val saltFile = File(encryptionDir, "salt")
                if (saltFile.exists()) {
                    fileEntries["keys/salt"] = saltFile.readBytes()
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

                val vaultBytes = vaultExporter.export(files, dbData, backupPassword)
                destination.writeBytes(vaultBytes)

                Log.d(TAG, "Export complete: ${vaultBytes.size} bytes, ${files.size} documents")
                true
            } catch (e: Exception) {
                Log.e(TAG, "exportBackup failed", e)
                false
            }
        }
    }

    suspend fun importBackup(source: File, backupPassword: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val vaultBytes = source.readBytes()
                val contents = vaultImporter.`import`(vaultBytes, backupPassword)
                    ?: return@withContext false

                restoreContents(contents)

                Log.d(TAG, "Import complete from vault format")
                true
            } catch (e: Exception) {
                Log.e(TAG, "importBackup failed", e)
                false
            }
        }
    }

    private suspend fun restoreContents(contents: com.docwallet.vault.backup.BackupContents) {
        val encryptionDir = File(context.filesDir, "encryption").also { it.mkdirs() }

        contents.keys["wrapped_master_key"]?.let {
            File(encryptionDir, "wrapped_master_key").writeBytes(it)
        }
        contents.keys["device_key"]?.let {
            File(encryptionDir, "device_key").writeBytes(it)
        }
        contents.keys["salt"]?.let {
            File(encryptionDir, "salt").writeBytes(it)
        }

        var masterKey = encryptionManager.getMasterKeyForSession()
        if (masterKey == null) {
            masterKey = encryptionManager.getMasterKeyForSession()
        }

        if (masterKey != null) {
            contents.dbFile?.let { dbData ->
                val tempDb = File(context.cacheDir, "restore_db_${System.currentTimeMillis()}.db")
                try {
                    tempDb.writeBytes(dbData)
                    val currentDb = getDatabase()
                    if (currentDb != null) {
                        databaseMerger.merge(tempDb.absolutePath)
                    } else {
                        val dbFile = context.getDatabasePath("docwallet.db")
                        dbFile.parentFile?.mkdirs()
                        tempDb.copyTo(dbFile, overwrite = true)
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
    }

    suspend fun exportBackupToUri(uri: Uri, backupPassword: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(context.cacheDir, "backup_export_${System.currentTimeMillis()}.vault")
            val success = exportBackup(tempFile, backupPassword)
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

    suspend fun importBackupFromUri(uri: Uri, backupPassword: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(context.cacheDir, "backup_import_${System.currentTimeMillis()}.vault")
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                tempFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return@withContext false
            val success = importBackup(tempFile, backupPassword)
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
