package com.docwallet.vault.database

import com.docwallet.vault.model.DocumentType
import com.docwallet.vault.model.VaultDocument

class VaultSearchEngine(private val db: SqlHandle) {

    fun search(query: String, filterType: DocumentType? = null): List<VaultDocument> {
        if (query.isBlank()) {
            return if (filterType != null) {
                queryAllByType(filterType)
            } else {
                queryAll()
            }
        }
        return if (query.length < 3) searchSimple(query, filterType)
        else searchFts(query, filterType)
    }

    fun getSuggestions(prefix: String): List<String> {
        val sql = "SELECT DISTINCT title FROM documents WHERE title LIKE ? ORDER BY title LIMIT 10"
        db.query(sql, arrayOf("$prefix%")).use { cursor ->
            val titles = mutableListOf<String>()
            while (cursor.moveToNext()) {
                cursor.getString(cursor.columnIndexOrThrow("title"))?.let { titles.add(it) }
            }
            return titles
        }
    }

    private fun queryAll(): List<VaultDocument> {
        return readDocuments(db, "SELECT * FROM documents ORDER BY imported_at DESC")
    }

    private fun queryAllByType(filterType: DocumentType): List<VaultDocument> {
        val mimePattern = filterType.mimeType.replace("/*", "/%")
        return readDocuments(
            db, "SELECT * FROM documents WHERE mime_type LIKE ? ORDER BY imported_at DESC",
            arrayOf(mimePattern)
        )
    }

    private fun searchSimple(query: String, filterType: DocumentType?): List<VaultDocument> {
        val sql = buildString {
            append("SELECT * FROM documents WHERE title LIKE ?")
            if (filterType != null) {
                append(" AND mime_type LIKE ?")
            }
            append(" ORDER BY imported_at DESC")
        }
        val args = mutableListOf<Any>("%$query%")
        if (filterType != null) {
            args.add(filterType.mimeType.replace("/*", "/%"))
        }
        return readDocuments(db, sql, args.toTypedArray())
    }

    private fun searchFts(query: String, filterType: DocumentType?): List<VaultDocument> {
        val sanitized = sanitizeFtsQuery(query)
        val sql = buildString {
            append("SELECT d.* FROM documents d INNER JOIN documents_fts fts ON d.rowid = fts.rowid WHERE documents_fts MATCH ?")
            if (filterType != null) {
                append(" AND d.mime_type LIKE ?")
            }
            append(" ORDER BY rank")
        }
        val args = mutableListOf<Any>(sanitized)
        if (filterType != null) {
            args.add(filterType.mimeType.replace("/*", "/%"))
        }
        return readDocuments(db, sql, args.toTypedArray())
    }

    private fun readDocuments(db: SqlHandle, sql: String, bindArgs: Array<Any?> = emptyArray()): List<VaultDocument> {
        val list = mutableListOf<VaultDocument>()
        db.query(sql, bindArgs).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    VaultDocument(
                        id = cursor.getString(cursor.columnIndexOrThrow("id")) ?: "",
                        title = cursor.getString(cursor.columnIndexOrThrow("title")) ?: "",
                        fileName = cursor.getString(cursor.columnIndexOrThrow("file_name")) ?: "",
                        mimeType = cursor.getString(cursor.columnIndexOrThrow("mime_type")) ?: "",
                        filePath = cursor.getString(cursor.columnIndexOrThrow("file_path")) ?: "",
                        fileSize = cursor.getLong(cursor.columnIndexOrThrow("file_size")),
                        pageCount = cursor.getInt(cursor.columnIndexOrThrow("page_count")),
                        author = cursor.getString(cursor.columnIndexOrThrow("author")) ?: "",
                        description = cursor.getString(cursor.columnIndexOrThrow("description")) ?: "",
                        thumbnailPath = cursor.getStringOrNull("thumbnail_path"),
                        importedAt = cursor.getLong(cursor.columnIndexOrThrow("imported_at")),
                        lastOpenedAt = cursor.getLong(cursor.columnIndexOrThrow("last_opened_at")),
                        isFavorite = cursor.getInt(cursor.columnIndexOrThrow("is_favorite")) != 0,
                        collectionId = cursor.getStringOrNull("collection_id"),
                        encryptionIv = cursor.getBlobOrNull("encryption_iv"),
                        textContent = cursor.getStringOrNull("text_content"),
                        barcodeFormat = cursor.getStringOrNull("barcode_format"),
                        barcodeValue = cursor.getStringOrNull("barcode_value"),
                        currentPage = cursor.getInt(cursor.columnIndexOrThrow("current_page")),
                        readingPosition = cursor.getStringOrNull("reading_position"),
                    )
                )
            }
        }
        return list
    }

    companion object {
        fun sanitizeFtsQuery(query: String): String {
            return query.trim()
                .split("\\s+".toRegex())
                .filter { it.isNotBlank() }
                .joinToString(" ") { "${it}*" }
        }
    }
}
