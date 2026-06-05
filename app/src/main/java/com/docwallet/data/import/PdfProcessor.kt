package com.docwallet.data.import

import android.graphics.Bitmap
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PdfProcessor : DocumentProcessor {

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
                            append(stext.asText())
                            append("\n")
                        } finally {
                            stext.destroy()
                        }
                    } finally {
                        page.destroy()
                    }
                }
            }

            val thumbnailBitmap = if (pageCount > 0) {
                val page = doc.loadPage(0)
                try {
                    val bounds = page.bounds
                    val pageWidth = bounds.x1 - bounds.x0
                    val scale = 200f / pageWidth
                    val matrix = Matrix(scale, 0f, 0f, scale, 0f, 0f)
                    val pixmap = page.toPixmap(matrix, ColorSpace.DeviceRGB, true)
                    try {
                        val bitmap = Bitmap.createBitmap(
                            pixmap.width, pixmap.height,
                            Bitmap.Config.ARGB_8888
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
                        bitmap
                    } finally {
                        pixmap.destroy()
                    }
                } finally {
                    page.destroy()
                }
            } else null

            ProcessorResult(
                title = title,
                author = author,
                pageCount = pageCount,
                textContent = textContent,
                thumbnailBitmap = thumbnailBitmap,
            )
        } finally {
            doc.destroy()
        }
    }
}
