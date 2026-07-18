package com.librecrate.app.cli

import com.librecrate.app.vault.database.DocumentManager
import com.librecrate.app.vault.database.VaultDatabase
import com.librecrate.app.vault.storage.Storage
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class DocumentManagerTest {
    private lateinit var dbPath: String
    private lateinit var vault: VaultDatabase
    private lateinit var storage: Storage
    private lateinit var mgr: DocumentManager

    @Before
    fun setUp() {
        dbPath = File.createTempFile("docman", ".db").absolutePath
        vault = VaultDatabase(SqlHandleJdbc.open(dbPath))
        vault.initialize()
        val baseDir = File(dbPath).parentFile ?: File(".")
        storage = DirectoryStorage(baseDir)
        mgr = DocumentManager(vault.handle, storage)
    }

    @After
    fun tearDown() {
        val paths = try {
            mgr.listDocuments().map { it.filePath }
        } catch (_: Exception) {
            emptyList()
        }
        vault.close()
        File(dbPath).delete()
        for (path in paths) {
            try { storage.delete(path) } catch (_: Exception) {}
        }
    }

    @Test
    fun `importDocument creates document and stores file`() {
        val data = "hello world".toByteArray()
        val doc = mgr.importDocument(
            id = "doc1", title = "Test Doc", file = data,
            mimeType = "text/plain", author = "Tester",
        )
        assertEquals("doc1", doc.id)
        assertEquals("Test Doc", doc.title)
        assertEquals("text/plain", doc.mimeType)
        assertEquals(data.size.toLong(), doc.fileSize)
        assertEquals("Tester", doc.author)
        assertNotNull(doc.encryptionIv)

        val stored = storage.load(doc.filePath)
        assertArrayEquals(data, stored)
    }

    @Test
    fun `listDocuments returns all documents`() {
        mgr.importDocument("a", "Doc A", "aaa".toByteArray(), "text/plain")
        mgr.importDocument("b", "Doc B", "bbb".toByteArray(), "text/plain")
        val docs = mgr.listDocuments()
        assertEquals(2, docs.size)
    }

    @Test
    fun `getDocument returns document by id`() {
        mgr.importDocument("x", "Find Me", "data".toByteArray(), "text/plain")
        val doc = mgr.getDocument("x")
        assertNotNull(doc)
        assertEquals("Find Me", doc!!.title)
    }

    @Test
    fun `getDocument returns null for missing id`() {
        assertNull(mgr.getDocument("nonexistent"))
    }

    @Test
    fun `deleteDocument removes document and file`() {
        val data = "delete me".toByteArray()
        mgr.importDocument("del", "To Delete", data, "text/plain")
        assertNotNull(mgr.getDocument("del"))
        assertTrue(storage.exists("files/del"))
        mgr.deleteDocument("del")
        assertNull(mgr.getDocument("del"))
    }

    @Test
    fun `loadDocumentFile retrieves stored file bytes`() {
        val data = "file content".toByteArray()
        mgr.importDocument("f1", "File Doc", data, "text/plain")
        val loaded = mgr.loadDocumentFile("f1")
        assertArrayEquals(data, loaded)
    }

    @Test
    fun `import with textContent indexes for FTS`() {
        mgr.importDocument(
            id = "fts1", title = "FTS Test", file = "ignored".toByteArray(),
            mimeType = "text/plain", description = "a test description",
            textContent = "the quick brown fox jumps over the lazy dog",
        )
        val results = vault.searchEngine.search("quick")
        assertEquals(1, results.size)
        assertEquals("fts1", results[0].id)
    }
}
