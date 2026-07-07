package com.docwallet.domain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docwallet.data.db.DocWalletDatabase
import com.docwallet.data.encryption.EncryptionManager
import com.docwallet.vault.crypto.Argon2Hasher
import com.docwallet.vault.crypto.FileEncryptor
import com.docwallet.vault.crypto.KeyStoreCryptographer
import com.docwallet.data.model.Document
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

@RunWith(AndroidJUnit4::class)
class BackupManagerTest {

    private lateinit var context: Context
    private lateinit var encryptionManager: EncryptionManager
    private lateinit var fileEncryptor: FileEncryptor
    private lateinit var backupManager: BackupManager
    private lateinit var db: DocWalletDatabase
    private lateinit var dao: com.docwallet.data.db.DocumentDao
    private lateinit var masterKey: ByteArray

    companion object {
        private const val TEST_PASSWORD = "test_vault_password"
        private const val TEST_PASSWORD_2 = "different_password"
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // Wipe leftover state from prior runs so initializeDeviceKeyMode()
        // always creates fresh keys that match the new TestKeyStoreCryptographer
        File(context.filesDir, "files").deleteRecursively()
        File(context.filesDir, "encryption").deleteRecursively()
        context.getDatabasePath("docwallet.db").let { dbFile ->
            dbFile.delete()
            File(dbFile.parentFile, "docwallet.db-wal").delete()
            File(dbFile.parentFile, "docwallet.db-shm").delete()
        }

        encryptionManager = EncryptionManager(context, DeterministicHasher(), TestKeyStoreCryptographer())
        encryptionManager.initializeWithPassword(TEST_PASSWORD)

        masterKey = encryptionManager.getMasterKeyForSession()!!
        fileEncryptor = FileEncryptor()

        db = DocWalletDatabase.create(context, masterKey)
        dao = db.documentDao()

        backupManager = BackupManager(context, encryptionManager, { db }, DeterministicHasher())
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        context.getDatabasePath("docwallet.db").let { dbFile ->
            dbFile.delete()
            File(dbFile.parentFile, "docwallet.db-wal").delete()
            File(dbFile.parentFile, "docwallet.db-shm").delete()
        }
        File(context.filesDir, "files").deleteRecursively()
        File(context.filesDir, "encryption").deleteRecursively()
        File(context.cacheDir, "test_backup.backup").delete()
        File(context.cacheDir, "test_backup").deleteRecursively()
        File(context.cacheDir, "src_doc1.txt").delete()
        File(context.cacheDir, "src_doc2.txt").delete()
        File(context.cacheDir, "verify_doc1.txt").delete()
        File(context.cacheDir, "verify_doc2.txt").delete()
        File(context.cacheDir, "src_ri1.txt").delete()
        File(context.cacheDir, "verify_ri1.txt").delete()
        File(context.cacheDir, "src_wp.txt").delete()
        File(context.cacheDir, "saved_encryption_a").deleteRecursively()
        File(context.cacheDir, "backup_b.vault").delete()
        File(context.cacheDir, "src_doc_a_cross.txt").delete()
        File(context.cacheDir, "src_doc_b_cross.txt").delete()
        File(context.cacheDir, "verify_doc_a_cross.txt").delete()
        File(context.cacheDir, "verify_doc_b_cross.txt").delete()
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    @Test
    fun backupAndRestoreRoundTrip() = runTest {
        val filesDir = File(context.filesDir, "files").also { it.mkdirs() }

        val doc1Content = "Content of document one".toByteArray()
        val doc1File = File(filesDir, "doc1.enc")
        val doc1Src = File(context.cacheDir, "src_doc1.txt").apply { writeBytes(doc1Content) }
        val doc1Iv = fileEncryptor.encrypt(doc1Src, doc1File, masterKey)
        doc1Src.delete()

        val doc2Content = "Content of document two with more data".toByteArray()
        val doc2File = File(filesDir, "doc2.enc")
        val doc2Src = File(context.cacheDir, "src_doc2.txt").apply { writeBytes(doc2Content) }
        val doc2Iv = fileEncryptor.encrypt(doc2Src, doc2File, masterKey)
        doc2Src.delete()

        val doc1 = Document(
            id = "bk-doc-1",
            title = "Document One",
            fileName = "doc1.txt",
            mimeType = "text/plain",
            filePath = doc1File.absolutePath,
            fileSize = doc1Content.size.toLong(),
            encryptionIv = doc1Iv,
        )
        val doc2 = Document(
            id = "bk-doc-2",
            title = "Document Two",
            fileName = "doc2.txt",
            mimeType = "text/plain",
            filePath = doc2File.absolutePath,
            fileSize = doc2Content.size.toLong(),
            encryptionIv = doc2Iv,
        )
        dao.insert(doc1)
        dao.insert(doc2)

        assertEquals(2, dao.getAllDocuments().first().size)
        assertTrue(doc1File.exists())
        assertTrue(doc2File.exists())

        val backupFile = File(context.cacheDir, "test_backup.backup")
        val exported = backupManager.exportBackup(backupFile, TEST_PASSWORD)
        assertTrue("Backup export should succeed", exported)
        assertTrue("Backup file should exist", backupFile.exists())
        assertTrue("Backup file should have content", backupFile.length() > 0)

        // Delete everything locally but keep the DB open — merge import inserts
        // backup records into the current database and Room flows auto-refresh.
        dao.deleteAll()
        doc1File.delete()
        doc2File.delete()

        assertEquals(0, dao.getAllDocuments().first().size)
        assertFalse(doc1File.exists())
        assertFalse(doc2File.exists())

        val imported = backupManager.importBackup(backupFile, TEST_PASSWORD)
        assertTrue("Backup import should succeed", imported)

        val restoredDocs = dao.getAllDocuments().first()
        assertEquals("All documents should be restored", 2, restoredDocs.size)

        val restored1 = restoredDocs.find { it.id == "bk-doc-1" }
        assertNotNull("Document 1 should exist", restored1)
        assertEquals("Document One", restored1!!.title)
        assertEquals("text/plain", restored1.mimeType)
        assertNotNull("encryptionIv should be present", restored1.encryptionIv)

        val restored2 = restoredDocs.find { it.id == "bk-doc-2" }
        assertNotNull("Document 2 should exist", restored2)
        assertEquals("Document Two", restored2!!.title)

        val restoredFile1 = File(restored1.filePath)
        assertTrue("Encrypted file for doc1 should exist after restore", restoredFile1.exists())

        val restoredFile2 = File(restored2.filePath)
        assertTrue("Encrypted file for doc2 should exist after restore", restoredFile2.exists())

        val decrypted1 = File(context.cacheDir, "verify_doc1.txt")
        fileEncryptor.decrypt(restoredFile1, decrypted1, masterKey, restored1.encryptionIv!!)
        assertArrayEquals(doc1Content, decrypted1.readBytes())
        decrypted1.delete()

        val decrypted2 = File(context.cacheDir, "verify_doc2.txt")
        fileEncryptor.decrypt(restoredFile2, decrypted2, masterKey, restored2.encryptionIv!!)
        assertArrayEquals(doc2Content, decrypted2.readBytes())
        decrypted2.delete()
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    @Test
    fun backupAndRestoreCrossDevice() = runTest {
        val filesDir = File(context.filesDir, "files").also { it.mkdirs() }

        val doc1Content = "Cross-device doc".toByteArray()
        val doc1File = File(filesDir, "cd1.enc")
        val doc1Src = File(context.cacheDir, "src_cd1.txt").apply { writeBytes(doc1Content) }
        val doc1Iv = fileEncryptor.encrypt(doc1Src, doc1File, masterKey)
        doc1Src.delete()

        val doc1 = Document(
            id = "cd-doc-1",
            title = "Cross Device Doc",
            fileName = "cd.txt",
            mimeType = "text/plain",
            filePath = doc1File.absolutePath,
            fileSize = doc1Content.size.toLong(),
            encryptionIv = doc1Iv,
        )
        dao.insert(doc1)
        assertEquals(1, dao.getAllDocuments().first().size)

        val backupFile = File(context.cacheDir, "test_backup.backup")
        assertTrue(backupManager.exportBackup(backupFile, TEST_PASSWORD))
        assertTrue(backupFile.exists())

        db.close()

        // Simulate a fresh install: wipe everything and import the backup.
        context.getDatabasePath("docwallet.db").let { dbFile ->
            dbFile.delete()
            File(dbFile.parentFile, "docwallet.db-wal").delete()
            File(dbFile.parentFile, "docwallet.db-shm").delete()
        }
        File(context.filesDir, "files").deleteRecursively()
        File(context.filesDir, "encryption").deleteRecursively()

        // On fresh install there is no master key yet.
        assertTrue(encryptionManager.isFirstLaunch())

        // Clear in-memory cached master key so fresh-install path is exercised.
        encryptionManager.lock()

        // Import with a fresh BackupManager that has no database reference.
        val freshBackupManager = BackupManager(context, encryptionManager, { null }, DeterministicHasher())
        assertTrue("Backup import should succeed on fresh install", freshBackupManager.importBackup(backupFile, TEST_PASSWORD))

        // Key files should be restored from the backup.
        val encryptionDir = File(context.filesDir, "encryption")
        assertTrue("wrapped_master_key should be restored", File(encryptionDir, "wrapped_master_key").exists())
        assertTrue("salt should be restored", File(encryptionDir, "salt").exists())

        // Now getMasterKeyForSession should return the original master key
        // (import verified the password and set up device key for daily unlock).
        val restoredMasterKey = encryptionManager.getMasterKeyForSession()
        assertNotNull("Master key should be recoverable after restore", restoredMasterKey)
        assertArrayEquals("Restored master key should match original", masterKey, restoredMasterKey)

        // Open the database with the restored key and verify data.
        val restoredDb = DocWalletDatabase.create(context, restoredMasterKey!!)
        val restoredDao = restoredDb.documentDao()
        val docs = restoredDao.getAllDocuments().first()
        assertEquals(1, docs.size)
        assertEquals("cd-doc-1", docs[0].id)
        assertEquals("Cross Device Doc", docs[0].title)

        // Files should also be restored.
        val restoredFile = File(docs[0].filePath)
        assertTrue("Encrypted file should exist", restoredFile.exists())

        restoredDb.close()
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    @Test
    fun exportWithWrongPasswordFails() = runTest {
        val filesDir = File(context.filesDir, "files").also { it.mkdirs() }

        val docContent = "Wrong password test".toByteArray()
        val docFile = File(filesDir, "wp.enc")
        val docSrc = File(context.cacheDir, "src_wp.txt").apply { writeBytes(docContent) }
        val docIv = fileEncryptor.encrypt(docSrc, docFile, masterKey)
        docSrc.delete()

        val doc = Document(
            id = "wp-doc-1",
            title = "Wrong Password Doc",
            fileName = "wp.txt",
            mimeType = "text/plain",
            filePath = docFile.absolutePath,
            fileSize = docContent.size.toLong(),
            encryptionIv = docIv,
        )
        dao.insert(doc)
        assertEquals(1, dao.getAllDocuments().first().size)

        val backupFile = File(context.cacheDir, "test_backup.backup")
        assertTrue(backupManager.exportBackup(backupFile, TEST_PASSWORD))

        // Try importing with the wrong password.
        val result = backupManager.importBackup(backupFile, "wrong-password-123")
        assertFalse("Import with wrong password should fail", result)
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    @Test
    fun backupAfterUninstallReinstall() = runTest {
        val filesDir = File(context.filesDir, "files").also { it.mkdirs() }

        // --- First install: create data and export backup ---
        val docContent = "Data from first install after reinstall".toByteArray()
        val docFile = File(filesDir, "ri1.enc")
        val docSrc = File(context.cacheDir, "src_ri1.txt").apply { writeBytes(docContent) }
        val docIv = fileEncryptor.encrypt(docSrc, docFile, masterKey)
        docSrc.delete()

        val doc = Document(
            id = "ri-doc-1",
            title = "Reinstall Doc",
            fileName = "ri.txt",
            mimeType = "text/plain",
            filePath = docFile.absolutePath,
            fileSize = docContent.size.toLong(),
            encryptionIv = docIv,
        )
        dao.insert(doc)
        assertEquals(1, dao.getAllDocuments().first().size)

        val backupFile = File(context.cacheDir, "test_backup.backup")
        assertTrue(backupManager.exportBackup(backupFile, TEST_PASSWORD))
        db.close()

        // --- Uninstall: wipe everything ---
        context.getDatabasePath("docwallet.db").let { dbFile ->
            dbFile.delete()
            File(dbFile.parentFile, "docwallet.db-wal").delete()
            File(dbFile.parentFile, "docwallet.db-shm").delete()
        }
        File(context.filesDir, "files").deleteRecursively()
        File(context.filesDir, "encryption").deleteRecursively()

        // --- Reinstall: fresh EncryptionManager with a *different*
        //     TestKeyStoreCryptographer (simulates lost Android KeyStore key).
        //     No initializeDeviceKeyMode() — app has never launched before. ---
        val freshEm = EncryptionManager(context, DeterministicHasher(), TestKeyStoreCryptographer())
        assertTrue("Should be first launch after reinstall", freshEm.isFirstLaunch())

        val freshBm = BackupManager(context, freshEm, { null }, DeterministicHasher())
        assertTrue("Import should succeed after reinstall", freshBm.importBackup(backupFile, TEST_PASSWORD))

        // Keys restored from the backup — master key is recoverable via the
        // password path (wrapped_master_key + salt). A new device key is set up
        // for daily unlock on the fresh device.
        val restoredKey = freshEm.getMasterKeyForSession()
        assertNotNull("Master key should be recoverable", restoredKey)
        assertArrayEquals("Master key must match the original", masterKey, restoredKey)

        // Data intact
        val restoredDb = DocWalletDatabase.create(context, restoredKey!!)
        val restoredDao = restoredDb.documentDao()
        val docs = restoredDao.getAllDocuments().first()
        assertEquals(1, docs.size)
        assertEquals("ri-doc-1", docs[0].id)

        val restoredFile = File(docs[0].filePath)
        assertTrue("Encrypted file should exist", restoredFile.exists())

        val decrypted = File(context.cacheDir, "verify_ri1.txt")
        fileEncryptor.decrypt(restoredFile, decrypted, restoredKey, docs[0].encryptionIv!!)
        assertArrayEquals(docContent, decrypted.readBytes())
        decrypted.delete()

        restoredDb.close()
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    @Test
    fun importBackupWithDifferentMasterKeyAndPasswordIntoExistingContent() = runTest {
        val filesDir = File(context.filesDir, "files").also { it.mkdirs() }

        // --- Device A: existing content ---
        val docAContent = "Existing document on device A".toByteArray()
        val docAFile = File(filesDir, "doc_a_cross.enc")
        val docASrc = File(context.cacheDir, "src_doc_a_cross.txt").apply { writeBytes(docAContent) }
        val docAIv = fileEncryptor.encrypt(docASrc, docAFile, masterKey)
        docASrc.delete()

        val docA = Document(
            id = "cross-a-doc-1",
            title = "Device A Doc",
            fileName = "a.txt",
            mimeType = "text/plain",
            filePath = docAFile.absolutePath,
            fileSize = docAContent.size.toLong(),
            encryptionIv = docAIv,
        )
        dao.insert(docA)

        // --- Save A's encryption files and close A's DB ---
        val encryptionDir = File(context.filesDir, "encryption")
        val savedEncryption = File(context.cacheDir, "saved_encryption_a").also {
            it.deleteRecursively()
            it.mkdirs()
        }
        encryptionDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                val dest = File(savedEncryption, file.relativeTo(encryptionDir).path)
                dest.parentFile?.mkdirs()
                file.copyTo(dest, overwrite = true)
            }
        }
        db.close()
        context.getDatabasePath("docwallet.db").let { dbFile ->
            dbFile.delete()
            File(dbFile.parentFile, "docwallet.db-wal").delete()
            File(dbFile.parentFile, "docwallet.db-shm").delete()
        }

        // --- Device B: different master key AND different password ---
        // B's DB uses the same file path ("docwallet.db") since exportBackup() hardcodes it.
        encryptionDir.deleteRecursively()
        encryptionManager.lock()
        encryptionManager.initializeWithPassword(TEST_PASSWORD_2)
        val masterKeyB = encryptionManager.getMasterKeyForSession()
        assertNotNull("MK_B should be available", masterKeyB)
        assertFalse("MK_B differs from MK_A", masterKey.contentEquals(masterKeyB))

        val dbB = DocWalletDatabase.create(context, masterKeyB!!)
        val daoB = dbB.documentDao()

        val docBContent = "Document from device B".toByteArray()
        val docBFile = File(filesDir, "doc_b_cross.enc")
        val docBSrc = File(context.cacheDir, "src_doc_b_cross.txt").apply { writeBytes(docBContent) }
        val docBIv = fileEncryptor.encrypt(docBSrc, docBFile, masterKeyB)
        docBSrc.delete()

        val docB = Document(
            id = "cross-b-doc-1",
            title = "Device B Doc",
            fileName = "b.txt",
            mimeType = "text/plain",
            filePath = docBFile.absolutePath,
            fileSize = docBContent.size.toLong(),
            encryptionIv = docBIv,
        )
        daoB.insert(docB)

        // Export backup from B
        val backupManagerB = BackupManager(context, encryptionManager, { dbB }, DeterministicHasher())
        val backupFile = File(context.cacheDir, "backup_b.vault")
        assertTrue("Export from B should succeed", backupManagerB.exportBackup(backupFile, TEST_PASSWORD_2))
        assertTrue("Backup file should exist", backupFile.exists())

        dbB.close()
        context.getDatabasePath("docwallet.db").let { dbFile ->
            dbFile.delete()
            File(dbFile.parentFile, "docwallet.db-wal").delete()
            File(dbFile.parentFile, "docwallet.db-shm").delete()
        }

        // Remove B's encrypted files so they don't collide with A's import (same device)
        docBFile.delete()
        File(filesDir, "doc_b_cross.enc").delete()

        // --- Restore A's encryption files and re-create A's DB ---
        encryptionDir.deleteRecursively()
        savedEncryption.walkTopDown().forEach { file ->
            if (file.isFile) {
                val dest = File(encryptionDir, file.relativeTo(savedEncryption).path)
                dest.parentFile?.mkdirs()
                file.copyTo(dest, overwrite = true)
            }
        }
        encryptionManager.lock()
        val restoredMasterKeyA = encryptionManager.getMasterKeyForSession()
        assertNotNull("MK_A should be recoverable after restore", restoredMasterKeyA)
        assertArrayEquals("Restored MK_A matches original", masterKey, restoredMasterKeyA)

        val dbA2 = DocWalletDatabase.create(context, restoredMasterKeyA!!)
        val daoA2 = dbA2.documentDao()
        daoA2.insert(docA)
        backupManager = BackupManager(context, encryptionManager, { dbA2 }, DeterministicHasher())

        // --- Import B's backup into A ---
        assertTrue("Import from B into A should succeed", backupManager.importBackup(backupFile, TEST_PASSWORD_2))

        // --- Verify merged content ---
        val docs = daoA2.getAllDocuments().first()
        assertEquals("Both devices' documents should be present", 2, docs.size)

        val restoredA = docs.find { it.id == "cross-a-doc-1" }
        assertNotNull("Device A doc exists", restoredA)
        assertEquals("Device A Doc", restoredA!!.title)
        assertTrue("Doc A encrypted file exists", File(restoredA.filePath).exists())

        val restoredB = docs.find { it.id == "cross-b-doc-1" }
        assertNotNull("Device B doc exists", restoredB)
        assertEquals("Device B Doc", restoredB!!.title)
        assertTrue("Doc B encrypted file exists", File(restoredB.filePath).exists())

        // Decrypt doc A with MK_A
        val decryptedA = File(context.cacheDir, "verify_doc_a_cross.txt")
        fileEncryptor.decrypt(
            File(restoredA.filePath), decryptedA,
            masterKey, restoredA.encryptionIv!!,
        )
        assertArrayEquals("Doc A content matches", docAContent, decryptedA.readBytes())
        decryptedA.delete()

        // Decrypt doc B with MK_A (re-encrypted during import)
        val decryptedB = File(context.cacheDir, "verify_doc_b_cross.txt")
        fileEncryptor.decrypt(
            File(restoredB.filePath), decryptedB,
            masterKey, restoredB.encryptionIv!!,
        )
        assertArrayEquals("Doc B content matches", docBContent, decryptedB.readBytes())
        decryptedB.delete()

        // --- reopenDatabase() must work with MK_A (local key preserved) ---
        dbA2.close()
        val reopenedDb = DocWalletDatabase.create(context, masterKey)
        val reopenedDao = reopenedDb.documentDao()
        val reopenedDocs = reopenedDao.getAllDocuments().first()
        assertEquals("Reopened DB should have 2 docs", 2, reopenedDocs.size)
        reopenedDb.close()
    }

    private class TestKeyStoreCryptographer : KeyStoreCryptographer {
        private val aesKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        override fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, aesKey)
            return Pair(cipher.iv, cipher.doFinal(plaintext))
        }

        override fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
            return cipher.doFinal(ciphertext)
        }

        override fun deleteKey() {}
    }

    private class DeterministicHasher : Argon2Hasher {
        override fun hash(
            password: ByteArray,
            salt: ByteArray,
            tCostInIterations: Int,
            mCostInKibibyte: Int,
            parallelism: Int,
            hashLengthInBytes: Int,
        ): ByteArray {
            return ByteArray(hashLengthInBytes) { i ->
                (password.getOrElse(i % password.size) { 0 } +
                 salt.getOrElse(i % salt.size) { 0 } + i).toByte()
            }
        }
    }
}
