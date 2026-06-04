package com.docwallet.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix

@Composable
fun BarcodeImage(
    format: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val barcodeFormat = remember(format) {
        when (format.uppercase().removePrefix("PK").removePrefix("BARCODEFORMAT")) {
            "QR" -> BarcodeFormat.QR_CODE
            "AZTEC" -> BarcodeFormat.AZTEC
            "PDF417" -> BarcodeFormat.PDF_417
            "CODE128" -> BarcodeFormat.CODE_128
            "CODE39" -> BarcodeFormat.CODE_39
            "EAN13" -> BarcodeFormat.EAN_13
            "EAN8" -> BarcodeFormat.EAN_8
            "UPC_A" -> BarcodeFormat.UPC_A
            "UPC_E" -> BarcodeFormat.UPC_E
            "DATA_MATRIX" -> BarcodeFormat.DATA_MATRIX
            else -> BarcodeFormat.QR_CODE
        }
    }

    val bitmap = remember(barcodeFormat, value) {
        try {
            val writer = MultiFormatWriter()
            val bitMatrix: BitMatrix = writer.encode(value, barcodeFormat, 512, 512)
            bitMatrixToBitmap(bitMatrix)
        } catch (e: WriterException) {
            null
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Barcode ($format)",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(16.dp),
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
    }
}

private fun bitMatrixToBitmap(bitMatrix: BitMatrix): Bitmap {
    val width = bitMatrix.width
    val height = bitMatrix.height
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
    for (x in 0 until width) {
        for (y in 0 until height) {
            bitmap.setPixel(
                x,
                y,
                if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE,
            )
        }
    }
    return bitmap
}
