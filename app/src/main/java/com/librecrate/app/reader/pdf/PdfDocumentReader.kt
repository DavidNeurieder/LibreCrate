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

    fun renderPageBitmap(pageIndex: Int, targetWidthPx: Int? = null): Bitmap {
        val page = document.loadPage(pageIndex)
        try {
            val pageWidthPoints = (page.bounds.x1 - page.bounds.x0).toDouble()
            val scale = if (targetWidthPx != null) targetWidthPx.toFloat() / pageWidthPoints.toFloat() else (150f / 72f)
            val matrix = Matrix(scale, 0f, 0f, scale, 0f, 0f)
            val pixmap = page.toPixmap(matrix, ColorSpace.DeviceRGB, true)
            try {
                val bitmap = Bitmap.createBitmap(
                    pixmap.width, pixmap.height,
                    Bitmap.Config.ARGB_8888,
                )
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixmap.samples))
                compositeOverWhite(bitmap)
                return bitmap
            } finally {
                pixmap.destroy()
            }
        } finally {
            page.destroy()
        }
    }

    override suspend fun renderPage(pageIndex: Int, config: RenderConfig): RenderedPage {
        return withContext(Dispatchers.IO) {
            val bitmap = renderPageBitmap(pageIndex)
            try {
                val buffer = ByteBuffer.allocate(bitmap.byteCount)
                bitmap.copyPixelsToBuffer(buffer)
                RenderedPage(
                    width = bitmap.width,
                    height = bitmap.height,
                    pixelData = buffer.array(),
                )
            } finally {
                bitmap.recycle()
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

    private fun compositeOverWhite(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        val rowPixels = IntArray(w)
        var changed = false
        for (y in 0 until h) {
            bitmap.getPixels(rowPixels, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = rowPixels[x]
                val a = p ushr 24
                if (a != 0xFF) {
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val af = a / 255f
                    val nr = (r * af + 255f * (1f - af)).toInt().coerceIn(0, 255)
                    val ng = (g * af + 255f * (1f - af)).toInt().coerceIn(0, 255)
                    val nb = (b * af + 255f * (1f - af)).toInt().coerceIn(0, 255)
                    rowPixels[x] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
                    changed = true
                }
            }
            if (changed) {
                bitmap.setPixels(rowPixels, 0, w, 0, y, w, 1)
            }
        }
    }
}
