package com.librecrate.app.vault.backup

import com.librecrate.app.vault.crypto.Argon2HasherImpl
import com.librecrate.app.vault.crypto.FileEncryptor
import com.librecrate.app.vault.crypto.KdfParams
import com.librecrate.app.vault.crypto.KeyDerivation
import com.librecrate.app.vault.crypto.KeyWrap
import com.librecrate.app.vault.database.VaultDatabaseMerger
import java.io.File

class BackupRestoreService(
    private val keyDerivation: KeyDerivation = KeyDerivation(Argon2HasherImpl()),
    private val kdfParams: KdfParams = KdfParams(),
    private val fileEncryptor: FileEncryptor = FileEncryptor(),
) {
    fun deriveBackupMasterKey(
        contents: BackupContents,
        vaultPassword: String,
    ): ByteArray? {
        val wrappedKey = contents.keys["wrapped_master_key"] ?: return null
        val salt = contents.keys["salt"] ?: return null
        return try {
            val userKey = keyDerivation.deriveAndZero(vaultPassword, salt, kdfParams)
            try {
                KeyWrap.unwrap(wrappedKey, userKey).copyOf()
            } finally {
                userKey.fill(0)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun deriveUserKey(password: String, salt: ByteArray): ByteArray {
        return keyDerivation.deriveAndZero(password, salt, kdfParams)
    }

    fun restore(
        contents: BackupContents,
        vaultPassword: String,
        env: RestoreEnvironment,
    ): Boolean {
        env.onProgress(0.10f, "Decrypting backup")
        env.log("Restore: currentDb=${env.getCurrentSqlHandle() != null}, " +
            "backupMasterKey=${deriveBackupMasterKey(contents, vaultPassword) != null}, " +
            "dbFile=${contents.dbFile != null}, keys=${contents.keys.size}")

        env.encryptionDir.mkdirs()
        val currentDb = env.getCurrentSqlHandle()
        val backupMasterKey = deriveBackupMasterKey(contents, vaultPassword)

        if (backupMasterKey == null && currentDb == null) {
            env.log("No valid master key for backup restoration")
            return false
        }

        env.onProgress(0.30f, "Restoring keys")

        contents.dbFile?.let { dbData ->
            val tempDb = File(env.cacheDir, "restore_db_${System.currentTimeMillis()}.db")
            try {
                tempDb.writeBytes(dbData)
                env.onProgress(0.35f, "Merging database")

                when {
                    currentDb != null && backupMasterKey != null -> {
                        env.log("Branch A: merging backup into existing database")
                        branchAmerge(contents, backupMasterKey, tempDb, env)
                    }
                    currentDb == null && backupMasterKey != null -> {
                        env.log("Branch B: fresh install restore")
                        branchBfreshInstall(contents, vaultPassword, tempDb, env)
                    }
                    currentDb != null && backupMasterKey == null -> {
                        env.log("Legacy backup without key material — merging with current key")
                        branchClegacyMerge(tempDb, env)
                    }
                    else -> {
                        env.log("Unhandled restore branch — skipping DB restore")
                    }
                }
            } finally {
                tempDb.delete()
            }
        }

        env.onProgress(0.70f, "Restoring files")
        val fileList = contents.files.entries.toList()
        val fileTotal = fileList.size.coerceAtLeast(1)
        fileList.forEachIndexed { i, (entryName, data) ->
            val targetFile = File(env.filesDir, entryName)
            targetFile.parentFile?.mkdirs()
            if (!targetFile.exists()) {
                targetFile.writeBytes(data)
            }
            env.onProgress(
                0.70f + 0.30f * ((i + 1).toFloat() / fileTotal),
                "Restoring files"
            )
        }

        return true
    }

    private fun branchAmerge(
        contents: BackupContents,
        backupMasterKey: ByteArray,
        tempDb: File,
        env: RestoreEnvironment,
    ) {
        val backupHandle = env.openBackupDb(tempDb.absolutePath, backupMasterKey)
        val currentSqlHandle = env.getCurrentSqlHandle() ?: return
        val localKey = env.getSessionMasterKey()
        try {
            val merger = VaultDatabaseMerger()
            if (localKey != null) {
                merger.mergeWithFileReencryption(
                    backupDb = backupHandle,
                    currentDb = currentSqlHandle,
                    files = contents.files,
                    backupKey = backupMasterKey,
                    localKey = localKey,
                    filesDirPath = env.filesDir.absolutePath,
                    fileEncryptor = fileEncryptor,
                )
            } else {
                merger.merge(backupHandle, currentSqlHandle)
            }
        } finally {
            backupHandle.close()
        }

        env.log("Branch A: merge complete — cleaning WAL")
        try {
            currentSqlHandle.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                cursor.moveToNext()
            }
        } catch (_: Exception) {}
        File(env.databaseDir, "librecrate.db-wal").delete()
        File(env.databaseDir, "librecrate.db-shm").delete()
    }

    private fun branchBfreshInstall(
        contents: BackupContents,
        vaultPassword: String,
        tempDb: File,
        env: RestoreEnvironment,
    ) {
        val origWrappedKey = File(env.encryptionDir, "wrapped_master_key").let { f ->
            if (f.exists()) f.readBytes() else null
        }
        val origSalt = File(env.encryptionDir, "salt").let { f ->
            if (f.exists()) f.readBytes() else null
        }

        contents.keys["wrapped_master_key"]?.let {
            File(env.encryptionDir, "wrapped_master_key").writeBytes(it)
        }
        contents.keys["salt"]?.let {
            File(env.encryptionDir, "salt").writeBytes(it)
        }

        val passwordVerified = env.verifyPassword(vaultPassword)
        if (!passwordVerified) {
            env.log("Password verification failed — restoring original key material")
            if (origWrappedKey != null) {
                File(env.encryptionDir, "wrapped_master_key").writeBytes(origWrappedKey)
            }
            if (origSalt != null) {
                File(env.encryptionDir, "salt").writeBytes(origSalt)
            }
            return
        }
        env.log("Branch B: password verified, master key cached")

        val deviceKeySetup = env.setupDeviceKey()
        if (!deviceKeySetup) {
            env.log("Device key setup failed — restoring original key material and aborting")
            if (origWrappedKey != null) {
                File(env.encryptionDir, "wrapped_master_key").writeBytes(origWrappedKey)
            } else {
                File(env.encryptionDir, "wrapped_master_key").delete()
            }
            if (origSalt != null) {
                File(env.encryptionDir, "salt").writeBytes(origSalt)
            } else {
                File(env.encryptionDir, "salt").delete()
            }
            return
        }
        env.log("Branch B: device key setup complete")

        val dbFile = File(env.databaseDir, "librecrate.db")
        dbFile.parentFile?.mkdirs()
        tempDb.copyTo(dbFile, overwrite = true)
        File(env.databaseDir, "librecrate.db-wal").delete()
        File(env.databaseDir, "librecrate.db-shm").delete()
        env.log("Branch B: DB copied and WAL/SHM cleaned")
    }

    private fun branchClegacyMerge(
        tempDb: File,
        env: RestoreEnvironment,
    ) {
        val mk = env.getSessionMasterKey()
        if (mk != null) {
            val legacyOpener: (String) -> com.librecrate.app.vault.database.SqlHandle = { path ->
                env.openBackupDb(path, mk)
            }
            val currentHandle = env.getCurrentSqlHandle()
            if (currentHandle != null) {
                val backupHandle = legacyOpener(tempDb.absolutePath)
                try {
                    VaultDatabaseMerger().merge(backupHandle, currentHandle)
                } finally {
                    backupHandle.close()
                }
            }
        } else {
            env.log("No master key available for legacy merge")
        }
    }
}
