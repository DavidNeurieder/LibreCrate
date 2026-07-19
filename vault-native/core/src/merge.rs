use crate::crypto::aes_gcm;
use crate::error::{Error, Result};
use crate::format::import::ImportedContents;
use rusqlite::Connection;
use std::collections::HashMap;
use std::path::Path;

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
    for (name, data) in &contents.keys {
        std::fs::write(encryption_dir.join(name), data)?;
    }

    // Write the DB
    std::fs::create_dir_all(database_dir)?;
    std::fs::write(database_dir.join("librecrate.db"), db_data)?;

    // Clean WAL/SHM
    let _ = std::fs::remove_file(database_dir.join("librecrate.db-wal"));
    let _ = std::fs::remove_file(database_dir.join("librecrate.db-shm"));

    // Write files from the vault
    std::fs::create_dir_all(files_dir)?;
    for (name, data) in &contents.files {
        let target = files_dir.join(name);
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(target, data)?;
    }

    Ok(())
}

/// Merge backup DB into existing current DB (Branch A).
/// Supports file re-encryption if local/backup keys are provided.
pub fn branch_a_merge(
    backup_path: &str,
    backup_master_key: &[u8],
    current_conn: &Connection,
    files: &[(String, Vec<u8>)],
    backup_key: Option<&[u8]>,
    local_key: Option<&[u8]>,
    files_dir: &Path,
) -> Result<MergeStats> {
    // Open backup DB
    let backup_conn =
        crate::db::schema::open_encrypted(backup_path, backup_master_key)
            .map_err(|e| Error::Database(e))?;

    // Read documents from backup
    let backup_docs = crate::db::queries::list_documents(&backup_conn)
        .map_err(|e| Error::Database(e))?;
    let backup_collections = crate::db::queries::list_collections(&backup_conn)
        .map_err(|e| Error::Database(e))?;
    let backup_tags = crate::db::queries::list_tags(&backup_conn)
        .map_err(|e| Error::Database(e))?;

    let mut stats = MergeStats {
        documents_added: 0,
        documents_updated: 0,
        documents_conflicted: 0,
        documents_skipped: 0,
        collections_added: 0,
        tags_added: 0,
    };

    // Use a transaction for the merge
    current_conn
        .execute_batch("BEGIN TRANSACTION")
        .map_err(|e| Error::Database(e))?;

    let merge_result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        // Merge collections
        for col in &backup_collections {
            let exists: bool = current_conn
                .query_row(
                    "SELECT 1 FROM collections WHERE id = ?",
                    rusqlite::params![col.id],
                    |_| Ok(()),
                )
                .is_ok();
            if exists {
                current_conn
                    .execute(
                        "UPDATE collections SET name = ?1, icon = ?2, sort_order = ?3, parent_id = ?4 WHERE id = ?5",
                        rusqlite::params![col.name, col.icon, col.sort_order, col.parent_id, col.id],
                    )
                    .ok();
            } else {
                current_conn
                    .execute(
                        "INSERT INTO collections (id, name, icon, sort_order, parent_id) VALUES (?1, ?2, ?3, ?4, ?5)",
                        rusqlite::params![col.id, col.name, col.icon, col.sort_order, col.parent_id],
                    )
                    .ok();
                stats.collections_added += 1;
            }
        }

        // Merge tags
        for tag in &backup_tags {
            let inserted = current_conn
                .execute(
                    "INSERT OR IGNORE INTO tags (id, name, color) VALUES (?1, ?2, ?3)",
                    rusqlite::params![tag.id, tag.name, tag.color],
                )
                .unwrap_or(0);
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
                     encryption_iv, current_page, reading_position, barcode_format, barcode_value
                     FROM documents WHERE id = ?",
                    rusqlite::params![doc.id],
                    |row| crate::db::queries::document_from_row(row),
                );

            match existing_row {
                Err(_) => {
                    // No existing document — add backup doc
                    crate::db::queries::add_document(current_conn, doc)
                        .ok();
                    stats.documents_added += 1;
                }
                Ok(existing) => {
                    // Detect conflict: file content differs
                    let content_differs = existing.file_size != doc.file_size
                        || existing.mime_type != doc.mime_type;

                    if content_differs {
                        // Mark existing as conflict
                        let _ = current_conn.execute(
                            "UPDATE documents SET is_conflict = 1, modified_at = ?1 WHERE id = ?2",
                            rusqlite::params![doc.modified_at, doc.id],
                        );
                        // Insert backup doc as conflict copy
                        let conflict_id = format!("{}-conflict-{}", doc.id, std::time::SystemTime::now()
                            .duration_since(std::time::UNIX_EPOCH)
                            .unwrap_or_default()
                            .as_millis());
                        let mut conflict_doc = doc.clone();
                        conflict_doc.id = conflict_id;
                        conflict_doc.conflict_with = Some(doc.id.clone());
                        crate::db::queries::add_document(current_conn, &conflict_doc)
                            .ok();
                        stats.documents_conflicted += 1;
                    } else if doc.modified_at > 0 {
                        current_conn
                            .execute(
                                "UPDATE documents SET title = ?1, modified_at = ?2, is_favorite = ?3, current_page = ?4 WHERE id = ?5",
                                rusqlite::params![doc.title, doc.modified_at, doc.is_favorite as i32, doc.current_page, doc.id],
                            )
                            .ok();
                        stats.documents_updated += 1;
                    } else {
                        stats.documents_skipped += 1;
                    }
                }
            }
        }

        // Re-encrypt files if keys provided
        if let (Some(bk), Some(lk)) = (backup_key, local_key) {
            reencrypt_files(
                current_conn,
                &backup_docs,
                files,
                bk,
                lk,
                files_dir,
            );
        }
    }));

    if merge_result.is_ok() {
        current_conn
            .execute_batch("COMMIT")
            .map_err(|e| Error::Database(e))?;
    } else {
        current_conn
            .execute_batch("ROLLBACK")
            .map_err(|e| Error::Database(e))?;
        return Err(Error::Format("merge failed".into()));
    }

    Ok(stats)
}

fn reencrypt_files(
    conn: &Connection,
    docs: &[crate::db::queries::DocumentRow],
    files: &[(String, Vec<u8>)],
    backup_key: &[u8],
    local_key: &[u8],
    files_dir: &Path,
) {
    let file_map: HashMap<&str, &[u8]> =
        files.iter().map(|(k, v)| (k.as_str(), v.as_slice())).collect();

    for doc in docs {
        let iv = match &doc.encryption_iv {
            Some(iv) => iv,
            None => continue,
        };

        let file_name = doc.file_path.rsplit('/').next().unwrap_or("");
        let file_bytes = match file_map.get(file_name) {
            Some(b) => b,
            None => continue,
        };

        if file_bytes.len() <= aes_gcm::IV_LENGTH {
            continue;
        }

        let target = files_dir.join(file_name);
        if target.exists() {
            // Already restored, update IV in DB
            if let Ok(iv_bytes) = std::fs::read(&target)
                .map(|b| b[..aes_gcm::IV_LENGTH].to_vec())
            {
                let _ = conn.execute(
                    "UPDATE documents SET encryption_iv = ?1 WHERE file_path = ?2",
                    rusqlite::params![iv_bytes, doc.file_path],
                );
            }
            continue;
        }

        // Decrypt with backup key, re-encrypt with local key
        if let Some(parent) = target.parent() {
            let _ = std::fs::create_dir_all(parent);
        }

        let raw_ciphertext = &file_bytes[aes_gcm::IV_LENGTH..];
        if let Some(plaintext) = aes_gcm::decrypt_bytes(raw_ciphertext, backup_key, iv) {
            if let Some((new_iv, new_ct)) = aes_gcm::encrypt_bytes(&plaintext, local_key) {
                let mut output = new_iv.clone();
                output.extend_from_slice(&new_ct);
                let _ = std::fs::write(&target, &output);

                let _ = conn.execute(
                    "UPDATE documents SET encryption_iv = ?1, file_path = ?2 WHERE file_path = ?3",
                    rusqlite::params![new_iv, target.to_string_lossy().to_string(), doc.file_path],
                );
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::db::schema::{create_all_tables, open_encrypted};
    use crate::crypto::aes_kw;
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
                ("wrapped_master_key".into(), aes_kw::wrap(&master_key, &master_key).unwrap()),
                ("salt".into(), b"test-salt-12345678".to_vec()),
            ],
            db_file: Some(db_data),
            files: vec![("test.txt".into(), encrypted_file)],
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
        make_test_db(&mk, existing_path.to_str().unwrap(), "existing", "Existing Doc");
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
}
