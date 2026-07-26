package com.librecrate.app.reader.pdf

import android.graphics.Bitmap
import com.librecrate.app.vault.reader.DocumentReader
import com.librecrate.app.vault.reader.RenderConfig
import com.librecrate.app.vault.reader.RenderedPage
import com.librecrate.app.vault.reader.models.DocumentMetadata
import com.librecrate.app.vault.reader.models.ReaderLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.vault_native.PdfHandle
import java.nio.ByteBuffer

class PdfDocumentReader(filePath: String) : DocumentReader {

    private val handle: PdfHandle = try {
        PdfHandle.open(filePath)
    } catch (e: Exception) {
        throw RuntimeException("Failed to open PDF: $filePath", e)
    }

    override val pageCount: Int by lazy { handle.pageCount() }

    override val metadata: DocumentMetadata by lazy {
        DocumentMetadata(
            title = try { handle.metadata("title") } catch (_: Exception) { "" }
                .takeIf { it.isNotBlank() } ?: "",
            author = try { handle.metadata("author") } catch (_: Exception) { "" },
            pageCount = pageCount,
        )
    }

    override fun currentLocation(): ReaderLocation {
        return ReaderLocation(pageIndex = 0)
    }

    fun renderPageBitmap(pageIndex: Int, targetWidthPx: Int? = null): Bitmap {
        val target = targetWidthPx ?: 0
        val rendered = handle.renderPage(pageIndex, target)
        val bitmap = Bitmap.createBitmap(
            rendered.width, rendered.height,
            Bitmap.Config.ARGB_8888,
        )
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rendered.data))
        compositeOverWhite(bitmap)
        return bitmap
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
                try {
                    val text = handle.extractText(i)
                    if (text.isNotBlank()) {
                        appendLine(text)
                    }
                } catch (_: Exception) { }
            }
        }.takeIf { it.isNotBlank() }
    }

    override fun close() {
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
