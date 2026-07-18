package com.librecrate.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.librecrate.app.data.model.Document
import net.sqlcipher.database.SupportFactory

@RunWith(AndroidJUnit4::class)
class FtsSearchTest {

    private lateinit var db: LibreCrateDatabase
    private lateinit var dao: DocumentDao

    private val ftsQuery =
        "SELECT d.id, d.title, d.file_name, d.mime_type, d.file_size, d.page_count, d.author, d.description, d.thumbnail_path, d.imported_at, d.last_opened_at, d.is_favorite, d.collection_id, d.barcode_format, d.barcode_value, d.current_page, d.reading_position FROM documents d INNER JOIN documents_fts fts ON d.rowid = fts.rowid WHERE documents_fts MATCH ? ORDER BY rank"

    private val highlightQuery =
        "SELECT d.id, d.title, d.mime_type, d.page_count, d.author, d.thumbnail_path, d.text_content, highlight(documents_fts, 3, '\u0001', '\u0002') AS highlight_content FROM documents d INNER JOIN documents_fts fts ON d.rowid = fts.rowid WHERE documents_fts MATCH ? ORDER BY rank"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val passphrase = ByteArray(32).also { it[0] = 1 }
        val factory = SupportFactory(passphrase, null, false)
        db = Room.inMemoryDatabaseBuilder(
            context,
            LibreCrateDatabase::class.java
        )
            .openHelperFactory(factory)
            .allowMainThreadQueries()
            .addCallback(LibreCrateDatabase.FTS_CALLBACK)
            .build()
        dao = db.documentDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun searchByTitle() = runBlocking {
        dao.insert(Document(id = "1", title = "Quick Brown Fox"))
        dao.insert(Document(id = "2", title = "Lazy Dog"))

        val results = dao.searchDocuments(
            SimpleSQLiteQuery(ftsQuery, arrayOf("Quick*"))
        ).first()

        assertEquals(1, results.size)
        assertEquals("1", results[0].id)
    }

    @Test
    fun searchByTextContent() = runBlocking {
        dao.insert(
            Document(
                id = "1",
                title = "Doc One",
                textContent = "The quick brown fox jumps over the lazy dog."
            )
        )
        dao.insert(
            Document(
                id = "2",
                title = "Doc Two",
                textContent = "Nothing about foxes here."
            )
        )

        val results = dao.searchDocuments(
            SimpleSQLiteQuery(ftsQuery, arrayOf("fox*"))
        ).first()

        assertEquals(2, results.size)
    }

    @Test
    fun searchByAuthor() = runBlocking {
        dao.insert(Document(id = "1", title = "Book", author = "Jane Austen"))
        dao.insert(Document(id = "2", title = "Article", author = "John Doe"))

        val results = dao.searchDocuments(
            SimpleSQLiteQuery(ftsQuery, arrayOf("Jane*"))
        ).first()

        assertEquals(1, results.size)
        assertEquals("1", results[0].id)
    }

    @Test
    fun searchMultiplePrefixTerms() = runBlocking {
        dao.insert(
            Document(
                id = "1",
                title = "Gardening Guide",
                textContent = "How to grow tomatoes and carrots."
            )
        )
        dao.insert(
            Document(
                id = "2",
                title = "Cooking Book",
                textContent = "Recipes for delicious soups."
            )
        )

        val results = dao.searchDocuments(
            SimpleSQLiteQuery(ftsQuery, arrayOf("grow* tomato*"))
        ).first()

        assertEquals(1, results.size)
        assertEquals("1", results[0].id)
    }

    @Test
    fun searchNoMatchesReturnsEmpty() = runBlocking {
        dao.insert(Document(id = "1", title = "Something", textContent = "irrelevant text"))

        val results = dao.searchDocuments(
            SimpleSQLiteQuery(ftsQuery, arrayOf("zzzzz*"))
        ).first()

        assertTrue(results.isEmpty())
    }

    @Test
    fun searchAutoIndexesNewDocumentsViaTrigger() = runBlocking {
        dao.insert(
            Document(
                id = "2",
                title = "Newly Inserted",
                textContent = "Fresh content for searching"
            )
        )

        val results = dao.searchDocuments(
            SimpleSQLiteQuery(ftsQuery, arrayOf("fresh*"))
        ).first()

        assertEquals(1, results.size)
        assertEquals("2", results[0].id)
    }

    @Test
    fun searchAfterUpdateReflectsNewContentViaTrigger() = runBlocking {
        dao.insert(
            Document(
                id = "1",
                title = "Original Title",
                textContent = "Original content"
            )
        )

        val beforeUpdate = dao.searchDocuments(
            SimpleSQLiteQuery(ftsQuery, arrayOf("updated*"))
        ).first()
        assertTrue(beforeUpdate.isEmpty())

        dao.update(
            Document(
                id = "1",
                title = "Updated Title",
                textContent = "Updated content now searchable"
            )
        )

        val afterUpdate = dao.searchDocuments(
            SimpleSQLiteQuery(ftsQuery, arrayOf("updated*"))
        ).first()

        assertEquals(1, afterUpdate.size)
        assertEquals("1", afterUpdate[0].id)
    }

    @Test
    fun searchAfterDeleteRemovesFromFtsViaTrigger() = runBlocking {
        dao.insert(
            Document(
                id = "1",
                title = "To Delete",
                textContent = "Will be deleted"
            )
        )

        val beforeDelete = dao.searchDocuments(
            SimpleSQLiteQuery(ftsQuery, arrayOf("delete*"))
        ).first()
        assertEquals(1, beforeDelete.size)

        dao.deleteById("1")

        val afterDelete = dao.searchDocuments(
            SimpleSQLiteQuery(ftsQuery, arrayOf("delete*"))
        ).first()
        assertTrue(afterDelete.isEmpty())
    }

    @Test
    fun searchIgnoresNullTextContent() = runBlocking {
        dao.insert(
            Document(
                id = "1",
                title = "Book",
                textContent = "This document has text content"
            )
        )
        dao.insert(
            Document(
                id = "2",
                title = "Empty Doc",
                textContent = null
            )
        )

        val results = dao.searchDocuments(
            SimpleSQLiteQuery(ftsQuery, arrayOf("text*"))
        ).first()

        assertEquals(1, results.size)
        assertEquals("1", results[0].id)
    }

    @Test
    fun highlightReturnsContentWithMarkers() = runBlocking {
        dao.insert(
            Document(
                id = "1",
                title = "Animal Facts",
                textContent = "The quick brown fox jumps over the lazy dog."
            )
        )

        val results = dao.searchDocumentsWithOffsets(
            SimpleSQLiteQuery(highlightQuery, arrayOf("fox*"))
        ).first()

        assertEquals(1, results.size)
        assertEquals("1", results[0].id)
        assertTrue("highlightContent should contain sentinel markers", results[0].highlightContent.contains("\u0001"))
        assertTrue("highlightContent should contain match text", results[0].highlightContent.contains("fox"))
        assertEquals("textContent should be present", results[0].textContent, results[0].highlightContent.replace("\u0001", "").replace("\u0002", ""))
    }

    @Test
    fun highlightIsEmptyWhenNoMatch() = runBlocking {
        dao.insert(
            Document(
                id = "1",
                title = "Something",
                textContent = "irrelevant text"
            )
        )

        val results = dao.searchDocumentsWithOffsets(
            SimpleSQLiteQuery(highlightQuery, arrayOf("zzzzz*"))
        ).first()

        assertTrue(results.isEmpty())
    }

    @Test
    fun highlightWorksWithTitleMatch() = runBlocking {
        dao.insert(
            Document(
                id = "1",
                title = "Foxes are quick animals",
                textContent = "This document contains interesting animal facts."
            )
        )

        val results = dao.searchDocumentsWithOffsets(
            SimpleSQLiteQuery(highlightQuery, arrayOf("fox*"))
        ).first()

        assertEquals(1, results.size)
        assertEquals("1", results[0].id)
    }

    @Test
    fun highlightIsOrderedByRelevance() = runBlocking {
        dao.insert(
            Document(
                id = "1",
                title = "Doc One",
                textContent = "The fox is quick."
            )
        )
        dao.insert(
            Document(
                id = "2",
                title = "Doc Two",
                textContent = "Fox facts for fox lovers."
            )
        )

        val results = dao.searchDocumentsWithOffsets(
            SimpleSQLiteQuery(highlightQuery, arrayOf("fox*"))
        ).first()

        assertEquals(2, results.size)
        assertEquals("2", results[0].id)
    }

    @Test
    fun highlightAfterUpdateShowsNewContent() = runBlocking {
        dao.insert(
            Document(
                id = "1",
                title = "Original",
                textContent = "Original content no animals"
            )
        )

        val before = dao.searchDocumentsWithOffsets(
            SimpleSQLiteQuery(highlightQuery, arrayOf("fox*"))
        ).first()
        assertTrue(before.isEmpty())

        dao.update(
            Document(
                id = "1",
                title = "Original",
                textContent = "Updated content about foxes and nature"
            )
        )

        val after = dao.searchDocumentsWithOffsets(
            SimpleSQLiteQuery(highlightQuery, arrayOf("fox*"))
        ).first()

        assertEquals(1, after.size)
        assertEquals("1", after[0].id)
        assertTrue("highlightContent should contain sentinel markers", after[0].highlightContent.contains("\u0001"))
        assertTrue("highlightContent should contain match text", after[0].highlightContent.contains("fox"))
    }

    @Test
    fun highlightReturnsMultipleMatches() = runBlocking {
        dao.insert(
            Document(
                id = "1",
                title = "Fox Facts",
                textContent = "A fox is a clever animal. The red fox lives in forests."
            )
        )

        val results = dao.searchDocumentsWithOffsets(
            SimpleSQLiteQuery(highlightQuery, arrayOf("fox*"))
        ).first()

        assertEquals(1, results.size)
        val count = results[0].highlightContent.count { it == '\u0001' }
        assertTrue("Expected at least 2 matches, got $count", count >= 2)
    }

    @Test
    fun highlightWithPageMarkers() = runBlocking {
        dao.insert(
            Document(
                id = "1",
                title = "Multi-Page Doc",
                textContent = "This is page one content with the target fox.[PAGE=1]Page two also mentions the fox.[PAGE=2]Page three has different text."
            )
        )

        val results = dao.searchDocumentsWithOffsets(
            SimpleSQLiteQuery(highlightQuery, arrayOf("fox*"))
        ).first()

        assertEquals(1, results.size)
        val hl = results[0].highlightContent
        val text = results[0].textContent
        assertTrue(hl.contains("\u0001"))

        val markerPositions = mutableListOf<Int>()
        var searchIdx = 0
        while (true) {
            val pos = text.indexOf("[PAGE=", searchIdx)
            if (pos == -1) break
            markerPositions.add(pos)
            searchIdx = pos + 1
        }
        assertEquals("Expected 2 page markers", 2, markerPositions.size)

        var rawOffset = 0
        val matchStarts = mutableListOf<Int>()
        for (ch in hl) {
            when (ch) {
                '\u0001' -> matchStarts.add(rawOffset)
                '\u0002' -> { }
                else -> rawOffset++
            }
        }
        assertTrue("Expected at least 2 matches", matchStarts.size >= 2)
        for (start in matchStarts) {
            val pageBefore = markerPositions.count { it < start }
            val expectedPage = pageBefore + 1
            assertTrue("Match at offset $start should be on page $expectedPage", expectedPage in 1..2)
        }
    }

    @Test
    fun highlightWithSectionMarkers() = runBlocking {
        dao.insert(
            Document(
                id = "1",
                title = "Multi-Section EPUB",
                textContent = "Section one introduction.[SECTION=0]Section two with the fox word.[SECTION=1]Section three also has fox.[SECTION=2]"
            )
        )

        val results = dao.searchDocumentsWithOffsets(
            SimpleSQLiteQuery(highlightQuery, arrayOf("fox*"))
        ).first()

        assertEquals(1, results.size)
        val hl = results[0].highlightContent
        val text = results[0].textContent
        assertTrue(hl.contains("\u0001"))

        val sectionPositions = mutableListOf<Int>()
        var searchIdx = 0
        while (true) {
            val pos = text.indexOf("[SECTION=", searchIdx)
            if (pos == -1) break
            sectionPositions.add(pos)
            searchIdx = pos + 1
        }
        assertEquals("Expected 3 section markers", 3, sectionPositions.size)

        var rawOffset = 0
        val matchStarts = mutableListOf<Int>()
        for (ch in hl) {
            when (ch) {
                '\u0001' -> matchStarts.add(rawOffset)
                '\u0002' -> { }
                else -> rawOffset++
            }
        }
        assertTrue("Expected at least 2 matches", matchStarts.size >= 2)
        for (start in matchStarts) {
            val sectionBefore = sectionPositions.count { it < start }
            assertTrue(
                "Match at offset $start should be in section $sectionBefore",
                sectionBefore in 1..2
            )
        }
    }

    @Test
    fun highlightMultipleMatchesAcrossPages() = runBlocking {
        dao.insert(
            Document(
                id = "1",
                title = "Two Page Doc",
                textContent = "fox on page one.[PAGE=1]fox on page two.[PAGE=2]"
            )
        )

        val results = dao.searchDocumentsWithOffsets(
            SimpleSQLiteQuery(highlightQuery, arrayOf("fox*"))
        ).first()

        assertEquals(1, results.size)
        val hl = results[0].highlightContent
        val text = results[0].textContent

        val pageOnePos = text.indexOf("[PAGE=1]")
        val pageTwoPos = text.indexOf("[PAGE=2]")
        assertTrue(pageOnePos >= 0)
        assertTrue(pageTwoPos > pageOnePos)

        var rawOffset = 0
        val matchStarts = mutableListOf<Int>()
        for (ch in hl) {
            when (ch) {
                '\u0001' -> matchStarts.add(rawOffset)
                '\u0002' -> { }
                else -> rawOffset++
            }
        }

        assertEquals("Expected exactly 2 matches", 2, matchStarts.size)

        val first = matchStarts[0]
        val second = matchStarts[1]
        assertTrue("First match should be before [PAGE=1] at $pageOnePos", first < pageOnePos)
        assertTrue(
            "Second match should be between [PAGE=1] and [PAGE=2]",
            second in (pageOnePos + 1) until pageTwoPos
        )

        val firstPage = text.substring(0, pageOnePos).let { before ->
            Regex("\\[PAGE=\\d+\\]").findAll(before).count() + 1
        }
        val secondPage = text.substring(0, second).let { before ->
            Regex("\\[PAGE=\\d+\\]").findAll(before).count() + 1
        }
        assertEquals("First match should be on page 1", 1, firstPage)
        assertEquals("Second match should be on page 2", 2, secondPage)
    }

    @Test
    fun highlightWithoutMarkers() = runBlocking {
        dao.insert(
            Document(
                id = "1",
                title = "Old Doc",
                textContent = "This document has no page markers but still contains the fox."
            )
        )

        val results = dao.searchDocumentsWithOffsets(
            SimpleSQLiteQuery(highlightQuery, arrayOf("fox*"))
        ).first()

        assertEquals(1, results.size)
        assertTrue(results[0].highlightContent.contains("\u0001"))
        assertFalse("textContent should not contain page markers", results[0].textContent.contains("[PAGE="))
        assertEquals(
            "highlightContent without markers should equal textContent",
            results[0].textContent,
            results[0].highlightContent.replace("\u0001", "").replace("\u0002", "")
        )
    }

    @Test
    fun searchByTextContentWithMarkers() = runBlocking {
        dao.insert(
            Document(
                id = "1",
                title = "Marked Doc",
                textContent = "[PAGE=1]Introduction content.[PAGE=2]The fox is here.[PAGE=3]"
            )
        )

        val results = dao.searchDocuments(
            SimpleSQLiteQuery(ftsQuery, arrayOf("fox*"))
        ).first()

        assertEquals(1, results.size)
        assertEquals("1", results[0].id)
    }

    @Test
    fun highlightMarkerDoesNotBreakTokenization() = runBlocking {
        dao.insert(
            Document(
                id = "1",
                title = "Adjacent Markers",
                textContent = "word[PAGE=1]word fox[PAGE=2]text"
            )
        )

        val results = dao.searchDocumentsWithOffsets(
            SimpleSQLiteQuery(highlightQuery, arrayOf("fox*"))
        ).first()

        assertEquals(1, results.size)
        assertTrue(results[0].highlightContent.contains("\u0001"))
    }
}
