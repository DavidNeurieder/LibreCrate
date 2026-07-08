package com.docwallet.vault.database

import com.docwallet.vault.crypto.FileEncryptor
import com.docwallet.vault.model.VaultCollection
import com.docwallet.vault.model.VaultDocument
import com.docwallet.vault.model.VaultDocumentTag
import com.docwallet.vault.model.VaultTag
import java.io.File
import java.util.UUID

class VaultDatabaseMerger {

    fun merge(backupDb: SqlHandle, currentDb: SqlHandle): MergeResult {
        val collections = readCollections(backupDb)
        val tags = readTags(backupDb)
        val documents = readDocuments(backupDb)
        val documentTags = readDocumentTags(backupDb)

        var added = 0
        var updated = 0
        var conflicted = 0
        var skipped = 0
        var collectionsAdded = 0
        var tagsAdded = 0

        currentDb.beginTransaction()
        try {
            for (item in collections) {
                val existing = currentDb.query(
                    "SELECT id FROM collections WHERE id = ?", arrayOf(item.id)
                ).use { it.moveToNext() }
                if (existing) {
                    currentDb.execSQL(
                        "UPDATE collections SET name = ?, icon = ?, sort_order = ?, parent_id = ? WHERE id = ?",
                        arrayOf(item.name, item.icon, item.sortOrder, item.parentId, item.id)
                    )
                } else {
                    currentDb.execSQL(
                        "INSERT INTO collections(id, name, icon, sort_order, parent_id) VALUES (?, ?, ?, ?, ?)",
                        arrayOf(item.id, item.name, item.icon, item.sortOrder, item.parentId)
                    )
                    collectionsAdded++
                }
            }

            for (item in tags) {
                currentDb.execSQL(
                    "INSERT OR IGNORE INTO tags(id, name, color) VALUES (?, ?, ?)",
                    arrayOf(item.id, item.name, item.color)
                )
                val inserted = currentDb.query(
                    "SELECT changes()"
                ).use { if (it.moveToNext()) it.getInt(0) else 0 }
                if (inserted > 0) tagsAdded++
            }

            for (item in documents) {
                val existing = readDocumentById(currentDb, item.id)

                if (existing == null) {
                    insertDocument(currentDb, item)
                    added++
                } else {
                    val contentChanged = item.fileSize != existing.fileSize || item.mimeType != existing.mimeType
                    val metadataChanged = item.modifiedAt > existing.modifiedAt

                    if (contentChanged && fileAlreadyStored(currentDb, item)) {
                        // same file, metadata only
                        if (metadataChanged) {
                            updateDocumentMetadata(currentDb, item)
                            updated++
                        } else {
                            skipped++
                        }
                    } else if (contentChanged) {
                        // different file content — create conflict
                        flagAsConflict(currentDb, item.id)
                        val conflictId = "${item.id}--${UUID.randomUUID().toString().take(8)}"
                        val conflictCopy = item.copy(
                            id = conflictId,
                            isConflict = false,
                            conflictWith = item.id,
                            importedAt = System.currentTimeMillis(),
                            modifiedAt = System.currentTimeMillis(),
                        )
                        insertDocument(currentDb, conflictCopy)
                        conflicted++
                    } else {
                        // same file, maybe update metadata
                        if (metadataChanged) {
                            updateDocumentMetadata(currentDb, item)
                            updated++
                        } else {
                            skipped++
                        }
                    }
                }
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

        return MergeResult(
            documentsAdded = added,
            documentsUpdated = updated,
            documentsConflicted = conflicted,
            documentsSkipped = skipped,
            collectionsAdded = collectionsAdded,
            tagsAdded = tagsAdded,
        )
    }

    fun mergeWithFileReencryption(
        backupDb: SqlHandle,
        currentDb: SqlHandle,
        files: Map<String, ByteArray>,
        backupKey: ByteArray,
        localKey: ByteArray,
        filesDirPath: String,
        fileEncryptor: FileEncryptor = FileEncryptor(),
    ): MergeResult {
        val backupDocs = readDocuments(backupDb)
        val result = merge(backupDb, currentDb)

        for (doc in backupDocs) {
            val iv = doc.encryptionIv ?: continue
            val relativeName = doc.filePath.substringAfterLast("/")
            val fileBytes = files[relativeName] ?: continue
            if (fileBytes.size <= FileEncryptor.IV_LENGTH) continue

            try {
                val target = File(filesDirPath, relativeName)
                if (target.exists()) {
                    val localIv = readFileIv(target)
                    updateFilePaths(currentDb, doc.filePath, target.absolutePath, localIv)
                    continue
                }
                val rawCiphertext = fileBytes.copyOfRange(FileEncryptor.IV_LENGTH, fileBytes.size)
                val plaintext = fileEncryptor.decryptBytes(rawCiphertext, backupKey, iv)
                val (newIv, reencrypted) = fileEncryptor.encryptBytes(plaintext, localKey)
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    out.write(newIv)
                    out.write(reencrypted)
                }
                updateFilePaths(currentDb, doc.filePath, target.absolutePath, newIv)
            } catch (_: Exception) {
            }
        }

        for (doc in backupDocs) {
            val thumbPath = doc.thumbnailPath ?: continue
            val relativeName = thumbPath.substringAfterLast("/")
            val fileBytes = files[relativeName] ?: continue
            if (fileBytes.size <= FileEncryptor.IV_LENGTH) continue

            try {
                val target = File(filesDirPath, relativeName)
                if (target.exists()) continue

                val thumbIv = fileBytes.copyOfRange(0, FileEncryptor.IV_LENGTH)
                val rawCiphertext = fileBytes.copyOfRange(FileEncryptor.IV_LENGTH, fileBytes.size)
                val plaintext = fileEncryptor.decryptBytes(rawCiphertext, backupKey, thumbIv)
                val (newIv, reencrypted) = fileEncryptor.encryptBytes(plaintext, localKey)
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    out.write(newIv)
                    out.write(reencrypted)
                }
            } catch (_: Exception) {
            }
        }

        return result
    }

    private fun readFileIv(file: File): ByteArray {
        val iv = ByteArray(FileEncryptor.IV_LENGTH)
        file.inputStream().use { stream ->
            var offset = 0
            while (offset < iv.size) {
                val n = stream.read(iv, offset, iv.size - offset)
                if (n == -1) break
                offset += n
            }
            if (offset < iv.size) error("File too short to contain IV: ${file.name}")
        }
        return iv
    }

    private fun updateFilePaths(db: SqlHandle, oldPath: String, newPath: String, iv: ByteArray) {
        db.execSQL(
            "UPDATE documents SET encryption_iv = ?, file_path = ? WHERE file_path = ?",
            arrayOf(iv, newPath, oldPath),
        )
    }

    private fun fileAlreadyStored(db: SqlHandle, doc: VaultDocument): Boolean {
        return db.query(
            "SELECT id FROM documents WHERE file_path = ? AND file_size = ? LIMIT 1",
            arrayOf(doc.filePath, doc.fileSize)
        ).use { it.moveToNext() }
    }

    private fun flagAsConflict(db: SqlHandle, documentId: String) {
        db.execSQL(
            "UPDATE documents SET is_conflict = 1 WHERE id = ?",
            arrayOf(documentId)
        )
    }

    private fun insertDocument(db: SqlHandle, doc: VaultDocument) {
        db.execSQL(
            """INSERT INTO documents(
                id, title, file_name, mime_type, file_path,
                file_size, page_count, author, description, thumbnail_path,
                imported_at, last_opened_at, modified_at, is_favorite, is_conflict,
                conflict_with, collection_id, encryption_iv,
                text_content, barcode_format, barcode_value, current_page, reading_position
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
            arrayOf(
                doc.id, doc.title, doc.fileName, doc.mimeType, doc.filePath,
                doc.fileSize, doc.pageCount, doc.author, doc.description, doc.thumbnailPath,
                doc.importedAt, doc.lastOpenedAt, doc.modifiedAt, if (doc.isFavorite) 1 else 0,
                if (doc.isConflict) 1 else 0, doc.conflictWith,
                doc.collectionId, doc.encryptionIv,
                doc.textContent, doc.barcodeFormat, doc.barcodeValue, doc.currentPage, doc.readingPosition,
            )
        )
    }

    private fun updateDocumentMetadata(db: SqlHandle, doc: VaultDocument) {
        db.execSQL(
            """UPDATE documents SET
                title = ?, file_name = ?, mime_type = ?, author = ?, description = ?,
                thumbnail_path = ?, modified_at = ?, is_favorite = ?,
                collection_id = ?, text_content = ?, barcode_format = ?, barcode_value = ?,
                current_page = ?, reading_position = ?
                WHERE id = ?""".trimIndent(),
            arrayOf(
                doc.title, doc.fileName, doc.mimeType, doc.author, doc.description,
                doc.thumbnailPath, doc.modifiedAt, if (doc.isFavorite) 1 else 0,
                doc.collectionId, doc.textContent, doc.barcodeFormat, doc.barcodeValue,
                doc.currentPage, doc.readingPosition, doc.id,
            )
        )
    }

    private fun readDocumentById(db: SqlHandle, id: String): VaultDocument? {
        return db.query("SELECT * FROM documents WHERE id = ?", arrayOf(id)).use { cursor ->
            if (cursor.moveToNext()) readDocument(cursor) else null
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
                list.add(readDocument(cursor))
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
