package com.docwallet.vault.crypto

import java.security.SecureRandom
import java.util.Arrays

class KeyDerivation(
    private val hasher: Argon2Hasher,
) {

    fun deriveKey(password: String, salt: ByteArray, params: KdfParams = KdfParams()): ByteArray {
        return hasher.hash(
            password = password.toByteArray(),
            salt = salt,
            tCostInIterations = params.iterations,
            mCostInKibibyte = params.memoryCost,
            parallelism = params.parallelism,
            hashLengthInBytes = params.hashLengthInBytes,
        )
    }

    fun generateSalt(length: Int = 16): ByteArray {
        val salt = ByteArray(length)
        SecureRandom().nextBytes(salt)
        return salt
    }

    fun deriveAndZero(password: String, salt: ByteArray, params: KdfParams = KdfParams()): ByteArray {
        val key = deriveKey(password, salt, params)
        val pwdBytes = password.toByteArray()
        Arrays.fill(pwdBytes, 0)
        return key
    }
}
