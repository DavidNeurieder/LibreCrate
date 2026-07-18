package com.librecrate.app.vault.backup

import com.librecrate.app.vault.database.SqlHandle
import java.io.File

interface RestoreEnvironment {
    fun openBackupDb(path: String, password: ByteArray): SqlHandle
    fun getCurrentSqlHandle(masterKey: ByteArray? = null): SqlHandle?
    fun getLocalMasterKey(password: String): ByteArray?
    fun getSessionMasterKey(): ByteArray?
    fun verifyPassword(password: String): Boolean
    fun setupDeviceKey(): Boolean

    val encryptionDir: File
    val databaseDir: File
    val filesDir: File
    val cacheDir: File

    fun log(message: String)
    fun onProgress(fraction: Float, phase: String)
}
