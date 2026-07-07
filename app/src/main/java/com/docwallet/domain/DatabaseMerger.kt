package com.docwallet.domain

import android.util.Log
import com.docwallet.data.db.DocWalletDatabase
import com.docwallet.vault.database.SqlHandleAndroid
import com.docwallet.vault.database.SqlHandleSupportAndroid
import com.docwallet.vault.database.VaultDatabaseMerger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.sqlcipher.database.SQLiteDatabase
import java.io.File

class DatabaseMerger(
    private val getDatabase: () -> DocWalletDatabase?,
    private val vaultMerger: VaultDatabaseMerger = VaultDatabaseMerger(),
) {
    suspend fun merge(backupDbFile: File, masterKey: ByteArray): Boolean {
        return withContext(Dispatchers.IO) {
            val currentDb = getDatabase() ?: return@withContext false
            val backupDb = SQLiteDatabase.openOrCreateDatabase(
                backupDbFile.absolutePath, masterKey, null
            )

            try {
                val backupHandle = SqlHandleAndroid(backupDb)
                val currentHandle = SqlHandleSupportAndroid(currentDb.openHelper.writableDatabase)
                val result = vaultMerger.merge(backupHandle, currentHandle)
                if (result) {
                    Log.d(TAG, "Database merge completed")
                } else {
                    Log.e(TAG, "Database merge failed")
                }
                result
            } catch (e: Exception) {
                Log.e(TAG, "Database merge failed", e)
                false
            } finally {
                backupDb.close()
            }
        }
    }

    companion object {
        private const val TAG = "DatabaseMerger"
    }
}
