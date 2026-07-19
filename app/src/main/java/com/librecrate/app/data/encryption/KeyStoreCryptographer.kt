package com.librecrate.app.data.encryption

interface KeyStoreCryptographer {
    fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray>
    fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray
    fun deleteKey()
}
