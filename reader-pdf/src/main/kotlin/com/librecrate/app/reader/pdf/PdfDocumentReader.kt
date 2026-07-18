package com.librecrate.app.reader.pdf

import android.graphics.Bitmap
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.librecrate.app.vault.reader.DocumentReader
import com.librecrate.app.vault.reader.RenderConfig
import com.librecrate.app.vault.reader.RenderedPage
import com.librecrate.app.vault.reader.models.DocumentMetadata
import com.librecrate.app.vault.reader.models.ReaderLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

class PdfDocumentReader(filePath: String) : DocumentReader {

    private val document: Document = try {
        Document.openDocument(filePath)
    } catch (e: Exception) {
        throw RuntimeException("Failed to open PDF: $filePath", e)
    }

    override val pageCount: Int by lazy { document.countPages() }

    override val metadata: DocumentMetadata by lazy {
        DocumentMetadata(
            title = document.getMetaData(Document.META_INFO_TITLE)
                ?.takeIf { it.isNotBlank() } ?: "",
            author = document.getMetaData(Document.META_INFO_AUTHOR) ?: "",
            pageCount = pageCount,
        )
    }

    override fun currentLocation(): ReaderLocation {
        return ReaderLocation(pageIndex = 0)
    }

    override suspend fun renderPage(pageIndex: Int, config: RenderConfig): RenderedPage {
        return withContext(Dispatchers.IO) {
            val page = document.loadPage(pageIndex)
            try {
                val scale = 150f / 72f
                val matrix = Matrix(scale, 0f, 0f, scale, 0f, 0f)
                val alpha = !config.nightMode
                val pixmap = page.toPixmap(matrix, ColorSpace.DeviceRGB, alpha)
                try {
                    val bitmap = Bitmap.createBitmap(
                        pixmap.width, pixmap.height,
                        Bitmap.Config.ARGB_8888,
                    )
                    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixmap.samples))
                    fixTransparentPixels(bitmap)

                    if (config.nightMode) {
                        invertBitmapColors(bitmap)
                    }

                    val buffer = ByteBuffer.allocate(bitmap.byteCount)
                    bitmap.copyPixelsToBuffer(buffer)
                    RenderedPage(
                        width = bitmap.width,
                        height = bitmap.height,
                        pixelData = buffer.array(),
                    )
                } finally {
                    pixmap.destroy()
                }
            } finally {
                page.destroy()
            }
        }
    }

    override fun extractText(): String? {
        return buildString {
            for (i in 0 until pageCount) {
                val page = document.loadPage(i)
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
            }
        }.takeIf { it.isNotBlank() }
    }

    override fun close() {
        document.destroy()
    }

    private fun fixTransparentPixels(bitmap: Bitmap) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var hasTransparent = false
        for (i in pixels.indices) {
            if (pixels[i] ushr 24 == 0) {
                pixels[i] = -0x1
                hasTransparent = true
            }
        }
        if (hasTransparent) {
            bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
    }

    private fun invertBitmapColors(bitmap: Bitmap) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p ushr 24
            val r = 255 - ((p shr 16) and 0xFF)
            val g = 255 - ((p shr 8) and 0xFF)
            val b = 255 - (p and 0xFF)
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
}
