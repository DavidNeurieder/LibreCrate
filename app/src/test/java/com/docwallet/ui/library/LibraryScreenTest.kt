package com.docwallet.ui.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.docwallet.DocWalletApplication
import com.docwallet.data.db.DocumentDao
import com.docwallet.data.db.DocumentListItem
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
    private val mockApp = mockk<DocWalletApplication>(relaxed = true)
    private lateinit var viewModel: LibraryViewModel

    @Before
    fun setUp() {
        every { mockApp.documentDao } returns mockDao
        every { mockDao.getDocumentList() } returns flowOf(emptyList())
        viewModel = LibraryViewModel(mockApp)
    }

    @Test
    fun `screen displays DocWallet title`() {
        composeTestRule.setContent {
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
                    onSettingsClick = {},
                    viewModel = viewModel,
                )
            }
        }
        composeTestRule.onNodeWithText("DocWallet").assertExists()
    }

    @Test
    fun `search elements exist`() {
        composeTestRule.setContent {
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
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
                    onSettingsClick = {},
                    viewModel = viewModel,
                )
            }
        }
        composeTestRule.onNodeWithText("No documents yet").assertExists()
        composeTestRule.onNodeWithText("Tap + to add your first document").assertExists()
    }
}
