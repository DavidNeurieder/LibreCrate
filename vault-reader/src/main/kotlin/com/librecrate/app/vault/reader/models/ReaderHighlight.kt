package com.librecrate.app.vault.reader.models

data class ReaderHighlight(
    val id: String,
    val documentId: String,
    val range: TextRange,
    val color: Long = 0xFFFFEB3B,
    val note: String? = null,
    val created: Long = System.currentTimeMillis(),
)
