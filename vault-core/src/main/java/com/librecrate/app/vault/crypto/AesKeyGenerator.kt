package com.librecrate.app.vault.crypto

import javax.crypto.KeyGenerator

object AesKeyGenerator {

    private const val ALGORITHM = "AES"
    private const val KEY_SIZE = 256

    fun generateKey(): ByteArray {
        val keyGen = KeyGenerator.getInstance(ALGORITHM)
        keyGen.init(KEY_SIZE)
        return keyGen.generateKey().encoded
    }
}
