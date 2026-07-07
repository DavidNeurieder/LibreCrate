package com.docwallet.data.search

import com.docwallet.data.db.DocumentDao
import com.docwallet.data.model.Document
import com.docwallet.data.model.toRoomEntity
import com.docwallet.vault.database.VaultSearchEngine
import com.docwallet.vault.model.DocumentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

class SearchEngine(
    private val vaultSearch: VaultSearchEngine,
    private val documentDao: DocumentDao,
) {
    fun search(query: String, filterType: DocumentType? = null): Flow<List<Document>> {
        if (query.isBlank()) {
            return if (filterType != null) {
                documentDao.getDocumentsByType(filterType.mimeType.replace("/*", "/%"))
            } else {
                documentDao.getAllDocuments()
            }
        }

        return flow {
            val results = withContext(Dispatchers.IO) {
                vaultSearch.search(query, filterType)
            }
            emit(results.map { it.toRoomEntity() })
        }
    }

    fun searchByType(mimeType: String): Flow<List<Document>> {
        return documentDao.getDocumentsByType(mimeType)
    }

    fun getSuggestions(prefix: String): Flow<List<String>> = flow {
        val results = withContext(Dispatchers.IO) {
            vaultSearch.getSuggestions(prefix)
        }
        emit(results)
    }
}
