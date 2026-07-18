package com.librecrate.app.vault.format

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class VaultPackageData(
    val manifest: VaultManifest,
    val encryptedBlob: ByteArray,
)

object VaultPackage {

    private val MAGIC = "LIBRECRATE_VAULT".toByteArray()
    private const val VERSION_SIZE = 4
    private const val MANIFEST_LEN_SIZE = 4

    fun write(manifest: VaultManifest, encryptedBlob: ByteArray): ByteArray {
        val manifestBytes = VaultManifest.serialize(manifest).toByteArray()

        val buffer = ByteArrayOutputStream()
        buffer.write(MAGIC)
        buffer.write(ByteBuffer.allocate(VERSION_SIZE).order(ByteOrder.BIG_ENDIAN).putInt(manifest.version).array())
        buffer.write(ByteBuffer.allocate(MANIFEST_LEN_SIZE).order(ByteOrder.BIG_ENDIAN).putInt(manifestBytes.size).array())
        buffer.write(manifestBytes)
        buffer.write(encryptedBlob)
        return buffer.toByteArray()
    }

    fun read(data: ByteArray): VaultPackageData {
        var offset = 0

        val magic = data.copyOfRange(offset, offset + MAGIC.size)
        require(magic.contentEquals(MAGIC)) { "Invalid vault file: bad magic" }
        offset += MAGIC.size

        val version = ByteBuffer.wrap(data, offset, VERSION_SIZE).order(ByteOrder.BIG_ENDIAN).int
        require(VaultVersion.from(version) != null) { "Unsupported vault version: $version" }
        offset += VERSION_SIZE

        val manifestLen = ByteBuffer.wrap(data, offset, MANIFEST_LEN_SIZE).order(ByteOrder.BIG_ENDIAN).int
        offset += MANIFEST_LEN_SIZE

        val manifestBytes = data.copyOfRange(offset, offset + manifestLen)
        val manifest = VaultManifest.deserialize(manifestBytes.decodeToString())
        offset += manifestLen

        val encryptedBlob = data.copyOfRange(offset, data.size)

        return VaultPackageData(manifest = manifest, encryptedBlob = encryptedBlob)
    }

    fun createZipBlob(entries: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    fun readZipBlob(blob: ByteArray): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(blob)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    entries[entry.name] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return entries
    }
}
