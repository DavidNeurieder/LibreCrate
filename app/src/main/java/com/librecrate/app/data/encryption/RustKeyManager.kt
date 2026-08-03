package com.librecrate.app.data.encryption
import com.librecrate.app.util.ErrorLogger
import uniffi.vault_native.*


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
            val salt = keyStore.read(SALT_FILE) ?: generateSalt()
            val masterKey = generateMasterKey()
            val (memory, iterations, parallelism) = kdfParams()
            val derivedKey = deriveKey(password, salt, memory, iterations, parallelism)
            val wrappedKey = wrapKey(derivedKey, masterKey)
            keyStore.write(SALT_FILE, salt)
            keyStore.write(WRAPPED_KEY_FILE, wrappedKey)
            ensureParamsToml()
            sessionMasterKey = masterKey
            true
        } catch (e: Exception) {
            ErrorLogger.logException(null, TAG, "initializeWithPassword failed", e); false
        }
    }
    override fun getMasterKeyForSession(): ByteArray? = sessionMasterKey
    override fun setPassword(password: String): Boolean {
        return if (isFirstLaunch()) initializeWithPassword(password)
        else changePassword("", password)
    }
    override fun verifyPassword(password: String): Boolean {
        return try {
            val salt = keyStore.read(SALT_FILE) ?: return false
            val wrappedKey = keyStore.read(WRAPPED_KEY_FILE) ?: return false
            val (memory, iterations, parallelism) = kdfParams()
            val result = verifyPassword(password, salt, wrappedKey, memory, iterations, parallelism)
            if (result) {
                val derivedKey = deriveKey(password, salt, memory, iterations, parallelism)
                sessionMasterKey = unwrapKey(wrappedKey, derivedKey)
                ensureParamsToml()
            }
            result
        } catch (e: Exception) {
            ErrorLogger.logException(null, TAG, "verifyPassword failed", e)
            sessionMasterKey = null; false
        }
    }
    override fun changePassword(oldPassword: String, newPassword: String): Boolean {
        return try {
            val (memory, iterations, parallelism) = kdfParams()
            val salt = keyStore.read(SALT_FILE) ?: generateSalt().also { keyStore.write(SALT_FILE, it) }
            val wrappedKey = keyStore.read(WRAPPED_KEY_FILE)
            val masterKey = if (wrappedKey != null && oldPassword.isNotEmpty()) {
                val oldDerivedKey = deriveKey(oldPassword, keyStore.read(SALT_FILE)!!, memory, iterations, parallelism)
                unwrapKey(wrappedKey, oldDerivedKey)
            } else {
                generateMasterKey()
            }
            val newDerivedKey = deriveKey(newPassword, salt, memory, iterations, parallelism)
            val newWrappedKey = wrapKey(newDerivedKey, masterKey)
            keyStore.write(WRAPPED_KEY_FILE, newWrappedKey)
            ensureParamsToml()
            sessionMasterKey = masterKey
            true
        } catch (e: Exception) {
            ErrorLogger.logException(null, TAG, "changePassword failed", e); false
        }
    }
    override fun disablePassword(): Boolean {
        keyStore.delete(SALT_FILE); keyStore.delete(WRAPPED_KEY_FILE); keyStore.delete(DEVICE_WRAPPED_KEY_FILE); keyStore.delete(PARAMS_TOML_FILE)
        sessionMasterKey = null; return true
    }
    override fun lock() { sessionMasterKey = null }
    override fun ensureParamsToml() {
        if (keyStore.read(PARAMS_TOML_FILE) == null) {
            keyStore.write(PARAMS_TOML_FILE, buildParamsToml(MEMORY_COST, ITERATIONS, PARALLELISM).toByteArray(Charsets.UTF_8))
        }
    }
    private fun kdfParams(): Triple<UInt, UInt, UInt> {
        keyStore.read(PARAMS_TOML_FILE)
            ?.toString(Charsets.UTF_8)
            ?.let { parseParamsToml(it) }
            ?.let { return it }
        return Triple(MEMORY_COST, ITERATIONS, PARALLELISM)
    }
    fun resolveDeviceKeyForBackup(): ByteArray? {
        val data = keyStore.read(DEVICE_WRAPPED_KEY_FILE) ?: return null
        return try {
            val iv = data.copyOfRange(0, 12)
            val ciphertext = data.copyOfRange(12, data.size)
            crypto.decrypt(iv, ciphertext)
        } catch (e: Exception) { ErrorLogger.logWarning(null, TAG, "resolveDeviceKeyForBackup failed", e); null }
    }
    fun setupDeviceKeyForDailyUnlock(): Boolean {
        val masterKey = sessionMasterKey ?: return false
        return try {
            val (iv, encrypted) = crypto.encrypt(masterKey)
            keyStore.write(DEVICE_WRAPPED_KEY_FILE, iv + encrypted); true
        } catch (e: Exception) { ErrorLogger.logException(null, TAG, "setupDeviceKeyForDailyUnlock failed", e); false }
    }
    companion object {
        private const val TAG = "RustKeyManager"
        private const val SALT_FILE = "salt"
        private const val WRAPPED_KEY_FILE = "wrapped_master_key"
        private const val PARAMS_TOML_FILE = "params.toml"
        private const val DEVICE_WRAPPED_KEY_FILE = "device_wrapped_master_key"
        private const val MEMORY_COST: UInt = 16_384u
        private const val ITERATIONS: UInt = 3u
        private const val PARALLELISM: UInt = 2u
    }
}

internal fun buildParamsToml(memory: UInt, iterations: UInt, parallelism: UInt): String =
    "memory_cost = $memory\niterations = $iterations\nparallelism = $parallelism\nhash_length = 32\n"

internal fun parseParamsToml(toml: String): Triple<UInt, UInt, UInt>? {
    val lines = toml.lines()
    fun u(key: String): UInt? = lines
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .firstOrNull { it.startsWith("$key =") }
        ?.substringAfter("=")
        ?.trim()
        ?.toULongOrNull()
        ?.toUInt()
    val memory = u("memory_cost") ?: return null
    val iterations = u("iterations") ?: return null
    val parallelism = u("parallelism") ?: return null
    return Triple(memory, iterations, parallelism)
}
