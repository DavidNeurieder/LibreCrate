package com.docwallet.ui.unlock

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.docwallet.DocWalletApplication
import com.docwallet.data.encryption.EncryptionManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnlockScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val mockEncryptionManager = mockk<EncryptionManager>(relaxed = true)
    private val mockApp = mockk<DocWalletApplication>(relaxed = true)
    private lateinit var viewModel: UnlockViewModel

    @Before
    fun setUp() {
        every { mockApp.encryptionManager } returns mockEncryptionManager
        viewModel = UnlockViewModel(mockApp)
    }

    @Test
    fun `password field exists`() {
        composeTestRule.setContent {
            MaterialTheme {
                UnlockScreen(onUnlocked = {}, viewModel = viewModel)
            }
        }
        composeTestRule.onNodeWithText("Password").assertExists()
    }

    @Test
    fun `unlock button exists`() {
        composeTestRule.setContent {
            MaterialTheme {
                UnlockScreen(onUnlocked = {}, viewModel = viewModel)
            }
        }
        composeTestRule.onNodeWithText("Unlock").assertExists()
    }

    @Test
    fun `typing password updates the field`() {
        composeTestRule.setContent {
            MaterialTheme {
                UnlockScreen(onUnlocked = {}, viewModel = viewModel)
            }
        }
        composeTestRule.onNodeWithText("Password").performTextInput("testPassword123")
        composeTestRule.waitForIdle()
        assertEquals("testPassword123", viewModel.password)
    }

    @Test
    fun `error message displayed when error is set`() {
        composeTestRule.setContent {
            MaterialTheme {
                UnlockScreen(onUnlocked = {}, viewModel = viewModel)
            }
        }
        composeTestRule.onNodeWithText("Password").performTextInput("abc")
        composeTestRule.onNodeWithText("Unlock").performClick()
        composeTestRule.onNodeWithText("Password must be at least 6 characters").assertExists()
    }
}
