package com.librecrate.app.vault.reader.models

data class ReaderProgress(
    val documentId: String,
    val location: ReaderLocation,
    val percentage: Int,
    val lastReadAt: Long = System.currentTimeMillis(),
)
