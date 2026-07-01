package com.docwallet.ui.unlock

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.docwallet.DocWalletApplication
import com.docwallet.data.encryption.EncryptionManager

@OptIn(ExperimentalCoroutinesApi::class)
class UnlockViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val mockEncryptionManager = mockk<EncryptionManager>(relaxed = true)
    private val mockApp = mockk<DocWalletApplication>()
    private lateinit var viewModel: UnlockViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { mockApp.encryptionManager } returns mockEncryptionManager
        viewModel = UnlockViewModel(mockApp, testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `isPasswordSet delegates to encryptionManager`() {
        every { mockEncryptionManager.isPasswordSet() } returns true
        assertTrue(viewModel.isPasswordSet)

        every { mockEncryptionManager.isPasswordSet() } returns false
        assertFalse(viewModel.isPasswordSet)

        verify(exactly = 2) { mockEncryptionManager.isPasswordSet() }
    }

    @Test
    fun `onPasswordChange updates password and clears error`() {
        viewModel.onPasswordChange("ab")
        viewModel.unlock {}
        assertEquals("Password must be at least 6 characters", viewModel.error)

        viewModel.onPasswordChange("newpass")
        assertEquals("newpass", viewModel.password)
        assertNull(viewModel.error)
    }

    @Test
    fun `unlock with short password sets error`() {
        viewModel.onPasswordChange("ab")
        viewModel.unlock {}
        assertEquals("Password must be at least 6 characters", viewModel.error)
        assertFalse(viewModel.isLoading)
    }

    @Test
    fun `unlock with correct password calls onSuccess`() = runTest(testDispatcher) {
        every { mockEncryptionManager.verifyPassword(any()) } returns true
        var successCalled = false
        viewModel.onPasswordChange("correct")
        viewModel.unlock { successCalled = true }
        advanceUntilIdle()
        assertTrue(successCalled)
        verify { mockEncryptionManager.verifyPassword("correct") }
    }

    @Test
    fun `unlock with wrong password sets error`() = runTest(testDispatcher) {
        every { mockEncryptionManager.verifyPassword(any()) } returns false
        viewModel.onPasswordChange("wrongpwd")
        viewModel.unlock {}
        advanceUntilIdle()
        assertEquals("Wrong password", viewModel.error)
        verify { mockEncryptionManager.verifyPassword("wrongpwd") }
    }

    @Test
    fun `clearError sets error to null`() {
        viewModel.onPasswordChange("ab")
        viewModel.unlock {}
        assertEquals("Password must be at least 6 characters", viewModel.error)
        viewModel.clearError()
        assertNull(viewModel.error)
    }

    @Test
    fun `isLoading is true during unlock`() = runTest(testDispatcher) {
        every { mockEncryptionManager.verifyPassword(any()) } answers {
            assertTrue(viewModel.isLoading)
            true
        }
        viewModel.onPasswordChange("validpass")
        viewModel.unlock {}
        advanceUntilIdle()
        assertFalse(viewModel.isLoading)
    }
}
