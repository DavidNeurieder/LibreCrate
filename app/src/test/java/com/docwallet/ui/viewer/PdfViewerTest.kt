package com.docwallet.ui.viewer

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.docwallet.data.model.Document
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfViewerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().applicationContext
        PDFBoxResourceLoader.init(context)
    }

    @Test
    fun `renders single page as image on JVM`() {
        val bodyText = "PDF content text"
        val file = createTestPdf(bodyText)
        val doc = Document(
            id = "render-test",
            title = "Test PDF",
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
        composeTestRule.onNodeWithText("Page 1 of 1").assertExists()
    }

    @Test
    fun `renders multi page PDF as images on JVM`() {
        val file = createMultiPagePdf(2)
        val doc = Document(
            id = "multi-page-test",
            title = "Multi-Page PDF",
            fileName = "test.pdf",
            mimeType = "application/pdf",
            filePath = file.absolutePath,
            pageCount = 2,
        )

        composeTestRule.setContent {
            PdfViewer(file = file, document = doc)
        }

        composeTestRule.waitUntil(10_000) {
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
    fun `PDF file contains the expected body text`() {
        val bodyText = "Unique body text for PDF viewer test"
        val file = createTestPdf(bodyText)

        PDDocument.load(file).use { pdf ->
            val stripper = PDFTextStripper()
            val text = stripper.getText(pdf)
            assertTrue(text.contains(bodyText))
            assertEquals(1, pdf.numberOfPages)
        }
    }

    private fun createMultiPagePdf(pageCount: Int): File {
        val file = File(context.cacheDir, "test_${System.nanoTime()}.pdf")
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

    private fun createTestPdf(body: String): File {
        val file = File(context.cacheDir, "test_${System.nanoTime()}.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            doc.documentInformation.title = "Test PDF"

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
}
