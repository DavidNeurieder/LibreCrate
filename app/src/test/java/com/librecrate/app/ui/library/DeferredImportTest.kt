package com.librecrate.app.ui.library

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import com.librecrate.app.LibreCrateApplication
import com.librecrate.app.data.model.Document
import com.librecrate.app.data.vault.VaultRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Opening the system document picker backgrounds the app, which locks the
 * vault. A selection made in the picker must therefore survive until the user
 * has unlocked again instead of being imported into — and lost on — a closed
 * vault.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("RememberReturnType")
class DeferredImportTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val documentsFlow = MutableStateFlow<List<Document>>(emptyList())
    private val mockVault = mockk<VaultRepository>(relaxed = true)
    private val mockApp = mockk<LibreCrateApplication>(relaxed = true)
    private val pendingUris = listOf(Uri.parse("content://test/doc.pdf"))

    private fun render(vaultOpen: Boolean, consumed: () -> Unit) {
        every { mockApp.vaultRepository } returns mockVault
        every { mockVault.documents } returns documentsFlow
        every { mockVault.isOpen() } returns vaultOpen
        composeTestRule.setContent {
            val vm = remember { LibraryViewModel(mockApp, searchDebounceMs = 0) }
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
                    onDocumentClickWithPage = { _, _ -> },
                    onSettingsClick = {},
                    pendingImportUris = pendingUris,
                    onPendingImportConsumed = consumed,
                    viewModel = vm,
                )
            }
        }
    }

    @Test
    fun `pending import is held back while the vault is locked`() {
        var consumed = false
        render(vaultOpen = false) { consumed = true }
        composeTestRule.waitForIdle()
        assertFalse(consumed)
    }

    @Test
    fun `pending import is performed once the vault is open`() {
        var consumed = false
        render(vaultOpen = true) { consumed = true }
        composeTestRule.waitForIdle()
        assertTrue(consumed)
    }
}
