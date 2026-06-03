package com.docwallet.data.encryption

import android.content.Context
import android.util.Log
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class EncryptionManager(private val context: Context) {

    companion object {
        private const val TAG = "EncryptionManager"
        private const val KEY_DIR = "encryption"
        private const val WRAPPED_KEY_FILE = "wrapped_master_key"
        private const val DEVICE_KEY_FILE = "device_key"
        private const val SALT_FILE = "salt"
        private const val ALGORITHM = "AES"
        private const val KEY_WRAP_ALGORITHM = "AESWrap"
        private const val KEY_SIZE = 256
        private const val ARGON_MEMORY_COST = 19456  // 19 MiB, ~3s on modern phone
        private const val ARGON_ITERATIONS = 2
        private const val ARGON_PARALLELISM = 2
    }

    private var cachedMasterKey: ByteArray? = null

    private val encryptionDir: File
        get() = File(context.filesDir, KEY_DIR).also { it.mkdirs() }

    private val wrappedKeyFile: File
        get() = File(encryptionDir, WRAPPED_KEY_FILE)

    private val deviceKeyFile: File
        get() = File(encryptionDir, DEVICE_KEY_FILE)

    private val saltFile: File
        get() = File(encryptionDir, SALT_FILE)

    fun isPasswordSet(): Boolean {
        return deviceKeyFile.exists().not() && wrappedKeyFile.exists()
    }

    fun isFirstLaunch(): Boolean {
        return wrappedKeyFile.exists().not()
    }

    fun initializeDeviceKeyMode() {
        if (wrappedKeyFile.exists()) return

        val masterKey = generateAesKey()
        val deviceKey = generateAesKey()

        val wrappedKey = wrapKey(masterKey, deviceKey)
        wrappedKeyFile.writeBytes(wrappedKey)
        deviceKeyFile.writeBytes(deviceKey)

        cachedMasterKey = masterKey
        Log.d(TAG, "Initialized device-key mode (no password)")
    }

    fun getMasterKeyForSession(): ByteArray? {
        cachedMasterKey?.let { return it }

        if (deviceKeyFile.exists()) {
            val deviceKey = deviceKeyFile.readBytes()
            val wrappedKey = wrappedKeyFile.readBytes()
            try {
                val masterKey = unwrapKey(wrappedKey, deviceKey)
                cachedMasterKey = masterKey
                return masterKey
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unwrap with device key", e)
                return null
            }
        }

        return null
    }

    fun setPassword(password: String): Boolean {
        try {
            val masterKey = getMasterKeyForSession() ?: return false
            val salt = generateSalt()
            val userKey = deriveKey(password, salt)

            val wrappedKey = wrapKey(masterKey, userKey)
            wrappedKeyFile.writeBytes(wrappedKey)
            saltFile.writeBytes(salt)

            if (deviceKeyFile.exists()) deviceKeyFile.delete()

            cachedMasterKey = masterKey
            Log.d(TAG, "Password enabled")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set password", e)
            return false
        }
    }

    fun verifyPassword(password: String): Boolean {
        return try {
            val wrappedKey = wrappedKeyFile.readBytes()
            val salt = if (saltFile.exists()) saltFile.readBytes() else return false
            val userKey = deriveKey(password, salt)

            val masterKey = unwrapKey(wrappedKey, userKey)
            cachedMasterKey = masterKey
            Log.d(TAG, "Password verified")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Password verification failed", e)
            false
        }
    }

    fun changePassword(oldPassword: String, newPassword: String): Boolean {
        return try {
            val wrappedKey = wrappedKeyFile.readBytes()
            val salt = saltFile.readBytes()
            val oldUserKey = deriveKey(oldPassword, salt)
            val masterKey = unwrapKey(wrappedKey, oldUserKey)

            val newSalt = generateSalt()
            val newUserKey = deriveKey(newPassword, newSalt)

            val newWrappedKey = wrapKey(masterKey, newUserKey)
            wrappedKeyFile.writeBytes(newWrappedKey)
            saltFile.writeBytes(newSalt)

            cachedMasterKey = masterKey
            Log.d(TAG, "Password changed")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Password change failed", e)
            false
        }
    }

    fun disablePassword(): Boolean {
        return try {
            val masterKey = getMasterKeyForSession() ?: return false
            val deviceKey = generateAesKey()
            val wrappedKey = wrapKey(masterKey, deviceKey)

            wrappedKeyFile.writeBytes(wrappedKey)
            deviceKeyFile.writeBytes(deviceKey)

            if (saltFile.exists()) saltFile.delete()

            cachedMasterKey = masterKey
            Log.d(TAG, "Password disabled")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable password", e)
            false
        }
    }

    fun lock() {
        cachedMasterKey = null
        Log.d(TAG, "Session locked")
    }

    private fun generateAesKey(): ByteArray {
        val keyGen = KeyGenerator.getInstance(ALGORITHM)
        keyGen.init(KEY_SIZE)
        return keyGen.generateKey().encoded
    }

    private fun generateSalt(): ByteArray {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return salt
    }

    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val argon2Kt = Argon2Kt()
        val hash = argon2Kt.hash(
            mode = Argon2Mode.ARGON2_ID,
            password = password.toByteArray(),
            salt = salt,
            tCostInIterations = ARGON_ITERATIONS,
            mCostInKibibyte = ARGON_MEMORY_COST,
            parallelism = ARGON_PARALLELISM,
            hashLengthInBytes = KEY_SIZE / 8,
        )
        return hash.rawHashAsByteArray()
    }

    private fun wrapKey(key: ByteArray, wrappingKey: ByteArray): ByteArray {
        val keySpec = SecretKeySpec(wrappingKey, ALGORITHM)
        val cipher = Cipher.getInstance(KEY_WRAP_ALGORITHM)
        cipher.init(Cipher.WRAP_MODE, keySpec)
        val secretKey = SecretKeySpec(key, ALGORITHM)
        return cipher.wrap(secretKey)
    }

    private fun unwrapKey(wrappedKey: ByteArray, wrappingKeyBytes: ByteArray): ByteArray {
        val keySpec = SecretKeySpec(wrappingKeyBytes, ALGORITHM)
        val cipher = Cipher.getInstance(KEY_WRAP_ALGORITHM)
        cipher.init(Cipher.UNWRAP_MODE, keySpec)
        val unwrapped = cipher.unwrap(wrappedKey, ALGORITHM, Cipher.SECRET_KEY)
        return unwrapped.encoded
    }
}
