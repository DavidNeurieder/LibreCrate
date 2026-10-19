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
        val titles = docs.map { it.title }
        for (expected in SEEDED_TITLES) {
            // Imported titles carry the source filename (extension included);
            // match on the stem so the assertion holds either way.
            assertTrue(
                "missing seeded document: $expected (have: $titles)",
                titles.any { it == expected || it.startsWith("$expected.") },
            )
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
        /** Titles imported into the seed snapshot; keep in sync with the spec. */
        val SEEDED_TITLES = listOf("test_1page", "test.epub", "test.cbz")
    }
}
