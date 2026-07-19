use crate::db::queries::{self, DocumentRow};
use rusqlite::Connection;
use std::path::Path;

/// Save a thumbnail blob at `base_dir/files/<id>.thumb`.
pub fn store_thumbnail(base_dir: &Path, id: &str, data: &[u8]) -> std::io::Result<()> {
    let path = base_dir.join("files").join(format!("{id}.thumb"));
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(&path, data)
}

/// Load a thumbnail blob from `base_dir/files/<id>.thumb`.
pub fn load_thumbnail(base_dir: &Path, id: &str) -> Option<Vec<u8>> {
    let path = base_dir.join("files").join(format!("{id}.thumb"));
    if path.exists() {
        std::fs::read(&path).ok()
    } else {
        None
    }
}

/// Save a file blob at `base_dir/files/<id>`.
pub fn save_file(base_dir: &Path, id: &str, data: &[u8]) -> std::io::Result<()> {
    let path = base_dir.join("files").join(id);
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(&path, data)
}

/// Load a file blob from `base_dir/files/<id>`.
pub fn load_file(base_dir: &Path, id: &str) -> Option<Vec<u8>> {
    let path = base_dir.join("files").join(id);
    if path.exists() {
        std::fs::read(&path).ok()
    } else {
        None
    }
}

/// Delete a file blob at `base_dir/files/<id>`.
pub fn delete_file(base_dir: &Path, id: &str) {
    let path = base_dir.join("files").join(id);
    let _ = std::fs::remove_file(&path);
}

/// Import a document: store the file blob, insert DB row with IV, and index into FTS5.
/// Returns the document ID.
pub fn import_document(
    conn: &Connection,
    base_dir: &Path,
    id: &str,
    title: &str,
    file_data: &[u8],
    mime_type: &str,
    author: &str,
    description: &str,
    text_content: Option<&str>,
) -> rusqlite::Result<String> {
    // Store blob
    save_file(base_dir, id, file_data).map_err(|e| {
        rusqlite::Error::ToSqlConversionFailure(Box::new(e))
    })?;

    // Generate random 12-byte IV (matching app behavior)
    let iv: Vec<u8> = (0..12).map(|_| rand::random::<u8>()).collect();
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;
    let file_path = format!("files/{id}");

    let doc = DocumentRow {
        id: id.to_string(),
        title: title.to_string(),
        file_name: id.rsplit('/').next().unwrap_or(id).to_string(),
        mime_type: mime_type.to_string(),
        file_path,
        file_size: file_data.len() as i64,
        author: author.to_string(),
        description: description.to_string(),
        imported_at: now,
        last_opened_at: now,
        modified_at: now,
        encryption_iv: Some(iv),
        ..Default::default()
    };

    queries::add_document_full(conn, &doc, text_content)?;
    Ok(id.to_string())
}

/// Export a document's file blob from storage.
pub fn export_document_file(conn: &Connection, base_dir: &Path, id: &str) -> Option<Vec<u8>> {
    let doc = queries::get_document(conn, id).ok()??;
    load_file(base_dir, &doc.id)
}

/// Delete a document: remove file blob, delete DB row, remove FTS entry.
pub fn delete_document_full(conn: &Connection, base_dir: &Path, id: &str) -> rusqlite::Result<bool> {
    // Remove from FTS first
    conn.execute(
        "INSERT INTO documents_fts(documents_fts, rowid) VALUES ('delete', (SELECT rowid FROM documents WHERE id = ?1))",
        rusqlite::params![id],
    )?;
    delete_file(base_dir, id);
    queries::delete_document(conn, id)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::db::schema::create_encrypted_db;

    #[test]
    fn test_import_export_roundtrip() {
        let mk = (0..32).collect::<Vec<u8>>();
        let tmp = tempfile::TempDir::new().unwrap();

        let db_path = tmp.path().join("databases/librecrate.db");
        std::fs::create_dir_all(db_path.parent().unwrap()).unwrap();
        let conn = create_encrypted_db(db_path.to_str().unwrap(), &mk).unwrap();

        let data = b"Hello, world!".to_vec();
        let doc_id = import_document(
            &conn, tmp.path(), "test-doc-1",
            "Test Doc", &data, "text/plain",
            "Author", "Description", Some("hello world content"),
        ).unwrap();
        assert_eq!(doc_id, "test-doc-1");

        // Verify file exists
        assert!(tmp.path().join("files/test-doc-1").exists());

        // Verify list
        let docs = queries::list_documents(&conn).unwrap();
        assert_eq!(docs.len(), 1);

        // Export back
        let exported = export_document_file(&conn, tmp.path(), "test-doc-1").unwrap();
        assert_eq!(exported, data);

        // Verify FTS
        let results = crate::db::fts::search(&conn, "hello").unwrap();
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].id, "test-doc-1");

        // Delete
        delete_document_full(&conn, tmp.path(), "test-doc-1").unwrap();
        assert!(!tmp.path().join("files/test-doc-1").exists());
        assert_eq!(queries::list_documents(&conn).unwrap().len(), 0);
    }
}
