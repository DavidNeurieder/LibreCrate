package com.docwallet.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.docwallet.ui.settings.SettingsScreen
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        composeTestRule.setContent {
            MaterialTheme {
                SettingsScreen(onBack = {})
            }
        }
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun showsTitle() {
        composeTestRule.onNodeWithText("Settings").assertExists()
    }

    @Test
    fun backButtonExists() {
        composeTestRule.onNodeWithContentDescription("Back").assertExists()
    }

    @Test
    fun showsSecuritySection() {
        composeTestRule.onNodeWithText("Security").assertExists()
    }

    @Test
    fun showsBackupSection() {
        composeTestRule.onNodeWithText("Backup").assertExists()
    }

    @Test
    fun showsAboutSection() {
        composeTestRule.onNodeWithText("About").assertExists()
    }

    @Test
    fun showsExportBackupButton() {
        composeTestRule.onNodeWithText("Export Backup").assertExists()
    }

    @Test
    fun showsImportBackupButton() {
        composeTestRule.onNodeWithText("Import Backup").assertExists()
    }

    @Test
    fun showsAppVersion() {
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        composeTestRule.onNodeWithText("Version $versionName").assertExists()
    }

    @Test
    fun showsLicense() {
        composeTestRule.onNodeWithText("GPL-3.0-only").assertExists()
    }

    @Test
    fun showsSourceCodeLink() {
        composeTestRule.onNodeWithText("github.com/DavidNeurieder/DocWallet").assertExists()
    }
}
