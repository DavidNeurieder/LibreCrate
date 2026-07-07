package com.docwallet.vault.crypto

import java.util.Arrays

class DefaultKeyManager(
    private val keyStore: KeyStore,
    private val storeCryptographer: KeyStoreCryptographer,
    private val argon2Hasher: Argon2Hasher = Argon2HasherImpl(),
) : KeyManager {

    private val keyDerivation = KeyDerivation(argon2Hasher)
    private val kdfParams = KdfParams()

    @Volatile private var cachedMasterKey: ByteArray? = null

    override fun isPasswordSet(): Boolean = keyStore.exists(SALT)
    override fun isFirstLaunch(): Boolean =
        !keyStore.exists(WRAPPED_KEY) && !keyStore.exists(DEVICE_WRAPPED_KEY)

    override fun initializeDeviceKeyMode() {
        if (keyStore.exists(WRAPPED_KEY) || keyStore.exists(DEVICE_WRAPPED_KEY)) return

        val masterKey = AesKeyGenerator.generateKey()
        val deviceKey = AesKeyGenerator.generateKey()

        try {
            val wrappedKey = KeyWrap.wrap(masterKey, deviceKey)
            keyStore.write(DEVICE_WRAPPED_KEY, wrappedKey)
            val (iv, encrypted) = storeCryptographer.encrypt(deviceKey)
            val payload = ByteArray(GCM_IV_LENGTH + encrypted.size)
            System.arraycopy(iv, 0, payload, 0, GCM_IV_LENGTH)
            System.arraycopy(encrypted, 0, payload, GCM_IV_LENGTH, encrypted.size)
            keyStore.write(ENCRYPTED_DEVICE_KEY, payload)
            cachedMasterKey = masterKey
        } finally {
            Arrays.fill(deviceKey, 0)
        }
    }

    override fun initializeWithPassword(password: String): Boolean {
        if (keyStore.exists(WRAPPED_KEY)) return false

        val masterKey = AesKeyGenerator.generateKey()
        val deviceKey = AesKeyGenerator.generateKey()

        try {
            val deviceWrappedKey = KeyWrap.wrap(masterKey, deviceKey)
            keyStore.write(DEVICE_WRAPPED_KEY, deviceWrappedKey)
            val (iv, encrypted) = storeCryptographer.encrypt(deviceKey)
            val payload = ByteArray(GCM_IV_LENGTH + encrypted.size)
            System.arraycopy(iv, 0, payload, 0, GCM_IV_LENGTH)
            System.arraycopy(encrypted, 0, payload, GCM_IV_LENGTH, encrypted.size)
            keyStore.write(ENCRYPTED_DEVICE_KEY, payload)

            val salt = keyDerivation.generateSalt()
            val userKey = keyDerivation.deriveAndZero(password, salt, kdfParams)

            try {
                val wrappedKey = KeyWrap.wrap(masterKey, userKey)
                keyStore.write(WRAPPED_KEY, wrappedKey)
                keyStore.write(SALT, salt)
                cachedMasterKey = masterKey
                return true
            } finally {
                Arrays.fill(userKey, 0)
            }
        } finally {
            Arrays.fill(deviceKey, 0)
        }
    }

    @Synchronized
    override fun getMasterKeyForSession(): ByteArray? {
        cachedMasterKey?.let { return it.copyOf() }

        if (keyStore.exists(ENCRYPTED_DEVICE_KEY)) {
            val data = keyStore.read(ENCRYPTED_DEVICE_KEY) ?: return null
            val iv = data.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = data.copyOfRange(GCM_IV_LENGTH, data.size)
            val deviceKey = try {
                storeCryptographer.decrypt(iv, ciphertext)
            } catch (e: Exception) {
                return null
            }
            val wrappedKey = keyStore.read(DEVICE_WRAPPED_KEY)
                ?: keyStore.read(WRAPPED_KEY) ?: return null
            try {
                val masterKey = KeyWrap.unwrap(wrappedKey, deviceKey)
                cachedMasterKey = masterKey
                return masterKey.copyOf()
            } catch (e: Exception) {
                return null
            }
        }

        if (keyStore.exists(DEVICE_KEY)) {
            val deviceKey = keyStore.read(DEVICE_KEY) ?: return null
            val wrappedKey = keyStore.read(DEVICE_WRAPPED_KEY)
                ?: keyStore.read(WRAPPED_KEY) ?: return null
            try {
                val masterKey = KeyWrap.unwrap(wrappedKey, deviceKey)
                cachedMasterKey = masterKey
                migrateDeviceKeyToKeyStore(deviceKey)
                return masterKey.copyOf()
            } catch (e: Exception) {
                return null
            }
        }

        return null
    }

    private fun migrateDeviceKeyToKeyStore(deviceKey: ByteArray) {
        try {
            val (iv, encrypted) = storeCryptographer.encrypt(deviceKey)
            val payload = ByteArray(GCM_IV_LENGTH + encrypted.size)
            System.arraycopy(iv, 0, payload, 0, GCM_IV_LENGTH)
            System.arraycopy(encrypted, 0, payload, GCM_IV_LENGTH, encrypted.size)
            keyStore.write(ENCRYPTED_DEVICE_KEY, payload)
            keyStore.delete(DEVICE_KEY)
        } catch (_: Exception) {
        }
    }

    fun setupDeviceKeyForDailyUnlock(): Boolean {
        val masterKey = cachedMasterKey ?: return false
        val deviceKey = AesKeyGenerator.generateKey()
        try {
            val wrappedKey = KeyWrap.wrap(masterKey, deviceKey)
            keyStore.write(DEVICE_WRAPPED_KEY, wrappedKey)
            val (iv, encrypted) = storeCryptographer.encrypt(deviceKey)
            val payload = ByteArray(GCM_IV_LENGTH + encrypted.size)
            System.arraycopy(iv, 0, payload, 0, GCM_IV_LENGTH)
            System.arraycopy(encrypted, 0, payload, GCM_IV_LENGTH, encrypted.size)
            keyStore.write(ENCRYPTED_DEVICE_KEY, payload)
            return true
        } finally {
            Arrays.fill(deviceKey, 0)
        }
    }

    fun resolveDeviceKeyForBackup(): ByteArray? {
        if (keyStore.exists(ENCRYPTED_DEVICE_KEY)) {
            val data = keyStore.read(ENCRYPTED_DEVICE_KEY) ?: return null
            val iv = data.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = data.copyOfRange(GCM_IV_LENGTH, data.size)
            return storeCryptographer.decrypt(iv, ciphertext)
        }
        if (keyStore.exists(DEVICE_KEY)) {
            return keyStore.read(DEVICE_KEY)
        }
        return null
    }

    override fun setPassword(password: String): Boolean {
        try {
            val masterKey = getMasterKeyForSession() ?: return false
            val salt = keyDerivation.generateSalt()
            val userKey = keyDerivation.deriveAndZero(password, salt, kdfParams)

            try {
                val wrappedKey = KeyWrap.wrap(masterKey, userKey)
                keyStore.write(WRAPPED_KEY, wrappedKey)
                keyStore.write(SALT, salt)
                cachedMasterKey = masterKey
                return true
            } finally {
                Arrays.fill(userKey, 0)
            }
        } catch (_: Exception) {
            return false
        }
    }

    override fun verifyPassword(password: String): Boolean {
        return try {
            val wrappedKey = keyStore.read(WRAPPED_KEY) ?: return false
            val salt = keyStore.read(SALT) ?: return false
            val userKey = keyDerivation.deriveAndZero(password, salt, kdfParams)

            try {
                val masterKey = KeyWrap.unwrap(wrappedKey, userKey)
                cachedMasterKey = masterKey
                true
            } finally {
                Arrays.fill(userKey, 0)
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun changePassword(oldPassword: String, newPassword: String): Boolean {
        return try {
            val wrappedKey = keyStore.read(WRAPPED_KEY) ?: return false
            val salt = keyStore.read(SALT) ?: return false
            val oldUserKey = keyDerivation.deriveAndZero(oldPassword, salt, kdfParams)
            val masterKey = KeyWrap.unwrap(wrappedKey, oldUserKey)

            val newSalt = keyDerivation.generateSalt()
            val newUserKey = keyDerivation.deriveAndZero(newPassword, newSalt, kdfParams)

            try {
                val newWrappedKey = KeyWrap.wrap(masterKey, newUserKey)
                keyStore.write(WRAPPED_KEY, newWrappedKey)
                keyStore.write(SALT, newSalt)
                cachedMasterKey = masterKey
                true
            } finally {
                Arrays.fill(oldUserKey, 0)
                Arrays.fill(newUserKey, 0)
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun disablePassword(): Boolean {
        return try {
            val masterKey = getMasterKeyForSession() ?: return false
            val deviceKey = AesKeyGenerator.generateKey()

            try {
                val wrappedKey = KeyWrap.wrap(masterKey, deviceKey)
                keyStore.write(DEVICE_WRAPPED_KEY, wrappedKey)
                val (iv, encrypted) = storeCryptographer.encrypt(deviceKey)
                val payload = ByteArray(GCM_IV_LENGTH + encrypted.size)
                System.arraycopy(iv, 0, payload, 0, GCM_IV_LENGTH)
                System.arraycopy(encrypted, 0, payload, GCM_IV_LENGTH, encrypted.size)
                keyStore.write(ENCRYPTED_DEVICE_KEY, payload)
                keyStore.delete(SALT)
                keyStore.delete(DEVICE_KEY)
                cachedMasterKey = masterKey
                true
            } finally {
                Arrays.fill(deviceKey, 0)
            }
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    override fun lock() {
        cachedMasterKey?.let { Arrays.fill(it, 0) }
        cachedMasterKey = null
    }

    companion object {
        private const val GCM_IV_LENGTH = 12
        internal const val WRAPPED_KEY = "wrapped_master_key"
        internal const val DEVICE_WRAPPED_KEY = "device_wrapped_key"
        internal const val DEVICE_KEY = "device_key"
        internal const val ENCRYPTED_DEVICE_KEY = "encrypted_device_key"
        internal const val SALT = "salt"
    }
}
