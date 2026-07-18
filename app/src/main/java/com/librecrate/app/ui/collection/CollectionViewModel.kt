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
    private val collectionDao = (application as LibreCrateApplication).collectionDao

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val collections: StateFlow<List<Collection>> = collectionDao.getAllCollections()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val nameInput = MutableStateFlow("")
    val editingCollection = MutableStateFlow<Collection?>(null)
    val showDialog = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            collectionDao.getAllCollections().collect {
                _isLoading.value = false
            }
        }
    }

    fun createCollection(name: String) {
        viewModelScope.launch {
            collectionDao.insert(
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
            val collection = collectionDao.getCollectionById(id) ?: return@launch
            collectionDao.update(collection.copy(name = newName))
        }
    }

    fun deleteCollection(id: String) {
        viewModelScope.launch {
            collectionDao.deleteById(id)
        }
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
        if (editing != null) {
            renameCollection(editing.id, name)
        } else {
            createCollection(name)
        }
        showDialog.value = false
        nameInput.value = ""
        editingCollection.value = null
    }
}
