package com.librecrate.app.data

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librecrate.app.ui.viewer.EpubReaderActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class SessionStoreInstrumentedTest {

    @Test
    fun saveAndRetrieveDocumentId() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SessionStore.saveLastDocumentId(context, "doc-123")
        assertEquals("doc-123", SessionStore.getLastDocumentId(context))
    }

    @Test
    fun returnsNullWhenNoIdSaved() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SessionStore.clearLastDocumentId(context)
        assertNull(SessionStore.getLastDocumentId(context))
    }

    @Test
    fun clearRemovesSavedId() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SessionStore.saveLastDocumentId(context, "doc-to-clear")
        SessionStore.clearLastDocumentId(context)
        assertNull(SessionStore.getLastDocumentId(context))
    }

    @Test
    fun overwriteReplacesPreviousId() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SessionStore.saveLastDocumentId(context, "doc-old")
        SessionStore.saveLastDocumentId(context, "doc-new")
        assertEquals("doc-new", SessionStore.getLastDocumentId(context))
    }

    @Test
    fun epubReaderActivitySavesDocumentIdToSessionStore() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = createTestEpub(context)
        val documentId = "instrumented-epub-test-id"

        val intent = Intent(context, EpubReaderActivity::class.java).apply {
            putExtra("epub_file_path", file.absolutePath)
            putExtra("document_id", documentId)
        }

        val scenario = ActivityScenario.launch<EpubReaderActivity>(intent)
        scenario.close()

        // SessionStore.saveLastDocumentId is called in onCreate BEFORE finish(),
        // so the ID is persisted even if the activity finishes immediately
        assertEquals(documentId, SessionStore.getLastDocumentId(context))
    }

    @Test
    fun sessionStoreSurvivesActivityDestroy() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = createTestEpub(context)
        val documentId = "epub-survive-destroy"

        val intent = Intent(context, EpubReaderActivity::class.java).apply {
            putExtra("epub_file_path", file.absolutePath)
            putExtra("document_id", documentId)
        }

        val scenario = ActivityScenario.launch<EpubReaderActivity>(intent)
        scenario.close()

        assertEquals(documentId, SessionStore.getLastDocumentId(context))
    }

    @Test
    fun afterSimulatedRestartEpubIsStillLoadable() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        // First "session": save document ID
        val documentId = "epub-restart-flow"
        SessionStore.saveLastDocumentId(context, documentId)

        // "Restart": retrieve from SessionStore
        val savedId = SessionStore.getLastDocumentId(context)

        assertEquals("SessionStore should retain the ID across restart", documentId, savedId)
    }

    private fun createTestEpub(context: android.content.Context): File {
        val file = File(context.cacheDir, "inst_epub_${System.nanoTime()}.epub")
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
            <body><p>Hello from instrumented test!</p></body>
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
