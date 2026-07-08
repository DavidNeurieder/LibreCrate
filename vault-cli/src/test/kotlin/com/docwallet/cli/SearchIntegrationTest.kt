package com.docwallet.cli

import com.docwallet.vault.database.InDocumentMatch
import com.docwallet.vault.database.VaultDatabase
import com.docwallet.vault.database.VaultFtsIndexer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SearchIntegrationTest {
    private lateinit var dbPath: String
    private lateinit var vault: VaultDatabase
    private lateinit var indexer: VaultFtsIndexer

    @Before
    fun setUp() {
        dbPath = File.createTempFile("search", ".db").absolutePath
        vault = VaultDatabase(SqlHandleJdbc.open(dbPath))
        vault.initialize()
        indexer = vault.ftsIndexer

        vault.handle.execSQL(
            """INSERT INTO documents(id, title, file_name, mime_type, file_path, file_size, page_count,
               author, description, imported_at, last_opened_at, is_favorite, text_content)
               VALUES(?, ?, '', ?, '', 0, 0, ?, ?, ?, 0, 0, ?)""".trimIndent(),
            arrayOf("1", "Quick Fox", "text/plain", "John", "A brown fox", 1000L, "quick brown fox")
        )
        vault.handle.execSQL(
            """INSERT INTO documents(id, title, file_name, mime_type, file_path, file_size, page_count,
               author, description, imported_at, last_opened_at, is_favorite, text_content)
               VALUES(?, ?, '', ?, '', 0, 0, ?, ?, ?, 0, 0, ?)""".trimIndent(),
            arrayOf("2", "Lazy Dog", "text/plain", "Jane", "A lazy dog", 1000L, "lazy dog slept")
        )
        vault.handle.execSQL(
            """INSERT INTO documents(id, title, file_name, mime_type, file_path, file_size, page_count,
               author, description, imported_at, last_opened_at, is_favorite, text_content)
               VALUES(?, ?, '', ?, '', 0, 0, ?, ?, ?, 0, 0, ?)""".trimIndent(),
            arrayOf("3", "Unrelated", "text/plain", "Bob", "Something else", 1000L, "nothing in common")
        )
        vault.handle.execSQL(
            """INSERT INTO documents(id, title, file_name, mime_type, file_path, file_size, page_count,
               author, description, imported_at, last_opened_at, is_favorite, text_content)
               VALUES(?, ?, '', ?, '', 0, 0, ?, ?, ?, 0, 0, ?)""".trimIndent(),
            arrayOf("4", "Multi-Page", "text/plain", "Alice", "A doc with pages", 1000L,
                "The quick brown fox jumps.[PAGE=1]The lazy fox sleeps.[PAGE=2]Another fox runs.")
        )
        indexer.indexDocument("1", "Quick Fox", "John", "A brown fox", "quick brown fox")
        indexer.indexDocument("2", "Lazy Dog", "Jane", "A lazy dog", "lazy dog slept")
        indexer.indexDocument("3", "Unrelated", "Bob", "Something else", "nothing in common")
        indexer.indexDocument("4", "Multi-Page", "Alice", "A doc with pages",
            "The quick brown fox jumps.[PAGE=1]The lazy fox sleeps.[PAGE=2]Another fox runs.")
    }

    @After
    fun tearDown() {
        vault.close()
        File(dbPath).delete()
    }

    @Test
    fun `search by title with FTS finds matching document`() {
        val results = vault.searchEngine.search("fox")
        assertTrue(results.any { it.id == "1" })
    }

    @Test
    fun `search by content finds matching document`() {
        val results = vault.searchEngine.search("lazy")
        assertTrue(results.any { it.id == "2" })
    }

    @Test
    fun `search with short query uses LIKE fallback`() {
        val results = vault.searchEngine.search("ox")
        assertEquals(1, results.size)
        assertEquals("1", results[0].id)
    }

    @Test
    fun `search with blank query returns all`() {
        val results = vault.searchEngine.search("")
        assertEquals(4, results.size)
    }

    @Test
    fun `suggestions return matching titles`() {
        val suggestions = vault.searchEngine.getSuggestions("Q")
        assertTrue(suggestions.contains("Quick Fox"))
    }

    @Test
    fun `remove document from FTS`() {
        val before = vault.searchEngine.search("common")
        assertEquals(1, before.size)
        indexer.removeDocument("3")
        val after = vault.searchEngine.search("common")
        assertEquals(0, after.size)
    }

    @Test
    fun `search in document finds matches`() {
        val results = vault.searchEngine.searchInDocument("4", "fox")
        assertEquals(3, results.size)
    }

    @Test
    fun `search in document returns correct page numbers`() {
        val results = vault.searchEngine.searchInDocument("4", "fox")
        assertEquals(3, results.size)
        assertTrue("first match should be on page 0 (before any marker)", results[0].pageNumber == 0)
        assertTrue("second match should be on page 1", results[1].pageNumber == 1)
        assertTrue("third match should be on page 2", results[2].pageNumber == 2)
    }

    @Test
    fun `search in document snippets contain bold tags`() {
        val results = vault.searchEngine.searchInDocument("4", "fox")
        assertTrue(results.all { it.snippet.contains("<b>fox</b>") })
    }

    @Test
    fun `search in document with no match returns empty`() {
        val results = vault.searchEngine.searchInDocument("4", "xyzzy")
        assertEquals(0, results.size)
    }

    @Test
    fun `search in document with blank query returns empty`() {
        val results = vault.searchEngine.searchInDocument("4", "")
        assertEquals(0, results.size)
    }

    @Test
    fun `search in document with unknown id returns empty`() {
        val results = vault.searchEngine.searchInDocument("nonexistent", "fox")
        assertEquals(0, results.size)
    }

    @Test
    fun `search in document works on existing doc without markers`() {
        val results = vault.searchEngine.searchInDocument("1", "quick")
        assertEquals(1, results.size)
        assertTrue(results[0].snippet.contains("<b>quick</b>"))
        assertEquals(0, results[0].pageNumber)
    }
}
