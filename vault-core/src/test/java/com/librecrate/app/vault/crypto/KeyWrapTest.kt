package com.librecrate.app.vault.crypto

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class KeyWrapTest {

    private fun generateKey(size: Int = 256): ByteArray {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(size)
        return keyGen.generateKey().encoded
    }

    @Test
    fun `wrap then unwrap returns original key`() {
        val key = generateKey()
        val wrappingKey = generateKey()

        val wrapped = KeyWrap.wrap(key, wrappingKey)
        val unwrapped = KeyWrap.unwrap(wrapped, wrappingKey)

        assertArrayEquals(key, unwrapped)
    }

    @Test
    fun `wrapped key is different from original`() {
        val key = generateKey()
        val wrappingKey = generateKey()

        val wrapped = KeyWrap.wrap(key, wrappingKey)

        assertFalse(wrapped.contentEquals(key))
    }

    @Test(expected = Exception::class)
    fun `unwrap with wrong key throws`() {
        val key = generateKey()
        val wrappingKeyA = generateKey()
        val wrappingKeyB = generateKey()

        val wrapped = KeyWrap.wrap(key, wrappingKeyA)
        KeyWrap.unwrap(wrapped, wrappingKeyB)
    }
}
