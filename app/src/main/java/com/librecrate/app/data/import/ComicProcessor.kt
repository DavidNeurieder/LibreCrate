package com.librecrate.app.data.import

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.github.junrar.Archive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile

class ComicProcessor : DocumentProcessor {

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    override suspend fun process(input: File, mimeType: String): ProcessorResult = withContext(Dispatchers.IO) {
        val isCbr = mimeType == "application/x-cbr" || input.extension.equals("cbr", ignoreCase = true)

        if (isCbr) {
            val entries = readRarEntries(input)
            val imageEntries = entries.filter { isImageEntry(it) }
            val pageCount = imageEntries.size

            val thumbnailBitmap = imageEntries.firstOrNull()?.let { entryName ->
                decodeRarImage(input, entryName)?.let { scaleToWidth(it, 200) }
            }

            return@withContext ProcessorResult(
                title = input.nameWithoutExtension,
                author = "",
                pageCount = pageCount,
                textContent = null,
                thumbnailBitmap = thumbnailBitmap,
            )
        }

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
        } catch (_: Exception) {
        }
        return result
    }

    private fun decodeZipImage(file: File, entryName: String): Bitmap? {
        return try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(entryName) ?: return@use null
                BitmapFactory.decodeStream(zip.getInputStream(entry))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readRarEntries(file: File): List<String> {
        val result = mutableListOf<String>()
        try {
            FileInputStream(file).use { fis ->
                Archive(fis).use { archive ->
                    for (fh in archive.fileHeaders) {
                        if (!fh.isDirectory) {
                            result.add(fh.fileName)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun decodeRarImage(file: File, entryName: String): Bitmap? {
        return try {
            FileInputStream(file).use { fis ->
                Archive(fis).use { archive ->
                    val fh = archive.fileHeaders.firstOrNull { it.fileName == entryName }
                        ?: return@use null
                    archive.getInputStream(fh).use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }
            }
        } catch (_: Exception) {
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
