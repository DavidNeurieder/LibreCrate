package com.librecrate.app.vault.format

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class VaultPackageTest {

    @Test
    fun `write then read returns same manifest and blob`() {
        val manifest = VaultManifest(
            version = 1,
            salt = Base64.getEncoder().encodeToString(ByteArray(16)),
            documentCount = 5,
        )
        val blob = "encrypted-data-here".toByteArray()

        val pkg = VaultPackage.write(manifest, blob)
        val result = VaultPackage.read(pkg)

        assertEquals(manifest.version, result.manifest.version)
        assertEquals(manifest.salt, result.manifest.salt)
        assertEquals(manifest.documentCount, result.manifest.documentCount)
        assertArrayEquals(blob, result.encryptedBlob)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `read with bad magic throws`() {
        val data = "not-a-vault-file".toByteArray()
        VaultPackage.read(data)
    }

    @Test
    fun `createZipBlob and readZipBlob roundtrip`() {
        val entries = mapOf(
            "hello.txt" to "world".toByteArray(),
            "sub/file.bin" to byteArrayOf(1, 2, 3, 4),
        )

        val blob = VaultPackage.createZipBlob(entries)
        val result = VaultPackage.readZipBlob(blob)

        assertEquals(2, result.size)
        assertArrayEquals("world".toByteArray(), result["hello.txt"])
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), result["sub/file.bin"])
    }
}
