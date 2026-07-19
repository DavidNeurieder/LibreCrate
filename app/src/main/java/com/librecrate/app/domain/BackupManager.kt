package com.librecrate.app.domain

import android.content.Context
import android.net.Uri
import android.util.Log
import com.librecrate.app.data.encryption.EncryptionManager
import com.librecrate.app.data.vault.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import uniffi.vault_native.*
import java.io.File

data class BackupProgress(
    val phase: String,
    val fraction: Float,
    val detail: String = "",
)

class BackupManager(
    private val context: Context,
    private val encryptionManager: EncryptionManager,
    private val vaultRepository: VaultRepository,
) {
    suspend fun exportBackup(
        destination: File,
        vaultPassword: String,
        onProgress: (BackupProgress) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val filesDir = vaultRepository.filesDir
            val encryptionDir = vaultRepository.encryptionDir

            onProgress(BackupProgress("Reading key files", 0.0f))
            val keyEntries = mutableListOf<KeyValueFfi>()
            val wrappedKeyFile = File(encryptionDir, "wrapped_master_key")
            if (wrappedKeyFile.exists()) keyEntries.add(KeyValueFfi("wrapped_master_key", wrappedKeyFile.readBytes()))
            onProgress(BackupProgress("Reading key files", 0.05f))
            val saltFile = File(encryptionDir, "salt")
            if (saltFile.exists()) keyEntries.add(KeyValueFfi("salt", saltFile.readBytes()))
            onProgress(BackupProgress("Reading key files", 0.1f))

            val dbFile = context.getDatabasePath("librecrate.db")
            val dbData = if (dbFile.exists()) dbFile.readBytes() else null
            onProgress(BackupProgress("Reading database", 0.15f))

            val allFiles = if (filesDir.exists()) filesDir.walkTopDown().filter { it.isFile }.toList() else emptyList()
            val total = allFiles.size.coerceAtLeast(1)
            val files = mutableListOf<KeyValueFfi>()
            allFiles.forEachIndexed { i, file ->
                files.add(KeyValueFfi(file.relativeTo(filesDir).path, file.readBytes()))
                onProgress(BackupProgress("Reading files", 0.15f + 0.45f * ((i + 1).toFloat() / total), detail = "$i of $total"))
            }

            onProgress(BackupProgress("Encrypting backup", 0.6f))
            val vaultBytes = uniffi.vault_native.exportVault(files, dbData, vaultPassword, keyEntries, Argon2ParamsFfi())
            onProgress(BackupProgress("Encrypting backup", 0.8f))

            destination.writeBytes(vaultBytes)
            onProgress(BackupProgress("Writing output", 1.0f))
            Log.d(TAG, "Export complete: ${vaultBytes.size} bytes, ${files.size} documents")
            true
        } catch (e: Exception) {
            Log.e(TAG, "exportBackup failed", e); false
        }
    }

    suspend fun importBackup(
        source: File,
        vaultPassword: String,
        onProgress: (BackupProgress) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            onProgress(BackupProgress("Decrypting backup", 0.10f))
            val vaultBytes = source.readBytes()
            val contents = uniffi.vault_native.importVault(vaultBytes, vaultPassword) ?: return@withContext false
            onProgress(BackupProgress("Decrypting backup", 0.30f))

            val dbData = contents.dbFileData ?: return@withContext false
            onProgress(BackupProgress("Restoring database", 0.40f))

            val success = uniffi.vault_native.restoreToLayout(
                contents, dbData,
                vaultRepository.encryptionDir.absolutePath,
                vaultRepository.databaseDir.absolutePath,
                vaultRepository.filesDir.absolutePath,
            )
            onProgress(if (success) BackupProgress("Restore complete", 1.0f) else BackupProgress("Restore failed", 0.0f))
            success
        } catch (e: Exception) {
            Log.e(TAG, "importBackup failed", e); false
        }
    }

    suspend fun exportBackupToUri(
        uri: Uri, vaultPassword: String,
        onProgress: (BackupProgress) -> Unit = {},
    ): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        try {
            val tempFile = File(context.cacheDir, "backup_export_${System.currentTimeMillis()}.vault")
            if (!exportBackup(tempFile, vaultPassword, onProgress)) return@withContext false
            onProgress(BackupProgress("Writing to file", 0.80f))
            context.contentResolver.openOutputStream(uri)?.use { out ->
                tempFile.inputStream().use { `in` -> `in`.copyTo(out) }
            } ?: return@withContext false
            tempFile.delete()
            onProgress(BackupProgress("Export complete", 1.0f)); true
        } catch (e: Exception) {
            Log.e(TAG, "exportBackupToUri failed", e); false
        }
    }

    suspend fun importBackupFromUri(
        uri: Uri, vaultPassword: String,
        onProgress: (BackupProgress) -> Unit = {},
    ): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        try {
            val tempFile = File(context.cacheDir, "backup_import_${System.currentTimeMillis()}.vault")
            onProgress(BackupProgress("Reading backup file", 0.0f))
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext false
            onProgress(BackupProgress("Reading backup file", 0.10f))
            val success = importBackup(tempFile, vaultPassword, onProgress)
            tempFile.delete(); success
        } catch (e: Exception) {
            Log.e(TAG, "importBackupFromUri failed", e); false
        }
    }

    companion object {
        private const val TAG = "BackupManager"
    }
}
