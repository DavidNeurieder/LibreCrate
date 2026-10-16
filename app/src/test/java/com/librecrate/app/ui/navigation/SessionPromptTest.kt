package com.librecrate.app.ui.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the "library shows no documents instead of the password
 * prompt" bug: backgrounding locks the vault, and `MainActivity` is reused on
 * return, so the session has to explicitly re-ask for the password.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionPromptTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `locked session prompts again when it becomes observable`() {
        val locked = mutableStateOf(false)
        val prompts = mutableListOf<String>()
        composeTestRule.setContent {
            PromptForPasswordOnRelock(
                isReady = true,
                sessionLocked = locked.value,
                isPasswordSet = { true },
                onPrompt = { prompts.add("prompt") },
            )
        }
        composeTestRule.runOnIdle { locked.value = true }
        composeTestRule.waitForIdle()
        assertEquals(1, prompts.size)
    }

    @Test
    fun `does not prompt while the vault is still unlocked`() {
        val prompts = mutableListOf<String>()
        composeTestRule.setContent {
            PromptForPasswordOnRelock(
                isReady = true,
                sessionLocked = false,
                isPasswordSet = { true },
                onPrompt = { prompts.add("prompt") },
            )
        }
        composeTestRule.waitForIdle()
        assertEquals(0, prompts.size)
    }

    @Test
    fun `does not prompt on first launch without a password`() {
        val locked = mutableStateOf(false)
        val prompts = mutableListOf<String>()
        composeTestRule.setContent {
            PromptForPasswordOnRelock(
                isReady = true,
                sessionLocked = locked.value,
                isPasswordSet = { false },
                onPrompt = { prompts.add("prompt") },
            )
        }
        composeTestRule.runOnIdle { locked.value = true }
        composeTestRule.waitForIdle()
        assertEquals(0, prompts.size)
    }

    @Test
    fun `waits for the nav graph before prompting`() {
        val ready = mutableStateOf(false)
        val locked = mutableStateOf(true)
        val prompts = mutableListOf<String>()
        composeTestRule.setContent {
            PromptForPasswordOnRelock(
                isReady = ready.value,
                sessionLocked = locked.value,
                isPasswordSet = { true },
                onPrompt = { prompts.add("prompt") },
            )
        }
        composeTestRule.waitForIdle()
        assertEquals(0, prompts.size)
        composeTestRule.runOnIdle { ready.value = true }
        composeTestRule.waitForIdle()
        assertEquals(1, prompts.size)
    }
}
