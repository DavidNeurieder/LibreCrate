package com.librecrate.app.ui.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.librecrate.app.LibreCrateApplication
import com.librecrate.app.data.db.DocumentDao
import com.librecrate.app.data.db.DocumentListItem
import com.librecrate.app.data.db.SearchResultWithOffsets
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val mockDao = mockk<DocumentDao>(relaxed = true)
    private val mockApp = mockk<LibreCrateApplication>(relaxed = true)
    private lateinit var viewModel: LibraryViewModel

    @Before
    fun setUp() {
        every { mockApp.documentDao } returns mockDao
        every { mockDao.getDocumentList() } returns flowOf(emptyList())
        every { mockDao.searchDocumentsWithOffsets(any()) } returns flowOf(emptyList())
        viewModel = LibraryViewModel(mockApp)
    }

    @Test
    fun `screen displays LibreCrate title`() {
        composeTestRule.setContent {
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
                    onDocumentClickWithPage = { _, _ -> },
                    onSettingsClick = {},
                    viewModel = viewModel,
                )
            }
        }
        composeTestRule.onNodeWithText("LibreCrate").assertExists()
    }

    @Test
    fun `search elements exist`() {
        composeTestRule.setContent {
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
                    onDocumentClickWithPage = { _, _ -> },
                    onSettingsClick = {},
                    viewModel = viewModel,
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Search").performClick()
        composeTestRule.onNodeWithText("Search documents").assertExists()
    }

    @Test
    fun `settings icon exists`() {
        composeTestRule.setContent {
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
                    onDocumentClickWithPage = { _, _ -> },
                    onSettingsClick = {},
                    viewModel = viewModel,
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Settings").assertExists()
    }

    @Test
    fun `filter chips display`() {
        composeTestRule.setContent {
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
                    onDocumentClickWithPage = { _, _ -> },
                    onSettingsClick = {},
                    viewModel = viewModel,
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
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
                    onDocumentClickWithPage = { _, _ -> },
                    onSettingsClick = {},
                    viewModel = viewModel,
                )
            }
        }
        composeTestRule.onNodeWithText("New").assertExists()
    }

    @Test
    fun `empty state displayed when no documents`() {
        composeTestRule.setContent {
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
                    onDocumentClickWithPage = { _, _ -> },
                    onSettingsClick = {},
                    viewModel = viewModel,
                )
            }
        }
        composeTestRule.onNodeWithText("No documents yet").assertExists()
        composeTestRule.onNodeWithText("Tap + to add your first document").assertExists()
    }

    @Test
    fun `search shows results inline`() {
        every { mockDao.searchDocumentsWithOffsets(any()) } returns flowOf(
            listOf(
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
        )

        composeTestRule.setContent {
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
                    onDocumentClickWithPage = { _, _ -> },
                    onSettingsClick = {},
                    viewModel = viewModel,
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
        every { mockDao.searchDocumentsWithOffsets(any()) } returns flowOf(
            listOf(
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
        )

        composeTestRule.setContent {
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
                    onDocumentClickWithPage = { _, _ -> },
                    onSettingsClick = {},
                    viewModel = viewModel,
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
