use rusqlite::Connection;

/// Execute a PRAGMA statement that returns results without consuming the rows.
fn set_pragma(conn: &Connection, sql: &str) -> rusqlite::Result<()> {
    let mut stmt = conn.prepare(sql)?;
    let _ = stmt.query([])?;
    Ok(())
}

/// Create all tables, FTS virtual table, triggers, and indexes.
pub fn create_all_tables(conn: &Connection) -> rusqlite::Result<()> {
    conn.execute_batch(
        "
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
        );

        CREATE TABLE IF NOT EXISTS tags (
            id TEXT NOT NULL PRIMARY KEY,
            name TEXT NOT NULL UNIQUE,
            color INTEGER NOT NULL
        );

        CREATE TABLE IF NOT EXISTS collections (
            id TEXT NOT NULL PRIMARY KEY,
            name TEXT NOT NULL,
            icon TEXT NOT NULL,
            sort_order INTEGER NOT NULL,
            parent_id TEXT
        );

        CREATE TABLE IF NOT EXISTS document_tags (
            document_id TEXT NOT NULL,
            tag_id TEXT NOT NULL,
            PRIMARY KEY (document_id, tag_id),
            FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
            FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE
        );

        CREATE VIRTUAL TABLE IF NOT EXISTS documents_fts USING fts5(
            title, author, description, text_content,
            content=documents,
            content_rowid=rowid
        );

        CREATE INDEX IF NOT EXISTS idx_document_tags_document_id
            ON document_tags(document_id);
        CREATE INDEX IF NOT EXISTS idx_document_tags_tag_id
            ON document_tags(tag_id);
        ",
    )?;

    create_fts_triggers(conn)?;

    Ok(())
}

fn create_fts_triggers(conn: &Connection) -> rusqlite::Result<()> {
    conn.execute_batch(
        "
        CREATE TRIGGER IF NOT EXISTS documents_fts_ai AFTER INSERT ON documents BEGIN
            INSERT INTO documents_fts(rowid, title, author, description, text_content)
            VALUES (new.rowid, new.title, new.author, new.description, new.text_content);
        END;

        CREATE TRIGGER IF NOT EXISTS documents_fts_ad AFTER DELETE ON documents BEGIN
            INSERT INTO documents_fts(documents_fts, rowid, title, author, description, text_content)
            VALUES ('delete', old.rowid, old.title, old.author, old.description, old.text_content);
        END;

        CREATE TRIGGER IF NOT EXISTS documents_fts_au AFTER UPDATE ON documents BEGIN
            INSERT INTO documents_fts(documents_fts, rowid, title, author, description, text_content)
            VALUES ('delete', old.rowid, old.title, old.author, old.description, old.text_content);
            INSERT INTO documents_fts(rowid, title, author, description, text_content)
            VALUES (new.rowid, new.title, new.author, new.description, new.text_content);
        END;
        ",
    )?;
    Ok(())
}

/// Open a SQLCipher-encrypted database.
/// Cipher settings are set BEFORE the key, as recommended by SQLCipher docs.
pub fn open_encrypted(path: &str, master_key: &[u8]) -> rusqlite::Result<Connection> {
    let hex_key = hex::encode(master_key);
    let conn = Connection::open(path)?;
    set_pragma(&conn, "PRAGMA cipher_compatibility = 4")?;
    set_pragma(&conn, "PRAGMA cipher_kdf_algorithm = sha512")?;
    set_pragma(&conn, "PRAGMA cipher_hmac_algorithm = sha512")?;
    set_pragma(&conn, "PRAGMA kdf_iter = 256000")?;
    set_pragma(&conn, "PRAGMA cipher_page_size = 4096")?;
    set_pragma(&conn, "PRAGMA cipher_default_kdf_iter = 256000")?;
    set_pragma(&conn, &format!("PRAGMA key = \"x'{hex_key}'\""))?;
    // Verify the key
    conn.query_row("SELECT count(*) FROM sqlite_master", [], |_| Ok(()))?;
    Ok(conn)
}

pub fn open_plain(path: &str) -> rusqlite::Result<Connection> {
    Connection::open(path)
}

pub fn get_schema_version(conn: &Connection) -> rusqlite::Result<i64> {
    conn.query_row("PRAGMA user_version", [], |row| row.get(0))
}

pub fn set_schema_version(conn: &Connection, version: i64) -> rusqlite::Result<()> {
    conn.execute_batch(&format!("PRAGMA user_version = {version}"))
}

/// Open an encrypted DB and create all tables in one call.
/// Creates parent directories if they don't exist.
pub fn create_encrypted_db(path: &str, master_key: &[u8]) -> rusqlite::Result<Connection> {
    if let Some(parent) = std::path::Path::new(path).parent() {
        std::fs::create_dir_all(parent).map_err(|e| {
            rusqlite::Error::ToSqlConversionFailure(Box::new(e))
        })?;
    }
    let conn = open_encrypted(path, master_key)?;
    create_all_tables(&conn)?;
    Ok(conn)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_create_all_tables_plain_db() {
        let conn = Connection::open_in_memory().unwrap();
        create_all_tables(&conn).unwrap();

        let tables: Vec<String> = conn
            .prepare("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
            .unwrap()
            .query_map([], |row| row.get(0))
            .unwrap()
            .filter_map(|r| r.ok())
            .collect();
        assert!(tables.contains(&"documents".into()));
        assert!(tables.contains(&"tags".into()));
        assert!(tables.contains(&"collections".into()));
        assert!(tables.contains(&"document_tags".into()));

        let fts_exists: bool = conn
            .prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name='documents_fts'")
            .unwrap()
            .exists([])
            .unwrap();
        assert!(fts_exists);
    }

    #[test]
    fn test_open_encrypted_in_memory() {
        let master_key = (0..32).collect::<Vec<u8>>();
        let conn = Connection::open_in_memory().unwrap();
        let hex_key = hex::encode(&master_key);
        set_pragma(&conn, "PRAGMA cipher_compatibility = 4").unwrap();
        set_pragma(&conn, &format!("PRAGMA key = \"x'{hex_key}'\""))
            .unwrap();
        conn.execute("CREATE TABLE t (id INTEGER)", []).unwrap();
        conn.execute("INSERT INTO t VALUES (42)", []).unwrap();
        let val: i32 = conn
            .query_row("SELECT id FROM t", [], |row| row.get(0))
            .unwrap();
        assert_eq!(val, 42);
    }

    #[test]
    fn test_encrypted_db_file_roundtrip() {
        let master_key = (0..32).collect::<Vec<u8>>();
        let tmp = tempfile::NamedTempFile::new().unwrap();
        let path = tmp.path().to_str().unwrap().to_string();

        {
            let conn = open_encrypted(&path, &master_key).unwrap();
            create_all_tables(&conn).unwrap();
            conn.execute(
                "INSERT INTO documents (id, title, file_name, mime_type, file_path, file_size, page_count, author, description, imported_at, last_opened_at, modified_at, is_favorite, is_conflict, current_page) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15)",
                rusqlite::params!["doc1", "Test Doc", "test.txt", "text/plain", "files/test.txt", 100i64, 1, "Author", "Desc", 1000i64, 1000i64, 1000i64, 0, 0, 0],
            ).unwrap();
        }

        {
            let conn = open_encrypted(&path, &master_key).unwrap();
            let title: String = conn
                .query_row(
                    "SELECT title FROM documents WHERE id = ?1",
                    ["doc1"],
                    |row| row.get(0),
                )
                .unwrap();
            assert_eq!(title, "Test Doc");
        }
    }

    #[test]
    fn test_wrong_key_fails() {
        let master_key = (0..32).collect::<Vec<u8>>();
        let wrong_key = (1..33).collect::<Vec<u8>>();
        let tmp = tempfile::NamedTempFile::new().unwrap();
        let path = tmp.path().to_str().unwrap().to_string();

        {
            let conn = open_encrypted(&path, &master_key).unwrap();
            create_all_tables(&conn).unwrap();
        }

        {
            let result = open_encrypted(&path, &wrong_key);
            assert!(result.is_err());
        }
    }
}
