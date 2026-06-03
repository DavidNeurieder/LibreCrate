package com.docwallet.data.model

enum class DocumentType(val mimeType: String, val extensions: List<String>) {
    PDF("application/pdf", listOf("pdf")),
    EPUB("application/epub+zip", listOf("epub")),
    PKPASS("application/vnd.apple.pkpass", listOf("pkpass")),
    CBZ("application/vnd.comicbook+zip", listOf("cbz")),
    CBR("application/x-cbr", listOf("cbr")),
    IMAGE("image/*", listOf("jpg", "jpeg", "png", "webp", "gif", "bmp")),
    NOTE("text/markdown", listOf("md")),
    UNKNOWN("application/octet-stream", emptyList());

    companion object {
        fun fromMimeType(mime: String): DocumentType =
            entries.firstOrNull { it.mimeType == mime || (it == IMAGE && mime.startsWith("image/")) }
                ?: UNKNOWN

        fun fromExtension(ext: String): DocumentType =
            entries.firstOrNull { ext.lowercase() in it.extensions } ?: UNKNOWN
    }
}
