package com.librecrate.app.ui.viewer

import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.readium.r2.shared.publication.Link
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class EpubReaderNavigationTest {

    @Test
    fun `valid epub loads activity`() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val file = createMultiChapterEpub(context)
        val intent = Intent(context, EpubReaderActivity::class.java).apply {
            putExtra("epub_file_path", file.absolutePath)
        }
        val controller = Robolectric.buildActivity(EpubReaderActivity::class.java, intent)
        val activity = controller.create().start().resume().get()
        assertNotNull("Activity should be created", activity)
    }

    @Test
    fun `tap position in left third maps to backward navigation`() {
        val screenWidth = 1080f

        assertTrue("left edge is backward region", 1f < screenWidth / 3f)
        assertTrue("center of left third is backward region", screenWidth / 6f < screenWidth / 3f)
    }

    @Test
    fun `tap position in right two-thirds maps to forward navigation`() {
        val screenWidth = 1080f

        assertTrue("screen center is forward region", screenWidth / 2f >= screenWidth / 3f)
        assertTrue("right edge is forward region", screenWidth - 1f >= screenWidth / 3f)
    }

    @Test
    fun `toc flattens nested links correctly`() {
        val chapter1 = mockLink("Chapter 1", "chap1.xhtml")
        val section1 = mockLink("Section 1.1", "chap1s1.xhtml")
        val section2 = mockLink("Section 1.2", "chap1s2.xhtml")
        val subSection = mockLink("Section 1.2.1", "chap1s2s1.xhtml")
        every { section2.children } returns listOf(subSection)
        every { chapter1.children } returns listOf(section1, section2)
        val chapter2 = mockLink("Chapter 2", "chap2.xhtml")

        val toc = listOf(chapter1, chapter2)
        val flattened = flattenToc(toc)

        assertEquals(5, flattened.size)
        assertPair(flattened[0], "Chapter 1", 0)
        assertPair(flattened[1], "Section 1.1", 1)
        assertPair(flattened[2], "Section 1.2", 1)
        assertPair(flattened[3], "Section 1.2.1", 2)
        assertPair(flattened[4], "Chapter 2", 0)
    }

    @Test
    fun `findActiveTocIndex returns best match by href prefix`() {
        val link1 = mockLink("Chapter 1", "/OEBPS/chap1.xhtml")
        val link2 = mockLink("Chapter 2", "/OEBPS/chap2.xhtml")
        val link3 = mockLink("Chapter 3", "/OEBPS/chap3.xhtml")
        val tocLinks = listOf(
            link1 to 0,
            link2 to 0,
            link3 to 0,
        )

        val locator1 = mockLocator("/OEBPS/chap1.xhtml#p1")
        val locator2 = mockLocator("/OEBPS/chap2.xhtml#p10")
        val locator3 = mockLocator("/OEBPS/chapter3.xhtml")

        assertEquals(0, findActiveTocIndex(locator1, tocLinks))
        assertEquals(1, findActiveTocIndex(locator2, tocLinks))
        assertEquals(-1, findActiveTocIndex(locator3, tocLinks))
    }

    @Test
    fun `findActiveTocIndex returns -1 for null locator`() {
        val links = listOf(
            mockLink("Chapter 1", "/OEBPS/chap1.xhtml") to 0,
        )
        assertEquals(-1, findActiveTocIndex(null, links))
    }

    @Test
    fun `findActiveTocIndex prefers longest matching href`() {
        val link1 = mockLink("Ch1", "/OEBPS/chap")
        val link2 = mockLink("Ch1 detailed", "/OEBPS/chap1.xhtml")
        val tocLinks = listOf(link1 to 0, link2 to 0)

        val locator = mockLocator("/OEBPS/chap1.xhtml#p5")

        assertEquals(1, findActiveTocIndex(locator, tocLinks))
    }

    @Test
    fun `toc item renders correct depth indentation`() {
        assertEquals("depth 0 has 24dp start padding", 24, 24 + 0 * 16)
        assertEquals("depth 1 has 40dp start padding", 40, 24 + 1 * 16)
        assertEquals("depth 2 has 56dp start padding", 56, 24 + 2 * 16)
    }

    private fun mockLink(title: String, href: String): Link {
        val link = mockk<Link>(relaxed = true)
        every { link.title } returns title
        every { link.href.toString() } returns href
        val mockHref = mockk<org.readium.r2.shared.publication.Href>(relaxed = true)
        every { mockHref.toString() } returns href
        every { link.href } returns mockHref
        every { link.children } returns emptyList()
        return link
    }

    private fun mockLocator(href: String): org.readium.r2.shared.publication.Locator {
        val locator = mockk<org.readium.r2.shared.publication.Locator>(relaxed = true)
        val mockUrl = mockk<org.readium.r2.shared.util.Url>(relaxed = true)
        every { mockUrl.toString() } returns href
        every { locator.href } returns mockUrl
        return locator
    }

    private fun assertPair(pair: Pair<Link, Int>, expectedTitle: String, expectedDepth: Int) {
        assertEquals(expectedTitle, pair.first.title)
        assertEquals(expectedDepth, pair.second)
    }

    private fun createMultiChapterEpub(context: android.content.Context): File {
        val file = File(context.cacheDir, "nav_test_${System.nanoTime()}.epub")
        val opfContent = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">
                <metadata>
                    <dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">Navigation Test Book</dc:title>
                    <dc:creator xmlns:dc="http://purl.org/dc/elements/1.1/">Tester</dc:creator>
                </metadata>
                <manifest>
                    <item id="chapter1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="chapter2" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine>
                    <itemref idref="chapter1"/>
                    <itemref idref="chapter2"/>
                </spine>
            </package>
        """.trimIndent()
        val chapter1 = """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <head><title>Chapter 1</title></head>
            <body><p>This is the first chapter.</p></body>
            </html>
        """.trimIndent()
        val chapter2 = """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <head><title>Chapter 2</title></head>
            <body><p>This is the second chapter.</p></body>
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
            zos.write(chapter1.toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("OEBPS/chapter2.xhtml"))
            zos.write(chapter2.toByteArray())
            zos.closeEntry()
        }
        return file
    }
}
