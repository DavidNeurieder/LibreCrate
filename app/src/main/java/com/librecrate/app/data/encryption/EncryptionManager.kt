package com.librecrate.app.data.encryption

import android.content.Context
import java.io.File

class EncryptionManager(context: Context) : KeyManager {

    private val inner: RustKeyManager = RustKeyManager(
        keyStore = FileKeyStore(File(context.filesDir, KEY_DIR)),
        crypto = AndroidKeyStoreCryptographer(context),
    )

    override fun isPasswordSet(): Boolean = inner.isPasswordSet()
    override fun isFirstLaunch(): Boolean = inner.isFirstLaunch()
    override fun initializeDeviceKeyMode() = inner.initializeDeviceKeyMode()
    override fun initializeWithPassword(password: String): Boolean = inner.initializeWithPassword(password)
    override fun getMasterKeyForSession(): ByteArray? = inner.getMasterKeyForSession()
    override fun setPassword(password: String): Boolean = inner.setPassword(password)
    override fun verifyPassword(password: String): Boolean = inner.verifyPassword(password)
    override fun changePassword(oldPassword: String, newPassword: String): Boolean =
        inner.changePassword(oldPassword, newPassword)
    override fun disablePassword(): Boolean = inner.disablePassword()
    override fun lock() = inner.lock()

    fun setupDeviceKeyForDailyUnlock(): Boolean = inner.setupDeviceKeyForDailyUnlock()
    fun resolveDeviceKeyForBackup(): ByteArray? = inner.resolveDeviceKeyForBackup()

    companion object {
        private const val KEY_DIR = "encryption"
    }
}
