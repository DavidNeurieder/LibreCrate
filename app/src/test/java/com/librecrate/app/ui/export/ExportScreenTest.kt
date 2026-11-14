package com.librecrate.app.ui.export

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.librecrate.app.LibreCrateApplication
import com.librecrate.app.data.model.Document
import com.librecrate.app.data.vault.VaultRepository
import io.mockk.coEvery
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val mockVault = mockk<VaultRepository>(relaxed = true)
    private val mockApp = mockk<LibreCrateApplication>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { mockApp.vaultRepository } returns mockVault
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Test
    fun `screen displays Export Documents title`() {
        coEvery { mockVault.listDocuments() } returns emptyList()
        val viewModel = ExportViewModel(mockApp)

        composeTestRule.setContent {
            MaterialTheme {
                ExportScreen(onBack = {}, viewModel = viewModel)
            }
        }
        composeTestRule.onNodeWithText("Export Documents").assertExists()
    }

    @Test
    fun `search icon exists`() {
        coEvery { mockVault.listDocuments() } returns emptyList()
        val viewModel = ExportViewModel(mockApp)

        composeTestRule.setContent {
            MaterialTheme {
                ExportScreen(onBack = {}, viewModel = viewModel)
            }
        }
        composeTestRule.onNodeWithContentDescription("Search").assertExists()
    }

    @Test
    fun `search activates on search icon click`() {
        coEvery { mockVault.listDocuments() } returns emptyList()
        val viewModel = ExportViewModel(mockApp)

        composeTestRule.setContent {
            MaterialTheme {
                ExportScreen(onBack = {}, viewModel = viewModel)
            }
        }
        composeTestRule.onNodeWithContentDescription("Search").performClick()
        composeTestRule.onNodeWithContentDescription("Close search").assertExists()
    }

    @Test
    fun `empty state displayed when no documents`() {
        coEvery { mockVault.listDocuments() } returns emptyList()
        val viewModel = ExportViewModel(mockApp)

        composeTestRule.setContent {
            MaterialTheme {
                ExportScreen(onBack = {}, viewModel = viewModel)
            }
        }
        composeTestRule.onNodeWithText("No documents to export").assertExists()
    }

    @Test
    fun `back button exists`() {
        coEvery { mockVault.listDocuments() } returns emptyList()
        val viewModel = ExportViewModel(mockApp)

        composeTestRule.setContent {
            MaterialTheme {
                ExportScreen(onBack = {}, viewModel = viewModel)
            }
        }
        composeTestRule.onNodeWithContentDescription("Back").assertExists()
    }

    @Test
    fun `export button is disabled when nothing selected`() {
        coEvery { mockVault.listDocuments() } coAnswers { awaitCancellation() }
        val viewModel = ExportViewModel(mockApp)
        viewModel.setDocumentsForTest(
            listOf(Document(id = "1", title = "Doc One", fileName = "one.pdf")),
        )

        composeTestRule.setContent {
            MaterialTheme {
                ExportScreen(onBack = {}, viewModel = viewModel)
            }
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodesWithText("0 of 1 selected")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithText("0 of 1 selected").assertExists()
        composeTestRule.onNodeWithText("Export Selected (0)").assertExists()
        composeTestRule.onNodeWithText("Export Selected (0)").assertIsNotEnabled()
    }
}
