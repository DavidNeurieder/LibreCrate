package com.librecrate.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.librecrate.app.data.model.Document
import com.librecrate.app.ui.library.DocumentCard
import com.librecrate.app.ui.library.LibraryScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibraryScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displaysTitle() {
        composeTestRule.setContent {
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
                    onDocumentClickWithPage = { _, _ -> },
                    onSettingsClick = {},
                    onNewNoteClick = {},
                )
            }
        }
        composeTestRule.onNodeWithText("LibreCrate").assertExists()
    }

    @Test
    fun settingsIconExists() {
        composeTestRule.setContent {
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
                    onDocumentClickWithPage = { _, _ -> },
                    onSettingsClick = {},
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Settings").assertExists()
    }

    @Test
    fun searchFieldExists() {
        composeTestRule.setContent {
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
                    onDocumentClickWithPage = { _, _ -> },
                    onSettingsClick = {},
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Search").performClick()
        composeTestRule.onNodeWithText("Search documents").assertExists()
    }

    @Test
    fun fabWithNewTextExists() {
        composeTestRule.setContent {
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
                    onDocumentClickWithPage = { _, _ -> },
                    onSettingsClick = {},
                )
            }
        }
        composeTestRule.onNodeWithText("New").assertExists()
    }

    @Test
    fun emptyStateDisplayedWhenNoDocuments() {
        composeTestRule.setContent {
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
                    onDocumentClickWithPage = { _, _ -> },
                    onSettingsClick = {},
                )
            }
        }
        composeTestRule.onNodeWithText("No documents yet").assertExists()
        composeTestRule.onNodeWithText("Tap + to add your first document").assertExists()
    }

    @Test
    fun filterChipsExist() {
        composeTestRule.setContent {
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
                    onDocumentClickWithPage = { _, _ -> },
                    onSettingsClick = {},
                )
            }
        }
        composeTestRule.onNodeWithText("All").performClick()
        composeTestRule.onNodeWithText("PDFs").assertExists()
        composeTestRule.onNodeWithText("Books").assertExists()
    }

    @Test
    fun documentCardRendersFavoriteIcon() {
        val doc = Document(
            id = "doc-1", title = "Test Document", fileName = "test.pdf",
            mimeType = "application/pdf",
        )
        composeTestRule.setContent {
            MaterialTheme {
                DocumentCard(
                    document = doc,
                    onClick = {},
                    onFavoriteClick = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Test Document", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithContentDescription("Add to favorites").assertExists()
        composeTestRule.onNodeWithContentDescription("PDF").assertExists()
    }

    @Test
    fun documentCardTogglesFavoriteIcon() {
        val isFavorite = mutableStateOf(false)
        val doc = Document(
            id = "doc-2", title = "Toggle Doc", fileName = "test.pdf",
            mimeType = "application/pdf", isFavorite = isFavorite.value,
        )

        composeTestRule.setContent {
            MaterialTheme {
                DocumentCard(
                    document = doc,
                    onClick = {},
                    onFavoriteClick = { isFavorite.value = !isFavorite.value },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Add to favorites").assertExists()
        composeTestRule.onNodeWithContentDescription("Add to favorites").performClick()
        assertTrue(isFavorite.value)
    }

    @Test
    fun documentCardShowsFavoritedState() {
        val doc = Document(
            id = "doc-3", title = "Favorited Doc", fileName = "test.pdf",
            mimeType = "application/pdf", isFavorite = true,
        )
        composeTestRule.setContent {
            MaterialTheme {
                DocumentCard(
                    document = doc,
                    onClick = {},
                    onFavoriteClick = {},
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Remove from favorites").assertExists()
    }

    @Test
    fun documentCardIsClickable() {
        var clicked = false
        val doc = Document(
            id = "doc-4", title = "Clickable Doc", fileName = "test.pdf",
            mimeType = "application/pdf",
        )
        composeTestRule.setContent {
            MaterialTheme {
                DocumentCard(
                    document = doc,
                    onClick = { clicked = true },
                    onFavoriteClick = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Clickable Doc", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithContentDescription("PDF").performClick()
        assertTrue(clicked)
    }
}
