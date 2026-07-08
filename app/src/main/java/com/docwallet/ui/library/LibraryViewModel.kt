package com.docwallet.ui.library

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.docwallet.DocWalletApplication
import com.docwallet.data.db.DocumentListItem
import com.docwallet.data.db.SearchResultItem
import com.docwallet.data.db.SearchResultMatch
import com.docwallet.data.db.SearchResultWithOffsets
import com.docwallet.data.model.Document
import com.docwallet.vault.model.DocumentType
import com.docwallet.ui.common.ThumbnailCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream

enum class SortOption(val label: String) {
    NAME_ASC("Name A-Z"),
    NAME_DESC("Name Z-A"),
    RECENTLY_OPENED("Recently opened"),
    DATE_NEWEST("Newest first"),
    DATE_OLDEST("Oldest first"),
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as DocWalletApplication
    private val documentDao = app.documentDao

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val searchQuery = MutableStateFlow("")
    val selectedSort = MutableStateFlow(SortOption.RECENTLY_OPENED)
    val filterType = MutableStateFlow<DocumentType?>(null)
    val favoritesOnly = MutableStateFlow(false)

    val documents: StateFlow<List<Document>> = combine(
        searchQuery.flatMapLatest { query ->
            if (query.isBlank()) {
                documentDao.getDocumentList()
            } else {
                val sanitized = query.trim()
                    .split("\\s+".toRegex())
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { "${it}*" }
                (try {
                    documentDao.searchDocuments(
                        SimpleSQLiteQuery(
                            "SELECT d.id, d.title, d.file_name, d.mime_type, d.file_size, d.page_count, d.author, d.description, d.thumbnail_path, d.imported_at, d.last_opened_at, d.is_favorite, d.collection_id, d.barcode_format, d.barcode_value, d.current_page, d.reading_position FROM documents d INNER JOIN documents_fts fts ON d.rowid = fts.rowid WHERE documents_fts MATCH ? ORDER BY rank",
                            arrayOf(sanitized)
                        )
                    )
                } catch (_: Exception) {
                    flowOf(emptyList())
                }).catch { _ ->
                    emit(emptyList())
                }
            }
        },
        selectedSort,
        filterType,
        favoritesOnly
    ) { items: List<DocumentListItem>, sort: SortOption, type: DocumentType?, favs: Boolean ->
        var result = items.map { item ->
            Document(
                id = item.id,
                title = item.title,
                fileName = item.fileName,
                mimeType = item.mimeType,
                fileSize = item.fileSize,
                pageCount = item.pageCount,
                author = item.author,
                description = item.description,
                thumbnailPath = item.thumbnailPath,
                importedAt = item.importedAt,
                lastOpenedAt = item.lastOpenedAt,
                isFavorite = item.isFavorite,
                collectionId = item.collectionId,
                barcodeFormat = item.barcodeFormat,
                barcodeValue = item.barcodeValue,
                currentPage = item.currentPage,
                readingPosition = item.readingPosition,
            )
        }

        if (type != null) {
            result = result.filter { doc ->
                if (type.mimeType.endsWith("/*")) {
                    doc.mimeType.startsWith(type.mimeType.removeSuffix("/*"))
                } else {
                    doc.mimeType == type.mimeType
                }
            }
        }

        if (favs) {
            result = result.filter { it.isFavorite }
        }

        when (sort) {
            SortOption.NAME_ASC -> result.sortedBy { it.title.lowercase() }
            SortOption.NAME_DESC -> result.sortedByDescending { it.title.lowercase() }
            SortOption.DATE_NEWEST -> result.sortedByDescending { it.importedAt }
            SortOption.DATE_OLDEST -> result.sortedBy { it.importedAt }
            SortOption.RECENTLY_OPENED -> result.sortedByDescending { it.lastOpenedAt }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val searchResults: StateFlow<List<SearchResultItem>> = searchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) {
                flowOf(emptyList())
            } else {
                val sanitized = query.trim()
                    .split("\\s+".toRegex())
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { "${it}*" }
                ((try {
                    documentDao.searchDocumentsWithOffsets(
                        SimpleSQLiteQuery(
                            "SELECT d.id, d.title, d.mime_type, d.page_count, d.author, d.thumbnail_path, d.text_content, highlight(documents_fts, 3, '\u0001', '\u0002') AS highlight_content FROM documents d INNER JOIN documents_fts fts ON d.rowid = fts.rowid WHERE documents_fts MATCH ? ORDER BY rank",
                            arrayOf(sanitized)
                        )
                    )
                } catch (_: Exception) {
                    flowOf(emptyList())
                }).catch { _ ->
                    emit(emptyList())
                }).map { results ->
                    results.map { row -> row.toSearchResultItem() }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            documentDao.getDocumentList().collect {
                _isLoading.value = false
            }
        }
    }

    fun toggleFavorite(document: Document) {
        viewModelScope.launch {
            documentDao.update(document.copy(isFavorite = !document.isFavorite))
        }
    }

    fun deleteDocument(document: Document) {
        viewModelScope.launch {
            documentDao.deleteById(document.id)
        }
    }

    fun setSort(sort: SortOption) {
        selectedSort.value = sort
    }

    fun setFilter(type: DocumentType?) {
        filterType.value = type
    }

    fun search(query: String) {
        searchQuery.value = query
    }

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    fun importDocuments(uris: List<Uri>) {
        viewModelScope.launch {
            val app = getApplication<DocWalletApplication>()
            var successCount = 0
            var failCount = 0
            for (uri in uris) {
                try {
                    val mimeType = withContext(Dispatchers.IO) {
                        app.contentResolver.getType(uri) ?: "application/octet-stream"
                    }
                    val doc = withContext(Dispatchers.IO) {
                        app.documentImporter.importDocument(uri, mimeType)
                    }
                    if (doc != null) successCount++ else failCount++
                } catch (e: Exception) {
                    failCount++
                }
            }
            _snackbarMessage.value = when {
                failCount == 0 -> "Imported $successCount document${if (successCount != 1) "s" else ""}"
                successCount == 0 -> "Import failed"
                else -> "Imported $successCount document${if (successCount != 1) "s" else ""}, $failCount failed"
            }
        }
    }

    fun clearSnackbarMessage() {
        _snackbarMessage.value = null
    }

    private val _thumbnails = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val thumbnails: StateFlow<Map<String, ImageBitmap>> = _thumbnails.asStateFlow()

    private val thumbnailSemaphore = Semaphore(4)

    fun loadThumbnail(documentId: String, thumbnailPath: String) {
        viewModelScope.launch {
            val cached = ThumbnailCache.get(documentId)
            if (cached != null) {
                _thumbnails.value = _thumbnails.value + (documentId to cached.asImageBitmap())
                return@launch
            }
            thumbnailSemaphore.acquire()
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    decryptThumbnail(thumbnailPath)
                }
                if (bitmap != null) {
                    ThumbnailCache.put(documentId, bitmap)
                    _thumbnails.value = _thumbnails.value + (documentId to bitmap.asImageBitmap())
                    if (_thumbnails.value.size > 100) {
                        val toEvict = _thumbnails.value.keys
                            .sorted().take(_thumbnails.value.size - 50)
                        _thumbnails.value = _thumbnails.value - toEvict.toSet()
                    }
                }
            } finally {
                thumbnailSemaphore.release()
            }
        }
    }

    private fun decryptThumbnail(path: String): android.graphics.Bitmap? {
        return try {
            val file = File(path)
            if (!file.exists()) return null
            val bytes = FileInputStream(file).use { input ->
                DataInputStream(input).use { dis ->
                    val iv = ByteArray(12)
                    dis.readFully(iv)
                    val encrypted = dis.readBytes()
                    val masterKey = app.encryptionManager.getMasterKeyForSession()
                        ?: return null
                    app.fileEncryptor.decryptBytes(encrypted, masterKey, iv)
                }
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    private fun SearchResultWithOffsets.toSearchResultItem(): SearchResultItem {
        val matches = parseHighlight(highlightContent, textContent)
        return SearchResultItem(
            id = id,
            title = title,
            mimeType = mimeType,
            pageCount = pageCount,
            author = author,
            thumbnailPath = thumbnailPath,
            matches = matches.take(10),
        )
    }

    private fun parseHighlight(highlightContent: String, textContent: String): List<SearchResultMatch> {
        if (highlightContent.isBlank()) return emptyList()
        val matchList = mutableListOf<SearchResultMatch>()
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
                            matchList.add(SearchResultMatch(snippet = cleaned, pageNumber = pageNumber))
                        }
                        matchStart = -1
                    }
                }
                else -> rawOffset++
            }
        }
        return matchList
    }

    private fun extractSnippet(text: String, startOffset: Int, endOffset: Int): String {
        val contextBefore = 120
        val contextAfter = 120
        val snippetStart = (startOffset - contextBefore).coerceAtLeast(0)
        val snippetEnd = (endOffset + contextAfter).coerceAtMost(text.length)
        val prefix = text.substring(snippetStart, startOffset)
        val match = text.substring(startOffset, endOffset)
        val suffix = text.substring(endOffset, snippetEnd)
        return "${prefix}<b>$match</b>$suffix"
    }

    private fun stripMarkers(text: String): String {
        return text.replace(Regex("\\[PAGE=\\d+\\]"), "")
            .replace(Regex("\\[SECTION=\\d+\\]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractPageNumber(text: String, offset: Int): Int {
        val before = text.substring(0, offset.coerceAtMost(text.length))
        val pageMatches = Regex("\\[PAGE=(\\d+)\\]").findAll(before)
        val sectionMatches = Regex("\\[SECTION=(\\d+)\\]").findAll(before)
        val lastPage = pageMatches.lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
        val lastSection = sectionMatches.lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
        return lastPage ?: lastSection ?: 0
    }
}
