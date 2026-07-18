package com.librecrate.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.librecrate.app.ui.unlock.UnlockScreen
import org.junit.Rule
import org.junit.Test

class UnlockScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun showsTitle() {
        composeTestRule.setContent {
            MaterialTheme {
                UnlockScreen(onUnlocked = {})
            }
        }
        composeTestRule.onNodeWithText("LibreCrate").assertExists()
    }

    @Test
    fun showsSubtitle() {
        composeTestRule.setContent {
            MaterialTheme {
                UnlockScreen(onUnlocked = {})
            }
        }
        composeTestRule.onNodeWithText("Enter your password to unlock").assertExists()
    }

    @Test
    fun passwordFieldExists() {
        composeTestRule.setContent {
            MaterialTheme {
                UnlockScreen(onUnlocked = {})
            }
        }
        composeTestRule.onNodeWithText("Password").assertExists()
    }

    @Test
    fun unlockButtonExists() {
        composeTestRule.setContent {
            MaterialTheme {
                UnlockScreen(onUnlocked = {})
            }
        }
        composeTestRule.onNodeWithText("Unlock").assertExists()
    }
}
