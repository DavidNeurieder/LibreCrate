package com.docwallet.cli

import com.docwallet.vault.backup.VaultExporter
import com.docwallet.vault.backup.VaultImporter
import com.docwallet.vault.crypto.Argon2HasherImpl
import com.docwallet.vault.crypto.KeyDerivation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VaultRoundtripTest {
    private val hasher = Argon2HasherImpl()
    private val keyDerivation = KeyDerivation(hasher)
    private val exporter = VaultExporter(keyDerivation)
    private val importer = VaultImporter(keyDerivation)

    @Test
    fun `export and import roundtrip preserves files`() {
        val password = "test-password"
        val original = mapOf(
            "hello.txt" to "Hello World".toByteArray(),
            "sub/nested.txt" to "Nested".toByteArray(),
            "binary.bin" to byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte()),
        )
        val dbFile = "mock db content".toByteArray()

        val vaultBytes = exporter.export(original, dbFile, password)
        assertTrue(vaultBytes.size > 100)

        val contents = importer.`import`(vaultBytes, password)
        assertNotNull(contents)
        assertEquals(original.size, contents!!.files.size)
        assertArrayEquals(dbFile, contents.dbFile)
        for ((name, data) in original) {
            assertArrayEquals("Mismatch for $name", data, contents.files[name])
        }
    }

    @Test
    fun `wrong password returns null`() {
        val data = mapOf("a.txt" to "data".toByteArray())
        val vaultBytes = exporter.export(data, null, "correct-pw")
        val result = importer.`import`(vaultBytes, "wrong-pw")
        assertNull(result)
    }

    @Test
    fun `export without dbFile works`() {
        val data = mapOf("f.txt" to "content".toByteArray())
        val vaultBytes = exporter.export(data, null, "pw")
        val contents = importer.`import`(vaultBytes, "pw")
        assertNotNull(contents)
        assertNull(contents!!.dbFile)
        assertArrayEquals("content".toByteArray(), contents.files["f.txt"])
    }

    @Test
    fun `vault contains manifest metadata`() {
        val data = mapOf("d.txt" to "data".toByteArray())
        val vaultBytes = exporter.export(data, null, "pw")
        val vaultData = com.docwallet.vault.format.VaultPackage.read(vaultBytes)
        assertEquals(1, vaultData.manifest.version)
        assertEquals("argon2id", vaultData.manifest.kdf)
        assertEquals(1, vaultData.manifest.documentCount)
    }

    @Test
    fun `large file roundtrip`() {
        val largeData = ByteArray(100_000) { (it % 256).toByte() }
        val data = mapOf("large.bin" to largeData)
        val vaultBytes = exporter.export(data, null, "pw")
        val contents = importer.`import`(vaultBytes, "pw")
        assertNotNull(contents)
        assertArrayEquals(largeData, contents!!.files["large.bin"])
    }
}
