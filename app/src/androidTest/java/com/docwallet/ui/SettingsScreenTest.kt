package com.docwallet.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.docwallet.ui.settings.SettingsScreen
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun showsTitle() {
        composeTestRule.setContent {
            MaterialTheme {
                SettingsScreen(onBack = {})
            }
        }
        composeTestRule.onNodeWithText("Settings").assertExists()
    }

    @Test
    fun backButtonExists() {
        composeTestRule.setContent {
            MaterialTheme {
                SettingsScreen(onBack = {})
            }
        }
        composeTestRule.onNodeWithContentDescription("Back").assertExists()
    }

    @Test
    fun showsSecuritySection() {
        composeTestRule.setContent {
            MaterialTheme {
                SettingsScreen(onBack = {})
            }
        }
        composeTestRule.onNodeWithText("Security").assertExists()
    }

    @Test
    fun showsBackupSection() {
        composeTestRule.setContent {
            MaterialTheme {
                SettingsScreen(onBack = {})
            }
        }
        composeTestRule.onNodeWithText("Backup").assertExists()
    }

    @Test
    fun showsManagementSection() {
        composeTestRule.setContent {
            MaterialTheme {
                SettingsScreen(onBack = {})
            }
        }
        composeTestRule.onNodeWithText("Management").assertExists()
    }

    @Test
    fun showsAboutSection() {
        composeTestRule.setContent {
            MaterialTheme {
                SettingsScreen(onBack = {})
            }
        }
        composeTestRule.onNodeWithText("About").assertExists()
    }

    @Test
    fun showsExportBackupButton() {
        composeTestRule.setContent {
            MaterialTheme {
                SettingsScreen(onBack = {})
            }
        }
        composeTestRule.onNodeWithText("Export Backup").assertExists()
    }

    @Test
    fun showsImportBackupButton() {
        composeTestRule.setContent {
            MaterialTheme {
                SettingsScreen(onBack = {})
            }
        }
        composeTestRule.onNodeWithText("Import Backup").assertExists()
    }

    @Test
    fun showsCollectionsButton() {
        composeTestRule.setContent {
            MaterialTheme {
                SettingsScreen(onBack = {})
            }
        }
        composeTestRule.onNodeWithText("Collections").assertExists()
    }

    @Test
    fun showsTagsButton() {
        composeTestRule.setContent {
            MaterialTheme {
                SettingsScreen(onBack = {})
            }
        }
        composeTestRule.onNodeWithText("Tags").assertExists()
    }

    @Test
    fun showsAppVersion() {
        composeTestRule.setContent {
            MaterialTheme {
                SettingsScreen(onBack = {})
            }
        }
        composeTestRule.onNodeWithText("1.0.0").assertExists()
    }

    @Test
    fun showsLicense() {
        composeTestRule.setContent {
            MaterialTheme {
                SettingsScreen(onBack = {})
            }
        }
        composeTestRule.onNodeWithText("GPL-3.0").assertExists()
    }

    @Test
    fun showsSourceCodeLink() {
        composeTestRule.setContent {
            MaterialTheme {
                SettingsScreen(onBack = {})
            }
        }
        composeTestRule.onNodeWithText("Source code on GitHub").assertExists()
    }
}
