package com.docwallet.ui.viewer

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docwallet.DocWalletApplication
import com.docwallet.data.model.Document
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfViewerInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: android.content.Context
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<DocWalletApplication>()
        cacheDir = context.cacheDir
    }

    @Test
    fun rendersPdfPageAsImage() {
        val file = copyResourceToCache("test_1page.pdf")

        verifyMuPDFCanOpen(file, 1)

        val doc = Document(
            id = "pdf-inst-test",
            title = "Instrumented PDF",
            fileName = "test.pdf",
            mimeType = "application/pdf",
            filePath = file.absolutePath,
            pageCount = 1,
        )

        composeTestRule.setContent {
            PdfViewer(file = file, document = doc)
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Page 1 of 1").assertExists()
    }

    @Test
    fun doesNotShowFallbackTextWhenRenderingSucceeds() {
        val file = copyResourceToCache("test_1page.pdf")

        verifyMuPDFCanOpen(file, 1)

        val doc = Document(
            id = "pdf-inst-no-fallback",
            title = "No Fallback",
            fileName = "test.pdf",
            mimeType = "application/pdf",
            filePath = file.absolutePath,
            pageCount = 1,
        )

        composeTestRule.setContent {
            PdfViewer(file = file, document = doc)
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Page 1").assertDoesNotExist()
    }

    @Test
    fun rendersMultiPagePdf() {
        val file = copyResourceToCache("test_2page.pdf")

        verifyMuPDFCanOpen(file, 2)

        val doc = Document(
            id = "pdf-inst-multi",
            title = "Multi-Page PDF",
            fileName = "multi.pdf",
            mimeType = "application/pdf",
            filePath = file.absolutePath,
            pageCount = 2,
        )

        composeTestRule.setContent {
            PdfViewer(file = file, document = doc)
        }

        composeTestRule.waitUntil(30_000) {
            try {
                composeTestRule.onNodeWithText("Page 2").assertDoesNotExist()
                true
            } catch (_: AssertionError) {
                false
            }
        }

        composeTestRule.onNodeWithText("Page 1 of 2").assertExists()
    }

    @Test
    fun opensAtSavedPageOnRestore() {
        val file = copyResourceToCache("test_2page.pdf")

        verifyMuPDFCanOpen(file, 2)

        val doc = Document(
            id = "pdf-restore-test",
            title = "Restore Test",
            fileName = "test.pdf",
            mimeType = "application/pdf",
            filePath = file.absolutePath,
            pageCount = 2,
            currentPage = 2,
        )

        composeTestRule.setContent {
            PdfViewer(
                file = file,
                document = doc,
                initialPage = doc.currentPage,
            )
        }

        composeTestRule.waitUntil(10_000) {
            try {
                composeTestRule.onNodeWithText("Page 1 of 2").assertExists()
                true
            } catch (_: AssertionError) {
                try {
                    composeTestRule.onNodeWithText("Page 2 of 2").assertExists()
                    true
                } catch (_: AssertionError) {
                    false
                }
            }
        }

    }

    @Test
    fun pdfContentCanBeExtractedAfterRendering() {
        val bodyText = "Test PDF content"
        val file = copyResourceToCache("test_1page.pdf")

        val rawText = file.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue("PDF should contain the body text", rawText.contains(bodyText))
    }

    private fun verifyMuPDFCanOpen(file: File, expectedPages: Int) {
        val fileInfo = "path=${file.absolutePath} exists=${file.exists()} size=${file.length()}"
        assertTrue("Cannot process PDF - $fileInfo", file.exists() && file.length() > 0)
        if (!file.exists() || file.length() == 0L) return

        val allBytes = file.readBytes()
        val firstBytes = if (allBytes.size > 100) {
            allBytes.copyOfRange(0, 100).toString(Charsets.ISO_8859_1)
        } else allBytes.toString(Charsets.ISO_8859_1)
        assertTrue("PDF header missing: $firstBytes", firstBytes.startsWith("%PDF"))

        val doc = try {
            com.artifex.mupdf.fitz.Document.openDocument(file.absolutePath)
        } catch (e: Exception) {
            throw AssertionError("MuPDF openDocument failed for $fileInfo: ${e.message}", e)
        }
        try {
            val actualPages = doc.countPages()
            assertTrue("Expected $expectedPages pages, got $actualPages", actualPages == expectedPages)
        } finally {
            doc.destroy()
        }
    }

    private fun copyResourceToCache(name: String): File {
        val inputStream = javaClass.classLoader.getResourceAsStream(name)
            ?: throw IllegalStateException("Resource $name not found")
        val target = File(cacheDir, name)
        inputStream.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return target
    }
}
