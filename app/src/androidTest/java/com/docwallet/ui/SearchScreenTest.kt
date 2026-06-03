package com.docwallet.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.docwallet.DocWalletApplication
import com.docwallet.ui.search.SearchScreen
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SearchScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<DocWalletApplication>()
        app.encryptionManager.initializeDeviceKeyMode()
    }

    @After
    fun tearDown() {
        val app = ApplicationProvider.getApplicationContext<DocWalletApplication>()
        app.filesDir.resolve("encryption").deleteRecursively()
    }

    @Test
    fun backButtonExists() {
        composeTestRule.setContent {
            MaterialTheme {
                SearchScreen(
                    onDocumentClick = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Back").assertExists()
    }

    @Test
    fun searchFieldExists() {
        composeTestRule.setContent {
            MaterialTheme {
                SearchScreen(
                    onDocumentClick = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Search documents").assertExists()
    }

    @Test
    fun showsFilterChips() {
        composeTestRule.setContent {
            MaterialTheme {
                SearchScreen(
                    onDocumentClick = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText("All").assertExists()
        composeTestRule.onNodeWithText("PDFs").assertExists()
        composeTestRule.onNodeWithText("Books").assertExists()
    }
}
