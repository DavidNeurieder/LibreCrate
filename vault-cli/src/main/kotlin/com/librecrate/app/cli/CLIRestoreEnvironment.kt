package com.librecrate.app.cli

import com.librecrate.app.vault.backup.RestoreEnvironment
import com.librecrate.app.vault.crypto.Argon2HasherImpl
import com.librecrate.app.vault.crypto.KdfParams
import com.librecrate.app.vault.crypto.KeyDerivation
import com.librecrate.app.vault.crypto.KeyWrap
import com.librecrate.app.vault.database.SqlHandle
import java.io.File

fun createCLIRestoreEnvironment(
    baseDir: File,
    onMessage: (String) -> Unit = { println(it) },
): RestoreEnvironment {
    val encryptionDir = File(baseDir, "encryption").also { it.mkdirs() }
    val databaseDir = File(baseDir, "databases").also { it.mkdirs() }
    val filesDir = File(baseDir, "files").also { it.mkdirs() }
    val cacheDir = File(baseDir, "cache").also { it.mkdirs() }

    val keyDerivation = KeyDerivation(Argon2HasherImpl())
    val kdfParams = KdfParams()
    val jdbcOpener = JdbcSqlHandleOpener()

    return object : RestoreEnvironment {
        override fun openBackupDb(path: String, password: ByteArray): SqlHandle {
            return jdbcOpener.openEncrypted(path, password)
        }

        override fun getCurrentSqlHandle(masterKey: ByteArray?): SqlHandle? {
            val dbFile = File(databaseDir, "librecrate.db")
            if (!dbFile.exists()) return null
            return if (masterKey != null) {
                jdbcOpener.openEncrypted(dbFile.absolutePath, masterKey)
            } else {
                jdbcOpener.open(dbFile.absolutePath)
            }
        }

        override fun getLocalMasterKey(password: String): ByteArray? {
            val wrappedKey = File(encryptionDir, "wrapped_master_key")
            val salt = File(encryptionDir, "salt")
            if (!wrappedKey.exists() || !salt.exists()) return null
            return try {
                val userKey = keyDerivation.deriveAndZero(password, salt.readBytes(), kdfParams)
                try {
                    KeyWrap.unwrap(wrappedKey.readBytes(), userKey).copyOf()
                } finally {
                    userKey.fill(0)
                }
            } catch (e: Exception) {
                null
            }
        }

        override fun getSessionMasterKey(): ByteArray? {
            return null
        }

        override fun verifyPassword(password: String): Boolean {
            val wrappedKey = File(encryptionDir, "wrapped_master_key")
            val salt = File(encryptionDir, "salt")
            if (!wrappedKey.exists() || !salt.exists()) return false
            return try {
                val userKey = keyDerivation.deriveAndZero(password, salt.readBytes(), kdfParams)
                try {
                    KeyWrap.unwrap(wrappedKey.readBytes(), userKey).copyOf()
                    true
                } finally {
                    userKey.fill(0)
                }
            } catch (e: Exception) {
                false
            }
        }

        override fun setupDeviceKey(): Boolean = true

        override val encryptionDir: File get() = encryptionDir
        override val databaseDir: File get() = databaseDir
        override val filesDir: File get() = filesDir
        override val cacheDir: File get() = cacheDir

        override fun log(message: String) {
            onMessage(message)
        }

        override fun onProgress(fraction: Float, phase: String) {
            onMessage("[$phase] ${(fraction * 100).toInt()}%")
        }
    }
}
