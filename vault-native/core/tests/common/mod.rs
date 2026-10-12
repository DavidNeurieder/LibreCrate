//! Shared helpers for the data-integrity integration tests.
//!
//! Included with `mod common;` from each suite (a `tests/common/mod.rs` is not
//! itself compiled as a test target). `dead_code` is allowed because different
//! suites use different subsets of these helpers.

#![allow(dead_code)]

use sha2::{Digest, Sha256};
use std::path::Path;
use vault_native::crypto::{aes_kw, argon2};
use vault_native::db::queries::list_documents;
use vault_native::db::schema::{get_schema_version, open_encrypted};
use vault_native::db::storage::{export_document_file, import_document};
use vault_native::format::export::create_vault_layout;
use vault_native::vault_ops::parse_kdf_params_from_toml;

/// Logical snapshot of a single document: identifiers, metadata, and a SHA-256
/// of its *decrypted* bytes. Comparing these (never raw SQLite files) is the
/// correctness contract for backup/restore and migration tests.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DocSnapshot {
    pub id: String,
    pub title: String,
    pub mime_type: String,
    pub file_name: String,
    pub file_size: i64,
    pub sha256: String,
}

/// Logical snapshot of a vault: its schema version plus its documents.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VaultSnapshot {
    pub schema_version: i64,
    pub documents: Vec<DocSnapshot>,
}

/// Synthetic document set exercising Unicode, binary, empty, and large payloads.
pub fn fixture_documents() -> Vec<(&'static str, &'static str, &'static str, Vec<u8>)> {
    vec![
        (
            "uni-1",
            "Ünïcode 世界 🎉",
            "text/plain",
            "héllo wörld — 世界 🎉\n".as_bytes().to_vec(),
        ),
        (
            "bin-1",
            "Binary Blob",
            "application/octet-stream",
            (0u16..=255).map(|b| b as u8).collect(),
        ),
        (
            "empty-1",
            "Empty File",
            "application/octet-stream",
            Vec::new(),
        ),
        (
            "large-1",
            "Large File",
            "application/octet-stream",
            vec![0xAB; 256 * 1024],
        ),
    ]
}

/// Create a vault layout with no documents; returns the master key.
pub fn create_empty_vault(root: &Path, password: &str) -> Vec<u8> {
    create_vault_layout(root, password).unwrap()
}

/// Import one document into an existing vault.
pub fn add_document(
    root: &Path,
    master_key: &[u8],
    id: &str,
    title: &str,
    mime: &str,
    content: &[u8],
) {
    let db_path = root.join("databases/librecrate.db");
    let conn = open_encrypted(db_path.to_str().unwrap(), master_key).unwrap();
    import_document(
        &conn,
        root,
        id,
        title,
        content,
        mime,
        "",
        "",
        None,
        Some(master_key),
    )
    .unwrap();
}

/// Create a vault populated with [`fixture_documents`]; returns the master key.
pub fn create_vault(root: &Path, password: &str) -> Vec<u8> {
    let master_key = create_empty_vault(root, password);
    for (id, title, mime, content) in fixture_documents() {
        add_document(root, &master_key, id, title, mime, &content);
    }
    master_key
}

/// Unlock a vault from disk exactly like the CLI/GUI do: read
/// `encryption/{salt,wrapped_master_key,params.toml}`, derive the KEK, unwrap
/// the master key, open the SQLCipher DB. Returns (connection, master key).
pub fn open_vault(root: &Path, password: &str) -> (rusqlite::Connection, Vec<u8>) {
    let enc = root.join("encryption");
    let salt = std::fs::read(enc.join("salt")).unwrap();
    let wrapped = std::fs::read(enc.join("wrapped_master_key")).unwrap();
    let params =
        parse_kdf_params_from_toml(&std::fs::read_to_string(enc.join("params.toml")).unwrap())
            .unwrap();
    let user_key = argon2::derive_key(password, &salt, &params).expect("KEK derivation");
    let master_key = aes_kw::unwrap(&wrapped, &user_key).expect("master key unwrap");
    let db_path = root.join("databases/librecrate.db");
    let conn = open_encrypted(db_path.to_str().unwrap(), &master_key).unwrap();
    (conn, master_key)
}

/// Build the logical snapshot of a vault directory.
pub fn snapshot_vault(root: &Path, password: &str) -> VaultSnapshot {
    let (conn, master_key) = open_vault(root, password);
    let mut rows = list_documents(&conn).unwrap();
    rows.sort_by(|a, b| a.id.cmp(&b.id));

    let documents = rows
        .iter()
        .map(|row| {
            let data = export_document_file(&conn, root, &row.id, Some(&master_key))
                .unwrap_or_else(|| panic!("attachment for {} must be readable", row.id));
            DocSnapshot {
                id: row.id.clone(),
                title: row.title.clone(),
                mime_type: row.mime_type.clone(),
                file_name: row.file_name.clone(),
                file_size: row.file_size,
                sha256: hex::encode(Sha256::digest(&data)),
            }
        })
        .collect();

    let schema_version = get_schema_version(&conn).unwrap();
    VaultSnapshot {
        schema_version,
        documents,
    }
}

/// Assert two vault snapshots are logically identical.
pub fn assert_snapshot_eq(before: &VaultSnapshot, after: &VaultSnapshot) {
    assert_eq!(
        before, after,
        "vault contents changed:\n  before = {before:#?}\n  after  = {after:#?}"
    );
}
