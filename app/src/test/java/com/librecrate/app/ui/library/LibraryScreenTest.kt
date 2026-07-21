package com.librecrate.app.ui.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.librecrate.app.LibreCrateApplication
import com.librecrate.app.data.model.Document
import com.librecrate.app.data.vault.VaultRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.vault_native.MultiMatchResult

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("RememberReturnType")
class LibraryScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val documentsFlow = MutableStateFlow<List<Document>>(emptyList())
    private val mockVault = mockk<VaultRepository>(relaxed = true)
    private val mockApp = mockk<LibreCrateApplication>(relaxed = true)

    @Before
    fun setUp() {
        documentsFlow.value = emptyList()
        every { mockApp.vaultRepository } returns mockVault
        every { mockVault.documents } returns documentsFlow
        coEvery { mockVault.loadThumbnail(any()) } returns null
        coEvery { mockVault.searchDocumentsWithAllMatches(any()) } returns listOf(
            MultiMatchResult(
                rank = 1.0,
                id = "1",
                title = "Doc One",
                firstSnippet = "...fox...",
                additionalMatches = emptyList(),
            )
        )
    }

    @Test
    fun `screen displays LibreCrate title`() {
        composeTestRule.setContent {
            val vm = remember { LibraryViewModel(mockApp, searchDebounceMs = 0) }
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
                    onDocumentClickWithPage = { _, _ -> },
                    onSettingsClick = {},
                    viewModel = vm,
                )
            }
        }
        composeTestRule.onNodeWithText("LibreCrate").assertExists()
    }

    @Test
    fun `search elements exist`() {
        composeTestRule.setContent {
            val vm = remember { LibraryViewModel(mockApp, searchDebounceMs = 0) }
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
                    onDocumentClickWithPage = { _, _ -> },
                    onSettingsClick = {},
                    viewModel = vm,
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Search").performClick()
        composeTestRule.onNodeWithText("Search documents").assertExists()
    }

    @Test
    fun `settings icon exists`() {
        composeTestRule.setContent {
            val vm = remember { LibraryViewModel(mockApp, searchDebounceMs = 0) }
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
                    onDocumentClickWithPage = { _, _ -> },
                    onSettingsClick = {},
                    viewModel = vm,
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Settings").assertExists()
    }

    @Test
    fun `filter chips display`() {
        composeTestRule.setContent {
            val vm = remember { LibraryViewModel(mockApp, searchDebounceMs = 0) }
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
                    onDocumentClickWithPage = { _, _ -> },
                    onSettingsClick = {},
                    viewModel = vm,
                )
            }
        }
        composeTestRule.onNodeWithText("All").performClick()
        composeTestRule.onNodeWithText("PDFs").assertExists()
        composeTestRule.onNodeWithText("Books").assertExists()
    }

    @Test
    fun `FAB with New text exists`() {
        composeTestRule.setContent {
            val vm = remember { LibraryViewModel(mockApp, searchDebounceMs = 0) }
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
                    onDocumentClickWithPage = { _, _ -> },
                    onSettingsClick = {},
                    viewModel = vm,
                )
            }
        }
        composeTestRule.onNodeWithText("New").assertExists()
    }

    @Test
    fun `empty state displayed when no documents`() {
        composeTestRule.setContent {
            val vm = remember { LibraryViewModel(mockApp, searchDebounceMs = 0) }
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
                    onDocumentClickWithPage = { _, _ -> },
                    onSettingsClick = {},
                    viewModel = vm,
                )
            }
        }
        composeTestRule.onNodeWithText("No documents yet").assertExists()
        composeTestRule.onNodeWithText("Tap + to add your first document").assertExists()
    }

    @Test
    fun `search shows results inline`() {
        documentsFlow.value = listOf(
            Document(
                id = "1", title = "Doc One", fileName = "doc.pdf",
                mimeType = "application/pdf", pageCount = 10, author = "Author",
                description = "The quick fox jumps",
            )
        )

        composeTestRule.setContent {
            val vm = remember { LibraryViewModel(mockApp, searchDebounceMs = 0) }
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
                    onDocumentClickWithPage = { _, _ -> },
                    onSettingsClick = {},
                    viewModel = vm,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Search").performClick()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("fox")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Doc One").assertExists()
    }

    @Test
    fun `search clears results on close`() {
        documentsFlow.value = listOf(
            Document(
                id = "1", title = "Doc One", fileName = "doc.pdf",
                mimeType = "application/pdf", pageCount = 10, author = "Author",
                description = "The quick fox jumps",
            )
        )

        composeTestRule.setContent {
            val vm = remember { LibraryViewModel(mockApp, searchDebounceMs = 0) }
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
                    onDocumentClickWithPage = { _, _ -> },
                    onSettingsClick = {},
                    viewModel = vm,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Search").performClick()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("fox")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Doc One").assertExists()

        composeTestRule.onNodeWithContentDescription("Close search").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("LibreCrate").assertExists()
        composeTestRule.onNodeWithText("Doc One").assertDoesNotExist()
    }
}
