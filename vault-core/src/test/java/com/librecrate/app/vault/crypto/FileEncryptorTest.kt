package com.librecrate.app.vault.crypto

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

class FileEncryptorTest {

    private val encryptor = FileEncryptor()
    private val random = SecureRandom()

    @Test
    fun `encryptBytes then decryptBytes returns original data`() {
        val key = encryptor.generateKey()
        val plaintext = ByteArray(256).also { random.nextBytes(it) }

        val (iv, encrypted) = encryptor.encryptBytes(plaintext, key)
        val decrypted = encryptor.decryptBytes(encrypted, key, iv)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `encryptBytes produces different IV each time`() {
        val key = encryptor.generateKey()
        val plaintext = ByteArray(64).also { random.nextBytes(it) }

        val (iv1, _) = encryptor.encryptBytes(plaintext, key)
        val (iv2, _) = encryptor.encryptBytes(plaintext, key)

        assertFalse(iv1.contentEquals(iv2))
    }

    @Test(expected = AEADBadTagException::class)
    fun `decryptBytes with wrong key throws exception`() {
        val keyA = encryptor.generateKey()
        val keyB = encryptor.generateKey()
        val plaintext = "hello".toByteArray()

        val (iv, encrypted) = encryptor.encryptBytes(plaintext, keyA)
        encryptor.decryptBytes(encrypted, keyB, iv)
    }

    @Test(expected = AEADBadTagException::class)
    fun `decryptBytes with wrong IV throws exception`() {
        val key = encryptor.generateKey()
        val plaintext = "hello".toByteArray()

        val (iv, encrypted) = encryptor.encryptBytes(plaintext, key)
        val wrongIv = iv.copyOf().also { it[0] = (it[0] + 1).toByte() }
        encryptor.decryptBytes(encrypted, key, wrongIv)
    }

    @Test
    fun `generateKey returns 32-byte key`() {
        assertEquals(32, encryptor.generateKey().size)
    }

    @Test
    fun `encryptBytes returns 12-byte IV`() {
        val key = encryptor.generateKey()
        val (iv, _) = encryptor.encryptBytes("data".toByteArray(), key)
        assertEquals(12, iv.size)
    }

    @Test
    fun `file roundtrip`() {
        val key = encryptor.generateKey()
        val original = File.createTempFile("encrypt", ".bin").apply {
            writeBytes(ByteArray(4096).also { random.nextBytes(it) })
        }
        val encrypted = File.createTempFile("encrypted", ".bin")
        val decrypted = File.createTempFile("decrypted", ".bin")

        try {
            val iv = encryptor.encrypt(original, encrypted, key)
            encryptor.decrypt(encrypted, decrypted, key, iv)

            assertArrayEquals(original.readBytes(), decrypted.readBytes())
        } finally {
            original.delete()
            encrypted.delete()
            decrypted.delete()
        }
    }

    @Test
    fun `encrypt is idempotent`() {
        val key = encryptor.generateKey()
        val plaintext = "same data".toByteArray()

        val (iv1, enc1) = encryptor.encryptBytes(plaintext, key)
        val (iv2, enc2) = encryptor.encryptBytes(plaintext, key)

        assertFalse(iv1.contentEquals(iv2))
        assertFalse(enc1.contentEquals(enc2))
    }
}
