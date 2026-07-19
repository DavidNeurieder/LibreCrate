package com.librecrate.app.data.model

enum class DocumentType(val mimeType: String, val extensions: List<String> = emptyList()) {
    PDF("application/pdf", listOf("pdf")),
    EPUB("application/epub+zip", listOf("epub")),
    CBZ("application/vnd.comicbook+zip", listOf("cbz")),
    CBR("application/x-cbr", listOf("cbr")),
    PKPASS("application/vnd.apple.pkpass", listOf("pkpass")),
    IMAGE("image/*", listOf("png", "jpg", "jpeg", "gif", "webp", "bmp")),
    NOTE("text/markdown", listOf("md", "markdown")),
    UNKNOWN("application/octet-stream");

    companion object {
        fun fromMimeType(mime: String): DocumentType {
            return entries.firstOrNull { mime.startsWith(it.mimeType.removeSuffix("/*")) } ?: UNKNOWN
        }

        fun fromExtension(ext: String): DocumentType {
            return entries.firstOrNull { ext.lowercase() in it.extensions } ?: UNKNOWN
        }
    }
}
