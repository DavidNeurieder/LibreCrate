package com.docwallet.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.docwallet.DocWalletApplication
import com.docwallet.ui.library.LibraryScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LibraryScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<DocWalletApplication>()
        app.encryptionManager.initializeDeviceKeyMode()
    }

    @After
    fun tearDown() {
        val app = ApplicationProvider.getApplicationContext<DocWalletApplication>()
        runBlocking { app.documentDao.deleteAll() }
        app.encryptionManager.lock()
        app.filesDir.resolve("encryption").deleteRecursively()
    }

    @Test
    fun displaysTitle() {
        composeTestRule.setContent {
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
                    onSettingsClick = {},
                    onSearchClick = {},
                    onNewNoteClick = {},
                )
            }
        }
        composeTestRule.onNodeWithText("DocWallet").assertExists()
    }

    @Test
    fun settingsIconExists() {
        composeTestRule.setContent {
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
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
                    onSettingsClick = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Search documents").assertExists()
    }

    @Test
    fun fabWithNewTextExists() {
        composeTestRule.setContent {
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
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
                    onSettingsClick = {},
                )
            }
        }
        composeTestRule.onNodeWithText("All").assertExists()
        composeTestRule.onNodeWithText("PDFs").assertExists()
        composeTestRule.onNodeWithText("Books").assertExists()
    }
}
