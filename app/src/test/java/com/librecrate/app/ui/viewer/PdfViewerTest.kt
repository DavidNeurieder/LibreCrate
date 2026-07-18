package com.librecrate.app.ui.viewer

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfViewerTest {

    @Test
    fun `PDF file contains the expected body text`() {
        val bodyText = "Test PDF content"
        val resource = javaClass.classLoader.getResource("test_1page.pdf")
            ?: throw IllegalStateException("test_1page.pdf not found")
        val file = java.io.File(resource.toURI())

        val raw = file.readBytes()
        val rawText = raw.toString(Charsets.ISO_8859_1)
        assert(rawText.contains(bodyText)) { "PDF should contain '$bodyText'" }
    }
}
