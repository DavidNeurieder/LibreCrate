package com.docwallet.vault.crypto

interface KeyStoreCryptographer {
    fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray>
    fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray
    fun deleteKey()
}
