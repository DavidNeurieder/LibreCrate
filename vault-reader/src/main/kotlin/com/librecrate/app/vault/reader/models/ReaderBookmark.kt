package com.librecrate.app.vault.reader.models

data class ReaderBookmark(
    val id: String,
    val documentId: String,
    val location: ReaderLocation,
    val label: String,
    val created: Long = System.currentTimeMillis(),
)
