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

    fun searchInDocument(documentId: String, query: String): List<InDocumentMatch> {
        if (query.isBlank()) return emptyList()
        val sanitized = sanitizeFtsQuery(query)
        val sql = """SELECT d.text_content, highlight(documents_fts, 3, ?, ?) AS hl
                     FROM documents d
                     INNER JOIN documents_fts fts ON d.rowid = fts.rowid
                     WHERE documents_fts MATCH ? AND d.id = ?"""
        db.query(sql, arrayOf("\u0001", "\u0002", sanitized, documentId)).use { cursor ->
            while (cursor.moveToNext()) {
                val textContent = cursor.getStringOrNull("text_content")
                val highlightContent = cursor.getStringOrNull("hl")
                if (highlightContent != null && textContent != null) {
                    return parseHighlight(highlightContent, textContent).take(10)
                }
            }
        }
        return emptyList()
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
                        modifiedAt = try { cursor.getLong(cursor.columnIndex("modified_at")) } catch (_: Exception) { cursor.getLong(cursor.columnIndexOrThrow("imported_at")) },
                        isFavorite = cursor.getInt(cursor.columnIndexOrThrow("is_favorite")) != 0,
                        isConflict = try { cursor.getInt(cursor.columnIndex("is_conflict")) != 0 } catch (_: Exception) { false },
                        conflictWith = try { cursor.getStringOrNull("conflict_with") } catch (_: Exception) { null },
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

        internal fun parseHighlight(highlightContent: String, textContent: String): List<InDocumentMatch> {
            if (highlightContent.isBlank()) return emptyList()
            val matchList = mutableListOf<InDocumentMatch>()
            var rawOffset = 0
            var matchStart = -1
            for (ch in highlightContent) {
                when (ch) {
                    '\u0001' -> matchStart = rawOffset
                    '\u0002' -> {
                        if (matchStart >= 0) {
                            val snippet = extractSnippet(textContent, matchStart, rawOffset)
                            if (snippet.isNotBlank()) {
                                val cleaned = stripMarkers(snippet)
                                val pageNumber = extractPageNumber(textContent, matchStart)
                                matchList.add(InDocumentMatch(snippet = cleaned, pageNumber = pageNumber))
                            }
                            matchStart = -1
                        }
                    }
                    else -> rawOffset++
                }
            }
            return matchList
        }

        internal fun extractSnippet(text: String, startOffset: Int, endOffset: Int): String {
            val contextBefore = 120
            val contextAfter = 120
            val snippetStart = (startOffset - contextBefore).coerceAtLeast(0)
            val snippetEnd = (endOffset + contextAfter).coerceAtMost(text.length)
            val prefix = text.substring(snippetStart, startOffset)
            val match = text.substring(startOffset, endOffset)
            val suffix = text.substring(endOffset, snippetEnd)
            return "${prefix}<b>$match</b>$suffix"
        }

        internal fun stripMarkers(text: String): String {
            return text.replace(Regex("\\[PAGE=\\d+\\]"), "")
                .replace(Regex("\\[SECTION=\\d+\\]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        internal fun extractPageNumber(text: String, offset: Int): Int {
            val before = text.substring(0, offset.coerceAtMost(text.length))
            val pageMatches = Regex("\\[PAGE=(\\d+)\\]").findAll(before)
            val sectionMatches = Regex("\\[SECTION=(\\d+)\\]").findAll(before)
            val lastPage = pageMatches.lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
            val lastSection = sectionMatches.lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
            return lastPage ?: lastSection ?: 0
        }
    }
}
