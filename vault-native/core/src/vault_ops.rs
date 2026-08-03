use crate::crypto::argon2::Argon2Params;
use crate::error::{Error, Result};
use crate::format::import::ImportedContents;
use crate::kdf;
use crate::merge::MergeStats;
use crate::types::KeyValue;
use std::path::Path;

/// A snapshot of a vault directory ready for export or merge.
pub struct VaultSnapshot {
    pub keys: Vec<KeyValue>,
    pub files: Vec<KeyValue>,
    pub db_data: Vec<u8>,
    pub kdf_params: Argon2Params,
}

/// Parse KDF parameters from a `params.toml` string.
/// Missing fields fall back to Argon2id defaults.
pub fn parse_kdf_params_from_toml(toml_str: &str) -> Result<Argon2Params> {
    let p: toml::Value = toml::from_str(toml_str)
        .map_err(|e| Error::InvalidData(format!("failed to parse params.toml: {e}")))?;
    Ok(Argon2Params {
        memory_cost: p
            .get("memory_cost")
            .and_then(|v| v.as_integer())
            .unwrap_or(19456) as u32,
        iterations: p
            .get("iterations")
            .and_then(|v| v.as_integer())
            .unwrap_or(2) as u32,
        parallelism: p
            .get("parallelism")
            .and_then(|v| v.as_integer())
            .unwrap_or(2) as u32,
        hash_length: p
            .get("hash_length")
            .and_then(|v| v.as_integer())
            .unwrap_or(32) as i32,
    })
}

/// Read all files under a directory tree into `Vec<KeyValue>`, keyed by the
/// path relative to `root` (like the Android `files` walk). Handles nesting.
fn read_dir_kv(root: &Path) -> Result<Vec<KeyValue>> {
    let mut entries = Vec::new();
    if root.exists() {
        let mut stack = vec![root.to_path_buf()];
        while let Some(dir) = stack.pop() {
            for entry in std::fs::read_dir(&dir)? {
                let entry = entry?;
                let path = entry.path();
                if path.is_file() {
                    let name = path
                        .strip_prefix(root)
                        .map_err(|e| Error::Io(e.to_string()))?
                        .to_string_lossy()
                        .to_string();
                    let data = std::fs::read(&path)?;
                    entries.push(KeyValue { key: name, value: data });
                } else if path.is_dir() {
                    stack.push(path);
                }
            }
        }
    }
    Ok(entries)
}

/// Read key/database/files directories and return everything needed for export or merge.
///
/// Handles both `wrapped_master_key` and `master_key` file names in `encryption/`.
/// Used by both the GUI and the Android app so backup/import share one code path.
pub fn serialize_vault_from_dirs(
    encryption_dir: &Path,
    database_dir: &Path,
    files_dir: &Path,
) -> Result<VaultSnapshot> {
    let keys = read_dir_kv(encryption_dir)?;

    let files = read_dir_kv(files_dir)?;

    let db_path = database_dir.join("librecrate.db");
    let db_data = if db_path.exists() {
        std::fs::read(&db_path)?
    } else {
        return Err(Error::InvalidData(format!(
            "database not found at {}",
            db_path.display()
        )));
    };

    let kdf_params = match std::fs::read_to_string(encryption_dir.join("params.toml")) {
        Ok(toml_str) => parse_kdf_params_from_toml(&toml_str)?,
        Err(_) => Argon2Params::default(),
    };

    Ok(VaultSnapshot {
        keys,
        files,
        db_data,
        kdf_params,
    })
}

/// Read a vault directory (GUI/CLI layout: `encryption/`, `databases/`, `files/`)
/// and return everything needed for export or merge.
pub fn serialize_vault_from_disk(vault_dir: &Path) -> Result<VaultSnapshot> {
    serialize_vault_from_dirs(
        &vault_dir.join("encryption"),
        &vault_dir.join("databases"),
        &vault_dir.join("files"),
    )
}

/// Extract crypto material from an `ImportedContents` and derive the master key.
///
/// Looks for `wrapped_master_key` (or `master_key`), `salt`, and `params.toml`
/// in the backup's key entries, then derives the master key via Argon2id + AES-KW unwrap.
pub fn derive_master_key_from_contents(
    contents: &ImportedContents,
    password: &str,
) -> Result<Vec<u8>> {
    let wrapped_key = contents
        .keys
        .iter()
        .find(|k| k.key == "wrapped_master_key" || k.key == "master_key")
        .map(|k| &k.value)
        .ok_or_else(|| Error::InvalidData("missing wrapped_master_key in backup".into()))?;

    let salt = contents
        .keys
        .iter()
        .find(|k| k.key == "salt")
        .map(|k| &k.value)
        .ok_or_else(|| Error::InvalidData("missing salt in backup".into()))?;

    let kdf_params = match contents.keys.iter().find(|k| k.key == "params.toml") {
        Some(kv) => {
            let toml_str = std::str::from_utf8(&kv.value)
                .map_err(|e| Error::InvalidData(format!("invalid params.toml UTF-8: {e}")))?;
            parse_kdf_params_from_toml(toml_str)?
        }
        None => Argon2Params::default(),
    };

    kdf::derive_backup_master_key(wrapped_key, password, salt, &kdf_params)
}

/// Read key/database/files directories and export them as an encrypted backup file.
///
/// Shared by the GUI and the Android app — a single implementation of the backup format.
pub fn export_vault_dirs(
    encryption_dir: &Path,
    database_dir: &Path,
    files_dir: &Path,
    password: &str,
) -> Result<Vec<u8>> {
    let snapshot = serialize_vault_from_dirs(encryption_dir, database_dir, files_dir)?;
    let exported = crate::format::export::export(
        &snapshot.files,
        Some(&snapshot.db_data),
        password,
        &snapshot.keys,
        &snapshot.kdf_params,
    )?;
    Ok(exported.data)
}

/// Read a vault directory from disk and export it as an encrypted backup file.
///
/// Returns the raw bytes of a `.librecrate-backup` / `.vault` file.
pub fn export_vault_dir(vault_dir: &Path, password: &str) -> Result<Vec<u8>> {
    export_vault_dirs(
        &vault_dir.join("encryption"),
        &vault_dir.join("databases"),
        &vault_dir.join("files"),
        password,
    )
}

/// Merge a backup into an existing vault directory (Branch A).
///
/// The backup is decrypted with `backup_password`. The target vault at
/// `vault_a_dir` is accessed with `vault_a_password`.
pub fn merge_vault_dir(
    vault_a_dir: &Path,
    backup_data: &[u8],
    backup_password: &str,
    vault_a_password: &str,
) -> Result<MergeStats> {
    let contents = crate::ffi::import_vault(
        backup_data.to_vec(),
        backup_password.to_string(),
    )?;

    let _db_data = contents
        .db_file
        .as_deref()
        .ok_or_else(|| Error::InvalidData("backup has no database file".into()))?;

    // Write backup DB to a temp file so branch_a_merge can open it
    let tmp_dir = tempfile::tempdir().map_err(|e| Error::Io(e.to_string()))?;
    let backup_db_path = tmp_dir.path().join("backup.db");
    if let Some(db_bytes) = &contents.db_file {
        std::fs::write(&backup_db_path, db_bytes)?;
    }

    // Derive the backup's master key using vault A's password
    // (assumes both vaults share the same password, matching CLI/GUI convention)
    let backup_master_key = derive_master_key_from_contents(&contents, vault_a_password)?;

    // Serialize vault A from disk and derive its master key
    let snapshot_a = serialize_vault_from_disk(vault_a_dir)?;

    let wrapped_key_a = snapshot_a.keys.iter()
        .find(|k| k.key == "wrapped_master_key" || k.key == "master_key")
        .map(|k| k.value.as_slice())
        .ok_or_else(|| Error::InvalidData("missing master key in vault A".into()))?;

    let salt_a = snapshot_a.keys.iter()
        .find(|k| k.key == "salt")
        .map(|k| k.value.as_slice())
        .ok_or_else(|| Error::InvalidData("missing salt in vault A".into()))?;

    let user_key_a = crate::kdf::derive_user_key(vault_a_password, salt_a, &snapshot_a.kdf_params)
        .ok_or(Error::AuthenticationFailed)?;

    let mk_a = crate::crypto::aes_kw::unwrap(wrapped_key_a, &user_key_a)
        .ok_or(Error::AuthenticationFailed)?;

    // Open vault A's database
    let a_db_path = vault_a_dir.join("databases").join("librecrate.db");
    let conn = crate::db::schema::open_encrypted(
        a_db_path.to_str().ok_or_else(|| Error::InvalidData("invalid db path".into()))?,
        &mk_a,
    )?;

    let files_dir = vault_a_dir.join("files");

    let stats = crate::merge::branch_a_merge(
        backup_db_path.to_str().ok_or_else(|| Error::InvalidData("invalid backup db path".into()))?,
        &backup_master_key,
        &conn,
        &contents.files,
        None,
        None,
        &files_dir,
    )?;

    drop(conn);

    // Copy B's file blobs into A's files dir
    for kv in &contents.files {
        let target = files_dir.join(&kv.key);
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(&target, &kv.value)?;
    }

    Ok(stats)
}

/// Import a backup and restore it to target directories (Branch B — full replace).
///
/// Shared by the GUI and the Android app — a single implementation of the import path.
pub fn restore_backup_to_dirs(
    backup_data: &[u8],
    password: &str,
    encryption_dir: &Path,
    database_dir: &Path,
    files_dir: &Path,
) -> Result<()> {
    let contents = crate::ffi::import_vault(
        backup_data.to_vec(),
        password.to_string(),
    )?;

    let db_data = contents
        .db_file
        .clone()
        .ok_or_else(|| Error::InvalidData("backup has no database file".into()))?;

    crate::merge::branch_b_fresh_install(
        &contents,
        "",
        &db_data,
        encryption_dir,
        database_dir,
        files_dir,
    )
}

/// Import a backup and restore it to a target directory (Branch B — full replace).
pub fn restore_backup_to_dir(
    backup_data: &[u8],
    password: &str,
    target_dir: &Path,
) -> Result<()> {
    restore_backup_to_dirs(
        backup_data,
        password,
        &target_dir.join("encryption"),
        &target_dir.join("databases"),
        &target_dir.join("files"),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::format::export;

    fn create_test_vault(dir: &Path, password: &str) -> Vec<u8> {
        export::create_vault_layout(dir, password).unwrap()
    }

    #[test]
    fn test_parse_kdf_params_valid() {
        let toml_str = r#"
memory_cost = 32768
iterations = 4
parallelism = 4
hash_length = 32
"#;
        let params = parse_kdf_params_from_toml(toml_str).unwrap();
        assert_eq!(params.memory_cost, 32768);
        assert_eq!(params.iterations, 4);
        assert_eq!(params.parallelism, 4);
        assert_eq!(params.hash_length, 32);
    }

    #[test]
    fn test_parse_kdf_params_defaults() {
        let params = parse_kdf_params_from_toml("").unwrap();
        assert_eq!(params.memory_cost, 19456);
        assert_eq!(params.iterations, 2);
        assert_eq!(params.parallelism, 2);
        assert_eq!(params.hash_length, 32);
    }

    #[test]
    fn test_parse_kdf_params_partial() {
        let toml_str = "memory_cost = 65536\n";
        let params = parse_kdf_params_from_toml(toml_str).unwrap();
        assert_eq!(params.memory_cost, 65536);
        assert_eq!(params.iterations, 2); // default
    }

    #[test]
    fn test_serialize_vault_from_disk_roundtrip() {
        let dir = tempfile::tempdir().unwrap();
        create_test_vault(dir.path(), "testpass");

        let snapshot = serialize_vault_from_disk(dir.path()).unwrap();
        assert!(!snapshot.keys.is_empty());
        assert!(!snapshot.db_data.is_empty());
        assert!(snapshot.kdf_params.memory_cost > 0);
    }

    #[test]
    fn test_serialize_vault_missing_db() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join("encryption")).unwrap();
        std::fs::create_dir_all(dir.path().join("databases")).unwrap();
        std::fs::create_dir_all(dir.path().join("files")).unwrap();

        let result = serialize_vault_from_disk(dir.path());
        assert!(result.is_err());
    }

    #[test]
    fn test_derive_master_key_from_contents_roundtrip() {
        let dir = tempfile::tempdir().unwrap();
        let mk = create_test_vault(dir.path(), "testpass");

        // Export
        let snapshot = serialize_vault_from_disk(dir.path()).unwrap();
        let exported = export::export(
            &snapshot.files,
            Some(&snapshot.db_data),
            "backuppass",
            &snapshot.keys,
            &snapshot.kdf_params,
        )
        .unwrap();

        // Import
        let contents = crate::format::import::import(
            &exported.data,
            "backuppass",
            &Argon2Params::default(),
        )
        .unwrap();

        // Derive master key using the vault password (not backup password)
        let recovered_mk = derive_master_key_from_contents(&contents, "testpass").unwrap();
        assert_eq!(recovered_mk, mk);
    }

    #[test]
    fn test_derive_master_key_wrong_password() {
        let dir = tempfile::tempdir().unwrap();
        create_test_vault(dir.path(), "correct");

        let snapshot = serialize_vault_from_disk(dir.path()).unwrap();
        let exported = export::export(
            &snapshot.files,
            Some(&snapshot.db_data),
            "backuppass",
            &snapshot.keys,
            &snapshot.kdf_params,
        )
        .unwrap();

        let contents = crate::format::import::import(
            &exported.data,
            "backuppass",
            &Argon2Params::default(),
        )
        .unwrap();

        let result = derive_master_key_from_contents(&contents, "wrong");
        assert!(result.is_err());
    }

    #[test]
    fn test_export_vault_dir_roundtrip() {
        let dir = tempfile::tempdir().unwrap();
        let mk = create_test_vault(dir.path(), "testpass");

        let backup_bytes = export_vault_dir(dir.path(), "backuppass").unwrap();
        assert!(!backup_bytes.is_empty());
        assert_eq!(&backup_bytes[..16], b"LIBCRATE_VAULT\0\0");

        // Re-import and verify we can recover the master key
        let contents = crate::format::import::import(
            &backup_bytes,
            "backuppass",
            &Argon2Params::default(),
        )
        .unwrap();
        let recovered_mk = derive_master_key_from_contents(&contents, "testpass").unwrap();
        assert_eq!(recovered_mk, mk);
    }

    #[test]
    fn test_export_vault_dir_with_documents() {
        let dir = tempfile::tempdir().unwrap();
        let mk = create_test_vault(dir.path(), "testpass");

        // Import a document
        let db_path = dir.path().join("databases").join("librecrate.db");
        let conn = crate::db::schema::open_encrypted(
            db_path.to_str().unwrap(),
            &mk,
        )
        .unwrap();
        let file_data = b"hello world".to_vec();
        crate::db::storage::import_document(
            &conn,
            dir.path(),
            "doc1",
            "Test Doc",
            &file_data,
            "text/plain",
            "",
            "",
            None,
            None,
        )
        .unwrap();
        drop(conn);

        // Export
        let backup_bytes = export_vault_dir(dir.path(), "backuppass").unwrap();

        // Import and verify document count
        let contents = crate::format::import::import(
            &backup_bytes,
            "backuppass",
            &Argon2Params::default(),
        )
        .unwrap();
        assert_eq!(contents.files.len(), 1);
        assert_eq!(contents.files[0].key, "doc1");
    }

    #[test]
    fn test_restore_backup_to_dir_roundtrip() {
        let dir_a = tempfile::tempdir().unwrap();
        let mk = create_test_vault(dir_a.path(), "testpass");

        // Import a document into vault A
        let db_path = dir_a.path().join("databases").join("librecrate.db");
        let conn = crate::db::schema::open_encrypted(
            db_path.to_str().unwrap(),
            &mk,
        )
        .unwrap();
        let file_data = b"test content".to_vec();
        crate::db::storage::import_document(
            &conn,
            dir_a.path(),
            "doc1",
            "Test",
            &file_data,
            "text/plain",
            "",
            "",
            None,
            None,
        )
        .unwrap();
        drop(conn);

        // Export vault A
        let backup_bytes = export_vault_dir(dir_a.path(), "backuppass").unwrap();

        // Restore into vault B
        let dir_b = tempfile::tempdir().unwrap();
        create_test_vault(dir_b.path(), "otherpass");
        restore_backup_to_dir(&backup_bytes, "backuppass", dir_b.path()).unwrap();

        // Verify vault B has the document
        let snapshot_b = serialize_vault_from_disk(dir_b.path()).unwrap();
        assert_eq!(snapshot_b.files.len(), 1);
        assert_eq!(snapshot_b.files[0].key, "doc1");
    }

    #[test]
    fn test_merge_vault_dir_roundtrip() {
        let dir_a = tempfile::tempdir().unwrap();
        let mk_a = create_test_vault(dir_a.path(), "testpass");

        // Import doc_a into vault A
        let db_path_a = dir_a.path().join("databases").join("librecrate.db");
        let conn_a = crate::db::schema::open_encrypted(
            db_path_a.to_str().unwrap(),
            &mk_a,
        )
        .unwrap();
        crate::db::storage::import_document(
            &conn_a,
            dir_a.path(),
            "doc_a",
            "Doc A",
            b"content a".as_slice(),
            "text/plain",
            "",
            "",
            None,
            None,
        )
        .unwrap();
        drop(conn_a);

        // Create vault B with doc_b
        let dir_b = tempfile::tempdir().unwrap();
        let mk_b = create_test_vault(dir_b.path(), "testpass");

        let db_path_b = dir_b.path().join("databases").join("librecrate.db");
        let conn_b = crate::db::schema::open_encrypted(
            db_path_b.to_str().unwrap(),
            &mk_b,
        )
        .unwrap();
        crate::db::storage::import_document(
            &conn_b,
            dir_b.path(),
            "doc_b",
            "Doc B",
            b"content b".as_slice(),
            "text/plain",
            "",
            "",
            None,
            None,
        )
        .unwrap();
        drop(conn_b);

        // Export vault B as backup
        let backup_b = export_vault_dir(dir_b.path(), "backuppass").unwrap();

        // Merge vault B into vault A
        let stats = merge_vault_dir(
            dir_a.path(),
            &backup_b,
            "backuppass",
            "testpass",
        )
        .unwrap();

        assert_eq!(stats.documents_added, 1);
        assert_eq!(stats.documents_updated, 0);
        assert_eq!(stats.documents_conflicted, 0);
    }
}
