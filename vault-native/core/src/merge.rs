use crate::crypto::aes_gcm;
use crate::error::{Error, Result};
use crate::format::import::ImportedContents;
use crate::types::KeyValue;
use rusqlite::Connection;
use std::collections::HashMap;
use std::path::Path;

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct MergeStats {
    pub documents_added: u32,
    pub documents_updated: u32,
    pub documents_conflicted: u32,
    pub documents_skipped: u32,
    pub collections_added: u32,
    pub tags_added: u32,
}

/// Restore a vault into a fresh environment (Branch B).
/// Copies DB file, writes key files, writes file blobs.
pub fn branch_b_fresh_install(
    contents: &ImportedContents,
    _password: &str,
    db_data: &[u8],
    encryption_dir: &Path,
    database_dir: &Path,
    files_dir: &Path,
) -> Result<()> {
    // Write key files from the vault
    std::fs::create_dir_all(encryption_dir)?;
    for kv in &contents.keys {
        let safe = crate::format::path::safe_archive_path(&kv.key)?;
        std::fs::write(encryption_dir.join(&safe), &kv.value)?;
    }

    // Write the DB
    std::fs::create_dir_all(database_dir)?;
    std::fs::write(database_dir.join("librecrate.db"), db_data)?;

    // Clean WAL/SHM
    let _ = std::fs::remove_file(database_dir.join("librecrate.db-wal"));
    let _ = std::fs::remove_file(database_dir.join("librecrate.db-shm"));

    // Write files from the vault
    std::fs::create_dir_all(files_dir)?;
    for kv in &contents.files {
        let safe = crate::format::path::safe_archive_path(&kv.key)?;
        let target = files_dir.join(&safe);
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(target, &kv.value)?;
    }

    Ok(())
}

/// Merge backup DB into existing current DB (Branch A).
///
/// **All-or-nothing:** the work runs inside a single `BEGIN IMMEDIATE …
/// COMMIT` transaction and every fallible statement propagates its error (no
/// swallowed `.ok()`), so a mid-merge failure rolls back and leaves the
/// existing vault untouched. When `backup_key` and `local_key` are both
/// supplied, the backup's file blobs are re-encrypted to the local key.
pub fn branch_a_merge(
    backup_path: &str,
    backup_master_key: &[u8],
    current_conn: &Connection,
    files: &[KeyValue],
    backup_key: Option<&[u8]>,
    local_key: Option<&[u8]>,
    files_dir: &Path,
) -> Result<MergeStats> {
    // Open backup DB
    let backup_conn = crate::db::schema::open_encrypted(backup_path, backup_master_key)?;

    // Read documents from backup
    let backup_docs = crate::db::queries::list_documents(&backup_conn)?;
    let backup_collections = crate::db::queries::list_collections(&backup_conn)?;
    let backup_tags = crate::db::queries::list_tags(&backup_conn)?;

    current_conn.execute_batch("BEGIN IMMEDIATE")?;

    let merge_result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(
        || -> Result<MergeStats> {
            let mut stats = MergeStats {
                documents_added: 0,
                documents_updated: 0,
                documents_conflicted: 0,
                documents_skipped: 0,
                collections_added: 0,
                tags_added: 0,
            };

            // Merge collections
            for col in &backup_collections {
                let exists = current_conn
                    .query_row(
                        "SELECT 1 FROM collections WHERE id = ?",
                        rusqlite::params![col.id],
                        |_| Ok(()),
                    )
                    .is_ok();
                if exists {
                    current_conn.execute(
                        "UPDATE collections SET name = ?1, icon = ?2, sort_order = ?3, parent_id = ?4 WHERE id = ?5",
                        rusqlite::params![col.name, col.icon, col.sort_order, col.parent_id, col.id],
                    )?;
                } else {
                    current_conn.execute(
                        "INSERT INTO collections (id, name, icon, sort_order, parent_id) VALUES (?1, ?2, ?3, ?4, ?5)",
                        rusqlite::params![col.id, col.name, col.icon, col.sort_order, col.parent_id],
                    )?;
                    stats.collections_added += 1;
                }
            }

            // Merge tags
            for tag in &backup_tags {
                let inserted = current_conn.execute(
                    "INSERT OR IGNORE INTO tags (id, name, color) VALUES (?1, ?2, ?3)",
                    rusqlite::params![tag.id, tag.name, tag.color],
                )?;
                if inserted > 0 {
                    stats.tags_added += 1;
                }
            }

            // Merge documents
            for doc in &backup_docs {
                let existing_row: rusqlite::Result<crate::db::queries::DocumentRow> = current_conn
                    .query_row(
                        "SELECT id, title, file_name, mime_type, file_path, file_size, page_count,
                         author, description, thumbnail_path, imported_at, last_opened_at,
                         modified_at, is_favorite, is_conflict, conflict_with, collection_id,
                         encryption_iv, current_page, reading_position, barcode_format, barcode_value,
                         content_hash
                         FROM documents WHERE id = ?",
                        rusqlite::params![doc.id],
                        crate::db::queries::document_from_row,
                    );
                match existing_row {
                    Err(_) => {
                        // No existing document — add backup doc
                        crate::db::queries::add_document(current_conn, doc)?;
                        copy_text_content(&backup_conn, current_conn, &doc.id)?;
                        stats.documents_added += 1;
                    }
                    Ok(existing) => {
                        // Detect conflict: file content differs
                        let content_differs = match (
                            existing.content_hash.as_deref(),
                            doc.content_hash.as_deref(),
                        ) {
                            (Some(a), Some(b)) => a != b,
                            _ => {
                                existing.file_size != doc.file_size
                                    || existing.mime_type != doc.mime_type
                            }
                        };

                        if content_differs {
                            // Mark existing as conflict
                            current_conn.execute(
                                "UPDATE documents SET is_conflict = 1, modified_at = ?1 WHERE id = ?2",
                                rusqlite::params![doc.modified_at, doc.id],
                            )?;
                            // Insert backup doc as conflict copy
                            let conflict_id = format!(
                                "{}-conflict-{}",
                                doc.id,
                                std::time::SystemTime::now()
                                    .duration_since(std::time::UNIX_EPOCH)
                                    .unwrap_or_default()
                                    .as_millis()
                            );
                            let mut conflict_doc = doc.clone();
                            conflict_doc.id = conflict_id;
                            conflict_doc.conflict_with = Some(doc.id.clone());
                            crate::db::queries::add_document(current_conn, &conflict_doc)?;
                            copy_text_content(&backup_conn, current_conn, &conflict_doc.id)?;
                            stats.documents_conflicted += 1;
                        } else if doc.modified_at > 0 {
                            current_conn.execute(
                                "UPDATE documents SET title = ?1, modified_at = ?2, is_favorite = ?3, current_page = ?4 WHERE id = ?5",
                                rusqlite::params![doc.title, doc.modified_at, doc.is_favorite as i32, doc.current_page, doc.id],
                            )?;
                            stats.documents_updated += 1;
                        } else {
                            stats.documents_skipped += 1;
                        }
                    }
                }
            }

            // Re-encrypt files if keys provided
            if let (Some(bk), Some(lk)) = (backup_key, local_key) {
                reencrypt_files(current_conn, &backup_docs, files, bk, lk, files_dir)?;
            }

            Ok(stats)
        },
    ));

    match merge_result {
        Ok(Ok(stats)) => {
            current_conn.execute_batch("COMMIT")?;
            Ok(stats)
        }
        Ok(Err(e)) => {
            let _ = current_conn.execute_batch("ROLLBACK");
            Err(e)
        }
        Err(_) => {
            let _ = current_conn.execute_batch("ROLLBACK");
            Err(Error::Format("merge failed".into()))
        }
    }
}

fn copy_text_content(
    backup_conn: &Connection,
    current_conn: &Connection,
    doc_id: &str,
) -> Result<()> {
    // DocumentRow omits text_content, so pull the indexed text straight from
    // the backup DB. update_document_text_content fires fts_after_update, which
    // repopulates the FTS row — without this, merged docs are not searchable.
    let content: rusqlite::Result<Option<String>> = backup_conn.query_row(
        "SELECT text_content FROM documents WHERE id = ?1",
        rusqlite::params![doc_id],
        |row| row.get(0),
    );
    if let Ok(Some(text)) = content {
        crate::db::queries::update_document_text_content(current_conn, doc_id, Some(&text))?;
    }
    Ok(())
}

/// Copy the backup's file blobs into the local `files/` dir, re-encrypting each
/// from `backup_key` to `local_key`.
///
/// A blob that is not decryptable under `backup_key` (a legacy plaintext blob,
/// or one already stored in the clear) is copied verbatim, so merging plaintext
/// vaults keeps working. Blobs for documents that already exist locally are left
/// untouched.
fn reencrypt_files(
    conn: &Connection,
    docs: &[crate::db::queries::DocumentRow],
    files: &[KeyValue],
    backup_key: &[u8],
    local_key: &[u8],
    files_dir: &Path,
) -> Result<()> {
    let file_map: HashMap<&str, &[u8]> = files
        .iter()
        .map(|kv| (kv.key.as_str(), kv.value.as_slice()))
        .collect();

    for doc in docs {
        let file_name = doc.file_path.rsplit('/').next().unwrap_or("");
        if file_name.is_empty() || crate::format::path::safe_archive_path(file_name).is_err() {
            continue;
        }
        let file_bytes = match file_map.get(file_name) {
            Some(b) => *b,
            None => continue,
        };

        let target = files_dir.join(file_name);
        if target.exists() {
            // The local vault already holds a blob for this document (it existed
            // before the merge) — do not clobber it.
            continue;
        }

        // Try to read the blob as IV‖AES-GCM ciphertext under the backup key.
        let reencrypted = doc.encryption_iv.as_deref().and_then(|iv| {
            if file_bytes.len() <= aes_gcm::IV_LENGTH {
                return None;
            }
            let plaintext =
                aes_gcm::decrypt_bytes(&file_bytes[aes_gcm::IV_LENGTH..], backup_key, iv)?;
            aes_gcm::encrypt_bytes(&plaintext, local_key)
        });

        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent)?;
        }
        match reencrypted {
            Some((new_iv, new_ct)) => {
                let mut output = new_iv.clone();
                output.extend_from_slice(&new_ct);
                std::fs::write(&target, &output)?;
                conn.execute(
                    "UPDATE documents SET encryption_iv = ?1, file_path = ?2 WHERE id = ?3",
                    rusqlite::params![new_iv, target.to_string_lossy().to_string(), doc.id],
                )?;
            }
            None => {
                // Plaintext (or not encrypted under the backup key): copy verbatim.
                std::fs::write(&target, file_bytes)?;
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::aes_kw;
    use crate::db::schema::{create_all_tables, open_encrypted};
    use tempfile::TempDir;

    fn make_master_key() -> Vec<u8> {
        (0..32).collect::<Vec<u8>>()
    }

    fn make_test_db(master_key: &[u8], path: &str, doc_id: &str, title: &str) {
        let conn = open_encrypted(path, master_key).unwrap();
        create_all_tables(&conn).unwrap();
        conn.execute(
            "INSERT INTO documents (id, title, file_name, mime_type, file_path, file_size, page_count, author, description, imported_at, last_opened_at, modified_at, is_favorite, is_conflict, current_page)
             VALUES (?1, ?2, ?3, ?4, ?5, 0, 0, '', '', 0, 0, 0, 0, 0, 0)",
            rusqlite::params![doc_id, title, "test.txt", "text/plain", "files/test.txt"],
        ).unwrap();
    }

    #[test]
    fn test_branch_b_fresh_install() {
        let master_key = make_master_key();
        let tmp = TempDir::new().unwrap();
        let enc_dir = tmp.path().join("encryption");
        let db_dir = tmp.path().join("databases");
        let files_dir = tmp.path().join("files");

        let db_path = tmp.path().join("source.db");
        make_test_db(&master_key, db_path.to_str().unwrap(), "doc1", "Fresh Doc");
        let db_data = std::fs::read(&db_path).unwrap();

        let content = "hello file".as_bytes().to_vec();
        let (iv, ct) = aes_gcm::encrypt_bytes(&content, &master_key).unwrap();
        let encrypted_file: Vec<u8> = iv.into_iter().chain(ct).collect();

        let contents = ImportedContents {
            keys: vec![
                crate::types::KeyValue {
                    key: "wrapped_master_key".into(),
                    value: aes_kw::wrap(&master_key, &master_key).unwrap(),
                },
                crate::types::KeyValue {
                    key: "salt".into(),
                    value: b"test-salt-12345678".to_vec(),
                },
            ],
            db_file: Some(db_data),
            files: vec![crate::types::KeyValue {
                key: "test.txt".into(),
                value: encrypted_file,
            }],
        };

        branch_b_fresh_install(
            &contents,
            "password",
            contents.db_file.as_ref().unwrap(),
            &enc_dir,
            &db_dir,
            &files_dir,
        )
        .unwrap();

        assert!(enc_dir.join("wrapped_master_key").exists());
        assert!(enc_dir.join("salt").exists());
        assert!(db_dir.join("librecrate.db").exists());
        assert!(files_dir.join("test.txt").exists());
    }

    #[test]
    fn test_branch_a_merge() {
        let mk = make_master_key();
        let tmp = TempDir::new().unwrap();

        // Create existing DB
        let existing_path = tmp.path().join("existing.db");
        make_test_db(
            &mk,
            existing_path.to_str().unwrap(),
            "existing",
            "Existing Doc",
        );
        let existing_conn = open_encrypted(existing_path.to_str().unwrap(), &mk).unwrap();

        // Create backup DB
        let backup_path = tmp.path().join("backup.db");
        make_test_db(&mk, backup_path.to_str().unwrap(), "new-doc", "New Doc");

        let stats = branch_a_merge(
            backup_path.to_str().unwrap(),
            &mk,
            &existing_conn,
            &[],
            None,
            None,
            tmp.path(),
        )
        .unwrap();

        assert_eq!(stats.documents_added, 1);

        let docs = crate::db::queries::list_documents(&existing_conn).unwrap();
        assert_eq!(docs.len(), 2);
    }

    #[test]
    fn test_branch_a_merge_rolls_back_on_error() {
        let mk = make_master_key();
        let tmp = TempDir::new().unwrap();

        let existing_path = tmp.path().join("existing.db");
        make_test_db(
            &mk,
            existing_path.to_str().unwrap(),
            "existing",
            "Existing Doc",
        );
        let existing_conn = open_encrypted(existing_path.to_str().unwrap(), &mk).unwrap();

        // Force document inserts to fail mid-merge: the FTS insert trigger
        // references a table we drop, so `add_document` returns an error.
        existing_conn
            .execute_batch("DROP TABLE documents_fts")
            .unwrap();

        let backup_path = tmp.path().join("backup.db");
        make_test_db(&mk, backup_path.to_str().unwrap(), "new-doc", "New Doc");

        let result = branch_a_merge(
            backup_path.to_str().unwrap(),
            &mk,
            &existing_conn,
            &[],
            None,
            None,
            tmp.path(),
        );

        assert!(
            result.is_err(),
            "a mid-merge failure must surface as an error"
        );

        // Rolled back: the existing vault is unchanged (no partial document).
        let docs = crate::db::queries::list_documents(&existing_conn).unwrap();
        assert_eq!(docs.len(), 1, "failed merge must not add partial documents");
        assert_eq!(docs[0].id, "existing");
    }
}
