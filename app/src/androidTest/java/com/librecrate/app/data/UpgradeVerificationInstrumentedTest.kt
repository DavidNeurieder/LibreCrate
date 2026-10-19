package com.librecrate.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librecrate.app.LibreCrateApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs after an upgrade (`adb install -r` over the previously installed
 * version). Assumes the app-private state was seeded by
 * `scripts/test_upgrade_e2e.sh` from `upgrade_v0_5_6_seed.tar.gz`, i.e. the
 * vault exists with the deterministic password below and holds known documents.
 */
@RunWith(AndroidJUnit4::class)
class UpgradeVerificationInstrumentedTest {
    @Test
    fun vaultOpensAfterUpgrade() {
        val app = ApplicationProvider.getApplicationContext<LibreCrateApplication>()
        val unlocked = app.encryptionManager.verifyPassword(PASSWORD)
        assertTrue("password from seed must still unlock", unlocked)
        val opened = runBlocking { app.openVault() }
        assertTrue("vault must open after upgrade", opened)
        val docs = runBlocking { app.vaultRepository.documents.first() }
        assertTrue("documents must survive the upgrade", docs.isNotEmpty())
    }

    @Test
    fun expectedDocumentTitlesPresent() {
        val app = ApplicationProvider.getApplicationContext<LibreCrateApplication>()
        assertTrue(runBlocking { app.encryptionManager.verifyPassword(PASSWORD) })
        runBlocking { app.openVault() }
        val docs = runBlocking { app.vaultRepository.documents.first() }
        val titles = docs.map { it.title }.toSet()
        for (expected in SEEDED_TITLES) {
            assertTrue("missing seeded document: $expected (have: $titles)", expected in titles)
        }
    }

    @Test
    fun noUnexpectedExtraDocuments() {
        val app = ApplicationProvider.getApplicationContext<LibreCrateApplication>()
        assertTrue(runBlocking { app.encryptionManager.verifyPassword(PASSWORD) })
        runBlocking { app.openVault() }
        val docs = runBlocking { app.vaultRepository.documents.first() }
        assertEquals("document count changed across the upgrade", SEEDED_TITLES.size, docs.size)
    }

    companion object {
        /** Password used when the seed snapshot was generated. */
        const val PASSWORD = "upgrade-test"
        /**
         * Titles written into the seed snapshot by the one-shot seeder in the
         * v0.5.6 worktree. Keep in sync with that fixture list.
         */
        val SEEDED_TITLES = listOf("test_1page.pdf", "test.epub", "test.cbz")
    }
}
