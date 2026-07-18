package com.librecrate.app.ui.viewer

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.librecrate.app.LibreCrateApplication
import com.librecrate.app.data.SessionStore
import com.librecrate.app.vault.crypto.FileEncryptor
import com.librecrate.app.data.model.Document
import com.librecrate.app.vault.model.DocumentType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ViewerViewModel @JvmOverloads constructor(
    application: Application,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AndroidViewModel(application) {
    private val app = application as LibreCrateApplication
    private val fileEncryptor = FileEncryptor()

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
            var doc = withContext(ioDispatcher) {
                app.documentDao.getDocumentById(documentId)
            }
            if (doc == null) {
                if (isNewNote) {
                    doc = withContext(ioDispatcher) {
                        val filesDir = File(app.filesDir, "files").also { it.mkdirs() }
                        val encryptedFile = File(filesDir, "${java.util.UUID.randomUUID()}.enc")
                        val tempFile = File(app.cacheDir, "new_note_$documentId.md")
                        tempFile.writeText("")
                        val masterKey = app.encryptionManager.getMasterKeyForSession()
                            ?: throw IllegalStateException("No master key available")
                        val iv = app.fileEncryptor.encrypt(tempFile, encryptedFile, masterKey)
                        tempFile.delete()
                        val document = Document(
                            id = documentId,
                            title = "New Note",
                            fileName = "${documentId}.md",
                            mimeType = "text/markdown",
                            filePath = encryptedFile.absolutePath,
                            importedAt = System.currentTimeMillis(),
                            encryptionIv = iv,
                            textContent = "",
                        )
                        app.documentDao.insert(document)
                        document
                    }
                    val emptyFile = File(app.cacheDir, "viewer_${doc.id}_${doc.fileName}")
                    emptyFile.deleteOnExit()
                    emptyFile.writeText("")
                    _decryptedFile.value = emptyFile
                    _document.value = doc
                    SessionStore.saveLastDocumentId(app, documentId)
                    _isLoading.value = false
                    return@launch
                }
                Log.w("ViewerViewModel", "Document not found for id=$documentId")
                SessionStore.clearLastDocumentId(app)
                _error.value = "Document not found"
                _isLoading.value = false
                return@launch
            }
            _document.value = doc

            val decrypted = withContext(ioDispatcher) {
                val masterKey = app.encryptionManager.getMasterKeyForSession()
                    ?: throw IllegalStateException("No master key available for decryption")
                val encryptedFile = File(doc.filePath)
                val tempFile = File(app.cacheDir, "viewer_${doc.id}_${doc.fileName}")
                if (tempFile.exists()) tempFile.delete()
                tempFile.deleteOnExit()
                val iv = doc.encryptionIv
                    ?: return@withContext null.also {
                        Log.e("ViewerViewModel", "Document $documentId has no encryption IV")
                    }
                fileEncryptor.decrypt(encryptedFile, tempFile, masterKey, iv)
                tempFile
            }
            if (decrypted == null) {
                _error.value = "Document is corrupted or has no encryption data"
                _isLoading.value = false
                return@launch
            }
            _decryptedFile.value = decrypted

            withContext(ioDispatcher) {
                val updated = doc.copy(lastOpenedAt = System.currentTimeMillis())
                app.documentDao.update(updated)
                _document.value = updated
                SessionStore.saveLastDocumentId(app, documentId)
            }
            _isLoading.value = false
        }
    }

    fun toggleFavorite() {
        val doc = _document.value ?: return
        viewModelScope.launch {
            val updated = doc.copy(isFavorite = !doc.isFavorite)
            withContext(ioDispatcher) {
                app.documentDao.update(updated)
            }
            _document.value = updated
        }
    }

    fun renameDocument(newTitle: String) {
        val doc = _document.value ?: return
        viewModelScope.launch {
            val updated = doc.copy(title = newTitle)
            withContext(ioDispatcher) {
                app.documentDao.update(updated)
            }
            _document.value = updated
        }
    }

    fun deleteDocument() {
        val doc = _document.value ?: return
        viewModelScope.launch {
            withContext(ioDispatcher) {
                File(doc.filePath).delete()
                doc.thumbnailPath?.let { File(it).delete() }
                app.documentDao.deleteById(doc.id)
            }
            _document.value = null
        }
    }

    fun saveReadingPosition(page: Int) {
        val doc = _document.value ?: return
        viewModelScope.launch {
            val updated = doc.copy(currentPage = page)
            withContext(ioDispatcher) {
                app.documentDao.update(updated)
            }
            _document.value = updated
        }
    }

    fun saveReadingPositionJson(positionJson: String) {
        val doc = _document.value ?: return
        viewModelScope.launch {
            val updated = doc.copy(
                readingPosition = positionJson,
                lastOpenedAt = System.currentTimeMillis(),
            )
            withContext(ioDispatcher) {
                app.documentDao.update(updated)
            }
            _document.value = updated
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
            try {
                if (file.exists()) file.delete()
            } catch (_: Exception) {
            }
        }
    }
}
