package com.librecrate.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibreCrateApplicationTest {

    private fun app(): LibreCrateApplication =
        RuntimeEnvironment.getApplication() as LibreCrateApplication

    @Test
    fun `a fresh session is not locked`() {
        assertFalse(app().sessionLocked.value)
    }

    @Test
    fun `lockSession tears down the vault and marks the session locked`() {
        val app = app()
        app.lockSession()
        assertTrue(app.sessionLocked.value)
        assertNull(app.encryptionManager.getMasterKeyForSession())
        assertFalse(app.vaultRepository.isOpen())
    }

    @Test
    fun `openVault without a session key fails and stays locked`() {
        val app = app()
        app.lockSession()
        assertFalse(runBlocking { app.openVault() })
        assertTrue(app.sessionLocked.value)
        assertFalse(app.vaultRepository.isOpen())
    }
}
