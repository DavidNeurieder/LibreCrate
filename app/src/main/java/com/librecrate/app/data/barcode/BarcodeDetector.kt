package com.librecrate.app.data.barcode

import android.content.Context
import android.graphics.Bitmap
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.Hashtable

data class BarcodeResult(val format: String, val value: String)

class BarcodeDetector(private val context: Context) {

    fun detect(bitmap: Bitmap): List<BarcodeResult> {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val source = RGBLuminanceSource(width, height, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        val reader = MultiFormatReader()
        val hints = Hashtable<DecodeHintType, Any>()
        hints[DecodeHintType.TRY_HARDER] = true

        return try {
            val result = reader.decode(binaryBitmap, hints)
            listOf(BarcodeResult(result.barcodeFormat.toString(), result.text))
        } catch (_: NotFoundException) {
            emptyList()
        }
    }
}
