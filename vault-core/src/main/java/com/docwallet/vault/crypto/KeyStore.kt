package com.docwallet.vault.crypto

interface KeyStore {
    fun read(name: String): ByteArray?
    fun write(name: String, data: ByteArray)
    fun delete(name: String)
    fun exists(name: String): Boolean
}
