package com.librecrate.app.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librecrate.app.LibreCrateApplication
import com.librecrate.app.data.model.Document
import com.librecrate.app.data.vault.VaultRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FtsSearchTest {

    private lateinit var app: LibreCrateApplication
    private lateinit var vault: VaultRepository
    private val testPassword = "fts_test_password"

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        vault = app.vaultRepository

        runTest {
            wipeDatabase(app)
            app.encryptionManager.initializeWithPassword(testPassword)
            val masterKey = app.encryptionManager.getMasterKeyForSession()
            check(masterKey != null)
            val opened = vault.open(masterKey)
            check(opened) { "vault.open() failed" }
            vault.rebuildFtsIndex()
        }
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

    private suspend fun insert(doc: Document, textContent: String? = null) {
        val ok = vault.addDocumentFull(doc, textContent)
        check(ok) { "addDocumentFull failed for doc ${doc.id}" }
        vault.rebuildFtsIndex()
    }

    @Test
    fun sanityCheckListDocuments() = runTest {
        insert(Document(id = "sanity-1", title = "Sanity Check"))
        val docs = vault.listDocuments()
        assertEquals("document should be listable", 1, docs.size)
        assertEquals("sanity-1", docs[0].id)
    }

    @Test
    fun searchByTitle() = runTest {
        insert(Document(id = "1", title = "Quick Brown Fox"))
        insert(Document(id = "2", title = "Lazy Dog"))

        val results = vault.searchDocuments("quick")
        assertEquals(1, results.size)
        assertEquals("1", results[0].id)
    }

    @Test
    fun searchByTextContent() = runTest {
        insert(Document(id = "1", title = "Doc One"), textContent = "The quick brown fox jumps over the lazy dog.")
        insert(Document(id = "2", title = "Doc Two"), textContent = "Nothing about fox here.")

        val results = vault.searchDocuments("fox")
        assertEquals(2, results.size)
    }

    @Test
    fun searchByAuthor() = runTest {
        insert(Document(id = "1", title = "Book", author = "Jane Austen"))
        insert(Document(id = "2", title = "Article", author = "John Doe"))

        val results = vault.searchDocuments("Jane")
        assertEquals(1, results.size)
        assertEquals("1", results[0].id)
    }

    @Test
    fun searchMultipleTerms() = runTest {
        insert(Document(id = "1", title = "Gardening Guide"), textContent = "How to grow tomatoes and carrots.")
        insert(Document(id = "2", title = "Cooking Book"), textContent = "Recipes for delicious soups.")

        val results = vault.searchDocuments("grow tomatoes")
        assertEquals(1, results.size)
        assertEquals("1", results[0].id)
    }

    @Test
    fun searchNoMatchesReturnsEmpty() = runTest {
        insert(Document(id = "1", title = "Something"), textContent = "irrelevant text")

        val results = vault.searchDocuments("zzzzz")
        assertTrue(results.isEmpty())
    }

    @Test
    fun searchAfterInsertReflectsNewContent() = runTest {
        val beforeUpdate = vault.searchDocuments("updated")
        assertTrue(beforeUpdate.isEmpty())

        insert(Document(id = "2", title = "Updated Title"), textContent = "Updated content now searchable")

        val afterUpdate = vault.searchDocuments("updated")
        assertEquals(1, afterUpdate.size)
        assertEquals("2", afterUpdate[0].id)
    }

    @Test
    fun searchAfterDeleteRemovesFromFts() = runTest {
        insert(Document(id = "1", title = "To Delete"), textContent = "Will be deleted")

        val beforeDelete = vault.searchDocuments("deleted")
        assertEquals(1, beforeDelete.size)

        vault.deleteDocumentFull("1")
        vault.rebuildFtsIndex()

        val afterDelete = vault.searchDocuments("deleted")
        assertTrue(afterDelete.isEmpty())
    }

    @Test
    fun highlightReturnsContentWithMarkers() = runTest {
        insert(Document(id = "1", title = "Animal Facts"), textContent = "The quick brown fox jumps over the lazy dog.")

        val results = vault.searchDocumentsWithSnippet("fox")
        assertEquals(1, results.size)
        assertEquals("1", results[0].id)
        assertTrue("snippet should contain 'fox'", results[0].snippet.contains("fox", ignoreCase = true))
        assertTrue("snippet should use highlight tags", results[0].snippet.contains("<b>") || results[0].snippet.contains("\u0001"))
    }

    @Test
    fun highlightIsEmptyWhenNoMatch() = runTest {
        insert(Document(id = "1", title = "Something"), textContent = "irrelevant text")

        val results = vault.searchDocumentsWithSnippet("zzzzz")
        assertTrue(results.isEmpty())
    }

    @Test
    fun searchByTextContentWithMarkers() = runTest {
        insert(Document(id = "1", title = "Marked Doc"), textContent = "[PAGE=1]Introduction content.[PAGE=2]The fox is here.[PAGE=3]")

        val results = vault.searchDocuments("fox")
        assertEquals(1, results.size)
        assertEquals("1", results[0].id)
    }

    @Test
    fun searchIgnoresNullTextContent() = runTest {
        insert(Document(id = "1", title = "Book"), textContent = "This document has text content")
        insert(Document(id = "2", title = "Empty Doc"), textContent = null)

        val results = vault.searchDocuments("text")
        assertEquals(1, results.size)
        assertEquals("1", results[0].id)
    }

    @Test
    fun highlightReturnsMultipleMatches() = runTest {
        insert(Document(id = "1", title = "Fox Facts"), textContent = "A fox is a clever animal. The red fox lives in forests.")

        val results = vault.searchDocumentsWithSnippet("fox")
        assertEquals(1, results.size)
        assertTrue("snippet should contain fox", results[0].snippet.contains("fox", ignoreCase = true))
    }
}
