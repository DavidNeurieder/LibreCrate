package com.librecrate.app.domain

import android.content.Context
import android.net.Uri
import android.util.Log
import com.librecrate.app.data.db.LibreCrateDatabase
import com.librecrate.app.data.encryption.EncryptionManager
import com.librecrate.app.vault.backup.BackupContents
import com.librecrate.app.vault.backup.BackupRestoreService
import com.librecrate.app.vault.backup.RestoreEnvironment
import com.librecrate.app.vault.backup.VaultExporter
import com.librecrate.app.vault.backup.VaultImporter
import com.librecrate.app.vault.crypto.Argon2Hasher
import com.librecrate.app.vault.crypto.Argon2HasherImpl
import com.librecrate.app.vault.crypto.FileEncryptor
import com.librecrate.app.vault.crypto.KdfParams
import com.librecrate.app.vault.crypto.KeyDerivation
import com.librecrate.app.vault.database.SqlCipherOpener
import com.librecrate.app.vault.database.SqlHandle
import com.librecrate.app.vault.database.SqlHandleSupportAndroid
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
    private val getDatabase: () -> LibreCrateDatabase? = { null },
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

                val dbFile = context.getDatabasePath("librecrate.db")
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

                val service = BackupRestoreService(keyDerivation, kdfParams, fileEncryptor)
                val env = androidRestoreEnv(onProgress)
                val success = service.restore(contents, vaultPassword, env)

                if (success) {
                    onProgress(BackupProgress("Restore complete", 1.0f))
                    Log.d(TAG, "Import complete from vault format")
                } else {
                    Log.e(TAG, "Import failed during restore")
                }
                success
            } catch (e: Exception) {
                Log.e(TAG, "importBackup failed", e)
                false
            }
        }
    }

    private fun androidRestoreEnv(
        onProgress: (BackupProgress) -> Unit,
    ): RestoreEnvironment = object : RestoreEnvironment {
        override fun openBackupDb(path: String, password: ByteArray): SqlHandle {
            return SqlCipherOpener(context, password).open(path)
        }

        override fun getCurrentSqlHandle(): SqlHandle? {
            return getDatabase()?.openHelper?.writableDatabase
                ?.let { SqlHandleSupportAndroid(it) }
        }

        override fun getSessionMasterKey(): ByteArray? {
            return encryptionManager.getMasterKeyForSession()
        }

        override fun verifyPassword(password: String): Boolean {
            return encryptionManager.verifyPassword(password)
        }

        override fun setupDeviceKey(): Boolean {
            return encryptionManager.setupDeviceKeyForDailyUnlock()
        }

        override val encryptionDir: File get() = File(context.filesDir, "encryption")
        override val databaseDir: File
            get() = context.getDatabasePath("librecrate.db").parentFile
                ?: error("Cannot determine database directory")
        override val filesDir: File get() = File(context.filesDir, "files")
        override val cacheDir: File get() = context.cacheDir

        override fun log(message: String) {
            Log.d(TAG, message)
        }

        override fun onProgress(fraction: Float, phase: String) {
            onProgress(BackupProgress(phase, fraction))
        }
    }

    val fileEncryptor: FileEncryptor by lazy { FileEncryptor() }

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
