package com.librecrate.app.data.encryption

import android.util.Log
import uniffi.vault_native.*
import java.io.File
import java.security.SecureRandom

class RustKeyManager(
    private val keyStore: KeyStore,
    private val crypto: KeyStoreCryptographer,
) : KeyManager {

    private var sessionMasterKey: ByteArray? = null

    override fun isPasswordSet(): Boolean = keyStore.exists(SALT_FILE) && keyStore.exists(WRAPPED_KEY_FILE)

    override fun isFirstLaunch(): Boolean = !isPasswordSet()

    override fun initializeDeviceKeyMode() {
        keyStore.delete(DEVICE_WRAPPED_KEY_FILE)
        val masterKey = sessionMasterKey ?: return
        val (iv, encrypted) = crypto.encrypt(masterKey)
        keyStore.write(DEVICE_WRAPPED_KEY_FILE, iv + encrypted)
    }

    override fun initializeWithPassword(password: String): Boolean {
        return try {
            val salt = keyStore.read(SALT_FILE) ?: generate_salt()
            val masterKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val params = Argon2ParamsFfi()
            val derivedKey = hash_argon2id(password.encodeToByteArray(), salt, params)
            val wrappedKey = wrap_aes_key(masterKey, derivedKey)
            keyStore.write(SALT_FILE, salt)
            keyStore.write(WRAPPED_KEY_FILE, wrappedKey)
            sessionMasterKey = masterKey
            true
        } catch (e: Exception) {
            Log.e(TAG, "initializeWithPassword failed", e); false
        }
    }

    override fun getMasterKeyForSession(): ByteArray? = sessionMasterKey

    override fun setPassword(password: String): Boolean {
        return if (isFirstLaunch()) {
            initializeWithPassword(password)
        } else {
            changePassword("", password)
        }
    }

    override fun verifyPassword(password: String): Boolean {
        return try {
            val salt = keyStore.read(SALT_FILE) ?: return false
            val wrappedKey = keyStore.read(WRAPPED_KEY_FILE) ?: return false
            val params = Argon2ParamsFfi()
            val derivedKey = hash_argon2id(password.encodeToByteArray(), salt, params)
            val masterKey = unwrap_aes_key(wrappedKey, derivedKey)
            sessionMasterKey = masterKey
            true
        } catch (e: Exception) {
            sessionMasterKey = null
            false
        }
    }

    override fun changePassword(oldPassword: String, newPassword: String): Boolean {
        return try {
            val salt = keyStore.read(SALT_FILE) ?: generate_salt().also { keyStore.write(SALT_FILE, it) }
            val wrappedKey = keyStore.read(WRAPPED_KEY_FILE)
            val params = Argon2ParamsFfi()

            val oldDerivedKey = if (oldPassword.isNotEmpty()) {
                val oldSalt = keyStore.read(SALT_FILE) ?: return false
                hash_argon2id(oldPassword.encodeToByteArray(), oldSalt, params)
            } else null

            val masterKey = if (wrappedKey != null && oldDerivedKey != null) {
                unwrap_aes_key(wrappedKey, oldDerivedKey)
            } else {
                ByteArray(32).also { SecureRandom().nextBytes(it) }
            }

            val newDerivedKey = hash_argon2id(newPassword.encodeToByteArray(), salt, params)
            val newWrappedKey = wrap_aes_key(masterKey, newDerivedKey)
            keyStore.write(WRAPPED_KEY_FILE, newWrappedKey)
            sessionMasterKey = masterKey
            true
        } catch (e: Exception) {
            Log.e(TAG, "changePassword failed", e); false
        }
    }

    override fun disablePassword(): Boolean {
        keyStore.delete(SALT_FILE)
        keyStore.delete(WRAPPED_KEY_FILE)
        keyStore.delete(DEVICE_WRAPPED_KEY_FILE)
        sessionMasterKey = null
        return true
    }

    override fun lock() {
        sessionMasterKey = null
    }

    fun resolveDeviceKeyForBackup(): ByteArray? {
        val data = keyStore.read(DEVICE_WRAPPED_KEY_FILE) ?: return null
        return try {
            val iv = data.copyOfRange(0, 12)
            val ciphertext = data.copyOfRange(12, data.size)
            crypto.decrypt(iv, ciphertext)
        } catch (e: Exception) {
            Log.w(TAG, "resolveDeviceKeyForBackup failed", e); null
        }
    }

    fun setupDeviceKeyForDailyUnlock(): Boolean {
        val masterKey = sessionMasterKey ?: return false
        return try {
            val (iv, encrypted) = crypto.encrypt(masterKey)
            keyStore.write(DEVICE_WRAPPED_KEY_FILE, iv + encrypted)
            true
        } catch (e: Exception) {
            Log.e(TAG, "setupDeviceKeyForDailyUnlock failed", e); false
        }
    }

    companion object {
        private const val TAG = "RustKeyManager"
        private const val SALT_FILE = "salt"
        private const val WRAPPED_KEY_FILE = "wrapped_master_key"
        private const val DEVICE_WRAPPED_KEY_FILE = "device_wrapped_master_key"
    }
}
