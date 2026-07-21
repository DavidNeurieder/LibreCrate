package com.librecrate.app.domain

import android.content.Context
import android.net.Uri
import android.util.Log
import com.librecrate.app.data.encryption.EncryptionManager
import com.librecrate.app.util.ErrorLogger
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
            val keyEntries = mutableListOf<KeyValue>()
            val wrappedKeyFile = File(encryptionDir, "wrapped_master_key")
            if (wrappedKeyFile.exists()) keyEntries.add(KeyValue("wrapped_master_key", wrappedKeyFile.readBytes()))
            onProgress(BackupProgress("Reading key files", 0.05f))
            val saltFile = File(encryptionDir, "salt")
            if (saltFile.exists()) keyEntries.add(KeyValue("salt", saltFile.readBytes()))
            onProgress(BackupProgress("Reading key files", 0.1f))

            val dbFile = context.getDatabasePath("librecrate.db")
            val dbData = if (dbFile.exists()) dbFile.readBytes() else null
            onProgress(BackupProgress("Reading database", 0.15f))

            val allFiles = if (filesDir.exists()) filesDir.walkTopDown().filter { it.isFile }.toList() else emptyList()
            val total = allFiles.size.coerceAtLeast(1)
            val files = mutableListOf<KeyValue>()
            allFiles.forEachIndexed { i, file ->
                files.add(KeyValue(file.relativeTo(filesDir).path, file.readBytes()))
                onProgress(BackupProgress("Reading files", 0.15f + 0.45f * ((i + 1).toFloat() / total), detail = "$i of $total"))
            }

            onProgress(BackupProgress("Encrypting backup", 0.6f))
            val vaultBytes = exportVault(
                files, dbData, vaultPassword, keyEntries,
                Argon2Params(MEMORY_COST, ITERATIONS, PARALLELISM, HASH_LENGTH),
            )
            onProgress(BackupProgress("Encrypting backup", 0.8f))

            destination.writeBytes(vaultBytes)
            onProgress(BackupProgress("Writing output", 1.0f))
            Log.d(TAG, "Export complete: ${vaultBytes.size} bytes, ${files.size} documents")
            true
        } catch (e: Exception) {
            ErrorLogger.logException(context, TAG, "exportBackup failed", e); false
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
            val contents = importVault(vaultBytes, vaultPassword)
            onProgress(BackupProgress("Decrypting backup", 0.30f))

            val dbData = contents.dbFile ?: return@withContext false
            onProgress(BackupProgress("Restoring database", 0.40f))

            restoreToLayout(
                contents, dbData,
                vaultRepository.encryptionDir.absolutePath,
                vaultRepository.databaseDir.absolutePath,
                vaultRepository.filesDir.absolutePath,
            )
            onProgress(BackupProgress("Restore complete", 1.0f))
            true
        } catch (e: Exception) {
            ErrorLogger.logException(context, TAG, "importBackup failed", e); false
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
            ErrorLogger.logException(context, TAG, "exportBackupToUri failed", e); false
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
            ErrorLogger.logException(context, TAG, "importBackupFromUri failed", e); false
        }
    }

    companion object {
        private const val TAG = "BackupManager"
        private const val MEMORY_COST: UInt = 16_384u
        private const val ITERATIONS: UInt = 3u
        private const val PARALLELISM: UInt = 2u
        private const val HASH_LENGTH: Int = 32
    }
}
