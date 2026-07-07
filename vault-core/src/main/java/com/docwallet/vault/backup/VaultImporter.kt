package com.docwallet.vault.backup

import com.docwallet.vault.crypto.FileEncryptor
import com.docwallet.vault.crypto.KdfParams
import com.docwallet.vault.crypto.KeyDerivation
import com.docwallet.vault.format.VaultPackage
import com.docwallet.vault.format.VaultPackageData
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class VaultImporter(
    private val keyDerivation: KeyDerivation,
    private val kdfParams: KdfParams = KdfParams(),
    private val fileEncryptor: FileEncryptor = FileEncryptor(),
) {
    fun `import`(vaultBytes: ByteArray, vaultPassword: String): BackupContents? {
        return try {
            val vaultData = VaultPackage.read(vaultBytes)
            importFromVault(vaultData, vaultPassword)
        } catch (_: Exception) {
            try {
                importLegacy(vaultBytes, vaultPassword)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun importFromVault(vaultData: VaultPackageData, vaultPassword: String): BackupContents {
        val manifest = vaultData.manifest
        val saltBytes = java.util.Base64.getDecoder().decode(manifest.salt)

        val importKdfParams = KdfParams(
            memoryCost = manifest.argon2Memory,
            iterations = manifest.argon2Iterations,
            parallelism = manifest.argon2Parallelism,
        )
        val derivedKey = keyDerivation.deriveAndZero(vaultPassword, saltBytes, importKdfParams)

        try {
            val encryptedBlob = vaultData.encryptedBlob
            val iv = encryptedBlob.copyOfRange(0, 12)
            val ciphertext = encryptedBlob.copyOfRange(12, encryptedBlob.size)
            val plainZipBytes = fileEncryptor.decryptBytes(ciphertext, derivedKey, iv)

            return parseZip(plainZipBytes)
        } finally {
            derivedKey.fill(0)
        }
    }

    private fun importLegacy(bytes: ByteArray, vaultPassword: String): BackupContents {
        val salt = bytes.copyOfRange(0, 16)
        val iv = bytes.copyOfRange(16, 28)
        val ciphertext = bytes.copyOfRange(28, bytes.size)

        val derivedKey = keyDerivation.deriveAndZero(vaultPassword, salt, kdfParams)

        try {
            val plainZipBytes = fileEncryptor.decryptBytes(ciphertext, derivedKey, iv)
            return parseZip(plainZipBytes)
        } finally {
            derivedKey.fill(0)
        }
    }

    private fun parseZip(zipBytes: ByteArray): BackupContents {
        val keys = mutableMapOf<String, ByteArray>()
        var dbFile: ByteArray? = null
        val files = mutableMapOf<String, ByteArray>()

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                val data = zis.readBytes()
                when {
                    name.startsWith("keys/") -> keys[name.removePrefix("keys/")] = data
                    name == "db/docwallet.db" || name == "docwallet.db" -> dbFile = data
                    name.startsWith("files/") -> files[name.removePrefix("files/")] = data
                    else -> {
                        if (!name.contains("/") && name != "docwallet.db") {
                            files[name] = data
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        return BackupContents(keys = keys, dbFile = dbFile, files = files)
    }
}
