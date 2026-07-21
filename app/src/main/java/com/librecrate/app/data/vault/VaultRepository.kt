package com.librecrate.app.data.vault

import android.content.Context
import com.librecrate.app.data.model.Collection
import com.librecrate.app.util.ErrorLogger
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
    val databaseDir: File get() = context.getDatabasePath("librecrate.db").parentFile
        ?: File(context.filesDir, "databases")

    suspend fun open(masterKey: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            val dbPath = context.getDatabasePath("librecrate.db").absolutePath
            File(dbPath).parentFile?.mkdirs()
            handle = DbHandle.createEncrypted(dbPath, masterKey)
            refreshAll()
            true
        } catch (e: Exception) {
            ErrorLogger.logException(context, TAG, "Failed to open DB", e)
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

    suspend fun findDocumentByHash(hash: String): Document? = withContext(Dispatchers.IO) {
        handle?.findDocumentByHash(hash)?.toUiModel()
    }

    suspend fun addDocument(doc: Document): Boolean = withContext(Dispatchers.IO) {
        try {
            handle?.addDocument(doc.toFfi())
            refreshAll()
            true
        } catch (e: Exception) {
            ErrorLogger.logException(context, TAG, "addDocument failed", e); false
        }
    }

    suspend fun addDocumentFull(doc: Document, textContent: String?): Boolean = withContext(Dispatchers.IO) {
        try {
            handle?.addDocumentFull(doc.toFfi(), textContent)
            refreshAll()
            true
        } catch (e: Exception) {
            ErrorLogger.logException(context, TAG, "addDocumentFull failed", e); false
        }
    }

    suspend fun deleteDocument(id: String): Boolean = withContext(Dispatchers.IO) {
        val deleted = handle?.deleteDocument(id) ?: false
        if (deleted) refreshAll()
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
            val result = handle?.updateDocument(id, title, isFavorite) ?: false
            if (result) refreshAll()
            result
        } catch (e: Exception) {
            ErrorLogger.logException(context, TAG, "updateDocument failed", e); false
        }
    }

    suspend fun updateDocumentFull(
        id: String, title: String, author: String, description: String,
        collectionId: String?, isFavorite: Boolean, isConflict: Boolean,
        conflictWith: String?, currentPage: Int, readingPosition: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = handle?.updateDocumentFull(
                id, title, author, description, collectionId,
                isFavorite, isConflict, conflictWith, currentPage, readingPosition,
            ) ?: false
            if (result) refreshAll()
            result
        } catch (e: Exception) {
            ErrorLogger.logException(context, TAG, "updateDocumentFull failed", e); false
        }
    }

    suspend fun setReadingPosition(id: String, position: String): Boolean = withContext(Dispatchers.IO) {
        try {
            handle?.setReadingPosition(id, position) ?: false
        } catch (e: Exception) {
            ErrorLogger.logException(context, TAG, "setReadingPosition failed", e); false
        }
    }

    suspend fun setCurrentPage(id: String, page: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            handle?.setCurrentPage(id, page) ?: false
        } catch (e: Exception) {
            ErrorLogger.logException(context, TAG, "setCurrentPage failed", e); false
        }
    }

    // -----------------------------------------------------------------------
    // Collections
    // -----------------------------------------------------------------------

    suspend fun addCollection(collection: Collection): Boolean = withContext(Dispatchers.IO) {
        try {
            handle?.addCollection(collection.toFfi())
            refreshAll(); true
        } catch (e: Exception) {
            ErrorLogger.logException(context, TAG, "addCollection failed", e); false
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
            ErrorLogger.logException(context, TAG, "updateCollection failed", e); false
        }
    }

    suspend fun deleteCollection(id: String): Boolean = withContext(Dispatchers.IO) {
        val deleted = handle?.deleteCollection(id) ?: false
        if (deleted) refreshAll()
        deleted
    }

    // -----------------------------------------------------------------------
    // Tags
    // -----------------------------------------------------------------------

    suspend fun addTag(tag: Tag): Boolean = withContext(Dispatchers.IO) {
        try {
            handle?.addTag(tag.toFfi())
            refreshAll(); true
        } catch (e: Exception) {
            ErrorLogger.logException(context, TAG, "addTag failed", e); false
        }
    }

    suspend fun updateTag(id: String, name: String, color: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            handle?.updateTag(id, name, color) ?: false
        } catch (e: Exception) {
            ErrorLogger.logException(context, TAG, "updateTag failed", e); false
        }
    }

    suspend fun deleteTag(id: String): Boolean = withContext(Dispatchers.IO) {
        val deleted = handle?.deleteTag(id) ?: false
        if (deleted) refreshAll()
        deleted
    }

    suspend fun linkDocumentTag(documentId: String, tagId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            handle?.linkDocumentTag(documentId, tagId)
            true
        } catch (e: Exception) {
            ErrorLogger.logException(context, TAG, "linkDocumentTag failed", e); false
        }
    }

    suspend fun unlinkDocumentTag(documentId: String, tagId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            handle?.unlinkDocumentTag(documentId, tagId) ?: false
        } catch (e: Exception) {
            ErrorLogger.logException(context, TAG, "unlinkDocumentTag failed", e); false
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

    suspend fun searchDocuments(query: String): List<FtsResult> = withContext(Dispatchers.IO) {
        handle?.searchDocuments(query) ?: emptyList()
    }

    suspend fun searchDocumentsWithSnippet(query: String): List<FtsSnippetResult> = withContext(Dispatchers.IO) {
        handle?.searchDocumentsWithSnippet(query) ?: emptyList()
    }

    suspend fun searchDocumentsWithAllMatches(query: String): List<MultiMatchResult> = withContext(Dispatchers.IO) {
        handle?.searchDocumentsWithAllMatches(query) ?: emptyList()
    }

    suspend fun searchInDocument(documentId: String, query: String): List<FtsSnippetResult> = withContext(Dispatchers.IO) {
        handle?.searchInDocument(documentId, query) ?: emptyList()
    }

    suspend fun rebuildFtsIndex(): Boolean = withContext(Dispatchers.IO) {
        try {
            handle?.rebuildFtsIndex()
            true
        } catch (e: Exception) {
            ErrorLogger.logException(context, TAG, "rebuildFtsIndex failed", e); false
        }
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
            refreshAll()
            result
        } catch (e: Exception) {
            ErrorLogger.logException(context, TAG, "importDocument failed", e); null
        }
    }

    suspend fun exportDocumentFile(id: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            handle?.exportDocumentFile(context.filesDir.absolutePath, id)
        } catch (e: Exception) {
            ErrorLogger.logException(context, TAG, "exportDocumentFile failed", e); null
        }
    }

    suspend fun deleteDocumentFull(id: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val deleted = handle?.deleteDocumentFull(context.filesDir.absolutePath, id) ?: false
            if (deleted) refreshAll()
            deleted
        } catch (e: Exception) {
            ErrorLogger.logException(context, TAG, "deleteDocumentFull failed", e); false
        }
    }

    suspend fun storeThumbnail(id: String, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            handle?.storeThumbnail(context.filesDir.absolutePath, id, data)
            true
        } catch (e: Exception) {
            ErrorLogger.logException(context, TAG, "storeThumbnail failed", e); false
        }
    }

    suspend fun loadThumbnail(id: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            handle?.loadThumbnail(context.filesDir.absolutePath, id)
        } catch (e: Exception) {
            ErrorLogger.logException(context, TAG, "loadThumbnail failed", e); null
        }
    }

    // -----------------------------------------------------------------------
    // Schema version
    // -----------------------------------------------------------------------

    suspend fun getSchemaVersion(): Long = withContext(Dispatchers.IO) {
        handle?.getSchemaVersion() ?: 0
    }

    suspend fun setSchemaVersion(version: Long) = withContext(Dispatchers.IO) {
        handle?.setSchemaVersion(version)
    }

    // -----------------------------------------------------------------------
    // Vault format (export/import) — static FFI functions, not on DbHandle
    // -----------------------------------------------------------------------

    suspend fun exportVaultStatic(
        files: List<KeyValue>,
        dbFile: ByteArray?,
        vaultPassword: String,
        keys: List<KeyValue>,
        kdfParams: Argon2Params,
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            exportVault(files, dbFile, vaultPassword, keys, kdfParams)
        } catch (e: Exception) {
            ErrorLogger.logException(context, TAG, "exportVault failed", e); null
        }
    }

    suspend fun importVaultStatic(vaultData: ByteArray, vaultPassword: String): ImportedContents? = withContext(Dispatchers.IO) {
        try {
            importVault(vaultData, vaultPassword)
        } catch (e: Exception) {
            ErrorLogger.logException(context, TAG, "importVault failed", e); null
        }
    }

    suspend fun restoreToLayoutStatic(
        contents: ImportedContents,
        dbData: ByteArray,
        encryptionDir: String,
        databaseDir: String,
        filesDir: String,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            restoreToLayout(contents, dbData, encryptionDir, databaseDir, filesDir)
            true
        } catch (e: Exception) {
            ErrorLogger.logException(context, TAG, "restoreToLayout failed", e); false
        }
    }

    suspend fun mergeBranchA(
        backupDbPath: String,
        backupMasterKey: ByteArray,
        files: List<KeyValue>,
        backupKey: ByteArray?,
        localKey: ByteArray?,
        filesDir: String,
    ): MergeStats? = withContext(Dispatchers.IO) {
        try {
            handle?.mergeBranchA(backupDbPath, backupMasterKey, files, backupKey, localKey, filesDir)
        } catch (e: Exception) {
            ErrorLogger.logException(context, TAG, "mergeBranchA failed", e); null
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

fun DocumentRow.toUiModel() = Document(
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
    contentHash = contentHash,
)

fun Document.toFfi() = DocumentRow(
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
    contentHash = contentHash,
)

fun CollectionRow.toUiModel() = Collection(
    id = id,
    name = name,
    icon = icon,
    sortOrder = sortOrder,
    parentId = parentId,
)

fun Collection.toFfi() = CollectionRow(
    id = id,
    name = name,
    icon = icon,
    sortOrder = sortOrder,
    parentId = parentId,
)

fun TagRow.toUiModel() = Tag(
    id = id,
    name = name,
    color = color,
)

fun Tag.toFfi() = TagRow(
    id = id,
    name = name,
    color = color,
)
