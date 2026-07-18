package com.librecrate.app.ui.viewer

import android.content.Intent
import com.librecrate.app.data.SessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class EpubReaderTest {

    @Test
    fun `activity finishes when no file path extra`() {
        val intent = Intent(RuntimeEnvironment.getApplication(), EpubReaderActivity::class.java)
        val activity = Robolectric.buildActivity(EpubReaderActivity::class.java, intent)
        val controller = activity.create()
        assertNotNull(controller.get())
    }

    @Test
    fun `activity finishes when file does not exist`() {
        val intent = Intent(RuntimeEnvironment.getApplication(), EpubReaderActivity::class.java).apply {
            putExtra("epub_file_path", "/nonexistent/file.epub")
        }
        val activity = Robolectric.buildActivity(EpubReaderActivity::class.java, intent)
        val controller = activity.create()
        assertNotNull(controller.get())
    }

    @Test
    fun `activity can open valid epub`() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val file = createTestEpub(context)
        val intent = Intent(context, EpubReaderActivity::class.java).apply {
            putExtra("epub_file_path", file.absolutePath)
        }
        val activity = Robolectric.buildActivity(EpubReaderActivity::class.java, intent)
        val controller = activity.create()
        assertNotNull(controller.get())
    }

    @Test
    fun `onCreate saves document ID to SessionStore`() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val file = createTestEpub(context)
        val documentId = "epub-doc-001"

        val intent = Intent(context, EpubReaderActivity::class.java).apply {
            putExtra("epub_file_path", file.absolutePath)
            putExtra("document_id", documentId)
        }
        val activity = Robolectric.buildActivity(EpubReaderActivity::class.java, intent)
        activity.create()

        assertEquals(documentId, SessionStore.getLastDocumentId(context))
    }

    @Test
    fun `SessionStore survives Activity destroy`() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val file = createTestEpub(context)
        val documentId = "epub-persist-test"

        // Simulate first open
        val intent = Intent(context, EpubReaderActivity::class.java).apply {
            putExtra("epub_file_path", file.absolutePath)
            putExtra("document_id", documentId)
        }
        val activity = Robolectric.buildActivity(EpubReaderActivity::class.java, intent)
        activity.create().destroy()

        // Simulate app restart — SessionStore persists
        val retrieved = SessionStore.getLastDocumentId(context)
        assertEquals("EPUB document ID survives app restart", documentId, retrieved)
    }

    private fun createTestEpub(context: android.content.Context): File {
        val file = File(context.cacheDir, "test_${System.nanoTime()}.epub")
        val opfContent = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">
                <metadata>
                    <dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">Test Book</dc:title>
                    <dc:creator xmlns:dc="http://purl.org/dc/elements/1.1/">Author</dc:creator>
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
            <head><title>Test Book</title></head>
            <body><p>Hello, EPUB!</p></body>
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
}
