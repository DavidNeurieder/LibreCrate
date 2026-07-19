package com.librecrate.app.data.model

data class SearchResultMatch(
    val snippet: String,
    val pageNumber: Int,
)

data class SearchResultItem(
    val id: String,
    val title: String,
    val mimeType: String,
    val pageCount: Int,
    val author: String,
    val thumbnailPath: String?,
    val matches: List<SearchResultMatch>,
)
