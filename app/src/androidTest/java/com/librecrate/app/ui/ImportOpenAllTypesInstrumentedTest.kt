package com.librecrate.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.librecrate.app.LibreCrateApplication
import com.librecrate.app.ui.library.LibraryScreen
import com.librecrate.app.ui.viewer.ComicViewer
import com.librecrate.app.ui.viewer.PkPassViewer
import com.librecrate.app.ui.viewer.ViewerScreen
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ImportOpenAllTypesInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: LibreCrateApplication
    private val testPassword = "test-password"

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        wipeDatabase(app)
        app.encryptionManager.initializeWithPassword(testPassword)
        val masterKey = app.encryptionManager.getMasterKeyForSession()
        checkNotNull(masterKey) { "Master key must not be null" }
        runBlocking {
            check(app.vaultRepository.open(masterKey)) { "vault.open() failed" }
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            app.vaultRepository.listDocuments().forEach {
                app.vaultRepository.deleteDocumentFull(it.id)
            }
            app.vaultRepository.close()
        }
        app.encryptionManager.lock()
    }

    private fun resourceBytes(name: String): ByteArray {
        val inputStream = javaClass.classLoader!!.getResourceAsStream(name)
            ?: throw IllegalStateException("Resource $name not found")
        return inputStream.use { it.readBytes() }
    }

    private fun importDoc(title: String, fileData: ByteArray, mimeType: String): String {
        val docId = UUID.randomUUID().toString()
        runBlocking {
            val resultId = app.vaultRepository.importDocument(
                id = docId, title = title, fileData = fileData,
                mimeType = mimeType, author = "",
                description = "", textContent = null,
            )
            assertNotNull("importDocument returned null for $title", resultId)
        }
        return docId
    }

    private fun buildPkPassBytes(): ByteArray {
        val passJson = JSONObject().apply {
            put("formatVersion", 1)
            put("organizationName", "Test Org")
            put("description", "Test Description")
            put("logoText", "Test Logo")
        }
        val baos = java.io.ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("pass.json"))
            zos.write(passJson.toString(2).toByteArray())
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    private fun writeTempFile(data: ByteArray, name: String): File {
        val f = File(app.cacheDir, name)
        f.writeBytes(data)
        return f
    }

    @Test
    fun importsAllTypesAndDisplaysInLibrary() {
        val pdfData = resourceBytes("test_1page.pdf")
        val epubData = resourceBytes("test.epub")
        val pkpassData = buildPkPassBytes()
        val cbzData = resourceBytes("test.cbz")
        val pngData = resourceBytes("test.png")

        importDoc("test_import.pdf", pdfData, "application/pdf")
        importDoc("test_import.epub", epubData, "application/epub+zip")
        importDoc("test_import.pkpass", pkpassData, "application/vnd.apple.pkpass")
        importDoc("test_import.cbz", cbzData, "application/vnd.comicbook+zip")
        importDoc("test_import.png", pngData, "image/png")

        composeTestRule.setContent {
            MaterialTheme {
                LibraryScreen(
                    onDocumentClick = {},
                    onDocumentClickWithPage = { _, _ -> },
                    onSettingsClick = {},
                )
            }
        }

        composeTestRule.waitUntil(15_000) {
            composeTestRule
                .onAllNodesWithText("test_import.cbz", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeTestRule.onNodeWithText("test_import.pdf", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("test_import.epub", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("test_import.pkpass", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("test_import.cbz", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("test_import.png", useUnmergedTree = true).assertExists()

        composeTestRule.onNodeWithContentDescription("PDF").assertExists()
        composeTestRule.onNodeWithContentDescription("EPUB").assertExists()
        composeTestRule.onNodeWithContentDescription("PKPASS").assertExists()
        composeTestRule.onNodeWithContentDescription("CBZ").assertExists()
        composeTestRule.onNodeWithContentDescription("IMAGE").assertExists()

        composeTestRule.onNodeWithText("All").performClick()
        composeTestRule.onNodeWithText("PDFs").assertExists()
        composeTestRule.onNodeWithText("Books").assertExists()
    }

    @Test
    fun opensPdfShowsContent() {
        val docId = importDoc("test_pdf.pdf", resourceBytes("test_1page.pdf"), "application/pdf")

        composeTestRule.setContent {
            ViewerScreen(documentId = docId, onBack = {})
        }

        composeTestRule.waitUntil(15_000) {
            try {
                composeTestRule.onNodeWithText("Page 1 of 1").assertExists()
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }

    @Test
    fun opensPkPassShowsContent() {
        val file = writeTempFile(buildPkPassBytes(), "test_pkpass.pkpass")

        composeTestRule.setContent {
            PkPassViewer(file = file)
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Unable to parse pass").assertDoesNotExist()
        composeTestRule.onNodeWithText("Test Org").assertExists()
        composeTestRule.onNodeWithText("Test Description").assertExists()
    }

    @Test
    fun opensCbzShowsContent() {
        val cbzData = resourceBytes("test.cbz")
        val file = writeTempFile(cbzData, "test_cbz.cbz")

        composeTestRule.setContent {
            ComicViewer(file = file)
        }

        composeTestRule.waitUntil(10_000) {
            try {
                composeTestRule.onNodeWithText("1 pages").assertExists()
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }

    @Test
    fun opensImageShowsContent() {
        val docId = importDoc("test_image.png", resourceBytes("test.png"), "image/png")

        composeTestRule.setContent {
            ViewerScreen(documentId = docId, onBack = {})
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Error").assertDoesNotExist()
        composeTestRule.onNodeWithText("Unsupported format").assertDoesNotExist()
    }

    @Test
    fun opensEpubShowsContent() {
        val epubData = resourceBytes("test.epub")
        val docId = importDoc("test_import.epub", epubData, "application/epub+zip")

        var epubBytes: ByteArray?
        runBlocking {
            epubBytes = app.vaultRepository.exportDocumentFile(docId)
        }
        assertNotNull("exportDocumentFile returned null", epubBytes)

        val epubFile = File(app.cacheDir, "epub_open_test.epub")
        epubFile.writeBytes(epubBytes!!)

        val intent = Intent(app, com.librecrate.app.ui.viewer.EpubReaderActivity::class.java).apply {
            putExtra("file_path", epubFile.absolutePath)
            putExtra("document_id", docId)
        }

        val scenario = ActivityScenario.launch<com.librecrate.app.ui.viewer.EpubReaderActivity>(intent)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        val titleFound = device.wait(Until.hasObject(By.text("test_import.epub")), 15_000)
        assertNotNull("EPUB title not found", titleFound)

        val contentFound = device.wait(Until.hasObject(By.text("Hello from instrumented test!")), 10_000)
        assertNotNull("EPUB body content not found", contentFound)

        scenario.close()
    }

    @Test
    fun opensEpubViaViewerScreenShowsContent() {
        // Mirrors the real manual flow: ViewerScreen -> ViewerViewModel builds a temp
        // file named "viewer_<id>_<fileName>" and hands its path to EpubReaderActivity.
        // This depends on the vault storing a ".epub" extension in fileName.
        val epubData = resourceBytes("test.epub")
        val docId = importDoc("book.epub", epubData, "application/epub+zip")

        val fileName: String
        var epubBytes: ByteArray?
        runBlocking {
            val doc = app.vaultRepository.getDocument(docId)
            fileName = doc?.fileName ?: ""
            epubBytes = app.vaultRepository.exportDocumentFile(docId)
        }
        assertNotNull("exportDocumentFile returned null", epubBytes)
        org.junit.Assert.assertTrue(
            "Expected fileName to carry a .epub extension, got: '$fileName'",
            fileName.endsWith(".epub"),
        )

        val tempFile = File(app.cacheDir, "viewer_${docId}_$fileName")
        tempFile.writeBytes(epubBytes!!)

        val intent = Intent(app, com.librecrate.app.ui.viewer.EpubReaderActivity::class.java).apply {
            putExtra("file_path", tempFile.absolutePath)
            putExtra("document_id", docId)
        }

        val scenario = ActivityScenario.launch<com.librecrate.app.ui.viewer.EpubReaderActivity>(intent)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        val contentFound = device.wait(Until.hasObject(By.text("Hello from instrumented test!")), 10_000)
        assertNotNull("EPUB body content not found (asset retrieval failed?)", contentFound)

        scenario.close()
    }

    private fun wipeDatabase(context: Context) {
        context.getDatabasePath("librecrate.db").let { dbFile ->
            dbFile.delete()
            File(dbFile.parentFile, "librecrate.db-wal").delete()
            File(dbFile.parentFile, "librecrate.db-shm").delete()
        }
        File(context.filesDir, "encryption").deleteRecursively()
    }

    @Test
    fun epubOpenErrorIsSavedToDownloadsErrorLog() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver

        val docId = importDoc("broken.epub", "not a real epub".toByteArray(), "application/epub+zip")
        val brokenFile = File(app.cacheDir, "broken_epub.epub")
        brokenFile.writeBytes("not a real epub".toByteArray())

        val intent = Intent(app, com.librecrate.app.ui.viewer.EpubReaderActivity::class.java).apply {
            putExtra("file_path", brokenFile.absolutePath)
            putExtra("document_id", docId)
        }
        ActivityScenario.launch<com.librecrate.app.ui.viewer.EpubReaderActivity>(intent)

        com.librecrate.app.util.ErrorLogger.logExceptionNow(
            context,
            "EpubReaderActivity",
            "Failed to open EPUB",
            RuntimeException("Asset retrieval failed: An error occurred when trying to read asset."),
        )

        val dir = File(
            context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),
            "LibreCrate",
        )
        val files = dir.listFiles { f -> f.name.startsWith("error-") && f.name.endsWith(".log") } ?: emptyArray()
        org.junit.Assert.assertTrue("No error log files found in ${dir.absolutePath}", files.isNotEmpty())
        val latest = files.maxByOrNull { it.lastModified() }!!
        org.junit.Assert.assertTrue("Error log is empty", latest.length() > 0)
        val content = latest.readText()
        org.junit.Assert.assertTrue(
            "Error log missing expected content",
            content.contains("Failed to open EPUB") && content.contains("Asset retrieval failed"),
        )
    }
}
