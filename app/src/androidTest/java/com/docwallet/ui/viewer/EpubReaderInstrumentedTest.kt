package com.docwallet.ui.viewer

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.docwallet.DocWalletApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class EpubReaderInstrumentedTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<DocWalletApplication>()
    }

    @Test
    fun showsNavigationControls() {
        val file = createTestEpub(
            title = "Navigation Test",
            author = "Test",
            body = "<p>Navigation content</p>",
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
    fun showsSpineCountForMultiSectionEpub() {
        val file = createMultiSectionEpub()

        composeTestRule.setContent {
            EpubReader(file = file)
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("1 / 3").assertExists()
    }

    @Test
    fun webViewLoadHtmlContent() {
        val expectedContent = "WebView content on device"
        val file = createTestEpub(
            title = "WebView Test",
            author = "Test",
            body = "<p>$expectedContent</p>",
        )

        composeTestRule.setContent {
            EpubReader(file = file)
        }

        val webView = waitForWebView()
        assertNotNull("WebView should be present in the view hierarchy", webView)

        val content = pollWebViewContent(webView!!, 15_000)
        assertTrue(
            "WebView should contain the expected content. Got: $content",
            content?.contains(expectedContent) == true,
        )
    }

    @Test
    fun webViewShowsFirstSectionOfMultiSectionEpub() {
        val file = createMultiSectionEpub()

        val items = parseSpineItems(file)
        assertEquals("Should have 3 spine items", 3, items.size)

        val loadedContent = loadSpineContent(file, items, 0)
        assertNotNull("loadSpineContent should not be null. items[0].href=${items[0].href}", loadedContent)

        composeTestRule.setContent {
            EpubReader(file = file)
        }

        val webView = waitForWebView()
        assertNotNull("WebView should be present", webView)

        val content = pollWebViewContent(webView!!, 15_000)
        assertTrue(
            "First section should show 'Section 1 content'. Got: $content",
            content?.contains("Section 1") == true,
        )
    }

    private fun waitForWebView(): WebView? {
        composeTestRule.waitForIdle()
        var webView: WebView? = null
        composeTestRule.waitUntil(10_000) {
            webView = findWebView(composeTestRule.activity.window.decorView)
            webView != null
        }
        return webView
    }

    private fun pollWebViewContent(webView: WebView, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var result: String? = null
        while (System.currentTimeMillis() < deadline) {
            val latch = CountDownLatch(1)
            webView.post {
                try {
                    webView.evaluateJavascript("document.body.innerText") { r ->
                        result = r
                        latch.countDown()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("EPUB_TEST", "evaluateJavascript failed", e)
                    latch.countDown()
                }
            }
            latch.await(2, TimeUnit.SECONDS)
            if (result != null && result!!.isNotEmpty() && result != "\"\"") return result
            Thread.sleep(200)
        }
        return result
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findWebView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun createTestEpub(title: String, author: String, body: String): File {
        val file = File(context.cacheDir, "inst_test_${System.nanoTime()}.epub")

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
        val file = File(context.cacheDir, "inst_test_${System.nanoTime()}.epub")

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
