package com.docwallet.vault.backup

import com.docwallet.vault.crypto.FileEncryptor
import com.docwallet.vault.crypto.KdfParams
import com.docwallet.vault.crypto.KeyDerivation
import com.docwallet.vault.format.VaultManifest
import com.docwallet.vault.format.VaultPackage
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VaultExporter(
    private val keyDerivation: KeyDerivation,
    private val kdfParams: KdfParams = KdfParams(),
    private val fileEncryptor: FileEncryptor = FileEncryptor(),
) {
    fun export(
        files: Map<String, ByteArray>,
        dbFile: ByteArray?,
        vaultPassword: String,
        keys: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val salt = keyDerivation.generateSalt()
        val derivedKey = keyDerivation.deriveAndZero(vaultPassword, salt, kdfParams)

        try {
            val zipEntries = mutableMapOf<String, ByteArray>()

            for ((name, data) in keys) {
                zipEntries["keys/$name"] = data
            }
            if (dbFile != null) {
                zipEntries["db/docwallet.db"] = dbFile
            }
            for ((name, data) in files) {
                zipEntries["files/$name"] = data
            }

            val plainZip = createZipBlob(zipEntries)
            val (iv, ciphertext) = fileEncryptor.encryptBytes(plainZip, derivedKey)
            val encryptedBlob = iv + ciphertext

            val documentCount = files.size
            val manifest = VaultManifest(
                version = 1,
                kdf = "argon2id",
                salt = java.util.Base64.getEncoder().encodeToString(salt),
                argon2Memory = kdfParams.memoryCost,
                argon2Iterations = kdfParams.iterations,
                argon2Parallelism = kdfParams.parallelism,
                documentCount = documentCount,
            )

            return VaultPackage.write(manifest, encryptedBlob)
        } finally {
            derivedKey.fill(0)
        }
    }

    private fun createZipBlob(entries: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
