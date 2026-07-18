package com.librecrate.app.reader.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.librecrate.app.vault.reader.DocumentProcessor
import com.librecrate.app.vault.reader.ProcessorResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

class PdfDocumentProcessor : DocumentProcessor {

    override suspend fun process(input: File, mimeType: String): ProcessorResult = withContext(Dispatchers.IO) {
        val doc = Document.openDocument(input.absolutePath)
        try {
            val title = doc.getMetaData(Document.META_INFO_TITLE)
                ?.takeIf { it.isNotBlank() } ?: input.nameWithoutExtension
            val author = doc.getMetaData(Document.META_INFO_AUTHOR) ?: ""
            val pageCount = doc.countPages()

            val textContent = buildString {
                for (i in 0 until pageCount) {
                    val page = doc.loadPage(i)
                    try {
                        val stext = page.toStructuredText()
                        try {
                            val text = stext.asText()
                            if (text.isNotBlank()) {
                                appendLine(text)
                            }
                        } finally {
                            stext.destroy()
                        }
                    } finally {
                        page.destroy()
                    }
                    append("[PAGE=${i + 1}]")
                }
            }.takeIf { it.isNotBlank() }

            val thumbnailData = if (pageCount > 0) {
                generateThumbnail(doc)
            } else null

            ProcessorResult(
                title = title,
                author = author,
                pageCount = pageCount,
                textContent = textContent,
                thumbnailData = thumbnailData,
            )
        } finally {
            doc.destroy()
        }
    }

    private fun generateThumbnail(doc: Document): ByteArray? {
        val page = doc.loadPage(0)
        return try {
            val bounds = page.bounds
            val pageWidth = bounds.x1 - bounds.x0
            val scale = 200f / pageWidth
            val matrix = Matrix(scale, 0f, 0f, scale, 0f, 0f)
            val pixmap = page.toPixmap(matrix, ColorSpace.DeviceRGB, true)
            try {
                val bitmap = Bitmap.createBitmap(
                    pixmap.width, pixmap.height,
                    Bitmap.Config.ARGB_8888,
                )
                bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(pixmap.samples))
                val pixels = IntArray(pixmap.width * pixmap.height)
                bitmap.getPixels(pixels, 0, pixmap.width, 0, 0, pixmap.width, pixmap.height)
                for (i in pixels.indices) {
                    if (pixels[i] ushr 24 == 0) {
                        pixels[i] = -0x1
                    }
                }
                bitmap.setPixels(pixels, 0, pixmap.width, 0, 0, pixmap.width, pixmap.height)
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                out.toByteArray()
            } finally {
                pixmap.destroy()
            }
        } finally {
            page.destroy()
        }
    }
}
