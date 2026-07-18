package com.librecrate.app.domain

import android.util.Log
import com.librecrate.app.vault.database.VaultDatabase
import com.librecrate.app.vault.database.VaultDatabaseMerger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DatabaseMerger(
    private val backupOpener: (String) -> com.librecrate.app.vault.database.SqlHandle,
    private val currentHandle: () -> com.librecrate.app.vault.database.SqlHandle?,
    private val vaultMerger: VaultDatabaseMerger = VaultDatabaseMerger(),
) {
    suspend fun merge(backupDbPath: String): Boolean {
        return withContext(Dispatchers.IO) {
            val current = currentHandle() ?: return@withContext false
            val backup = backupOpener(backupDbPath)
            try {
                val result = vaultMerger.merge(backup, current)
                Log.d(TAG, "Merge: ${result.documentsAdded} added, ${result.documentsUpdated} updated, " +
                    "${result.documentsConflicted} conflicts, ${result.documentsSkipped} skipped, " +
                    "${result.collectionsAdded} collections, ${result.tagsAdded} tags")
                if (result.hasConflicts) {
                    Log.w(TAG, "Merge completed with ${result.documentsConflicted} conflict(s)")
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Database merge failed", e)
                false
            } finally {
                backup.close()
            }
        }
    }

    companion object {
        private const val TAG = "DatabaseMerger"
    }
}
