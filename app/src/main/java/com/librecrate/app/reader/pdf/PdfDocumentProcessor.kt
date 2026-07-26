package com.librecrate.app.reader.pdf

import android.graphics.Bitmap
import com.librecrate.app.vault.reader.DocumentProcessor
import com.librecrate.app.vault.reader.ProcessorResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.vault_native.PdfHandle
import java.io.ByteArrayOutputStream
import java.io.File

class PdfDocumentProcessor : DocumentProcessor {

    override suspend fun process(input: File, mimeType: String): ProcessorResult = withContext(Dispatchers.IO) {
        val handle = PdfHandle.open(input.absolutePath)
        try {
            val pageCount = handle.pageCount()
            val title = try { handle.metadata("title") } catch (_: Exception) { null }
                ?.takeIf { it.isNotBlank() } ?: input.nameWithoutExtension
            val author = try { handle.metadata("author") } catch (_: Exception) { null } ?: ""

            val textContent = buildString {
                for (i in 0 until pageCount) {
                    try {
                        val text = handle.extractText(i)
                        if (text.isNotBlank()) {
                            appendLine(text)
                        }
                    } catch (_: Exception) { }
                    append("[PAGE=${i + 1}]")
                }
            }.takeIf { it.isNotBlank() }

            val thumbnailData = if (pageCount > 0) {
                generateThumbnail(handle)
            } else null

            ProcessorResult(
                title = title,
                author = author,
                pageCount = pageCount,
                textContent = textContent,
                thumbnailData = thumbnailData,
            )
        } finally {
        }
    }

    private fun generateThumbnail(handle: PdfHandle): ByteArray? {
        val rendered = handle.renderPage(0, 200)
        val bitmap = Bitmap.createBitmap(
            rendered.width, rendered.height,
            Bitmap.Config.ARGB_8888,
        )
        return try {
            bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(rendered.data))
            val pixels = IntArray(rendered.width * rendered.height)
            bitmap.getPixels(pixels, 0, rendered.width, 0, 0, rendered.width, rendered.height)
            for (i in pixels.indices) {
                if (pixels[i] ushr 24 == 0) {
                    pixels[i] = -0x1
                }
            }
            bitmap.setPixels(pixels, 0, rendered.width, 0, 0, rendered.width, rendered.height)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
