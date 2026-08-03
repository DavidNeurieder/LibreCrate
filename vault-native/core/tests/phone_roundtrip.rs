//! Cross-platform round-trip tests: the Android app and the desktop GUI must
//! use the *same* Rust backup/import implementation so backups move between
//! the two without "crypto error: key unwrap failed".
//!
//! The phone side is simulated faithfully to what the app produces:
//!   - KDF params 16384/3/2 (RustKeyManager constants), recorded in `params.toml`
//!   - `encryption/{salt,wrapped_master_key,params.toml}`
//!   - `databases/librecrate.db` encrypted with the master key
//!   - `files/<id>` AES-GCM encrypted with the master key
//!
//! and it goes through the same `vault_ops::export_vault_dirs` /
//! `vault_ops::restore_backup_to_dirs` functions the new uniffi exports call.

use vault_native::crypto::aes_gcm;
use vault_native::crypto::aes_kw;
use vault_native::crypto::argon2::{self, Argon2Params};
use vault_native::db::queries;
use vault_native::db::schema::{create_encrypted_db, open_encrypted};
use vault_native::db::storage::import_document;
use vault_native::format::export::create_vault_layout;
use vault_native::vault_ops::{
    export_vault_dir, export_vault_dirs, parse_kdf_params_from_toml, restore_backup_to_dirs,
    restore_backup_to_dir,
};
use std::path::Path;

/// Phone-side constants from `RustKeyManager.kt`.
const PHONE_MEMORY_COST: u32 = 16_384;
const PHONE_ITERATIONS: u32 = 3;
const PHONE_PARALLELISM: u32 = 2;
const PHONE_PASSWORD: &str = "phone-vault-pass";

fn phone_params() -> Argon2Params {
    Argon2Params::new(PHONE_MEMORY_COST, PHONE_ITERATIONS, PHONE_PARALLELISM, 32)
}

/// The exact `params.toml` written by `buildParamsToml()` on the phone.
fn phone_params_toml() -> String {
    "memory_cost = 16384\niterations = 3\nparallelism = 2\nhash_length = 32\n".to_string()
}

/// Build a phone-style vault layout (matching `RustKeyManager` + `VaultRepository`):
///   root/encryption  root/databases  root/files
/// Returns the master key.
fn build_phone_vault(root: &Path, password: &str) -> Vec<u8> {
    let enc_dir = root.join("encryption");
    let db_dir = root.join("databases");
    let files_dir = root.join("files");
    std::fs::create_dir_all(&enc_dir).unwrap();
    std::fs::create_dir_all(&db_dir).unwrap();
    std::fs::create_dir_all(&files_dir).unwrap();

    let params = phone_params();
    let salt = argon2::generate_salt();
    let master_key = aes_kw::generate_master_key();
    let kek = argon2::derive_key(password, &salt, &params).unwrap();
    let wrapped = aes_kw::wrap(&kek, &master_key).unwrap();

    std::fs::write(enc_dir.join("salt"), &salt).unwrap();
    std::fs::write(enc_dir.join("wrapped_master_key"), &wrapped).unwrap();
    std::fs::write(enc_dir.join("params.toml"), phone_params_toml()).unwrap();

    let db_path = db_dir.join("librecrate.db");
    let conn = create_encrypted_db(db_path.to_str().unwrap(), &master_key).unwrap();
    for (id, title, content) in [
        ("phone-doc-1", "Phone Doc One", &b"phone file one"[..]),
        ("phone-doc-2", "Phone Doc Two", &b"phone file two"[..]),
    ] {
        import_document(
            &conn,
            root,
            id,
            title,
            content,
            "text/plain",
            "",
            "",
            None,
            Some(&master_key),
        )
        .unwrap();
    }
    drop(conn);

    master_key
}

/// Replicate `gui/src/vault.rs::Vault::open`: parse `params.toml`, derive the KEK,
/// unwrap the master key, open the SQLCipher DB. Returns (conn, master_key).
fn open_like_gui(root: &Path, password: &str) -> (rusqlite::Connection, Vec<u8>) {
    let enc_dir = root.join("encryption");
    let salt = std::fs::read(enc_dir.join("salt")).unwrap();
    let wrapped = std::fs::read(enc_dir.join("wrapped_master_key")).unwrap();
    let params = parse_kdf_params_from_toml(
        &std::fs::read_to_string(enc_dir.join("params.toml")).unwrap(),
    )
    .unwrap();
    let kek = argon2::derive_key(password, &salt, &params).unwrap();
    let master_key = aes_kw::unwrap(&wrapped, &kek).expect("key unwrap failed");
    let conn = open_encrypted(
        root.join("databases").join("librecrate.db").to_str().unwrap(),
        &master_key,
    )
    .unwrap();
    (conn, master_key)
}

fn assert_vault_has_docs(conn: &rusqlite::Connection, expected: &[(&str, &str)]) {
    let docs = queries::list_documents(conn).unwrap();
    assert_eq!(docs.len(), expected.len(), "document count mismatch");
    for (i, (id, title)) in expected.iter().enumerate() {
        assert_eq!(docs[i].id, *id);
        assert_eq!(docs[i].title, *title);
    }
}

#[test]
fn test_phone_backup_exports_into_gui_and_unlocks() {
    let phone_root = tempfile::TempDir::new().unwrap();
    let master_key = build_phone_vault(phone_root.path(), PHONE_PASSWORD);

    // Export the way the phone's `exportVaultDir` FFI now does (shared Rust code).
    let backup = export_vault_dirs(
        &phone_root.path().join("encryption"),
        &phone_root.path().join("databases"),
        &phone_root.path().join("files"),
        PHONE_PASSWORD,
    )
    .unwrap();
    assert!(backup.starts_with(b"LIBCRATE_VAULT"));

    // The backup must be self-describing: it carries the phone's params.toml.
    let contents =
        vault_native::ffi::import_vault(backup.clone(), PHONE_PASSWORD.to_string()).unwrap();
    assert!(
        contents.keys.iter().any(|k| k.key == "params.toml"),
        "backup must include params.toml"
    );

    // Import into a fresh GUI-style vault directory (Branch B).
    let gui_root = tempfile::TempDir::new().unwrap();
    restore_backup_to_dir(&backup, PHONE_PASSWORD, gui_root.path()).unwrap();

    // Unlock via the GUI path with the phone password.
    let (conn, recovered_mk) = open_like_gui(gui_root.path(), PHONE_PASSWORD);
    assert_eq!(recovered_mk, master_key, "master key must round-trip");
    assert_vault_has_docs(
        &conn,
        &[("phone-doc-1", "Phone Doc One"), ("phone-doc-2", "Phone Doc Two")],
    );

    // File contents must survive the round trip.
    let file = vault_native::db::storage::export_document_file(
        &conn,
        gui_root.path(),
        "phone-doc-1",
        Some(&recovered_mk),
    )
    .expect("file must exist");
    assert_eq!(file, b"phone file one");

    // Wrong password must not unlock (regression for the original bug).
    assert!(aes_kw::unwrap(
        &std::fs::read(gui_root.path().join("encryption").join("wrapped_master_key")).unwrap(),
        &argon2::derive_key("wrong", &std::fs::read(gui_root.path().join("encryption").join("salt")).unwrap(), &phone_params()).unwrap(),
    )
    .is_none());
}

#[test]
fn test_desktop_backup_restores_into_phone_layout() {
    let gui_root = tempfile::TempDir::new().unwrap();
    let desktop_mk = create_vault_layout(gui_root.path(), "desktop-pass").unwrap();

    // Import a document (files stored AES-GCM under files/<id>).
    let db_path = gui_root.path().join("databases").join("librecrate.db");
    let conn = open_encrypted(db_path.to_str().unwrap(), &desktop_mk).unwrap();
    import_document(
        &conn,
        gui_root.path(),
        "desktop-doc",
        "Desktop Doc",
        &b"desktop file content"[..],
        "text/plain",
        "",
        "",
        None,
        Some(&desktop_mk),
    )
    .unwrap();
    drop(conn);

    let backup = export_vault_dir(gui_root.path(), "backuppass").unwrap();

    // Restore into phone-style directories via the shared import path.
    let phone_root = tempfile::TempDir::new().unwrap();
    restore_backup_to_dirs(
        &backup,
        "backuppass",
        &phone_root.path().join("encryption"),
        &phone_root.path().join("databases"),
        &phone_root.path().join("files"),
    )
    .unwrap();

    let enc_dir = phone_root.path().join("encryption");
    let salt = std::fs::read(enc_dir.join("salt")).unwrap();
    let wrapped = std::fs::read(enc_dir.join("wrapped_master_key")).unwrap();

    // Regression guard: the OLD phone behavior ignored params.toml and always
    // used 16384/3/2 — that must fail to unwrap a desktop vault.
    let old_kek =
        argon2::derive_key("desktop-pass", &salt, &phone_params()).unwrap();
    assert!(
        aes_kw::unwrap(&wrapped, &old_kek).is_none(),
        "old hardcoded-16384 behavior must NOT unlock a desktop vault"
    );

    // FIXED phone behavior: read params.toml (19456/2/2) and unlock.
    let params = parse_kdf_params_from_toml(
        &std::fs::read_to_string(enc_dir.join("params.toml")).unwrap(),
    )
    .unwrap();
    assert_eq!(params.memory_cost, 19456);
    let kek = argon2::derive_key("desktop-pass", &salt, &params).unwrap();
    let recovered_mk = aes_kw::unwrap(&wrapped, &kek).expect("key unwrap failed");
    assert_eq!(recovered_mk, desktop_mk, "master key must round-trip");

    let conn = open_encrypted(
        phone_root.path().join("databases").join("librecrate.db").to_str().unwrap(),
        &recovered_mk,
    )
    .unwrap();
    assert_vault_has_docs(&conn, &[("desktop-doc", "Desktop Doc")]);

    let file = vault_native::db::storage::export_document_file(
        &conn,
        phone_root.path(),
        "desktop-doc",
        Some(&recovered_mk),
    )
    .expect("file must exist");
    assert_eq!(file, b"desktop file content");
}

#[test]
fn test_phone_backup_overwrites_stale_desktop_params() {
    // The reported bug: a phone backup restored into a GUI dir that already has
    // a desktop params.toml must overwrite it, so unlock works.
    let gui_root = tempfile::TempDir::new().unwrap();
    create_vault_layout(gui_root.path(), "old-desktop-pass").unwrap();

    let phone_root = tempfile::TempDir::new().unwrap();
    let _ = build_phone_vault(phone_root.path(), PHONE_PASSWORD);
    let backup = export_vault_dirs(
        &phone_root.path().join("encryption"),
        &phone_root.path().join("databases"),
        &phone_root.path().join("files"),
        PHONE_PASSWORD,
    )
    .unwrap();

    restore_backup_to_dir(&backup, PHONE_PASSWORD, gui_root.path()).unwrap();

    let enc_dir = gui_root.path().join("encryption");
    let params = parse_kdf_params_from_toml(
        &std::fs::read_to_string(enc_dir.join("params.toml")).unwrap(),
    )
    .unwrap();
    assert_eq!(
        params.memory_cost, PHONE_MEMORY_COST,
        "restore must overwrite the stale desktop params.toml with the phone's"
    );

    let (conn, _) = open_like_gui(gui_root.path(), PHONE_PASSWORD);
    assert_vault_has_docs(
        &conn,
        &[("phone-doc-1", "Phone Doc One"), ("phone-doc-2", "Phone Doc Two")],
    );

    // Pre-fix behavior (stale desktop params still in place) would have failed.
    let salt = std::fs::read(enc_dir.join("salt")).unwrap();
    let wrapped = std::fs::read(enc_dir.join("wrapped_master_key")).unwrap();
    let stale_kek = argon2::derive_key(PHONE_PASSWORD, &salt, &Argon2Params::default()).unwrap();
    assert!(
        aes_kw::unwrap(&wrapped, &stale_kek).is_none(),
        "desktop-default KEK must not unwrap a phone vault key"
    );
}

#[test]
fn test_backup_encrypts_files_with_master_key_and_roundtrips() {
    // Confirm the on-disk file blob is AES-GCM (iv+ct) and decrypts with the
    // master key after a restore — i.e. the cross-platform crypto matches.
    let phone_root = tempfile::TempDir::new().unwrap();
    let master_key = build_phone_vault(phone_root.path(), PHONE_PASSWORD);

    let stored = std::fs::read(phone_root.path().join("files").join("phone-doc-1")).unwrap();
    assert!(stored.len() > aes_gcm::IV_LENGTH);
    let iv = &stored[..aes_gcm::IV_LENGTH];
    let ct = &stored[aes_gcm::IV_LENGTH..];
    assert_eq!(aes_gcm::decrypt_bytes(ct, &master_key, iv).unwrap(), b"phone file one");
}
