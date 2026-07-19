package com.librecrate.app.data.vault

import android.content.Context
import android.util.Log
import com.librecrate.app.data.model.Collection
import com.librecrate.app.data.model.Document
import com.librecrate.app.data.model.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import uniffi.vault_native.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class VaultRepository(private val context: Context) {

    private var handle: DbHandle? = null
    private val version = AtomicLong(0)

    private val _documents = MutableStateFlow<List<Document>>(emptyList())
    val documents: Flow<List<Document>> = _documents.asStateFlow()

    private val _collections = MutableStateFlow<List<Collection>>(emptyList())
    val collections: Flow<List<Collection>> = _collections.asStateFlow()

    private val _tags = MutableStateFlow<List<Tag>>(emptyList())
    val tags: Flow<List<Tag>> = _tags.asStateFlow()

    val filesDir: File get() = File(context.filesDir, "files")
    val encryptionDir: File get() = File(context.filesDir, "encryption")
    val databaseDir: File get() = context.getDatabasePath("librecrate.db").parentFile!!

    suspend fun open(masterKey: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            val dbPath = context.getDatabasePath("librecrate.db").absolutePath
            dbPath.parentFile?.mkdirs()
            handle = DbHandle.createEncrypted(dbPath, masterKey)
            refreshAll()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open DB", e)
            false
        }
    }

    suspend fun reopen(masterKey: ByteArray): Boolean = withContext(Dispatchers.IO) {
        close()
        open(masterKey)
    }

    fun close() {
        handle?.close()
        handle = null
    }

    fun invalidate() {
        version.incrementAndGet()
    }

    // -----------------------------------------------------------------------
    // Documents
    // -----------------------------------------------------------------------

    suspend fun listDocuments(): List<Document> = withContext(Dispatchers.IO) {
        val h = handle ?: return@withContext emptyList()
        h.listDocuments().map { it.toUiModel() }
    }

    suspend fun listDocumentsFiltered(
        limit: Long,
        offset: Long,
        collectionId: String? = null,
        favoriteOnly: Boolean = false,
        tagId: String? = null,
    ): List<Document> = withContext(Dispatchers.IO) {
        val h = handle ?: return@withContext emptyList()
        h.listDocumentsFiltered(limit, offset, collectionId, favoriteOnly, tagId)
            .map { it.toUiModel() }
    }

    suspend fun getDocument(id: String): Document? = withContext(Dispatchers.IO) {
        handle?.getDocument(id)?.toUiModel()
    }

    suspend fun addDocument(doc: Document): Boolean = withContext(Dispatchers.IO) {
        try {
            handle?.addDocument(doc.toFfi())
            invalidate()
            true
        } catch (e: Exception) {
            Log.e(TAG, "addDocument failed", e); false
        }
    }

    suspend fun addDocumentFull(doc: Document, textContent: String?): Boolean = withContext(Dispatchers.IO) {
        try {
            handle?.addDocumentFull(doc.toFfi(), textContent)
            invalidate()
            true
        } catch (e: Exception) {
            Log.e(TAG, "addDocumentFull failed", e); false
        }
    }

    suspend fun deleteDocument(id: String): Boolean = withContext(Dispatchers.IO) {
        val deleted = handle?.deleteDocument(id) ?: false
        if (deleted) invalidate()
        deleted
    }

    suspend fun documentCount(): Int = withContext(Dispatchers.IO) {
        listDocuments().size
    }

    suspend fun listCollections(): List<Collection> = withContext(Dispatchers.IO) {
        handle?.listCollections()?.map { it.toUiModel() } ?: emptyList()
    }

    suspend fun listTags(): List<Tag> = withContext(Dispatchers.IO) {
        handle?.listTags()?.map { it.toUiModel() } ?: emptyList()
    }

    // -----------------------------------------------------------------------
    // Rich updates
    // -----------------------------------------------------------------------

    suspend fun updateDocument(
        id: String, title: String, isFavorite: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            handle?.updateDocument(id, title, isFavorite) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "updateDocument failed", e); false
        }
    }

    suspend fun updateDocumentFull(
        id: String, title: String, author: String, description: String,
        collectionId: String?, isFavorite: Boolean, isConflict: Boolean,
        conflictWith: String?, currentPage: Int, readingPosition: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            handle?.updateDocumentFull(
                id, title, author, description, collectionId,
                isFavorite, isConflict, conflictWith, currentPage, readingPosition,
            ) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "updateDocumentFull failed", e); false
        }
    }

    suspend fun setReadingPosition(id: String, position: String): Boolean = withContext(Dispatchers.IO) {
        try {
            handle?.setReadingPosition(id, position) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "setReadingPosition failed", e); false
        }
    }

    suspend fun setCurrentPage(id: String, page: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            handle?.setCurrentPage(id, page) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "setCurrentPage failed", e); false
        }
    }

    // -----------------------------------------------------------------------
    // Collections
    // -----------------------------------------------------------------------

    suspend fun addCollection(collection: Collection): Boolean = withContext(Dispatchers.IO) {
        try {
            handle?.addCollection(collection.toFfi())
            invalidate(); true
        } catch (e: Exception) {
            Log.e(TAG, "addCollection failed", e); false
        }
    }

    suspend fun getCollection(id: String): Collection? = withContext(Dispatchers.IO) {
        handle?.getCollection(id)?.toUiModel()
    }

    suspend fun updateCollection(
        id: String, name: String, icon: String, sortOrder: Int, parentId: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            handle?.updateCollection(id, name, icon, sortOrder, parentId) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "updateCollection failed", e); false
        }
    }

    suspend fun deleteCollection(id: String): Boolean = withContext(Dispatchers.IO) {
        val deleted = handle?.deleteCollection(id) ?: false
        if (deleted) invalidate()
        deleted
    }

    // -----------------------------------------------------------------------
    // Tags
    // -----------------------------------------------------------------------

    suspend fun addTag(tag: Tag): Boolean = withContext(Dispatchers.IO) {
        try {
            handle?.addTag(tag.toFfi())
            invalidate(); true
        } catch (e: Exception) {
            Log.e(TAG, "addTag failed", e); false
        }
    }

    suspend fun updateTag(id: String, name: String, color: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            handle?.updateTag(id, name, color) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "updateTag failed", e); false
        }
    }

    suspend fun deleteTag(id: String): Boolean = withContext(Dispatchers.IO) {
        val deleted = handle?.deleteTag(id) ?: false
        if (deleted) invalidate()
        deleted
    }

    suspend fun linkDocumentTag(documentId: String, tagId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            handle?.linkDocumentTag(documentId, tagId)
            true
        } catch (e: Exception) {
            Log.e(TAG, "linkDocumentTag failed", e); false
        }
    }

    suspend fun unlinkDocumentTag(documentId: String, tagId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            handle?.unlinkDocumentTag(documentId, tagId) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "unlinkDocumentTag failed", e); false
        }
    }

    suspend fun getTagsForDocument(documentId: String): List<Tag> = withContext(Dispatchers.IO) {
        handle?.getTagsForDocument(documentId)?.map { it.toUiModel() } ?: emptyList()
    }

    suspend fun getDocumentsForTag(tagId: String): List<Document> = withContext(Dispatchers.IO) {
        handle?.getDocumentsForTag(tagId)?.map { it.toUiModel() } ?: emptyList()
    }

    // -----------------------------------------------------------------------
    // Search
    // -----------------------------------------------------------------------

    suspend fun searchDocuments(query: String): List<SearchResultFfi> = withContext(Dispatchers.IO) {
        handle?.searchDocuments(query) ?: emptyList()
    }

    suspend fun searchDocumentsWithSnippet(query: String): List<SnippetResultFfi> = withContext(Dispatchers.IO) {
        handle?.searchDocumentsWithSnippet(query) ?: emptyList()
    }

    suspend fun searchInDocument(documentId: String, query: String): List<SnippetResultFfi> = withContext(Dispatchers.IO) {
        handle?.searchInDocument(documentId, query) ?: emptyList()
    }

    // -----------------------------------------------------------------------
    // Storage
    // -----------------------------------------------------------------------

    suspend fun importDocument(
        id: String, title: String, fileData: ByteArray,
        mimeType: String, author: String, description: String,
        textContent: String?,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val result = handle?.importDocument(
                context.filesDir.absolutePath, id, title, fileData,
                mimeType, author, description, textContent,
            )
            invalidate()
            result
        } catch (e: Exception) {
            Log.e(TAG, "importDocument failed", e); null
        }
    }

    suspend fun exportDocumentFile(id: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            handle?.exportDocumentFile(context.filesDir.absolutePath, id)
        } catch (e: Exception) {
            Log.e(TAG, "exportDocumentFile failed", e); null
        }
    }

    suspend fun deleteDocumentFull(id: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val deleted = handle?.deleteDocumentFull(context.filesDir.absolutePath, id) ?: false
            if (deleted) invalidate()
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "deleteDocumentFull failed", e); false
        }
    }

    suspend fun storeThumbnail(id: String, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            handle?.storeThumbnail(context.filesDir.absolutePath, id, data)
            true
        } catch (e: Exception) {
            Log.e(TAG, "storeThumbnail failed", e); false
        }
    }

    suspend fun loadThumbnail(id: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            handle?.loadThumbnail(context.filesDir.absolutePath, id)
        } catch (e: Exception) {
            Log.e(TAG, "loadThumbnail failed", e); null
        }
    }

    // -----------------------------------------------------------------------
    // Schema version
    // -----------------------------------------------------------------------

    suspend fun getSchemaVersion(): Long = withContext(Dispatchers.IO) {
        handle?.schemaVersion ?: 0
    }

    suspend fun setSchemaVersion(version: Long) = withContext(Dispatchers.IO) {
        handle?.setSchemaVersion(version)
    }

    // -----------------------------------------------------------------------
    // Vault format (export/import) — static FFI functions, not on DbHandle
    // -----------------------------------------------------------------------

    suspend fun exportVaultStatic(
        files: List<KeyValueFfi>,
        dbFile: ByteArray?,
        vaultPassword: String,
        keys: List<KeyValueFfi>,
        kdfParams: Argon2ParamsFfi,
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            uniffi.vault_native.exportVault(files, dbFile, vaultPassword, keys, kdfParams)
        } catch (e: Exception) {
            Log.e(TAG, "exportVault failed", e); null
        }
    }

    suspend fun importVaultStatic(vaultData: ByteArray, vaultPassword: String): ImportedContentsFfi? = withContext(Dispatchers.IO) {
        try {
            uniffi.vault_native.importVault(vaultData, vaultPassword)
        } catch (e: Exception) {
            Log.e(TAG, "importVault failed", e); null
        }
    }

    suspend fun restoreToLayoutStatic(
        contents: ImportedContentsFfi,
        dbData: ByteArray,
        encryptionDir: String,
        databaseDir: String,
        filesDir: String,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            uniffi.vault_native.restoreToLayout(contents, dbData, encryptionDir, databaseDir, filesDir)
            true
        } catch (e: Exception) {
            Log.e(TAG, "restoreToLayout failed", e); false
        }
    }

    suspend fun mergeBranchA(
        backupDbPath: String,
        backupMasterKey: ByteArray,
        files: List<KeyValueFfi>,
        backupKey: ByteArray?,
        localKey: ByteArray?,
        filesDir: String,
    ): MergeStatsFfi? = withContext(Dispatchers.IO) {
        try {
            handle?.mergeBranchA(backupDbPath, backupMasterKey, files, backupKey, localKey, filesDir)
        } catch (e: Exception) {
            Log.e(TAG, "mergeBranchA failed", e); null
        }
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private suspend fun refreshAll() {
        _documents.value = listDocuments()
        _collections.value = listCollectionsInternal()
        _tags.value = listTagsInternal()
    }

    private suspend fun listCollectionsInternal(): List<Collection> = withContext(Dispatchers.IO) {
        handle?.listCollections()?.map { it.toUiModel() } ?: emptyList()
    }

    private suspend fun listTagsInternal(): List<Tag> = withContext(Dispatchers.IO) {
        handle?.listTags()?.map { it.toUiModel() } ?: emptyList()
    }

    companion object {
        private const val TAG = "VaultRepository"
    }
}

// ---------------------------------------------------------------------------
// Mapping extensions between FFI types and UI models
// ---------------------------------------------------------------------------

fun DocumentFfi.toUiModel() = Document(
    id = id,
    title = title,
    fileName = fileName,
    mimeType = mimeType,
    filePath = filePath,
    fileSize = fileSize,
    pageCount = pageCount,
    author = author,
    description = description,
    thumbnailPath = thumbnailPath,
    importedAt = importedAt,
    lastOpenedAt = lastOpenedAt,
    modifiedAt = modifiedAt,
    isFavorite = isFavorite,
    isConflict = isConflict,
    conflictWith = conflictWith,
    collectionId = collectionId,
    encryptionIv = encryptionIv,
    currentPage = currentPage,
    readingPosition = readingPosition,
    barcodeFormat = barcodeFormat,
    barcodeValue = barcodeValue,
)

fun Document.toFfi() = DocumentFfi(
    id = id,
    title = title,
    fileName = fileName,
    mimeType = mimeType,
    filePath = filePath,
    fileSize = fileSize,
    pageCount = pageCount,
    author = author,
    description = description,
    thumbnailPath = thumbnailPath,
    importedAt = importedAt,
    lastOpenedAt = lastOpenedAt,
    modifiedAt = modifiedAt,
    isFavorite = isFavorite,
    isConflict = isConflict,
    conflictWith = conflictWith,
    collectionId = collectionId,
    encryptionIv = encryptionIv,
    currentPage = currentPage,
    readingPosition = readingPosition,
    barcodeFormat = barcodeFormat,
    barcodeValue = barcodeValue,
)

fun CollectionFfi.toUiModel() = Collection(
    id = id,
    name = name,
    icon = icon,
    sortOrder = sortOrder,
    parentId = parentId,
)

fun Collection.toFfi() = CollectionFfi(
    id = id,
    name = name,
    icon = icon,
    sortOrder = sortOrder,
    parentId = parentId,
)

fun TagFfi.toUiModel() = Tag(
    id = id,
    name = name,
    color = color,
)

fun Tag.toFfi() = TagFfi(
    id = id,
    name = name,
    color = color,
)
