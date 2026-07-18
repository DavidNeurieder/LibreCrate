package com.librecrate.app.vault.reader.models

data class ReaderLocation(
    val pageIndex: Int = 0,
    val chapterUri: String? = null,
    val progression: Double = 0.0,
)
