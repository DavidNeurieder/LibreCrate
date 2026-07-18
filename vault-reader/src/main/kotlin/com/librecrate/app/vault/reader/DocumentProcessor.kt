package com.librecrate.app.vault.reader

import java.io.File

data class ProcessorResult(
    val title: String,
    val author: String,
    val pageCount: Int,
    val textContent: String?,
    val thumbnailData: ByteArray?,
)

interface DocumentProcessor {
    suspend fun process(input: File, mimeType: String): ProcessorResult
}
