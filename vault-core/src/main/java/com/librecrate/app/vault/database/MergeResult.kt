package com.librecrate.app.vault.database

data class MergeResult(
    val documentsAdded: Int,
    val documentsUpdated: Int,
    val documentsConflicted: Int,
    val documentsSkipped: Int,
    val collectionsAdded: Int,
    val tagsAdded: Int,
) {
    val hasConflicts: Boolean get() = documentsConflicted > 0
    val totalProcessed: Int get() = documentsAdded + documentsUpdated + documentsConflicted + documentsSkipped
}
