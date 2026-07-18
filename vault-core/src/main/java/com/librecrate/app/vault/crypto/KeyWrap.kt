package com.librecrate.app.vault.crypto

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object KeyWrap {

    private const val ALGORITHM = "AES"
    private const val KEY_WRAP_ALGORITHM = "AESWrap"

    fun wrap(key: ByteArray, wrappingKey: ByteArray): ByteArray {
        val keySpec = SecretKeySpec(wrappingKey, ALGORITHM)
        val cipher = Cipher.getInstance(KEY_WRAP_ALGORITHM)
        cipher.init(Cipher.WRAP_MODE, keySpec)
        val secretKey = SecretKeySpec(key, ALGORITHM)
        return cipher.wrap(secretKey)
    }

    fun unwrap(wrappedKey: ByteArray, wrappingKey: ByteArray): ByteArray {
        val keySpec = SecretKeySpec(wrappingKey, ALGORITHM)
        val cipher = Cipher.getInstance(KEY_WRAP_ALGORITHM)
        cipher.init(Cipher.UNWRAP_MODE, keySpec)
        return cipher.unwrap(wrappedKey, ALGORITHM, Cipher.SECRET_KEY).encoded
    }
}
