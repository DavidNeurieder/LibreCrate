package com.librecrate.app.domain

import android.util.Log
import com.librecrate.app.data.vault.VaultRepository
import com.librecrate.app.util.ErrorLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.vault_native.KeyValue

class DatabaseMerger(
    private val vaultRepository: VaultRepository,
) {
    suspend fun merge(
        backupDbPath: String,
        backupMasterKey: ByteArray,
        files: List<KeyValue>,
        backupKey: ByteArray?,
        localKey: ByteArray?,
        filesDir: String,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = vaultRepository.mergeBranchA(backupDbPath, backupMasterKey, files, backupKey, localKey, filesDir)
            if (result != null) {
                Log.d(TAG, "Merge: ${result.documentsAdded} added, ${result.documentsUpdated} updated, ${result.documentsConflicted} conflicts")
                true
            } else false
        } catch (e: Exception) {
            ErrorLogger.logException(null, TAG, "Database merge failed", e); false
        }
    }

    companion object {
        private const val TAG = "DatabaseMerger"
    }
}
