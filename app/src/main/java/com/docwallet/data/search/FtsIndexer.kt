package com.docwallet.data.search

import com.docwallet.vault.database.VaultFtsIndexer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FtsIndexer(private val vaultIndexer: VaultFtsIndexer) {

    suspend fun indexDocument(
        documentId: String,
        title: String,
        author: String,
        description: String,
        textContent: String?
    ) = withContext(Dispatchers.IO) {
        vaultIndexer.indexDocument(documentId, title, author, description, textContent)
    }

    suspend fun removeDocument(documentId: String) = withContext(Dispatchers.IO) {
        vaultIndexer.removeDocument(documentId)
    }
}
