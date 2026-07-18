package com.librecrate.app.reader.epub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.librecrate.app.vault.reader.DocumentProcessor
import com.librecrate.app.vault.reader.ProcessorResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

class EpubDocumentProcessor : DocumentProcessor {

    override suspend fun process(input: File, mimeType: String): ProcessorResult = withContext(Dispatchers.IO) {
        val result = EpubParser.parse(input)
        val opfDir = EpubParser.resolveOpfDir(result.opfPath)
        val textContent = EpubParser.readSpineContent(input, opfDir, result.spineItems)

        val thumbnailData = result.coverPath?.let { cover ->
            resolveCoverBytes(input, opfDir, cover)
        }

        ProcessorResult(
            title = result.title.takeIf { it.isNotBlank() } ?: input.nameWithoutExtension,
            author = result.author,
            pageCount = result.spineItems.size,
            textContent = textContent,
            thumbnailData = thumbnailData,
        )
    }

    private fun resolveCoverBytes(input: File, opfDir: String, cover: String): ByteArray? {
        val zip = java.util.zip.ZipFile(input)
        try {
            val href = if (opfDir.isNotEmpty() && !cover.startsWith("/")) "$opfDir$cover" else cover
            val entry = zip.getEntry(href) ?: return null
            val data = zip.getInputStream(entry).readBytes()
            val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
            val scaled = scaleToWidth(bmp, 200)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 90, out)
            return out.toByteArray()
        } finally {
            zip.close()
        }
    }

    private fun scaleToWidth(source: Bitmap, targetWidth: Int): Bitmap {
        val targetHeight = (targetWidth * source.height) / source.width
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight.coerceAtLeast(1), true)
    }
}
