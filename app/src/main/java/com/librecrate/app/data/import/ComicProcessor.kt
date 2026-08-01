package com.librecrate.app.data.import

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.librecrate.app.util.ErrorLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile

class ComicProcessor : DocumentProcessor {

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    override suspend fun process(input: File, mimeType: String): ProcessorResult = withContext(Dispatchers.IO) {
        val entries = readZipEntries(input)
        val imageEntries = entries.filter { isImageEntry(it) }
        val pageCount = imageEntries.size

        val thumbnailBitmap = imageEntries.firstOrNull()?.let { entryName ->
            decodeZipImage(input, entryName)?.let { scaleToWidth(it, 200) }
        }

        ProcessorResult(
            title = input.nameWithoutExtension,
            author = "",
            pageCount = pageCount,
            textContent = null,
            thumbnailBitmap = thumbnailBitmap,
        )
    }

    private fun readZipEntries(file: File): List<String> {
        val result = mutableListOf<String>()
        try {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory) {
                        result.add(entry.name)
                    }
                }
            }
        } catch (e: Exception) {
            ErrorLogger.logWarning(null, "ComicProcessor", "readZipEntries failed", e)
        }
        return result
    }

    private fun decodeZipImage(file: File, entryName: String): Bitmap? {
        return try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(entryName) ?: return@use null
                BitmapFactory.decodeStream(zip.getInputStream(entry))
            }
        } catch (e: Exception) {
            ErrorLogger.logWarning(null, "ComicProcessor", "decodeZipImage failed", e)
            null
        }
    }

    private fun isImageEntry(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in imageExtensions
    }

    private fun scaleToWidth(source: Bitmap, targetWidth: Int): Bitmap {
        val targetHeight = (targetWidth * source.height) / source.width
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight.coerceAtLeast(1), true)
    }
}
