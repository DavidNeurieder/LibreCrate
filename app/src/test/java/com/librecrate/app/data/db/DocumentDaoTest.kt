package com.librecrate.app.data.db

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.librecrate.app.data.model.Document

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentDaoTest {

    private lateinit var db: LibreCrateDatabase
    private lateinit var dao: DocumentDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            LibreCrateDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
        dao = db.documentDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insert and getById returns document`() = runTest {
        val doc = Document(id = "1", title = "Test")
        dao.insert(doc)
        val retrieved = dao.getDocumentById("1")
        assertEquals(doc.id, retrieved?.id)
        assertEquals(doc.title, retrieved?.title)
    }

    @Test
    fun `insert and getAllDocuments returns list`() = runTest {
        val doc1 = Document(id = "1", title = "A")
        val doc2 = Document(id = "2", title = "B")
        dao.insert(doc1)
        dao.insert(doc2)

        val all = dao.getAllDocuments().first()
        assertEquals(2, all.size)
    }

    @Test
    fun `update document changes fields`() = runTest {
        val doc = Document(id = "1", title = "Original")
        dao.insert(doc)

        val updated = doc.copy(title = "Updated")
        dao.update(updated)

        val retrieved = dao.getDocumentById("1")
        assertEquals("Updated", retrieved?.title)
    }

    @Test
    fun `deleteById removes document`() = runTest {
        val doc = Document(id = "1")
        dao.insert(doc)
        dao.deleteById("1")

        assertNull(dao.getDocumentById("1"))
    }

    @Test
    fun `getDocumentsByType returns filtered list`() = runTest {
        val pdf = Document(id = "1", mimeType = "application/pdf")
        val img = Document(id = "2", mimeType = "image/png")
        dao.insert(pdf)
        dao.insert(img)

        val pdfs = dao.getDocumentsByType("application/%").first()
        assertEquals(1, pdfs.size)
        assertEquals("1", pdfs[0].id)
    }

    @Test
    fun `getFavoriteDocuments returns only favorites`() = runTest {
        val fav = Document(id = "1", isFavorite = true)
        val notFav = Document(id = "2", isFavorite = false)
        dao.insert(fav)
        dao.insert(notFav)

        val faves = dao.getFavoriteDocuments().first()
        assertEquals(1, faves.size)
        assertEquals("1", faves[0].id)
    }

    @Test
    fun `getRecentDocuments returns documents after timestamp`() = runTest {
        val old = Document(id = "1", lastOpenedAt = 100)
        val recent = Document(id = "2", lastOpenedAt = 500)
        dao.insert(old)
        dao.insert(recent)

        val recentDocs = dao.getRecentDocuments(200).first()
        assertEquals(1, recentDocs.size)
        assertEquals("2", recentDocs[0].id)
    }

    @Test
    fun `getDocumentsByCollection returns filtered list`() = runTest {
        val inColl = Document(id = "1", collectionId = "coll1")
        val notInColl = Document(id = "2", collectionId = null)
        dao.insert(inColl)
        dao.insert(notInColl)

        val collDocs = dao.getDocumentsByCollection("coll1").first()
        assertEquals(1, collDocs.size)
        assertEquals("1", collDocs[0].id)
    }

    @Test
    fun `insert replaces on conflict`() = runTest {
        val doc1 = Document(id = "1", title = "First")
        dao.insert(doc1)

        val doc2 = Document(id = "1", title = "Second")
        dao.insert(doc2)

        val retrieved = dao.getDocumentById("1")
        assertEquals("Second", retrieved?.title)
    }
}
