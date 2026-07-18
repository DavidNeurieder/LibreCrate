package com.librecrate.app.data.import

import android.graphics.Bitmap
import java.io.File

data class ProcessorResult(
    val title: String,
    val author: String,
    val pageCount: Int,
    val textContent: String?,
    val thumbnailBitmap: Bitmap?,
)

interface DocumentProcessor {
    suspend fun process(input: File, mimeType: String): ProcessorResult
}
