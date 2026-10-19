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
import java.util.UUID

/**
 * One-shot generator for the v0.5.6 seed snapshot used by the upgrade E2E test.
 *
 * This file is NOT part of the normal test suite. It is kept here so the frozen
 * snapshot can be regenerated. To use it, copy it into a v0.5.6 worktree and
 * build that worktree's androidTest APK:
 *
 *     git worktree add /tmp/librecrate-v0.5.6 v0.5.6
 *     cp scripts/seed_v0_5_6/SeedVaultTest.kt \
 *        /tmp/librecrate-v0.5.6/app/src/androidTest/java/com/librecrate/app/data/
 *     cd /tmp/librecrate-v0.5.6 && ./gradlew :app:assembleDebug
 *     adb install -r app/build/outputs/apk/debug/app-debug.apk
 *     ./gradlew :app:assembleDebugAndroidTest
 *     adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 *     adb shell pm clear com.librecrate.app
 *     adb shell am instrument -w -e class com.librecrate.app.data.SeedVaultTest \
 *       com.librecrate.app.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Then snapshot the app-private dir, streaming to the host to avoid writing a
 * large archive to device storage:
 *
 *     adb exec-out run-as com.librecrate.app \
 *       sh -c 'cd /data/data/com.librecrate.app && tar czf - .' \
 *       > app/src/androidTest/resources/upgrade_v0_5_6_seed.tar.gz
 *
 * Titles are passed straight to `VaultRepository.importDocument` rather than
 * through `DocumentImporter`, because the importer derives the title from a
 * content-URI display name, which is unavailable for local files. The stored
 * bytes, DB rows, and encrypted files are identical either way. `textContent` is
 * left null; only the stored document data matters for upgrade verification.
 */
@RunWith(AndroidJUnit4::class)
class SeedVaultTest {
    @Test
    fun createVaultAndImportFixtures() {
        val app = ApplicationProvider.getApplicationContext<LibreCrateApplication>()

        assertTrue("initializeWithPassword", app.encryptionManager.initializeWithPassword(PASSWORD))
        assertTrue("openVault", runBlocking { app.openVault() })

        for ((name, mime) in FIXTURES) {
            val bytes = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()
            val resultId = runBlocking {
                app.vaultRepository.importDocument(
                    id = UUID.randomUUID().toString(),
                    title = name,
                    fileData = bytes,
                    mimeType = mime,
                    author = "",
                    description = "",
                    textContent = null,
                )
            }
            assertTrue("import $name returned null", resultId != null)
        }

        val docs = runBlocking { app.vaultRepository.documents.first() }
        assertEquals(
            "unexpected document set: ${docs.map { it.title }}",
            FIXTURES.map { it.first }.toSet(),
            docs.map { it.title }.toSet(),
        )
        assertEquals("document count", FIXTURES.size, docs.size)
    }

    companion object {
        const val PASSWORD = "upgrade-test"
        val FIXTURES = listOf(
            "test_1page.pdf" to "application/pdf",
            "test.epub" to "application/epub+zip",
            "test.cbz" to "application/vnd.comicbook+zip",
        )
    }
}
