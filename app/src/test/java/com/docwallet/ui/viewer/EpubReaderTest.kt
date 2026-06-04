package com.docwallet.ui.viewer

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpubReaderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().applicationContext
    }

    @Test
    fun `parseSpineItems returns correct items for valid EPUB`() {
        val file = createTestEpub(
            title = "Test Book",
            author = "Author Name",
            body = "<p>Chapter content here.</p>",
        )

        val items = parseSpineItems(file)

        assertEquals(1, items.size)
        assertEquals("chapter1", items[0].id)
        assertTrue(items[0].href.contains("chapter1.xhtml"))
    }

    @Test
    fun `loadSpineContent returns body text for valid EPUB`() {
        val bodyText = "<p>Hello, this is the EPUB content!</p>"
        val file = createTestEpub(
            title = "Content Test",
            author = "Test",
            body = bodyText,
        )

        val items = parseSpineItems(file)
        val content = loadSpineContent(file, items, 0)

        assertNotNull("Content should be loaded", content)
        assertTrue("Content should contain body text", content!!.contains("Hello, this is the EPUB content!"))
    }

    @Test
    fun `loadSpineContent returns null for out of bounds index`() {
        val file = createTestEpub(title = "T", author = "A", body = "<p>Test</p>")
        val items = parseSpineItems(file)

        assertNull(loadSpineContent(file, items, -1))
        assertNull(loadSpineContent(file, items, 1))
    }

    @Test
    fun `extractBody extracts content between body tags`() {
        val html = "<html><head></head><body><p>Hello World</p></body></html>"
        val body = extractBody(html)
        assertEquals("<p>Hello World</p>", body)
    }

    @Test
    fun `extractBody handles body with attributes`() {
        val html = "<html><body class=\"main\"><p>Content</p></body></html>"
        val body = extractBody(html)
        assertEquals("<p>Content</p>", body)
    }

    @Test
    fun `extractBody returns null when no body tag`() {
        val html = "<html><div>No body</div></html>"
        assertNull(extractBody(html))
    }

    @Test
    fun `shows navigation when EPUB has spine items`() {
        val file = createTestEpub(
            title = "Test EPUB",
            author = "Test Author",
            body = "<p>Hello from the EPUB!</p>",
        )

        composeTestRule.setContent {
            EpubReader(file = file)
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Previous").assertExists()
        composeTestRule.onNodeWithContentDescription("Next").assertExists()
        composeTestRule.onNodeWithText("1 / 1").assertExists()
    }

    @Test
    fun `shows navigation for multi-section EPUB`() {
        val file = createMultiSectionEpub()

        composeTestRule.setContent {
            EpubReader(file = file)
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Previous").assertExists()
        composeTestRule.onNodeWithContentDescription("Next").assertExists()
        composeTestRule.onNodeWithText("1 / 3").assertExists()
    }

    @Test
    fun `navigation updates spine content on section change`() {
        val file = createMultiSectionEpub()

        composeTestRule.setContent {
            EpubReader(file = file)
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Next").assertExists()
        composeTestRule.onNodeWithText("1 / 3").assertExists()
    }

    @Test
    fun `full round trip creates EPUB with correct spine and content`() {
        val expectedBody = "<p>Full round-trip content</p>"
        val file = createTestEpub(
            title = "Round Trip",
            author = "Test",
            body = expectedBody,
        )

        val items = parseSpineItems(file)
        assertEquals(1, items.size)

        val content = loadSpineContent(file, items, 0)
        assertNotNull("Content should not be null", content)
        assertEquals("Body content should match", expectedBody, content)
    }

    private fun createTestEpub(title: String, author: String, body: String): File {
        val file = File(context.cacheDir, "test_${System.nanoTime()}.epub")

        val opfContent = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">
                <metadata>
                    <dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">$title</dc:title>
                    <dc:creator xmlns:dc="http://purl.org/dc/elements/1.1/">$author</dc:creator>
                </metadata>
                <manifest>
                    <item id="chapter1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine>
                    <itemref idref="chapter1"/>
                </spine>
            </package>
        """.trimIndent()

        val xhtmlContent = """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <head><title>$title</title></head>
            <body>$body</body>
            </html>
        """.trimIndent()

        val containerXml = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                </rootfiles>
            </container>
        """.trimIndent()

        ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("mimetype"))
            zos.write("application/epub+zip".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(containerXml.toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zos.write(opfContent.toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/chapter1.xhtml"))
            zos.write(xhtmlContent.toByteArray())
            zos.closeEntry()
        }

        return file
    }

    private fun createMultiSectionEpub(): File {
        val file = File(context.cacheDir, "test_${System.nanoTime()}.epub")

        val opfContent = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">
                <metadata>
                    <dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">Multi-Section</dc:title>
                </metadata>
                <manifest>
                    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch3" href="ch3.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine>
                    <itemref idref="ch1"/>
                    <itemref idref="ch2"/>
                    <itemref idref="ch3"/>
                </spine>
            </package>
        """.trimIndent()

        val containerXml = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                </rootfiles>
            </container>
        """.trimIndent()

        ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("mimetype"))
            zos.write("application/epub+zip".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(containerXml.toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zos.write(opfContent.toByteArray())
            zos.closeEntry()

            for (i in 1..3) {
                zos.putNextEntry(ZipEntry("OEBPS/ch$i.xhtml"))
                zos.write("""<html xmlns="http://www.w3.org/1999/xhtml"><body><p>Section $i content</p></body></html>""".toByteArray())
                zos.closeEntry()
            }
        }

        return file
    }
}
