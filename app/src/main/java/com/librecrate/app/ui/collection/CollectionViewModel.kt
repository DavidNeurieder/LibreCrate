package com.librecrate.app.ui.collection

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.librecrate.app.LibreCrateApplication
import com.librecrate.app.data.model.Collection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CollectionViewModel(application: Application) : AndroidViewModel(application) {
    private val vault = (application as LibreCrateApplication).vaultRepository

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val collections: StateFlow<List<Collection>> = vault.collections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val nameInput = MutableStateFlow("")
    val editingCollection = MutableStateFlow<Collection?>(null)
    val showDialog = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            vault.collections.collect { _isLoading.value = false }
        }
    }

    fun createCollection(name: String) {
        viewModelScope.launch {
            vault.addCollection(
                Collection(
                    name = name,
                    icon = "folder",
                    sortOrder = System.currentTimeMillis().toInt(),
                )
            )
        }
    }

    fun renameCollection(id: String, newName: String) {
        viewModelScope.launch {
            val collection = vault.getCollection(id) ?: return@launch
            vault.updateCollection(id, newName, collection.icon, collection.sortOrder, collection.parentId)
        }
    }

    fun deleteCollection(id: String) {
        viewModelScope.launch { vault.deleteCollection(id) }
    }

    fun setEditing(collection: Collection?) {
        editingCollection.value = collection
        nameInput.value = collection?.name ?: ""
        showDialog.value = true
    }

    fun save() {
        val name = nameInput.value.trim()
        if (name.isEmpty()) return
        val editing = editingCollection.value
        if (editing != null) renameCollection(editing.id, name) else createCollection(name)
        showDialog.value = false
        nameInput.value = ""
        editingCollection.value = null
    }
}
