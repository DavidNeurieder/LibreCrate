package com.librecrate.app.ui.viewer

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.librecrate.app.LibreCrateApplication
import com.librecrate.app.data.SessionStore
import com.librecrate.app.data.model.Document
import com.librecrate.app.data.model.DocumentType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class ViewerViewModel @JvmOverloads constructor(
    application: Application,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(application) {
    private val app = application as LibreCrateApplication
    private val vault = app.vaultRepository

    private val _document = MutableStateFlow<Document?>(null)
    val document: StateFlow<Document?> = _document.asStateFlow()

    private val _decryptedFile = MutableStateFlow<File?>(null)
    val decryptedFile: StateFlow<File?> = _decryptedFile.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun loadDocument(documentId: String, isNewNote: Boolean = false) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            var doc = withContext(ioDispatcher) { vault.getDocument(documentId) }

            if (doc == null) {
                if (isNewNote) {
                    val content = ""
                    val imported = withContext(ioDispatcher) {
                        vault.importDocument(
                            id = documentId,
                            title = "New Note",
                            fileData = content.encodeToByteArray(),
                            mimeType = "text/markdown",
                            author = "",
                            description = "",
                            textContent = content,
                        )
                    }
                    if (imported != null) {
                        doc = vault.getDocument(documentId)
                        val emptyFile = File(app.cacheDir, "viewer_${documentId}_${doc?.fileName ?: "note.md"}")
                        emptyFile.deleteOnExit()
                        emptyFile.writeText("")
                        _decryptedFile.value = emptyFile
                        _document.value = doc
                        doc?.let { SessionStore.saveLastDocumentId(app, it.id) }
                        _isLoading.value = false; return@launch
                    }
                }
                Log.w(TAG, "Document not found for id=$documentId")
                SessionStore.clearLastDocumentId(app)
                _error.value = "Document not found"
                _isLoading.value = false; return@launch
            }

            _document.value = doc
            val decrypted = withContext(ioDispatcher) {
                val fileData = vault.exportDocumentFile(doc.id) ?: return@withContext null
                val tempFile = File(app.cacheDir, "viewer_${doc.id}_${doc.fileName}")
                if (tempFile.exists()) tempFile.delete()
                tempFile.deleteOnExit()
                tempFile.writeBytes(fileData)
                tempFile
            }
            if (decrypted == null) {
                _error.value = "Failed to read document file"
                _isLoading.value = false; return@launch
            }
            _decryptedFile.value = decrypted

            withContext(ioDispatcher) {
                vault.updateDocument(doc.id, doc.title, doc.isFavorite)
                vault.setReadingPosition(doc.id, doc.readingPosition ?: "")
                vault.setCurrentPage(doc.id, doc.currentPage)
                SessionStore.saveLastDocumentId(app, doc.id)
            }
            _isLoading.value = false
        }
    }

    fun toggleFavorite() {
        val doc = _document.value ?: return
        viewModelScope.launch {
            vault.updateDocument(doc.id, doc.title, !doc.isFavorite)
            _document.value = doc.copy(isFavorite = !doc.isFavorite)
        }
    }

    fun renameDocument(newTitle: String) {
        val doc = _document.value ?: return
        viewModelScope.launch {
            vault.updateDocument(doc.id, newTitle, doc.isFavorite)
            _document.value = doc.copy(title = newTitle)
        }
    }

    fun deleteDocument() {
        val doc = _document.value ?: return
        viewModelScope.launch {
            vault.deleteDocumentFull(doc.id)
            _document.value = null
        }
    }

    fun saveReadingPosition(page: Int) {
        val doc = _document.value ?: return
        viewModelScope.launch {
            vault.setCurrentPage(doc.id, page)
            vault.updateDocument(doc.id, doc.title, doc.isFavorite)
            _document.value = doc.copy(currentPage = page)
        }
    }

    fun saveReadingPositionJson(positionJson: String) {
        val doc = _document.value ?: return
        viewModelScope.launch {
            vault.setReadingPosition(doc.id, positionJson)
            _document.value = doc.copy(readingPosition = positionJson, lastOpenedAt = System.currentTimeMillis())
        }
    }

    fun getDocumentType(): DocumentType {
        val mime = _document.value?.mimeType ?: return DocumentType.UNKNOWN
        return DocumentType.fromMimeType(mime)
    }

    override fun onCleared() {
        super.onCleared()
        cleanupTempFiles()
    }

    fun cleanupTempFiles() {
        _decryptedFile.value?.let { file ->
            try { if (file.exists()) file.delete() } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "ViewerViewModel"
    }
}
