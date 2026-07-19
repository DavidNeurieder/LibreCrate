package com.librecrate.app.domain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librecrate.app.LibreCrateApplication
import com.librecrate.app.data.model.Document
import com.librecrate.app.data.vault.VaultRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupManagerTest {

    private lateinit var app: LibreCrateApplication
    private lateinit var vault: VaultRepository
    private lateinit var backupManager: BackupManager
    private val testPassword = "test_vault_password"

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()

        runTest {
            wipeDatabase(app)
            app.encryptionManager.initializeWithPassword(testPassword)
            val masterKey = app.encryptionManager.getMasterKeyForSession()
            check(masterKey != null)
            vault = app.vaultRepository
            val opened = vault.open(masterKey)
            check(opened) { "vault.open() failed" }
        }

        backupManager = BackupManager(app, app.encryptionManager, vault)
    }

    private fun wipeDatabase(context: Context) {
        context.getDatabasePath("librecrate.db").let { dbFile ->
            dbFile.delete()
            File(dbFile.parentFile, "librecrate.db-wal").delete()
            File(dbFile.parentFile, "librecrate.db-shm").delete()
        }
        File(context.filesDir, "files").deleteRecursively()
        File(context.filesDir, "encryption").deleteRecursively()
    }

    @After
    fun tearDown() {
        runTest {
            vault.listDocuments().forEach { vault.deleteDocumentFull(it.id) }
            vault.close()
        }
        app.encryptionManager.lock()
    }

    @Test
    fun backupAndRestoreRoundTrip() = runTest {
        vault.addDocumentFull(
            Document(id = "bk-doc-1", title = "Document One", fileName = "doc1.txt", mimeType = "text/plain"),
            textContent = "Content of document one",
        )
        vault.addDocumentFull(
            Document(id = "bk-doc-2", title = "Document Two", fileName = "doc2.txt", mimeType = "text/plain"),
            textContent = "Content of document two with more data",
        )

        assertEquals(2, vault.listDocuments().size)

        val backupFile = File(app.cacheDir, "test_backup_backupRoundTrip.vault")
        val exported = backupManager.exportBackup(backupFile, testPassword)
        assertTrue("Backup export should succeed", exported)
        assertTrue("Backup file should exist", backupFile.exists())
        assertTrue("Backup file should have content", backupFile.length() > 0)

        vault.listDocuments().forEach { vault.deleteDocumentFull(it.id) }
        vault.close()

        val imported = backupManager.importBackup(backupFile, testPassword)
        assertTrue("Backup import should succeed", imported)

        val masterKey = app.encryptionManager.getMasterKeyForSession()
        assertNotNull("Master key should be recoverable after import", masterKey)
        vault.open(masterKey!!)

        val restoredDocs = vault.listDocuments()
        assertEquals("All documents should be restored", 2, restoredDocs.size)

        val restored1 = restoredDocs.find { it.id == "bk-doc-1" }
        assertNotNull("Document 1 should exist", restored1)
        assertEquals("Document One", restored1!!.title)
        assertEquals("text/plain", restored1.mimeType)

        val restored2 = restoredDocs.find { it.id == "bk-doc-2" }
        assertNotNull("Document 2 should exist", restored2)
        assertEquals("Document Two", restored2!!.title)
    }

    @Test
    fun exportWithWrongPasswordFails() = runTest {
        vault.addDocumentFull(
            Document(id = "wp-doc-1", title = "Wrong Password Doc", fileName = "wp.txt", mimeType = "text/plain"),
            textContent = "Wrong password test",
        )

        val backupFile = File(app.cacheDir, "test_backup_wp.vault")
        assertTrue(backupManager.exportBackup(backupFile, testPassword))

        val result = backupManager.importBackup(backupFile, "wrong-password-123")
        assertFalse("Import with wrong password should fail", result)
    }

    @Test
    fun importBackupWithDifferentPasswordIntoExistingContent() = runTest {
        val masterKeyA = app.encryptionManager.getMasterKeyForSession()!!
        vault.addDocumentFull(
            Document(id = "cross-doc-1", title = "Existing Doc", fileName = "existing.txt", mimeType = "text/plain"),
            textContent = "Existing document",
        )

        val exportPassword = "export_password"
        val backupFile = File(app.cacheDir, "test_backup_cross.vault")
        assertTrue("Export should succeed", backupManager.exportBackup(backupFile, exportPassword))

        vault.listDocuments().forEach { vault.deleteDocumentFull(it.id) }
        vault.close()

        assertTrue("Import with correct password should succeed", backupManager.importBackup(backupFile, exportPassword))

        vault.open(masterKeyA)
        val docs = vault.listDocuments()
        assertEquals("Document should be restored", 1, docs.size)
    }

    @Test
    fun backupUninstallReinstallImport() = runTest {
        vault.addDocumentFull(
            Document(id = "survive-doc-1", title = "Survival Doc", fileName = "survive.txt", mimeType = "text/plain"),
            textContent = "Data survives reinstall",
        )

        val backupFile = File(app.cacheDir, "test_backup_survive.vault")
        assertTrue(backupManager.exportBackup(backupFile, testPassword))

        val masterKey = app.encryptionManager.getMasterKeyForSession()
        assertNotNull(masterKey)

        vault.close()
        vault.filesDir.deleteRecursively()
        vault.encryptionDir.deleteRecursively()

        val freshEm = app.encryptionManager
        freshEm.lock()

        val freshVault = VaultRepository(app)
        val freshBm = BackupManager(app, freshEm, freshVault)

        assertTrue("Import into fresh install should succeed", freshBm.importBackup(backupFile, testPassword))

        // After import, sessionMasterKey is null. Recover it by verifying the password.
        assertTrue("Should verify restored password", freshEm.verifyPassword(testPassword))
        val restoredKey = freshEm.getMasterKeyForSession()
        assertNotNull("Master key recoverable after import", restoredKey)

        freshVault.open(restoredKey!!)
        val docs = freshVault.listDocuments()
        assertEquals(1, docs.size)
        assertEquals("survive-doc-1", docs[0].id)
        assertEquals("Survival Doc", docs[0].title)

        freshVault.close()
    }
}
