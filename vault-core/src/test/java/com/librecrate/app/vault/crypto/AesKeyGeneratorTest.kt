package com.librecrate.app.vault.crypto

import org.junit.Assert.*
import org.junit.Test

class AesKeyGeneratorTest {

    @Test
    fun `generateKey returns 32-byte key`() {
        val key = AesKeyGenerator.generateKey()
        assertEquals(32, key.size)
    }

    @Test
    fun `generateKey produces different keys each time`() {
        val key1 = AesKeyGenerator.generateKey()
        val key2 = AesKeyGenerator.generateKey()
        assertFalse(key1.contentEquals(key2))
    }
}
