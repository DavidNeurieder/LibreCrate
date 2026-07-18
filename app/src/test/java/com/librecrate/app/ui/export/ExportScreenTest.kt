package com.librecrate.app.ui.export

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.librecrate.app.LibreCrateApplication
import com.librecrate.app.data.db.DocumentDao
import com.librecrate.app.data.encryption.EncryptionManager
import com.librecrate.app.data.model.Document
import io.mockk.coEvery
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    private val mockDao = mockk<DocumentDao>(relaxed = true)
    private val mockEncryptionManager = mockk<EncryptionManager>(relaxed = true)
    private val mockApp = mockk<LibreCrateApplication>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { mockApp.documentDao } returns mockDao
        every { mockApp.encryptionManager } returns mockEncryptionManager
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Test
    fun `screen displays Export Documents title`() {
        coEvery { mockDao.getAllDocumentsOnce() } returns emptyList()
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
        coEvery { mockDao.getAllDocumentsOnce() } returns emptyList()
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
        coEvery { mockDao.getAllDocumentsOnce() } returns emptyList()
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
        coEvery { mockDao.getAllDocumentsOnce() } returns emptyList()
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
        coEvery { mockDao.getAllDocumentsOnce() } returns emptyList()
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
        coEvery { mockDao.getAllDocumentsOnce() } returns listOf(
            Document(id = "1", title = "Doc One", fileName = "one.pdf"),
        )
        val viewModel = ExportViewModel(mockApp)

        composeTestRule.setContent {
            MaterialTheme {
                ExportScreen(onBack = {}, viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("0 of 1 selected").assertExists()
        composeTestRule.onNodeWithText("Export Selected (0)").assertExists()
        composeTestRule.onNodeWithText("Export Selected (0)").assertIsNotEnabled()
    }
}
