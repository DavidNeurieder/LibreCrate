//! Backup/restore and merge round trips (data-integrity PR 4).
//!
//! Guarantees:
//!   * Export → restore into a fresh vault (Branch B) preserves the *logical*
//!     contents exactly — identifiers, metadata, and decrypted attachment bytes
//!     — for Unicode, binary, empty, and large payloads.
//!   * Merge into an existing vault (Branch A) adds the backup's documents
//!     without altering existing ones, and re-encrypts merged attachments to the
//!     local master key so they stay readable (the FFI/GUI cross-password path).
//!   * A failed restore (wrong password, truncated, or tampered archive) never
//!     replaces or modifies the destination vault.
//!   * Frozen v1/v2 backup fixtures still restore into a fresh vault and match
//!     their recorded logical contents.
//!
//! Comparisons are logical (via `common::snapshot_vault`), never raw SQLite bytes.

mod common;

use common::*;
use vault_native::vault_ops::{export_vault_dir, merge_backup_to_vault, restore_backup_to_dir};

const V1_BACKUP: &[u8] =
    include_bytes!("fixtures/data-integrity/legacy-v1/vault.librecrate-backup");
const V1_EXPECTED: &str = include_str!("fixtures/data-integrity/legacy-v1/expected.json");
const V2_BACKUP: &[u8] =
    include_bytes!("fixtures/data-integrity/legacy-v2/vault.librecrate-backup");
const V2_EXPECTED: &str = include_str!("fixtures/data-integrity/legacy-v2/expected.json");

/// Export → restore into a fresh directory must reproduce the source vault.
#[test]
fn full_roundtrip_preserves_logical_contents() {
    let src = tempfile::tempdir().unwrap();
    create_vault(src.path(), "roundtrip-pass");
    let before = snapshot_vault(src.path(), "roundtrip-pass");

    assert_eq!(
        before.documents.len(),
        4,
        "synthetic set must cover unicode/binary/empty/large"
    );
    assert!(
        before.documents.iter().any(|d| d.file_size == 0),
        "an empty payload must be part of the round trip"
    );
    assert!(
        before.documents.iter().any(|d| d.file_size == 256 * 1024),
        "a large payload must be part of the round trip"
    );

    let backup = export_vault_dir(src.path(), "roundtrip-pass").unwrap();
    assert!(backup.starts_with(b"LIBCRATE_VAULT"));

    let dst = tempfile::tempdir().unwrap();
    restore_backup_to_dir(&backup, "roundtrip-pass", dst.path()).unwrap();

    let after = snapshot_vault(dst.path(), "roundtrip-pass");
    assert_snapshot_eq(&before, &after);
}

/// Branch A: merging a backup from a differently-keyed vault must add its
/// documents and re-encrypt their attachments to the local master key.
#[test]
fn branch_a_merge_reencrypts_attachments_to_local_key() {
    // Vault A (password "a-pass") with one document.
    let a = tempfile::tempdir().unwrap();
    let mk_a = create_empty_vault(a.path(), "a-pass");
    add_document(
        a.path(),
        &mk_a,
        "doc-a",
        "Doc A",
        "text/plain",
        b"alpha content bytes",
    );
    let before_a = snapshot_vault(a.path(), "a-pass");
    assert_eq!(before_a.documents.len(), 1);

    // Vault B (different password) with one different document.
    let b = tempfile::tempdir().unwrap();
    let mk_b = create_empty_vault(b.path(), "b-pass");
    add_document(
        b.path(),
        &mk_b,
        "doc-b",
        "Doc B",
        "text/plain",
        b"beta content bytes",
    );
    let before_b = snapshot_vault(b.path(), "b-pass");
    let backup_b = export_vault_dir(b.path(), "b-pass").unwrap();

    // Merge B into A's open connection, re-encrypting to A's key (the FFI/GUI path).
    let (conn_a, mk_opened) = open_vault(a.path(), "a-pass");
    assert_eq!(mk_opened, mk_a, "unlock must recover A's master key");
    let stats = merge_backup_to_vault(a.path(), &conn_a, Some(&mk_a), &backup_b, "b-pass").unwrap();
    assert_eq!(stats.documents_added, 1);
    assert_eq!(stats.documents_updated, 0);
    assert_eq!(stats.documents_conflicted, 0);
    drop(conn_a);

    let after = snapshot_vault(a.path(), "a-pass");
    assert_eq!(
        after.documents.len(),
        2,
        "merge must add exactly B's document"
    );

    // A's original document is untouched.
    let doc_a = after.documents.iter().find(|d| d.id == "doc-a").unwrap();
    assert_eq!(
        doc_a,
        before_a.documents.iter().find(|d| d.id == "doc-a").unwrap(),
        "existing document must be untouched"
    );

    // B's merged document decrypts under A's master key with identical bytes.
    let doc_b = after.documents.iter().find(|d| d.id == "doc-b").unwrap();
    let expected_b = before_b.documents.iter().find(|d| d.id == "doc-b").unwrap();
    assert_eq!(
        doc_b.sha256, expected_b.sha256,
        "merged attachment must decrypt under A's key"
    );
    assert_eq!(doc_b.file_size, expected_b.file_size);
    assert_eq!(doc_b.title, expected_b.title);
}

/// A failing restore must never replace or modify the destination vault.
#[test]
fn failed_restore_does_not_replace_destination() {
    // A populated destination vault we must not damage.
    let dest = tempfile::tempdir().unwrap();
    let mk = create_empty_vault(dest.path(), "dest-pass");
    add_document(
        dest.path(),
        &mk,
        "keep-me",
        "Keep Me",
        "text/plain",
        b"precious bytes",
    );
    let before = snapshot_vault(dest.path(), "dest-pass");
    assert_eq!(before.documents.len(), 1);

    // A valid backup created by a *different* vault/password.
    let other = tempfile::tempdir().unwrap();
    create_vault(other.path(), "other-pass");
    let backup = export_vault_dir(other.path(), "other-pass").unwrap();

    // Wrong password.
    assert!(
        restore_backup_to_dir(&backup, "wrong-pass", dest.path()).is_err(),
        "wrong password must fail"
    );

    // Truncated archive.
    assert!(
        restore_backup_to_dir(&backup[..backup.len() / 2], "other-pass", dest.path()).is_err(),
        "truncated archive must fail"
    );

    // Tampered authentication tag.
    let mut tampered = backup.clone();
    let last = tampered.len() - 1;
    tampered[last] ^= 0x01;
    assert!(
        restore_backup_to_dir(&tampered, "other-pass", dest.path()).is_err(),
        "tampered archive must fail"
    );

    let after = snapshot_vault(dest.path(), "dest-pass");
    assert_snapshot_eq(&before, &after);
}

/// Restore a frozen fixture into a fresh vault and compare logical contents.
fn restore_fixture_and_compare(backup: &[u8], expected_json: &str) {
    let expected: serde_json::Value = serde_json::from_str(expected_json).unwrap();
    let password = expected["password"].as_str().expect("fixture password");
    let expected_docs = expected["documents"]
        .as_array()
        .expect("expected documents");

    let dst = tempfile::tempdir().unwrap();
    restore_backup_to_dir(backup, password, dst.path()).expect("legacy backup must restore");

    let snapshot = snapshot_vault(dst.path(), password);
    assert_eq!(
        snapshot.documents.len(),
        expected_docs.len(),
        "restored document count must match the fixture"
    );
    for (got, want) in snapshot.documents.iter().zip(expected_docs) {
        assert_eq!(got.id, want["id"].as_str().unwrap(), "id");
        assert_eq!(got.title, want["title"].as_str().unwrap(), "title");
        assert_eq!(
            got.mime_type,
            want["mime_type"].as_str().unwrap(),
            "mime_type"
        );
        assert_eq!(
            got.file_name,
            want["file_name"].as_str().unwrap(),
            "file_name"
        );
        assert_eq!(
            got.file_size,
            want["file_size"].as_i64().unwrap(),
            "file_size"
        );
        assert_eq!(got.sha256, want["sha256"].as_str().unwrap(), "sha256");
    }
}

#[test]
fn legacy_v1_backup_restores_into_fresh_vault() {
    restore_fixture_and_compare(V1_BACKUP, V1_EXPECTED);
}

#[test]
fn legacy_v2_backup_restores_into_fresh_vault() {
    restore_fixture_and_compare(V2_BACKUP, V2_EXPECTED);
}

/// Assert no `.backup_merge_*` scratch database was left behind.
fn assert_no_merge_scratch(vault_dir: &std::path::Path) {
    let leftovers: Vec<String> = std::fs::read_dir(vault_dir)
        .unwrap()
        .filter_map(|e| e.ok())
        .map(|e| e.file_name().to_string_lossy().to_string())
        .filter(|n| n.starts_with(".backup_merge_"))
        .collect();
    assert!(
        leftovers.is_empty(),
        "leftover scratch files: {leftovers:?}"
    );
}

/// A malformed backup handed to the merge path must fail (before its scratch DB
/// is even written), leaving the vault untouched and the directory clean.
#[test]
fn malformed_backup_does_not_mutate_vault() {
    let a = tempfile::tempdir().unwrap();
    let mk = create_empty_vault(a.path(), "a-pass");
    add_document(a.path(), &mk, "keep", "Keep", "text/plain", b"keep me");
    let before = snapshot_vault(a.path(), "a-pass");

    // A real container, truncated so decryption/import fails.
    let b = tempfile::tempdir().unwrap();
    create_vault(b.path(), "b-pass");
    let valid = export_vault_dir(b.path(), "b-pass").unwrap();
    let truncated = &valid[..valid.len() / 2];

    let (conn_a, _) = open_vault(a.path(), "a-pass");
    assert!(
        merge_backup_to_vault(a.path(), &conn_a, Some(&mk), truncated, "b-pass").is_err(),
        "a truncated backup must fail the merge"
    );
    drop(conn_a);

    let after = snapshot_vault(a.path(), "a-pass");
    assert_snapshot_eq(&before, &after);
    assert_no_merge_scratch(a.path());
}

/// A merge that fails *after* its scratch DB is written must roll back
/// (all-or-nothing) and remove the scratch file.
#[test]
fn failed_merge_rolls_back_and_cleans_scratch_files() {
    let a = tempfile::tempdir().unwrap();
    let mk = create_empty_vault(a.path(), "a-pass");
    add_document(a.path(), &mk, "keep", "Keep", "text/plain", b"keep me");
    let before = snapshot_vault(a.path(), "a-pass");

    let b = tempfile::tempdir().unwrap();
    create_vault(b.path(), "b-pass");
    let backup = export_vault_dir(b.path(), "b-pass").unwrap();

    // Force the merge to fail once it starts inserting: without the FTS table
    // the insert trigger errors, so the transaction must roll back.
    let (conn_a, _) = open_vault(a.path(), "a-pass");
    conn_a.execute_batch("DROP TABLE documents_fts").unwrap();
    assert!(
        merge_backup_to_vault(a.path(), &conn_a, Some(&mk), &backup, "b-pass").is_err(),
        "merge must surface the mid-merge failure"
    );
    drop(conn_a);

    let after = snapshot_vault(a.path(), "a-pass");
    assert_snapshot_eq(&before, &after);
    assert_no_merge_scratch(a.path());
}
