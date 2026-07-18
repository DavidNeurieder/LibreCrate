package com.librecrate.app.cli

import com.librecrate.app.vault.backup.BackupRestoreService
import com.librecrate.app.vault.backup.VaultExporter
import com.librecrate.app.vault.backup.VaultImporter
import com.librecrate.app.vault.crypto.AesKeyGenerator
import com.librecrate.app.vault.crypto.Argon2HasherImpl
import com.librecrate.app.vault.crypto.FileEncryptor
import com.librecrate.app.vault.crypto.KdfParams
import com.librecrate.app.vault.crypto.KeyDerivation
import com.librecrate.app.vault.crypto.KeyWrap
import com.librecrate.app.vault.database.DatabaseSchema
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RestoreServiceIntegrationTest {

    private val hasher = Argon2HasherImpl()
    private val keyDerivation = KeyDerivation(hasher)
    private val kdfParams = KdfParams()
    private val vaultPassword = "integration-test-pw"
    private val backupService = BackupRestoreService(keyDerivation, kdfParams, FileEncryptor())

    @Test
    fun `restore Branch B fresh install with encrypted database`() {
        val masterKey = AesKeyGenerator.generateKey()
        val salt = ByteArray(16) { (it * 17).toByte() }
        val userKey = keyDerivation.deriveAndZero(vaultPassword, salt, kdfParams)

        val restoreDir = createTempDir("restore-branch-b-")
        val dbDir = createTempDir("source-db-")

        try {
            val dbPath = File(dbDir, "librecrate.db").absolutePath
            SqlHandleJdbc.openEncrypted(dbPath, masterKey).use { handle ->
                DatabaseSchema.createAllTables(handle)
                handle.execSQL(
                    "INSERT INTO documents(id, title, file_name, mime_type, file_path, file_size, page_count, " +
                        "author, description, imported_at, last_opened_at, modified_at, " +
                        "is_favorite, is_conflict, current_page) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf("doc1", "Restored Doc", "test.txt", "text/plain", "files/test.txt", 200L, 1,
                        "Author", "Description", 1000L, 1000L, 1000L, 0, 0, 0)
                )
                handle.execSQL(
                    "INSERT INTO documents(id, title, file_name, mime_type, file_path, file_size, page_count, " +
                        "author, description, imported_at, last_opened_at, modified_at, " +
                        "is_favorite, is_conflict, current_page) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf("doc2", "Second Doc", "test2.txt", "application/pdf", "files/test2.txt", 500L, 3,
                        "Author2", "Desc2", 2000L, 2000L, 2000L, 1, 0, 5)
                )
            }

            val wrappedKey = KeyWrap.wrap(masterKey, userKey)
            val vaultBytes = VaultExporter(keyDerivation).export(
                files = emptyMap(),
                dbFile = File(dbPath).readBytes(),
                vaultPassword = vaultPassword,
                keys = mapOf("wrapped_master_key" to wrappedKey, "salt" to salt)
            )

            val contents = VaultImporter(keyDerivation).`import`(vaultBytes, vaultPassword)
            assertNotNull("Vault should import successfully", contents)

            val env = createCLIRestoreEnvironment(restoreDir)
            val success = backupService.restore(contents!!, vaultPassword, env)
            assertTrue("Branch B restore should succeed", success)

            assertTrue("restored DB should exist", File(restoreDir, "databases/librecrate.db").exists())
            assertTrue("wrapped_master_key should exist", File(restoreDir, "encryption/wrapped_master_key").exists())
            assertTrue("salt should exist", File(restoreDir, "encryption/salt").exists())
            assertTrue("password should verify", env.verifyPassword(vaultPassword))

            val localKey = env.getLocalMasterKey(vaultPassword)!!
            assertNotNull("local master key should be derivable", localKey)
            assertArrayEquals("master key should match original", masterKey, localKey)

            SqlHandleJdbc.openEncrypted(
                File(restoreDir, "databases/librecrate.db").absolutePath, localKey
            ).use { handle ->
                handle.query("SELECT id, title, file_size FROM documents ORDER BY id").use { cursor ->
                    assertTrue("first doc exists", cursor.moveToNext())
                    assertEquals("doc1", cursor.getString(0))
                    assertEquals("Restored Doc", cursor.getString(1))
                    assertEquals(200L, cursor.getLong(2))

                    assertTrue("second doc exists", cursor.moveToNext())
                    assertEquals("doc2", cursor.getString(0))
                    assertEquals("Second Doc", cursor.getString(1))
                    assertEquals(500L, cursor.getLong(2))
                }
            }
        } finally {
            restoreDir.deleteRecursively()
            dbDir.deleteRecursively()
            userKey.fill(0)
        }
    }

    @Test
    fun `restore Branch A merges into existing database`() {
        val masterKey = AesKeyGenerator.generateKey()
        val salt = ByteArray(16) { ((it * 31) + 1).toByte() }
        val userKey = keyDerivation.deriveAndZero(vaultPassword, salt, kdfParams)
        val localSalt = ByteArray(16) { ((it * 13) + 7).toByte() }
        val localUserKey = keyDerivation.deriveAndZero(vaultPassword, localSalt, kdfParams)
        val wrappedKey = KeyWrap.wrap(masterKey, userKey)
        val localWrappedKey = KeyWrap.wrap(masterKey, localUserKey)

        val mergeDir = createTempDir("restore-branch-a-")
        val dbDir = createTempDir("source-db-")

        try {
            val existingDbPath = File(mergeDir, "databases/librecrate.db").apply {
                parentFile?.mkdirs()
            }.absolutePath

            File(mergeDir, "encryption").mkdirs()
            File(mergeDir, "encryption/wrapped_master_key").writeBytes(localWrappedKey)
            File(mergeDir, "encryption/salt").writeBytes(localSalt)

            SqlHandleJdbc.openEncrypted(existingDbPath, masterKey).use { handle ->
                DatabaseSchema.createAllTables(handle)
                handle.execSQL(
                    "INSERT INTO documents(id, title, file_name, mime_type, file_path, file_size, page_count, " +
                        "author, description, imported_at, last_opened_at, modified_at, " +
                        "is_favorite, is_conflict, current_page) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf("existing-doc", "Existing Doc", "existing.txt", "text/plain", "files/existing.txt",
                        300L, 1, "Author", "Description", 5000L, 5000L, 5000L, 0, 0, 0)
                )
            }

            val newDbPath = File(dbDir, "librecrate.db").absolutePath
            SqlHandleJdbc.openEncrypted(newDbPath, masterKey).use { handle ->
                DatabaseSchema.createAllTables(handle)
                handle.execSQL(
                    "INSERT INTO documents(id, title, file_name, mime_type, file_path, file_size, page_count, " +
                        "author, description, imported_at, last_opened_at, modified_at, " +
                        "is_favorite, is_conflict, current_page) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf("new-doc", "New Doc", "new.txt", "application/pdf", "files/new.txt",
                        400L, 2, "Author", "Description", 6000L, 6000L, 6000L, 1, 0, 10)
                )
            }

            val vaultBytes = VaultExporter(keyDerivation).export(
                files = emptyMap(),
                dbFile = File(newDbPath).readBytes(),
                vaultPassword = vaultPassword,
                keys = mapOf("wrapped_master_key" to wrappedKey, "salt" to salt)
            )

            val contents = VaultImporter(keyDerivation).`import`(vaultBytes, vaultPassword)
            assertNotNull("Vault should import", contents)

            val env = createCLIRestoreEnvironment(mergeDir)
            val success = backupService.restore(contents!!, vaultPassword, env)
            assertTrue("Branch A restore should succeed", success)

            val localKey = env.getLocalMasterKey(vaultPassword)!!
            assertNotNull("local key derivable", localKey)
            assertArrayEquals("master key matches", masterKey, localKey)

            SqlHandleJdbc.openEncrypted(
                File(mergeDir, "databases/librecrate.db").absolutePath, localKey
            ).use { handle ->
                handle.query("SELECT id, title FROM documents ORDER BY id").use { cursor ->
                    assertTrue("existing doc", cursor.moveToNext())
                    assertEquals("existing-doc", cursor.getString(0))
                    assertEquals("Existing Doc", cursor.getString(1))

                    assertTrue("new doc", cursor.moveToNext())
                    assertEquals("new-doc", cursor.getString(0))
                    assertEquals("New Doc", cursor.getString(1))

                    assertFalse("no more docs", cursor.moveToNext())
                }
            }
        } finally {
            mergeDir.deleteRecursively()
            dbDir.deleteRecursively()
            userKey.fill(0)
            localUserKey.fill(0)
        }
    }

    @Test
    fun `wrong password returns false`() {
        val masterKey = AesKeyGenerator.generateKey()
        val salt = ByteArray(16) { 66 }
        val userKey = keyDerivation.deriveAndZero(vaultPassword, salt, kdfParams)

        val restoreDir = createTempDir("restore-wrong-pw-")
        val dbDir = createTempDir("source-db-wrong-pw-")

        try {
            val dbPath = File(dbDir, "librecrate.db").absolutePath
            SqlHandleJdbc.openEncrypted(dbPath, masterKey).use { handle ->
                DatabaseSchema.createAllTables(handle)
            }

            val wrappedKey = KeyWrap.wrap(masterKey, userKey)
            val vaultBytes = VaultExporter(keyDerivation).export(
                files = emptyMap(),
                dbFile = File(dbPath).readBytes(),
                vaultPassword = vaultPassword,
                keys = mapOf("wrapped_master_key" to wrappedKey, "salt" to salt)
            )

            val contents = VaultImporter(keyDerivation).`import`(vaultBytes, vaultPassword)
            assertNotNull(contents)

            val env = createCLIRestoreEnvironment(restoreDir)
            val result = backupService.restore(contents!!, "wrong-password", env)
            assertFalse("wrong password should return false", result)

            assertFalse("DB should not exist", File(restoreDir, "databases/librecrate.db").exists())
        } finally {
            restoreDir.deleteRecursively()
            dbDir.deleteRecursively()
            userKey.fill(0)
        }
    }

    @Test
    fun `Branch B roundtrip with encrypted files`() {
        val masterKey = AesKeyGenerator.generateKey()
        val salt = ByteArray(16) { (it * 19).toByte() }
        val userKey = keyDerivation.deriveAndZero(vaultPassword, salt, kdfParams)
        val wrappedKey = KeyWrap.wrap(masterKey, userKey)
        val fileEncryptor = FileEncryptor()

        val restoreDir = createTempDir("restore-branch-b-files-")
        val dbDir = createTempDir("source-db-files-")

        try {
            val plaintext = "Hello, this is a test file!".encodeToByteArray()
            val (fileIv, fileCiphertext) = fileEncryptor.encryptBytes(plaintext, masterKey)
            val encryptedFileBytes = fileIv + fileCiphertext

            val dbPath = File(dbDir, "librecrate.db").absolutePath
            SqlHandleJdbc.openEncrypted(dbPath, masterKey).use { handle ->
                DatabaseSchema.createAllTables(handle)
                handle.execSQL(
                    "INSERT INTO documents(id, title, file_name, mime_type, file_path, file_size, page_count, " +
                        "author, description, imported_at, last_opened_at, modified_at, " +
                        "is_favorite, is_conflict, current_page, encryption_iv) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf("doc-file", "File Doc", "test_document.txt", "text/plain",
                        "files/test_document.txt", plaintext.size.toLong(), 1,
                        "Author", "Desc", 1000L, 1000L, 1000L, 0, 0, 0, fileIv)
                )
            }

            val vaultBytes = VaultExporter(keyDerivation).export(
                files = mapOf("test_document.txt" to encryptedFileBytes),
                dbFile = File(dbPath).readBytes(),
                vaultPassword = vaultPassword,
                keys = mapOf("wrapped_master_key" to wrappedKey, "salt" to salt)
            )

            val contents = VaultImporter(keyDerivation).`import`(vaultBytes, vaultPassword)
            assertNotNull("Vault should import", contents)

            val env = createCLIRestoreEnvironment(restoreDir)
            val success = backupService.restore(contents!!, vaultPassword, env)
            assertTrue("Branch B restore should succeed", success)

            assertTrue("DB exists", File(restoreDir, "databases/librecrate.db").exists())
            assertTrue("wrapped_master_key exists", File(restoreDir, "encryption/wrapped_master_key").exists())
            assertTrue("salt exists", File(restoreDir, "encryption/salt").exists())
            assertTrue("file restored", File(restoreDir, "files/test_document.txt").exists())

            val localKey = env.getLocalMasterKey(vaultPassword)!!
            assertNotNull("local key derivable", localKey)

            val restoredFileBytes = File(restoreDir, "files/test_document.txt").readBytes()
            val restoredIv = restoredFileBytes.copyOfRange(0, FileEncryptor.IV_LENGTH)
            val restoredCiphertext = restoredFileBytes.copyOfRange(FileEncryptor.IV_LENGTH, restoredFileBytes.size)
            val decrypted = fileEncryptor.decryptBytes(restoredCiphertext, localKey, restoredIv)
            assertArrayEquals("decrypted file matches original", plaintext, decrypted)

            SqlHandleJdbc.openEncrypted(
                File(restoreDir, "databases/librecrate.db").absolutePath, localKey
            ).use { handle ->
                handle.query("SELECT id, title, file_path, file_size FROM documents").use { cursor ->
                    assertTrue("doc exists", cursor.moveToNext())
                    assertEquals("doc-file", cursor.getString(0))
                    assertEquals("File Doc", cursor.getString(1))
                    assertEquals("files/test_document.txt", cursor.getString(2))
                    assertEquals(plaintext.size.toLong(), cursor.getLong(3))
                    assertFalse("only one doc", cursor.moveToNext())
                }
            }
        } finally {
            restoreDir.deleteRecursively()
            dbDir.deleteRecursively()
            userKey.fill(0)
        }
    }

    @Test
    fun `Branch A merge with encrypted files`() {
        val masterKey = AesKeyGenerator.generateKey()
        val salt = ByteArray(16) { ((it * 11) + 3).toByte() }
        val userKey = keyDerivation.deriveAndZero(vaultPassword, salt, kdfParams)
        val localSalt = ByteArray(16) { ((it * 17) + 5).toByte() }
        val localUserKey = keyDerivation.deriveAndZero(vaultPassword, localSalt, kdfParams)
        val wrappedKey = KeyWrap.wrap(masterKey, userKey)
        val localWrappedKey = KeyWrap.wrap(masterKey, localUserKey)
        val fileEncryptor = FileEncryptor()

        val mergeDir = createTempDir("restore-branch-a-files-")
        val dbDir = createTempDir("source-db-a-files-")

        try {
            val existingPlaintext = "Existing file content".encodeToByteArray()
            val (existingIv, existingCt) = fileEncryptor.encryptBytes(existingPlaintext, masterKey)
            val existingEncrypted = existingIv + existingCt

            File(mergeDir, "encryption").mkdirs()
            File(mergeDir, "encryption/wrapped_master_key").writeBytes(localWrappedKey)
            File(mergeDir, "encryption/salt").writeBytes(localSalt)
            File(mergeDir, "files").mkdirs()
            File(mergeDir, "files/existing.txt").writeBytes(existingEncrypted)

            val existingDbPath = File(mergeDir, "databases/librecrate.db").apply {
                parentFile?.mkdirs()
            }.absolutePath
            SqlHandleJdbc.openEncrypted(existingDbPath, masterKey).use { handle ->
                DatabaseSchema.createAllTables(handle)
                handle.execSQL(
                    "INSERT INTO documents(id, title, file_name, mime_type, file_path, file_size, page_count, " +
                        "author, description, imported_at, last_opened_at, modified_at, " +
                        "is_favorite, is_conflict, current_page, encryption_iv) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf("existing-file", "Existing", "existing.txt", "text/plain",
                        "files/existing.txt", existingPlaintext.size.toLong(), 1,
                        "EAuth", "EDesc", 5000L, 5000L, 5000L, 0, 0, 0, existingIv)
                )
            }

            val newPlaintext = "New document content".encodeToByteArray()
            val (newIv, newCt) = fileEncryptor.encryptBytes(newPlaintext, masterKey)
            val newEncrypted = newIv + newCt

            val newDbPath = File(dbDir, "librecrate.db").absolutePath
            SqlHandleJdbc.openEncrypted(newDbPath, masterKey).use { handle ->
                DatabaseSchema.createAllTables(handle)
                handle.execSQL(
                    "INSERT INTO documents(id, title, file_name, mime_type, file_path, file_size, page_count, " +
                        "author, description, imported_at, last_opened_at, modified_at, " +
                        "is_favorite, is_conflict, current_page, encryption_iv) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf("new-file", "New Doc", "new_doc.txt", "application/pdf",
                        "files/new_doc.txt", newPlaintext.size.toLong(), 5,
                        "NAuth", "NDesc", 6000L, 6000L, 6000L, 1, 0, 10, newIv)
                )
            }

            val vaultBytes = VaultExporter(keyDerivation).export(
                files = mapOf("new_doc.txt" to newEncrypted),
                dbFile = File(newDbPath).readBytes(),
                vaultPassword = vaultPassword,
                keys = mapOf("wrapped_master_key" to wrappedKey, "salt" to salt)
            )

            val contents = VaultImporter(keyDerivation).`import`(vaultBytes, vaultPassword)
            assertNotNull("Vault should import", contents)

            val env = createCLIRestoreEnvironment(mergeDir)
            val success = backupService.restore(contents!!, vaultPassword, env)
            assertTrue("Branch A restore should succeed", success)

            assertTrue("file exists: existing.txt",
                File(mergeDir, "files/existing.txt").exists())
            assertTrue("file exists: new_doc.txt",
                File(mergeDir, "files/new_doc.txt").exists())

            val localKey = env.getLocalMasterKey(vaultPassword)!!
            assertNotNull("local key derivable", localKey)

            val decryptedExisting = fileEncryptor.decryptBytes(
                File(mergeDir, "files/existing.txt").readBytes()
                    .let { it.copyOfRange(FileEncryptor.IV_LENGTH, it.size) },
                localKey,
                File(mergeDir, "files/existing.txt").readBytes()
                    .copyOfRange(0, FileEncryptor.IV_LENGTH)
            )
            assertArrayEquals("existing file content intact", existingPlaintext, decryptedExisting)

            val decryptedNew = fileEncryptor.decryptBytes(
                File(mergeDir, "files/new_doc.txt").readBytes()
                    .let { it.copyOfRange(FileEncryptor.IV_LENGTH, it.size) },
                localKey,
                File(mergeDir, "files/new_doc.txt").readBytes()
                    .copyOfRange(0, FileEncryptor.IV_LENGTH)
            )
            assertArrayEquals("new file content intact", newPlaintext, decryptedNew)

            SqlHandleJdbc.openEncrypted(
                File(mergeDir, "databases/librecrate.db").absolutePath, localKey
            ).use { handle ->
                handle.query("SELECT id, title, file_path FROM documents ORDER BY id").use { cursor ->
                    assertTrue("existing doc", cursor.moveToNext())
                    assertEquals("existing-file", cursor.getString(0))
                    assertEquals("files/existing.txt", cursor.getString(2))

                    assertTrue("new doc", cursor.moveToNext())
                    assertEquals("new-file", cursor.getString(0))
                    assertEquals("files/new_doc.txt", cursor.getString(2))

                    assertFalse("no more docs", cursor.moveToNext())
                }
            }
        } finally {
            mergeDir.deleteRecursively()
            dbDir.deleteRecursively()
            userKey.fill(0)
            localUserKey.fill(0)
        }
    }

    @Test
    fun `CLI export import roundtrip with encrypted DB and files`() {
        val masterKey = AesKeyGenerator.generateKey()
        val salt = ByteArray(16) { (it * 23 + 11).toByte() }
        val userKey = keyDerivation.deriveAndZero(vaultPassword, salt, kdfParams)
        val wrappedKey = KeyWrap.wrap(masterKey, userKey)
        val fileEncryptor = FileEncryptor()

        val sourceDir = createTempDir("roundtrip-source-")
        val targetDir = createTempDir("roundtrip-target-")

        try {
            val plaintext1 = "First document content for roundtrip".encodeToByteArray()
            val (iv1, ct1) = fileEncryptor.encryptBytes(plaintext1, masterKey)
            val encrypted1 = iv1 + ct1

            val plaintext2 = "Second document with different content".encodeToByteArray()
            val (iv2, ct2) = fileEncryptor.encryptBytes(plaintext2, masterKey)
            val encrypted2 = iv2 + ct2

            File(sourceDir, "encryption").mkdirs()
            File(sourceDir, "encryption/wrapped_master_key").writeBytes(wrappedKey)
            File(sourceDir, "encryption/salt").writeBytes(salt)

            File(sourceDir, "files").mkdirs()
            File(sourceDir, "files/doc1.txt").writeBytes(encrypted1)
            File(sourceDir, "files/doc2.txt").writeBytes(encrypted2)

            val dbPath = File(sourceDir, "databases/librecrate.db").apply {
                parentFile?.mkdirs()
            }.absolutePath
            SqlHandleJdbc.openEncrypted(dbPath, masterKey).use { handle ->
                DatabaseSchema.createAllTables(handle)
                handle.execSQL(
                    "INSERT INTO documents(id, title, file_name, mime_type, file_path, file_size, page_count, " +
                        "author, description, imported_at, last_opened_at, modified_at, " +
                        "is_favorite, is_conflict, current_page, encryption_iv) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf("rt-doc1", "Roundtrip Doc 1", "doc1.txt", "text/plain",
                        "files/doc1.txt", plaintext1.size.toLong(), 1,
                        "Author1", "Desc1", 1000L, 1000L, 1000L, 0, 0, 0, iv1)
                )
                handle.execSQL(
                    "INSERT INTO documents(id, title, file_name, mime_type, file_path, file_size, page_count, " +
                        "author, description, imported_at, last_opened_at, modified_at, " +
                        "is_favorite, is_conflict, current_page, encryption_iv) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf("rt-doc2", "Roundtrip Doc 2", "doc2.txt", "application/pdf",
                        "files/doc2.txt", plaintext2.size.toLong(), 3,
                        "Author2", "Desc2", 2000L, 2000L, 2000L, 1, 0, 5, iv2)
                )
                handle.execSQL(
                    "INSERT INTO collections(id, name, icon, sort_order) VALUES (?, ?, ?, ?)",
                    arrayOf("col1", "Test Collection", "book", 1)
                )
                handle.execSQL(
                    "INSERT INTO tags(id, name, color) VALUES (?, ?, ?)",
                    arrayOf("tag1", "favorite", 0xFF0000)
                )
                handle.execSQL(
                    "INSERT INTO document_tags(document_id, tag_id) VALUES (?, ?)",
                    arrayOf("rt-doc2", "tag1")
                )
            }

            val dbBytes = File(dbPath).readBytes()

            // --- Export step (same code path as CLI VaultExport) ---
            val vaultBytes = VaultExporter(keyDerivation).export(
                files = mapOf(
                    "doc1.txt" to encrypted1,
                    "doc2.txt" to encrypted2,
                ),
                dbFile = dbBytes,
                vaultPassword = vaultPassword,
                keys = mapOf("wrapped_master_key" to wrappedKey, "salt" to salt)
            )

            // --- Import step (same code path as CLI VaultImport --use-shared) ---
            val contents = VaultImporter(keyDerivation).`import`(vaultBytes, vaultPassword)
            assertNotNull("vault imports successfully", contents)

            val env = createCLIRestoreEnvironment(targetDir)
            val success = backupService.restore(contents!!, vaultPassword, env)
            assertTrue("restore succeeds", success)

            // --- Verification ---
            val localKey = env.getLocalMasterKey(vaultPassword)!!
            assertNotNull("local key derivable", localKey)
            assertArrayEquals("master key preserved", masterKey, localKey)

            assertTrue("wrapped_master_key restored",
                File(targetDir, "encryption/wrapped_master_key").exists())
            assertTrue("salt restored",
                File(targetDir, "encryption/salt").exists())

            val restoredDbPath = File(targetDir, "databases/librecrate.db")
            assertTrue("DB restored", restoredDbPath.exists())
            SqlHandleJdbc.openEncrypted(restoredDbPath.absolutePath, localKey).use { handle ->
                handle.query("SELECT id, title, file_path, file_size, encryption_iv FROM documents ORDER BY id").use { cursor ->
                    assertTrue("doc1 exists", cursor.moveToNext())
                    assertEquals("rt-doc1", cursor.getString(0))
                    assertEquals("files/doc1.txt", cursor.getString(2))
                    assertArrayEquals("doc1 iv matches source", iv1, cursor.getBlob(4))

                    assertTrue("doc2 exists", cursor.moveToNext())
                    assertEquals("rt-doc2", cursor.getString(0))
                    assertEquals("files/doc2.txt", cursor.getString(2))
                    assertArrayEquals("doc2 iv matches source", iv2, cursor.getBlob(4))

                    assertFalse("no extra docs", cursor.moveToNext())
                }

                handle.query("SELECT id, name FROM collections").use { cursor ->
                    assertTrue("collection exists", cursor.moveToNext())
                    assertEquals("col1", cursor.getString(0))
                    assertFalse("only one collection", cursor.moveToNext())
                }

                handle.query("SELECT id, name FROM tags").use { cursor ->
                    assertTrue("tag exists", cursor.moveToNext())
                    assertEquals("tag1", cursor.getString(0))
                }

                handle.query("SELECT document_id, tag_id FROM document_tags").use { cursor ->
                    assertTrue("doc-tag exists", cursor.moveToNext())
                    assertEquals("rt-doc2", cursor.getString(0))
                    assertEquals("tag1", cursor.getString(1))
                }
            }

            fun decryptStoredFile(path: String): ByteArray {
                val bytes = File(targetDir, path).readBytes()
                val storedIv = bytes.copyOfRange(0, FileEncryptor.IV_LENGTH)
                val ciphertext = bytes.copyOfRange(FileEncryptor.IV_LENGTH, bytes.size)
                return fileEncryptor.decryptBytes(ciphertext, localKey, storedIv)
            }

            assertTrue("doc1.txt exists", File(targetDir, "files/doc1.txt").exists())
            assertArrayEquals("doc1.txt content preserved", plaintext1, decryptStoredFile("files/doc1.txt"))

            assertTrue("doc2.txt exists", File(targetDir, "files/doc2.txt").exists())
            assertArrayEquals("doc2.txt content preserved", plaintext2, decryptStoredFile("files/doc2.txt"))
        } finally {
            sourceDir.deleteRecursively()
            targetDir.deleteRecursively()
            userKey.fill(0)
        }
    }

    private fun createTempDir(prefix: String): File {
        val tmp = File.createTempFile(prefix, "")
        tmp.delete()
        tmp.mkdirs()
        return tmp
    }
}
