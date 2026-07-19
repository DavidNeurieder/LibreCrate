package com.librecrate.app.ui.library

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.librecrate.app.LibreCrateApplication
import com.librecrate.app.data.model.Document
import com.librecrate.app.data.model.DocumentType
import com.librecrate.app.data.model.SearchResultItem
import com.librecrate.app.data.model.SearchResultMatch
import com.librecrate.app.ui.common.ThumbnailCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.File

enum class SortOption(val label: String) {
    NAME_ASC("Name A-Z"),
    NAME_DESC("Name Z-A"),
    RECENTLY_OPENED("Recently opened"),
    DATE_NEWEST("Newest first"),
    DATE_OLDEST("Oldest first"),
}

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LibreCrateApplication
    private val vault = app.vaultRepository

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val searchQuery = MutableStateFlow("")
    val selectedSort = MutableStateFlow(SortOption.RECENTLY_OPENED)
    val filterType = MutableStateFlow<DocumentType?>(null)
    val favoritesOnly = MutableStateFlow(false)

    val documents: StateFlow<List<Document>> = combine(
        vault.documents,
        searchQuery,
        selectedSort,
        filterType,
        favoritesOnly,
    ) { docs: List<Document>, query: String, sort: SortOption, type: DocumentType?, favs: Boolean ->
        var result = docs
        if (query.isNotBlank()) {
            val q = query.lowercase()
            result = result.filter { doc ->
                doc.title.lowercase().contains(q) ||
                doc.author.lowercase().contains(q) ||
                doc.description.lowercase().contains(q)
            }
        }
        if (type != null) {
            result = result.filter { doc ->
                if (type.mimeType.endsWith("/*")) {
                    doc.mimeType.startsWith(type.mimeType.removeSuffix("/*"))
                } else doc.mimeType == type.mimeType
            }
        }
        if (favs) result = result.filter { it.isFavorite }
        when (sort) {
            SortOption.NAME_ASC -> result.sortedBy { it.title.lowercase() }
            SortOption.NAME_DESC -> result.sortedByDescending { it.title.lowercase() }
            SortOption.DATE_NEWEST -> result.sortedByDescending { it.importedAt }
            SortOption.DATE_OLDEST -> result.sortedBy { it.importedAt }
            SortOption.RECENTLY_OPENED -> result.sortedByDescending { it.lastOpenedAt }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val searchResults: StateFlow<List<SearchResultItem>> = combine(
        vault.documents,
        searchQuery,
    ) { docs: List<Document>, query: String ->
        if (query.isBlank()) return@combine emptyList()
        val q = query.lowercase()
        docs.filter { doc ->
            doc.title.lowercase().contains(q) ||
            doc.author.lowercase().contains(q) ||
            doc.description.lowercase().contains(q)
        }.map { doc ->
            SearchResultItem(
                id = doc.id,
                title = doc.title,
                mimeType = doc.mimeType,
                pageCount = doc.pageCount,
                author = doc.author,
                thumbnailPath = doc.thumbnailPath,
                matches = listOf(
                    SearchResultMatch(snippet = doc.description, pageNumber = doc.currentPage)
                ),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { vault.documents.collect { _isLoading.value = false } }
    }

    fun toggleFavorite(document: Document) {
        viewModelScope.launch { vault.updateDocument(document.id, document.title, !document.isFavorite) }
    }

    fun deleteDocument(document: Document) {
        viewModelScope.launch { vault.deleteDocumentFull(document.id) }
    }

    fun setSort(sort: SortOption) { selectedSort.value = sort }
    fun setFilter(type: DocumentType?) { filterType.value = type }
    fun search(query: String) { searchQuery.value = query }

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    fun importDocuments(uris: List<Uri>) {
        viewModelScope.launch {
            var successCount = 0
            var failCount = 0
            for (uri in uris) {
                try {
                    val mimeType = withContext(Dispatchers.IO) { app.contentResolver.getType(uri) ?: "application/octet-stream" }
                    val doc = app.documentImporter.importDocument(uri, mimeType)
                    if (doc != null) successCount++ else failCount++
                } catch (_: Exception) { failCount++ }
            }
            _snackbarMessage.value = when {
                failCount == 0 -> "Imported $successCount document${if (successCount != 1) "s" else ""}"
                successCount == 0 -> "Import failed"
                else -> "Imported $successCount document${if (successCount != 1) "s" else ""}, $failCount failed"
            }
        }
    }

    fun clearSnackbarMessage() { _snackbarMessage.value = null }

    private val _thumbnails = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val thumbnails: StateFlow<Map<String, ImageBitmap>> = _thumbnails.asStateFlow()
    private val thumbnailSemaphore = Semaphore(4)

    fun loadThumbnail(documentId: String, thumbnailPath: String?) {
        if (thumbnailPath == null) return
        viewModelScope.launch {
            val cached = ThumbnailCache.get(documentId)
            if (cached != null) {
                _thumbnails.value = _thumbnails.value + (documentId to cached.asImageBitmap())
                return@launch
            }
            thumbnailSemaphore.acquire()
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val thumbData = vault.loadThumbnail(documentId)
                    if (thumbData != null) BitmapFactory.decodeByteArray(thumbData, 0, thumbData.size)
                    else { val f = File(thumbnailPath); if (f.exists()) BitmapFactory.decodeFile(thumbnailPath) else null }
                }
                if (bitmap != null) {
                    ThumbnailCache.put(documentId, bitmap)
                    _thumbnails.value = _thumbnails.value + (documentId to bitmap.asImageBitmap())
                    if (_thumbnails.value.size > 100) {
                        val toEvict = _thumbnails.value.keys.sorted().take(_thumbnails.value.size - 50)
                        _thumbnails.value = _thumbnails.value - toEvict.toSet()
                    }
                }
            } finally { thumbnailSemaphore.release() }
        }
    }
}
