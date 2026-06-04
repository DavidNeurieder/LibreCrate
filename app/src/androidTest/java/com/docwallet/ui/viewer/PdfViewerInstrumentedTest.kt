package com.docwallet.ui.viewer

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docwallet.DocWalletApplication
import com.docwallet.data.model.Document
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
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

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<DocWalletApplication>()
    }

    @Test
    fun rendersPdfPageAsImage() {
        val bodyText = "Rendered on device with 150 DPI"
        val file = createTestPdf(bodyText)
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

        composeTestRule.onNodeWithContentDescription("Page 1").assertExists()
    }

    @Test
    fun doesNotShowFallbackTextWhenRenderingSucceeds() {
        val bodyText = "No fallback text on device"
        val file = createTestPdf(bodyText)
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
        val file = createMultiPagePdf(2)
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
                composeTestRule.onNodeWithContentDescription("Page 2").assertExists()
                true
            } catch (_: AssertionError) {
                false
            }
        }

        composeTestRule.onNodeWithContentDescription("Page 1").assertExists()
        composeTestRule.onNodeWithText("Page 1 of 2").assertExists()
    }

    @Test
    fun pdfContentCanBeExtractedAfterRendering() {
        val bodyText = "Content verification in instrumented test"
        val file = createTestPdf(bodyText)

        PDDocument.load(file).use { pdf ->
            val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
            val text = stripper.getText(pdf)
            assertTrue("PDF should contain the body text", text.contains(bodyText))
        }
    }

    private fun createTestPdf(body: String): File {
        val file = File(context.cacheDir, "inst_test_${System.nanoTime()}.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(72f, 700f)
                cs.showText(body)
                cs.endText()
            }
            doc.save(file)
        }
        return file
    }

    private fun createMultiPagePdf(pageCount: Int): File {
        val file = File(context.cacheDir, "inst_test_${System.nanoTime()}.pdf")
        PDDocument().use { doc ->
            for (i in 0 until pageCount) {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 12f)
                    cs.newLineAtOffset(72f, 700f)
                    cs.showText("Page ${i + 1} content")
                    cs.endText()
                }
            }
            doc.save(file)
        }
        return file
    }
}
