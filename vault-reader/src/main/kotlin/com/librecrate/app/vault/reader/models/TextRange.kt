package com.librecrate.app.vault.reader.models

data class TextRange(
    val startLocation: ReaderLocation,
    val endLocation: ReaderLocation,
    val text: String,
)
