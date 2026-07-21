package com.librecrate.app.ui.library

import com.librecrate.app.LibreCrateApplication
import com.librecrate.app.data.model.Document
import com.librecrate.app.data.vault.VaultRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import uniffi.vault_native.PageMatch
import uniffi.vault_native.MultiMatchResult

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val documentsFlow = MutableStateFlow<List<Document>>(emptyList())
    private val mockVault = mockk<VaultRepository>(relaxed = true)
    private val mockApp = mockk<LibreCrateApplication>(relaxed = true)
    private lateinit var viewModel: LibraryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        documentsFlow.value = emptyList()
        every { mockApp.vaultRepository } returns mockVault
        every { mockVault.documents } returns documentsFlow
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
    fun `search results returns documents from vault`() = runTest(testDispatcher) {
        keepSearchActive()
        documentsFlow.value = listOf(
            Document(
                id = "1", title = "Doc One", fileName = "doc.pdf",
                mimeType = "application/pdf", pageCount = 10, author = "Author",
                description = "The quick fox jumps",
            )
        )
        coEvery { mockVault.searchDocumentsWithAllMatches("fox") } returns listOf(
            MultiMatchResult(rank = 1.0, id = "1", title = "Doc One", firstSnippet = "The quick <b>fox</b> jumps", additionalMatches = emptyList()),
        )

        viewModel.search("fox")
        advanceUntilIdle()

        assertEquals(1, viewModel.searchResults.value.size)
        assertTrue(viewModel.searchResults.value[0].matches[0].snippet.contains("fox"))
    }

    @Test
    fun `search results are empty after clearing query`() = runTest(testDispatcher) {
        keepSearchActive()
        documentsFlow.value = listOf(
            Document(
                id = "1", title = "Doc", fileName = "doc.txt",
                mimeType = "text/plain",
                description = "some text",
            )
        )
        coEvery { mockVault.searchDocumentsWithAllMatches("text") } returns listOf(
            MultiMatchResult(rank = 1.0, id = "1", title = "Doc", firstSnippet = "some <b>text</b>", additionalMatches = emptyList()),
        )

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

        viewModel.search("fox")
        advanceUntilIdle()
        assertTrue(viewModel.searchResults.value.isEmpty())

        documentsFlow.value = listOf(
            Document(
                id = "2", title = "Rabbit Facts", fileName = "rabbit.pdf",
                mimeType = "application/pdf", author = "Nature",
                description = "the quick rabbit",
            )
        )
        coEvery { mockVault.searchDocumentsWithAllMatches("rabbit") } returns listOf(
            MultiMatchResult(rank = 1.0, id = "2", title = "Rabbit Facts", firstSnippet = "the quick <b>rabbit</b>", additionalMatches = emptyList()),
        )

        viewModel.search("rabbit")
        advanceUntilIdle()
        assertEquals(1, viewModel.searchResults.value.size)
        assertEquals("2", viewModel.searchResults.value[0].id)
    }

    @Test
    fun `search results include additional page matches`() = runTest(testDispatcher) {
        keepSearchActive()
        documentsFlow.value = listOf(
            Document(
                id = "1", title = "Multi Match Doc", fileName = "doc.pdf",
                mimeType = "application/pdf", pageCount = 3, author = "Author",
                description = "a document",
            )
        )
        coEvery { mockVault.searchDocumentsWithAllMatches("fox") } returns listOf(
            MultiMatchResult(
                rank = 1.0, id = "1", title = "Multi Match Doc",
                firstSnippet = "The quick <b>fox</b> jumps",
                additionalMatches = listOf(
                    PageMatch(snippet = "Another <b>fox</b> sighting on page 2", pageNumber = 2),
                    PageMatch(snippet = "Third <b>fox</b> reference on page 3", pageNumber = 3),
                ),
            ),
        )

        viewModel.search("fox")
        advanceUntilIdle()

        assertEquals(1, viewModel.searchResults.value.size)
        assertEquals(3, viewModel.searchResults.value[0].matches.size)
        assertTrue(viewModel.searchResults.value[0].matches[0].snippet.contains("fox"))
        assertEquals(2, viewModel.searchResults.value[0].matches[1].pageNumber)
        assertEquals(3, viewModel.searchResults.value[0].matches[2].pageNumber)
    }

    @Test
    fun `search does not trigger when query is blank`() = runTest(testDispatcher) {
        keepSearchActive()
        viewModel.search("")
        advanceUntilIdle()
        assertTrue(viewModel.searchResults.value.isEmpty())
    }
}
