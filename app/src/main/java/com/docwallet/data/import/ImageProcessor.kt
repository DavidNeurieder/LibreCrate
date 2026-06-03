package com.docwallet.data.import

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ImageProcessor : DocumentProcessor {

    override suspend fun process(input: File, mimeType: String): ProcessorResult = withContext(Dispatchers.IO) {
        val bitmap = BitmapFactory.decodeFile(input.absolutePath)

        val pageCount = 1

        val exifTitle = try {
            val exif = ExifInterface(input.absolutePath)
            exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION) ?: ""
        } catch (_: Exception) {
            ""
        }

        val title = exifTitle.takeIf { it.isNotBlank() } ?: input.nameWithoutExtension

        val thumbnailBitmap = bitmap?.let { scaleDown(it, 200) }

        ProcessorResult(
            title = title,
            author = "",
            pageCount = pageCount,
            textContent = null,
            thumbnailBitmap = thumbnailBitmap,
        )
    }

    private fun scaleDown(source: Bitmap, maxDimension: Int): Bitmap {
        val max = maxOf(source.width, source.height)
        if (max <= maxDimension) return source
        val scale = maxDimension.toFloat() / max
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }
}
