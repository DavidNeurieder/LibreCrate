package com.docwallet.vault.database

class VaultFtsIndexer(private val db: SqlHandle) {

    fun indexDocument(
        documentId: String,
        title: String,
        author: String,
        description: String,
        textContent: String?
    ) {
        db.execSQL(
            """INSERT OR REPLACE INTO documents_fts(rowid, title, author, description, text_content)
               VALUES ((SELECT rowid FROM documents WHERE id = ?), ?, ?, ?, ?)""".trimIndent(),
            arrayOf(documentId, title, author, description, textContent)
        )
    }

    fun removeDocument(documentId: String) {
        db.execSQL(
            "DELETE FROM documents_fts WHERE rowid IN (SELECT rowid FROM documents WHERE id = ?)",
            arrayOf(documentId)
        )
    }
}
