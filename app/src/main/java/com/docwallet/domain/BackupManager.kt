package com.docwallet.domain

import android.content.Context
import android.net.Uri
import com.docwallet.data.encryption.EncryptionManager
import com.docwallet.data.encryption.FileEncryptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupManager(
    private val context: Context,
    private val encryptionManager: EncryptionManager,
) {

    suspend fun exportBackup(destination: File): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val tempDir = File(context.cacheDir, "backup_${System.currentTimeMillis()}")
                tempDir.mkdirs()

                val encryptionDir = File(context.filesDir, "encryption")
                copyToDir(File(encryptionDir, "wrapped_master_key"), tempDir)
                copyToDir(File(encryptionDir, "device_key"), tempDir)
                val saltFile = File(encryptionDir, "salt")
                if (saltFile.exists()) {
                    saltFile.copyTo(File(tempDir, "salt"), overwrite = true)
                }

                val dbFile = context.getDatabasePath("docwallet.db")
                if (dbFile.exists()) {
                    dbFile.copyTo(File(tempDir, "docwallet.db"), overwrite = true)
                }

                copyDirectory(context.filesDir, File(tempDir, "files"))

                val zipFile = File(tempDir, "backup.zip")
                ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                    tempDir.walkTopDown().forEach { file ->
                        if (file.isFile && file != zipFile) {
                            val entryName = file.relativeTo(tempDir).path
                            zos.putNextEntry(ZipEntry(entryName))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }

                val masterKey = encryptionManager.getMasterKeyForSession()
                    ?: return@withContext false
                val fileEncryptor = FileEncryptor()
                fileEncryptor.encrypt(zipFile, destination, masterKey)

                tempDir.deleteRecursively()
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    suspend fun importBackup(source: File, currentPassword: String?): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (encryptionManager.isPasswordSet()) {
                    if (currentPassword == null || !encryptionManager.verifyPassword(currentPassword)) {
                        return@withContext false
                    }
                }

                val tempDir = File(context.cacheDir, "restore_${System.currentTimeMillis()}")
                tempDir.mkdirs()

                val masterKey = encryptionManager.getMasterKeyForSession()
                    ?: return@withContext false
                val fileEncryptor = FileEncryptor()
                val iv = ByteArray(12)
                DataInputStream(FileInputStream(source)).use { it.readFully(iv) }
                val decryptedZip = File(tempDir, "backup.zip")
                fileEncryptor.decrypt(source, decryptedZip, masterKey, iv)

                ZipInputStream(FileInputStream(decryptedZip)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outputFile = File(tempDir, entry.name)
                        if (entry.isDirectory) {
                            outputFile.mkdirs()
                        } else {
                            outputFile.parentFile?.mkdirs()
                            FileOutputStream(outputFile).use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                val encryptionDir = File(context.filesDir, "encryption")
                encryptionDir.mkdirs()
                copyFromDir(tempDir, "wrapped_master_key", encryptionDir)
                copyFromDir(tempDir, "device_key", encryptionDir)
                val tempSalt = File(tempDir, "salt")
                if (tempSalt.exists()) {
                    tempSalt.copyTo(File(encryptionDir, "salt"), overwrite = true)
                }

                val tempDb = File(tempDir, "docwallet.db")
                if (tempDb.exists()) {
                    tempDb.copyTo(context.getDatabasePath("docwallet.db"), overwrite = true)
                }

                val tempFiles = File(tempDir, "files")
                if (tempFiles.exists()) {
                    context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
                    copyDirectoryContents(tempFiles, context.filesDir)
                }

                tempDir.deleteRecursively()
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun copyToDir(source: File, targetDir: File) {
        if (source.exists()) {
            source.copyTo(File(targetDir, source.name), overwrite = true)
        }
    }

    private fun copyFromDir(sourceDir: File, fileName: String, targetDir: File) {
        val source = File(sourceDir, fileName)
        if (source.exists()) {
            source.copyTo(File(targetDir, fileName), overwrite = true)
        }
    }

    private fun copyDirectory(source: File, target: File) {
        target.mkdirs()
        source.listFiles()?.forEach { file ->
            val dest = File(target, file.name)
            if (file.isDirectory) {
                copyDirectory(file, dest)
            } else {
                file.copyTo(dest, overwrite = true)
            }
        }
    }

    private fun copyDirectoryContents(source: File, target: File) {
        target.mkdirs()
        source.listFiles()?.forEach { file ->
            val dest = File(target, file.name)
            if (file.isDirectory) {
                copyDirectory(file, dest)
            } else {
                file.copyTo(dest, overwrite = true)
            }
        }
    }

    suspend fun exportBackupToUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(context.cacheDir, "backup_export_${System.currentTimeMillis()}.backup")
            val success = exportBackup(tempFile)
            if (!success) return@withContext false
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                tempFile.inputStream().use { it.copyTo(outputStream) }
            } ?: return@withContext false
            tempFile.delete()
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun importBackupFromUri(uri: Uri, currentPassword: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(context.cacheDir, "backup_import_${System.currentTimeMillis()}.backup")
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                tempFile.outputStream().use { it.write(inputStream.readBytes()) }
            } ?: return@withContext false
            val success = importBackup(tempFile, currentPassword)
            tempFile.delete()
            success
        } catch (e: Exception) {
            false
        }
    }
}
