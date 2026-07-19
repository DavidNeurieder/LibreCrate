package com.librecrate.app.ui.export

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.librecrate.app.LibreCrateApplication
import com.librecrate.app.data.model.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExportViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LibreCrateApplication
    private val vault = app.vaultRepository

    private val _allDocuments = MutableStateFlow<List<Document>>(emptyList())
    val searchQuery = MutableStateFlow("")

    private val _filteredDocuments = MutableStateFlow<List<Document>>(emptyList())
    val filteredDocuments: StateFlow<List<Document>> = _filteredDocuments.asStateFlow()

    private val _selectedDocIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedDocIds: StateFlow<Set<String>> = _selectedDocIds.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _exportProgress = MutableStateFlow<String?>(null)
    val exportProgress: StateFlow<String?> = _exportProgress.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        loadDocuments()
        observeSearch()
    }

    private fun loadDocuments() {
        viewModelScope.launch(Dispatchers.IO) {
            _allDocuments.value = vault.listDocuments()
        }
    }

    private fun observeSearch() {
        viewModelScope.launch {
            combine(_allDocuments, searchQuery) { docs, query ->
                if (query.isBlank()) docs
                else docs.filter { doc ->
                    val q = query.lowercase()
                    doc.title.lowercase().contains(q) ||
                    doc.fileName.lowercase().contains(q) ||
                    doc.author.lowercase().contains(q) ||
                    doc.description.lowercase().contains(q)
                }
            }.collect { _filteredDocuments.value = it }
        }
    }

    fun search(query: String) { searchQuery.value = query }

    fun toggleDocumentSelection(id: String) {
        val current = _selectedDocIds.value.toMutableSet()
        if (current.contains(id)) current.remove(id) else current.add(id)
        _selectedDocIds.value = current
    }

    fun selectAll() { _selectedDocIds.value = _filteredDocuments.value.map { it.id }.toSet() }
    fun deselectAll() { _selectedDocIds.value = emptySet() }

    fun onExportDocumentsConfirmed(uri: Uri) {
        val selectedIds = _selectedDocIds.value
        if (selectedIds.isEmpty()) { _message.value = "No documents selected"; return }

        _isExporting.value = true
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val docsToExport = _allDocuments.value.filter { it.id in selectedIds }
                    val total = docsToExport.size
                    val nameCounts = mutableMapOf<String, Int>()

                    val tempZip = File(app.cacheDir, "export_${System.currentTimeMillis()}.zip")
                    ZipOutputStream(tempZip.outputStream()).use { zos ->
                        docsToExport.forEachIndexed { i, doc ->
                            _exportProgress.value = "Reading ${i + 1} of $total"
                            val fileData = vault.exportDocumentFile(doc.id) ?: return@forEachIndexed

                            val name = doc.fileName.ifBlank { doc.title.ifBlank { "document" } }
                            val count = nameCounts.getOrDefault(name, 0)
                            nameCounts[name] = count + 1
                            val entryName = if (count > 0) {
                                val dot = name.lastIndexOf('.')
                                if (dot >= 0) "${name.substring(0, dot)}_$count${name.substring(dot)}"
                                else "${name}_$count"
                            } else name

                            zos.putNextEntry(ZipEntry(entryName))
                            zos.write(fileData)
                            zos.closeEntry()
                        }
                    }

                    _exportProgress.value = "Writing to file"
                    app.contentResolver.openOutputStream(uri)?.use { out ->
                        tempZip.inputStream().use { `in` -> `in`.copyTo(out) }
                    } ?: run { tempZip.delete(); return@withContext false }
                    tempZip.delete(); true
                } catch (e: Exception) {
                    android.util.Log.e("ExportVM", "Export failed", e)
                    _message.value = "Export failed: ${e.localizedMessage ?: "Unknown error"}"
                    false
                }
            }

            _exportProgress.value = null
            _isExporting.value = false
            if (success) deselectAll()
            _message.value = if (success) "Documents exported successfully" else null
        }
    }

    fun clearMessage() { _message.value = null }
    fun refreshDocuments() { loadDocuments() }
}
