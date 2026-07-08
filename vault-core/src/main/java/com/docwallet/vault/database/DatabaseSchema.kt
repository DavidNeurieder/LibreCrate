package com.docwallet.vault.database

object DatabaseSchema {
    const val DB_NAME = "docwallet.db"

    val CREATE_DOCUMENTS_TABLE = """
        CREATE TABLE IF NOT EXISTS documents (
            id TEXT NOT NULL PRIMARY KEY,
            title TEXT NOT NULL,
            file_name TEXT NOT NULL,
            mime_type TEXT NOT NULL,
            file_path TEXT NOT NULL,
            file_size INTEGER NOT NULL,
            page_count INTEGER NOT NULL,
            author TEXT NOT NULL,
            description TEXT NOT NULL,
            thumbnail_path TEXT,
            imported_at INTEGER NOT NULL,
            last_opened_at INTEGER NOT NULL,
            modified_at INTEGER NOT NULL DEFAULT 0,
            is_favorite INTEGER NOT NULL DEFAULT 0,
            is_conflict INTEGER NOT NULL DEFAULT 0,
            conflict_with TEXT,
            collection_id TEXT,
            encryption_iv BLOB,
            text_content TEXT,
            barcode_format TEXT,
            barcode_value TEXT,
            current_page INTEGER NOT NULL DEFAULT 0,
            reading_position TEXT
        )
    """.trimIndent()

    val CREATE_TAGS_TABLE = """
        CREATE TABLE IF NOT EXISTS tags (
            id TEXT NOT NULL PRIMARY KEY,
            name TEXT NOT NULL UNIQUE,
            color INTEGER NOT NULL
        )
    """.trimIndent()

    val CREATE_COLLECTIONS_TABLE = """
        CREATE TABLE IF NOT EXISTS collections (
            id TEXT NOT NULL PRIMARY KEY,
            name TEXT NOT NULL,
            icon TEXT NOT NULL,
            sort_order INTEGER NOT NULL,
            parent_id TEXT
        )
    """.trimIndent()

    val CREATE_DOCUMENT_TAGS_TABLE = """
        CREATE TABLE IF NOT EXISTS document_tags (
            document_id TEXT NOT NULL,
            tag_id TEXT NOT NULL,
            PRIMARY KEY (document_id, tag_id),
            FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
            FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE
        )
    """.trimIndent()

    val CREATE_DOCUMENTS_FTS_TABLE = """
        CREATE VIRTUAL TABLE IF NOT EXISTS documents_fts USING fts5(
            title, author, description, text_content,
            content=documents,
            content_rowid=rowid
        )
    """.trimIndent()

    const val CREATE_IDX_DOCUMENT_TAGS_DOCUMENT_ID =
        "CREATE INDEX IF NOT EXISTS idx_document_tags_document_id ON document_tags(document_id)"

    const val CREATE_IDX_DOCUMENT_TAGS_TAG_ID =
        "CREATE INDEX IF NOT EXISTS idx_document_tags_tag_id ON document_tags(tag_id)"

    fun createAllTables(handle: SqlHandle) {
        handle.execSQL(CREATE_DOCUMENTS_TABLE)
        handle.execSQL(CREATE_TAGS_TABLE)
        handle.execSQL(CREATE_COLLECTIONS_TABLE)
        handle.execSQL(CREATE_DOCUMENT_TAGS_TABLE)
        handle.execSQL(CREATE_DOCUMENTS_FTS_TABLE)
        handle.execSQL(CREATE_IDX_DOCUMENT_TAGS_DOCUMENT_ID)
        handle.execSQL(CREATE_IDX_DOCUMENT_TAGS_TAG_ID)
    }

    fun createFtsTable(handle: SqlHandle) {
        handle.execSQL(CREATE_DOCUMENTS_FTS_TABLE)
    }

    val CREATE_FTS_TRIGGER_INSERT = """
        CREATE TRIGGER IF NOT EXISTS documents_fts_ai AFTER INSERT ON documents BEGIN
            INSERT INTO documents_fts(rowid, title, author, description, text_content)
            VALUES (new.rowid, new.title, new.author, new.description, new.text_content);
        END;
    """.trimIndent()

    val CREATE_FTS_TRIGGER_DELETE = """
        CREATE TRIGGER IF NOT EXISTS documents_fts_ad AFTER DELETE ON documents BEGIN
            INSERT INTO documents_fts(documents_fts, rowid, title, author, description, text_content)
            VALUES ('delete', old.rowid, old.title, old.author, old.description, old.text_content);
        END;
    """.trimIndent()

    val CREATE_FTS_TRIGGER_UPDATE = """
        CREATE TRIGGER IF NOT EXISTS documents_fts_au AFTER UPDATE ON documents BEGIN
            INSERT INTO documents_fts(documents_fts, rowid, title, author, description, text_content)
            VALUES ('delete', old.rowid, old.title, old.author, old.description, old.text_content);
            INSERT INTO documents_fts(rowid, title, author, description, text_content)
            VALUES (new.rowid, new.title, new.author, new.description, new.text_content);
        END;
    """.trimIndent()

    val REBUILD_FTS_INDEX = "INSERT INTO documents_fts(documents_fts) VALUES('rebuild')"
}
