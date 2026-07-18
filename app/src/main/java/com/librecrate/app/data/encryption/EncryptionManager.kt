package com.librecrate.app.data.encryption

import android.content.Context
import com.librecrate.app.vault.crypto.DefaultKeyManager
import com.librecrate.app.vault.crypto.KeyManager
import java.io.File

class EncryptionManager(
    context: Context,
    argon2Hasher: com.librecrate.app.vault.crypto.Argon2Hasher = com.librecrate.app.vault.crypto.Argon2HasherImpl(),
    keyStoreCryptographer: com.librecrate.app.vault.crypto.KeyStoreCryptographer = AndroidKeyStoreCryptographer(context),
) : KeyManager {

    private val inner: DefaultKeyManager = DefaultKeyManager(
        keyStore = FileKeyStore(File(context.filesDir, KEY_DIR)),
        storeCryptographer = keyStoreCryptographer,
        argon2Hasher = argon2Hasher,
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
