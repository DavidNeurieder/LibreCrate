package com.docwallet.vault.database

import com.docwallet.vault.model.VaultCollection
import com.docwallet.vault.model.VaultDocument
import com.docwallet.vault.model.VaultDocumentTag
import com.docwallet.vault.model.VaultTag

class VaultDatabaseMerger {

    fun merge(backupDb: SqlHandle, currentDb: SqlHandle): Boolean {
        return try {
            val collections = readCollections(backupDb)
            val tags = readTags(backupDb)
            val documents = readDocuments(backupDb)
            val documentTags = readDocumentTags(backupDb)

            currentDb.beginTransaction()
            try {
                for (item in collections) {
                    currentDb.execSQL(
                        "INSERT OR IGNORE INTO collections(id, name, icon, sort_order, parent_id) VALUES (?, ?, ?, ?, ?)",
                        arrayOf(item.id, item.name, item.icon, item.sortOrder, item.parentId)
                    )
                }
                for (item in tags) {
                    currentDb.execSQL(
                        "INSERT OR IGNORE INTO tags(id, name, color) VALUES (?, ?, ?)",
                        arrayOf(item.id, item.name, item.color)
                    )
                }
                for (item in documents) {
                    currentDb.execSQL(
                        """INSERT OR IGNORE INTO documents(
                            id, title, file_name, mime_type, file_path,
                            file_size, page_count, author, description, thumbnail_path,
                            imported_at, last_opened_at, is_favorite, collection_id, encryption_iv,
                            text_content, barcode_format, barcode_value, current_page, reading_position
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
                        arrayOf(
                            item.id, item.title, item.fileName, item.mimeType, item.filePath,
                            item.fileSize, item.pageCount, item.author, item.description, item.thumbnailPath,
                            item.importedAt, item.lastOpenedAt, if (item.isFavorite) 1 else 0, item.collectionId, item.encryptionIv,
                            item.textContent, item.barcodeFormat, item.barcodeValue, item.currentPage, item.readingPosition
                        )
                    )
                }
                for (item in documentTags) {
                    currentDb.execSQL(
                        "INSERT OR IGNORE INTO document_tags(document_id, tag_id) VALUES (?, ?)",
                        arrayOf(item.documentId, item.tagId)
                    )
                }
                currentDb.setTransactionSuccessful()
            } finally {
                currentDb.endTransaction()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun readCollections(db: SqlHandle): List<VaultCollection> {
        val list = mutableListOf<VaultCollection>()
        db.query("SELECT * FROM collections").use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    VaultCollection(
                        id = cursor.getString(cursor.columnIndexOrThrow("id")) ?: "",
                        name = cursor.getString(cursor.columnIndexOrThrow("name")) ?: "",
                        icon = cursor.getString(cursor.columnIndexOrThrow("icon")) ?: "",
                        sortOrder = cursor.getInt(cursor.columnIndexOrThrow("sort_order")),
                        parentId = cursor.getStringOrNull("parent_id"),
                    )
                )
            }
        }
        return list
    }

    private fun readTags(db: SqlHandle): List<VaultTag> {
        val list = mutableListOf<VaultTag>()
        db.query("SELECT * FROM tags").use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    VaultTag(
                        id = cursor.getString(cursor.columnIndexOrThrow("id")) ?: "",
                        name = cursor.getString(cursor.columnIndexOrThrow("name")) ?: "",
                        color = cursor.getLong(cursor.columnIndexOrThrow("color")),
                    )
                )
            }
        }
        return list
    }

    private fun readDocuments(db: SqlHandle): List<VaultDocument> {
        val list = mutableListOf<VaultDocument>()
        db.query("SELECT * FROM documents").use { cursor ->
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

    private fun readDocumentTags(db: SqlHandle): List<VaultDocumentTag> {
        val list = mutableListOf<VaultDocumentTag>()
        db.query("SELECT * FROM document_tags").use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    VaultDocumentTag(
                        documentId = cursor.getString(cursor.columnIndexOrThrow("document_id")) ?: "",
                        tagId = cursor.getString(cursor.columnIndexOrThrow("tag_id")) ?: "",
                    )
                )
            }
        }
        return list
    }
}
