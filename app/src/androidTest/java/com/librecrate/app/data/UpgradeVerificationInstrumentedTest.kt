package com.librecrate.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librecrate.app.LibreCrateApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lightweight verification that runs after an upgrade (APK installed over v0.5.6).
 * Assumes the seeded vault with password "upgrade-test" exists.
 */
@RunWith(AndroidJUnit4::class)
class UpgradeVerificationInstrumentedTest {
    @Test
    fun vaultOpensAfterUpgrade() {
        val app = ApplicationProvider.getApplicationContext<LibreCrateApplication>()
        val key = app.encryptionManager.verifyPassword("upgrade-test")
        assertTrue(key)
        val opened = runBlocking { app.openVault() }
        assertTrue(opened)
        val docs = app.vaultRepository.documents.value
        assertNotNull(docs)
        assertTrue(docs.isNotEmpty())
    }
}
