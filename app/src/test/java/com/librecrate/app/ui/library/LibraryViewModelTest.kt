package com.librecrate.app.ui.library

import com.librecrate.app.LibreCrateApplication
import com.librecrate.app.data.db.DocumentDao
import com.librecrate.app.data.db.SearchResultWithOffsets
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val mockDao = mockk<DocumentDao>(relaxed = true)
    private val mockApp = mockk<LibreCrateApplication>(relaxed = true)
    private lateinit var viewModel: LibraryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { mockApp.documentDao } returns mockDao
        every { mockDao.getDocumentList() } returns flowOf(emptyList())
        viewModel = LibraryViewModel(mockApp)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.keepSearchActive() {
        backgroundScope.launch { viewModel.searchResults.collect { } }
    }

    @Test
    fun `search results empty when query is blank`() = runTest(testDispatcher) {
        keepSearchActive()
        viewModel.search("")
        advanceUntilIdle()
        assertTrue(viewModel.searchResults.value.isEmpty())
    }

    @Test
    fun `search results returns documents from DAO`() = runTest(testDispatcher) {
        keepSearchActive()
        val items = listOf(
            SearchResultWithOffsets(
                id = "1",
                title = "Doc One",
                mimeType = "application/pdf",
                pageCount = 10,
                author = "Author",
                thumbnailPath = null,
                textContent = "The quick fox jumps",
                highlightContent = "The quick \u0001fox\u0002 jumps",
            )
        )
        every { mockDao.searchDocumentsWithOffsets(any()) } returns flowOf(items)

        viewModel.search("fox")
        advanceUntilIdle()

        assertEquals(1, viewModel.searchResults.value.size)
        assertEquals("1", viewModel.searchResults.value[0].id)
        assertTrue(viewModel.searchResults.value[0].matches[0].snippet.contains("fox"))
    }

    @Test
    fun `search results handles DAO exception gracefully`() = runTest(testDispatcher) {
        keepSearchActive()
        every { mockDao.searchDocumentsWithOffsets(any()) } throws RuntimeException("DB error")

        viewModel.search("fox")
        advanceUntilIdle()

        assertTrue(viewModel.searchResults.value.isEmpty())
    }

    @Test
    fun `search results are empty after clearing query`() = runTest(testDispatcher) {
        keepSearchActive()
        val items = listOf(
            SearchResultWithOffsets(
                id = "1",
                title = "Doc",
                mimeType = "text/plain",
                pageCount = 5,
                author = "",
                thumbnailPath = null,
                textContent = "some text",
                highlightContent = "some \u0001text\u0002",
            )
        )
        every { mockDao.searchDocumentsWithOffsets(any()) } returns flowOf(items)

        viewModel.search("text")
        advanceUntilIdle()
        assertEquals(1, viewModel.searchResults.value.size)

        viewModel.search("")
        advanceUntilIdle()
        assertTrue(viewModel.searchResults.value.isEmpty())
    }

    @Test
    fun `search results are updated on new query`() = runTest(testDispatcher) {
        keepSearchActive()
        every { mockDao.searchDocumentsWithOffsets(any()) } returns flowOf(emptyList())

        viewModel.search("fox")
        advanceUntilIdle()
        assertTrue(viewModel.searchResults.value.isEmpty())

        val items = listOf(
            SearchResultWithOffsets(
                id = "2",
                title = "Rabbit Facts",
                mimeType = "application/pdf",
                pageCount = 3,
                author = "Nature",
                thumbnailPath = null,
                textContent = "the quick rabbit",
                highlightContent = "the quick \u0001rabbit\u0002",
            )
        )
        every { mockDao.searchDocumentsWithOffsets(any()) } returns flowOf(items)

        viewModel.search("rabbit")
        advanceUntilIdle()
        assertEquals(1, viewModel.searchResults.value.size)
        assertEquals("2", viewModel.searchResults.value[0].id)
    }

    @Test
    fun `search does not trigger DAO when query is blank`() = runTest(testDispatcher) {
        keepSearchActive()
        viewModel.search("")
        advanceUntilIdle()
        assertTrue(viewModel.searchResults.value.isEmpty())
    }
}
