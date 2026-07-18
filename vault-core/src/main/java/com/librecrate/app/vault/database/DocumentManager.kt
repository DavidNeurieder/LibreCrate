package com.librecrate.app.vault.database

import com.librecrate.app.vault.crypto.FileEncryptor
import com.librecrate.app.vault.model.VaultDocument
import com.librecrate.app.vault.storage.Storage

class DocumentManager(
    private val db: SqlHandle,
    private val storage: Storage,
    private val fileEncryptor: FileEncryptor = FileEncryptor(),
) {
    fun importDocument(
        id: String,
        title: String,
        file: ByteArray,
        mimeType: String,
        author: String = "",
        description: String = "",
        textContent: String? = null,
    ): VaultDocument {
        val filePath = "files/$id"
        storage.save(filePath, file)
        val (iv, _) = fileEncryptor.encryptBytes(file, fileEncryptor.generateKey())
        val now = System.currentTimeMillis()
        val doc = VaultDocument(
            id = id,
            title = title,
            fileName = id.substringAfterLast('/'),
            mimeType = mimeType,
            filePath = filePath,
            fileSize = file.size.toLong(),
            pageCount = 0,
            author = author,
            description = description,
            thumbnailPath = null,
            importedAt = now,
            lastOpenedAt = now,
            modifiedAt = now,
            isFavorite = false,
            isConflict = false,
            conflictWith = null,
            collectionId = null,
            encryptionIv = iv,
            textContent = textContent,
            barcodeFormat = null,
            barcodeValue = null,
            currentPage = 0,
            readingPosition = null,
        )

        db.execSQL(
            """INSERT OR REPLACE INTO documents(
                id, title, file_name, mime_type, file_path, file_size, page_count,
                author, description, imported_at, last_opened_at, is_favorite,
                encryption_iv, text_content
            ) VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, 0, ?, ?)""".trimIndent(),
            arrayOf(
                doc.id, doc.title, doc.fileName, doc.mimeType, doc.filePath, doc.fileSize,
                doc.author, doc.description, doc.importedAt, doc.lastOpenedAt,
                doc.encryptionIv, doc.textContent,
            )
        )

        VaultFtsIndexer(db).indexDocument(
            documentId = doc.id,
            title = doc.title,
            author = doc.author,
            description = doc.description,
            textContent = doc.textContent,
        )

        return doc
    }

    fun listDocuments(): List<VaultDocument> {
        return db.query("SELECT * FROM documents ORDER BY imported_at DESC")
            .use { cursor ->
                val docs = mutableListOf<VaultDocument>()
                while (cursor.moveToNext()) {
                    docs.add(readDocument(cursor))
                }
                docs
            }
    }

    fun getDocument(id: String): VaultDocument? {
        return db.query("SELECT * FROM documents WHERE id = ?", arrayOf(id))
            .use { cursor ->
                if (cursor.moveToNext()) readDocument(cursor) else null
            }
    }

    fun loadDocumentFile(id: String): ByteArray? {
        val doc = getDocument(id) ?: return null
        return if (storage.exists(doc.filePath)) storage.load(doc.filePath) else null
    }

    fun deleteDocument(id: String) {
        val doc = getDocument(id)
        if (doc != null) {
            storage.delete(doc.filePath)
        }
        VaultFtsIndexer(db).removeDocument(id)
        db.execSQL("DELETE FROM documents WHERE id = ?", arrayOf(id))
    }

    private fun readDocument(cursor: SqlCursor): VaultDocument {
        return VaultDocument(
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
            modifiedAt = cursor.getLong(cursor.columnIndexOrThrow("modified_at")),
            isFavorite = cursor.getInt(cursor.columnIndexOrThrow("is_favorite")) != 0,
            isConflict = cursor.getInt(cursor.columnIndexOrThrow("is_conflict")) != 0,
            conflictWith = cursor.getStringOrNull("conflict_with"),
            collectionId = cursor.getStringOrNull("collection_id"),
            encryptionIv = cursor.getBlobOrNull("encryption_iv"),
            textContent = cursor.getStringOrNull("text_content"),
            barcodeFormat = cursor.getStringOrNull("barcode_format"),
            barcodeValue = cursor.getStringOrNull("barcode_value"),
            currentPage = cursor.getInt(cursor.columnIndexOrThrow("current_page")),
            readingPosition = cursor.getStringOrNull("reading_position"),
        )
    }
}
