package com.librecrate.app.vault.reader

data class ReaderDocument(
    val id: String,
    val filePath: String,
    val mimeType: String = "",
)
