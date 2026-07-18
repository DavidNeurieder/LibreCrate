package com.librecrate.app.data.import

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

class PkPassProcessor : DocumentProcessor {

    override suspend fun process(input: File, mimeType: String): ProcessorResult = withContext(Dispatchers.IO) {
        ZipFile(input).use { zip ->
            val passEntry = zip.getEntry("pass.json")
                ?: return@withContext ProcessorResult(input.nameWithoutExtension, "", 0, null, null)

            val passJson = JSONObject(zip.getInputStream(passEntry).readBytes().decodeToString())

            val organizationName = passJson.optString("organizationName", "")
            val description = passJson.optString("description", "")
            val relevantDate = passJson.optString("relevantDate", "")

            var barcodeFormat: String? = null
            var barcodeValue: String? = null
            val barcode = passJson.optJSONObject("barcode")
            if (barcode != null) {
                barcodeFormat = barcode.optString("format", "")
                barcodeValue = barcode.optString("message", "")
            }

            val textContent = buildString {
                appendLine("Organization: $organizationName")
                appendLine("Description: $description")
                if (relevantDate.isNotEmpty()) appendLine("Relevant Date: $relevantDate")
                if (barcodeValue != null) {
                    appendLine("Barcode: $barcodeFormat - $barcodeValue")
                }
                val fields = passJson.optJSONArray("fields")
                if (fields != null) {
                    for (i in 0 until fields.length()) {
                        val field = fields.optJSONObject(i)
                        if (field != null) {
                            val key = field.optString("key", "")
                            val value = field.optString("value", "")
                            if (key.isNotEmpty()) appendLine("$key: $value")
                        }
                    }
                }
            }.takeIf { it.isNotBlank() }

            val thumbnailBitmap = loadThumbnail(zip)

            ProcessorResult(
                title = organizationName.takeIf { it.isNotBlank() } ?: input.nameWithoutExtension,
                author = "",
                pageCount = 0,
                textContent = textContent,
                thumbnailBitmap = thumbnailBitmap,
            )
        }
    }

    private fun loadThumbnail(zip: ZipFile): Bitmap? {
        val candidates = listOf("thumbnail.png", "icon.png", "logo.png")
        for (name in candidates) {
            val entry = zip.getEntry(name) ?: continue
            val bitmap = BitmapFactory.decodeStream(zip.getInputStream(entry))
            if (bitmap != null) return scaleToWidth(bitmap, 200)
        }
        return null
    }

    private fun scaleToWidth(source: Bitmap, targetWidth: Int): Bitmap {
        val targetHeight = (targetWidth * source.height) / source.width
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight.coerceAtLeast(1), true)
    }
}
