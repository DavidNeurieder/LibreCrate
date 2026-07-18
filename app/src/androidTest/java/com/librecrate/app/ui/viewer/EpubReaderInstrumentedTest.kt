package com.librecrate.app.ui.viewer

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class EpubReaderInstrumentedTest {

    @Test
    fun activityLaunchesWithValidEpub() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = createTestEpub(context)
        val intent = Intent(context, EpubReaderActivity::class.java).apply {
            putExtra("epub_file_path", file.absolutePath)
        }

        val scenario = ActivityScenario.launch<EpubReaderActivity>(intent)
        scenario.onActivity { activity ->
            assertNotNull(activity)
        }
        scenario.close()
    }

    private fun createTestEpub(context: android.content.Context): File {
        val file = File(context.cacheDir, "inst_test_${System.nanoTime()}.epub")
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
