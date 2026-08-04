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
            onProgress(BackupProgress("Preparing keys", 0.0f))
            encryptionManager.ensureParamsToml()

            onProgress(BackupProgress("Encrypting backup", 0.2f))
            val vaultBytes = exportVaultDir(
                vaultRepository.encryptionDir.absolutePath,
                vaultRepository.databaseDir.absolutePath,
                vaultRepository.filesDir.absolutePath,
                vaultPassword,
            )
            onProgress(BackupProgress("Writing output", 0.8f))

            destination.writeBytes(vaultBytes)
            onProgress(BackupProgress("Export complete", 1.0f))
            Log.d(TAG, "Export complete: ${vaultBytes.size} bytes")
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

            if (vaultRepository.isOpen()) {
                // Merge into the open library (Branch A) — existing documents are kept.
                val stats = vaultRepository.mergeBackup(vaultBytes, vaultPassword)
                    ?: return@withContext false
                onProgress(BackupProgress("Import complete", 1.0f))
                Log.d(
                    TAG,
                    "Import merged: ${stats.documentsAdded} added, " +
                        "${stats.documentsUpdated} updated, ${stats.documentsConflicted} conflicts",
                )
            } else {
                // No open local vault (fresh install / reinstall) — restore wholesale.
                onProgress(BackupProgress("Restoring vault", 0.30f))
                restoreBackupToDir(
                    vaultBytes,
                    vaultPassword,
                    vaultRepository.encryptionDir.absolutePath,
                    vaultRepository.databaseDir.absolutePath,
                    vaultRepository.filesDir.absolutePath,
                )
                onProgress(BackupProgress("Restore complete", 1.0f))
            }
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
    }
}
