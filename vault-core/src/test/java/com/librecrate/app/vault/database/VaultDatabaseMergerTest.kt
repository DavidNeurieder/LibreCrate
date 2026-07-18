package com.librecrate.app.vault.database

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class VaultDatabaseMergerTest {
    private lateinit var current: InMemorySqlHandle
    private lateinit var backup: InMemorySqlHandle
    private lateinit var merger: VaultDatabaseMerger

    @Before
    fun setUp() {
        current = InMemorySqlHandle.create()
        backup = InMemorySqlHandle.create()
        merger = VaultDatabaseMerger()
    }

    @After
    fun tearDown() {
        current.close()
        backup.close()
    }

    @Test
    fun `merge adds new documents to empty database`() {
        insertDocument(backup, "doc1", "Document One")
        insertDocument(backup, "doc2", "Document Two")

        val result = merger.merge(backup, current)

        assertEquals(2, result.documentsAdded)
        assertEquals(0, result.documentsUpdated)
        assertEquals(0, result.documentsConflicted)
        assertEquals(0, result.documentsSkipped)

        val count = current.query("SELECT count(*) FROM documents")
            .use { if (it.moveToNext()) it.getInt(0) else 0 }
        assertEquals(2, count)
    }

    @Test
    fun `merge skips identical document`() {
        val now = System.currentTimeMillis()
        insertDocument(current, "doc1", "Same Doc", fileSize = 100, modifiedAt = now)
        insertDocument(backup, "doc1", "Same Doc", fileSize = 100, modifiedAt = now)

        val result = merger.merge(backup, current)

        assertEquals(0, result.documentsAdded)
        assertEquals(0, result.documentsUpdated)
        assertEquals(0, result.documentsConflicted)
        assertEquals(1, result.documentsSkipped)
    }

    @Test
    fun `merge updates metadata when newer`() {
        val oldTime = 1000L
        val newTime = 2000L
        insertDocument(current, "doc1", "Old Title", fileSize = 100, modifiedAt = oldTime)
        insertDocument(backup, "doc1", "New Title", fileSize = 100, modifiedAt = newTime)

        val result = merger.merge(backup, current)

        assertEquals(0, result.documentsAdded)
        assertEquals(1, result.documentsUpdated)
        assertEquals(0, result.documentsConflicted)
        assertEquals(0, result.documentsSkipped)

        val title = current.query("SELECT title FROM documents WHERE id = 'doc1'")
            .use { if (it.moveToNext()) it.getString(0) else null }
        assertEquals("New Title", title)
    }

    @Test
    fun `merge creates conflict when content differs`() {
        insertDocument(current, "doc1", "Original", fileSize = 100, modifiedAt = 1000)
        insertDocument(backup, "doc1", "Original", fileSize = 200, modifiedAt = 1000)

        val result = merger.merge(backup, current)

        assertEquals(0, result.documentsAdded)
        assertEquals(0, result.documentsUpdated)
        assertEquals(1, result.documentsConflicted)
        assertEquals(0, result.documentsSkipped)

        val totalCount = current.query("SELECT count(*) FROM documents")
            .use { if (it.moveToNext()) it.getInt(0) else 0 }
        assertEquals(2, totalCount)

        val conflictCount = current.query("SELECT count(*) FROM documents WHERE is_conflict = 1")
            .use { if (it.moveToNext()) it.getInt(0) else 0 }
        assertEquals(1, conflictCount)

        val original = current.query(
            "SELECT is_conflict FROM documents WHERE id = 'doc1'"
        ).use { if (it.moveToNext()) it.getInt(0) else -1 }
        assertEquals(1, original)  // original flagged as conflict source
    }

    @Test
    fun `merge handles multiple documents correctly`() {
        insertDocument(backup, "new1", "New One")
        insertDocument(backup, "new2", "New Two")
        insertDocument(current, "existing", "Existing", fileSize = 50, modifiedAt = 500)
        insertDocument(backup, "existing", "Updated Existing", fileSize = 50, modifiedAt = 1500)
        insertDocument(backup, "conflict", "Conflict", fileSize = 200, modifiedAt = 1000)
        insertDocument(current, "conflict", "Conflict", fileSize = 100, modifiedAt = 1000)
        insertDocument(current, "same", "Same", fileSize = 75, modifiedAt = 800)
        insertDocument(backup, "same", "Same", fileSize = 75, modifiedAt = 800)

        val result = merger.merge(backup, current)

        assertEquals(2, result.documentsAdded)
        assertEquals(1, result.documentsUpdated)
        assertEquals(1, result.documentsConflicted)
        assertEquals(1, result.documentsSkipped)
    }

    @Test
    fun `merge adds new collections and tags`() {
        backup.execSQL(
            "INSERT INTO collections(id, name, icon, sort_order) VALUES (?, ?, ?, ?)",
            arrayOf("col1", "Docs", "folder", 0)
        )
        backup.execSQL(
            "INSERT INTO tags(id, name, color) VALUES (?, ?, ?)",
            arrayOf("tag1", "important", 0xFF0000)
        )

        val result = merger.merge(backup, current)

        assertEquals(1, result.collectionsAdded)
        assertEquals(1, result.tagsAdded)
    }

    @Test
    fun `empty backup produces zero counts`() {
        val result = merger.merge(backup, current)
        assertEquals(0, result.documentsAdded)
        assertEquals(0, result.documentsUpdated)
        assertEquals(0, result.documentsConflicted)
        assertEquals(0, result.documentsSkipped)
        assertEquals(0, result.collectionsAdded)
        assertEquals(0, result.tagsAdded)
    }

    @Test
    fun `MergeResult hasConflicts is true when conflicts exist`() {
        val noConflicts = MergeResult(1, 0, 0, 0, 0, 0)
        assertFalse(noConflicts.hasConflicts)

        val withConflicts = MergeResult(0, 0, 2, 0, 0, 0)
        assertTrue(withConflicts.hasConflicts)
    }

    @Test
    fun `MergeResult totalProcessed sums all document counts`() {
        val r = MergeResult(documentsAdded = 3, documentsUpdated = 1, documentsConflicted = 2, documentsSkipped = 4, collectionsAdded = 0, tagsAdded = 0)
        assertEquals(10, r.totalProcessed)
    }

    private fun insertDocument(
        db: SqlHandle,
        id: String,
        title: String,
        fileSize: Long = 100,
        modifiedAt: Long = System.currentTimeMillis(),
    ) {
        val now = System.currentTimeMillis()
        db.execSQL(
            """INSERT INTO documents(
                id, title, file_name, mime_type, file_path, file_size, page_count,
                author, description, imported_at, last_opened_at, modified_at,
                is_favorite, is_conflict, conflict_with,
                current_page, reading_position
            ) VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, 0, 0, NULL, 0, NULL)""".trimIndent(),
            arrayOf(
                id, title, "$id.txt", "text/plain", "files/$id.txt", fileSize,
                "Author", "Test document", now, now, modifiedAt,
            )
        )
    }
}
