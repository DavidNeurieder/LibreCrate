//! Legacy compatibility + fixture-integrity guard (P0).
//!
//! Proves that backups minted by earlier LibreCrate format versions remain
//! importable and read back exactly, using **frozen** fixtures that are never
//! regenerated from the current writer. See
//! `specification/data-integrity-tests.md` and the fixture `README.md`.
//!
//! Fixtures are embedded with `include_bytes!`/`include_str!` so a missing or
//! renamed fixture is a compile error, not a silently skipped test.

use sha2::{Digest, Sha256};
use vault_native::crypto::aes_gcm;
use vault_native::crypto::argon2::Argon2Params;
use vault_native::db::queries::list_documents;
use vault_native::db::schema::{get_schema_version, open_encrypted};
use vault_native::format::import::import;
use vault_native::vault_ops::derive_master_key_from_contents;

const FIXTURE_ROOT: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/data-integrity");

const V1_CONTAINER: &[u8] =
    include_bytes!("fixtures/data-integrity/legacy-v1/vault.librecrate-backup");
const V1_EXPECTED: &str = include_str!("fixtures/data-integrity/legacy-v1/expected.json");
const V2_CONTAINER: &[u8] =
    include_bytes!("fixtures/data-integrity/legacy-v2/vault.librecrate-backup");
const V2_EXPECTED: &str = include_str!("fixtures/data-integrity/legacy-v2/expected.json");
const MANIFEST: &str = include_str!("fixtures/data-integrity/manifest.json");

/// Import a fixture, recover the master key, open its embedded DB and compare
/// every logical field against `expected.json`, then decrypt the attachments.
fn verify_fixture(container: &[u8], expected_json: &str) {
    let expected: serde_json::Value = serde_json::from_str(expected_json).unwrap();
    let password = expected["password"].as_str().unwrap();
    let format_version = expected["format_version"].as_u64().unwrap() as u32;

    // The container header must still declare the historical format version.
    assert_eq!(
        &container[16..20],
        &format_version.to_le_bytes(),
        "container version header"
    );

    let kdf = Argon2Params::default();
    let contents = import(container, password, &kdf).expect("legacy fixture must import");

    let expected_docs = expected["documents"].as_array().unwrap();
    assert_eq!(
        contents.files.len(),
        expected_docs.len(),
        "attachment count"
    );
    assert_eq!(
        contents.files.len() as u64,
        expected["manifest_document_count"].as_u64().unwrap(),
        "manifest document_count"
    );

    let master_key = derive_master_key_from_contents(&contents, password)
        .expect("master key must unwrap from fixture keys");

    // Open the embedded SQLCipher DB with the recovered key and compare the
    // logical records (never the raw DB bytes — page layout may differ).
    let tmp = tempfile::tempdir().unwrap();
    let db_path = tmp.path().join("librecrate.db");
    std::fs::write(
        &db_path,
        contents.db_file.as_ref().expect("fixture carries a db"),
    )
    .unwrap();
    let conn = open_encrypted(db_path.to_str().unwrap(), &master_key)
        .expect("fixture DB must open with the recovered key");

    let mut rows = list_documents(&conn).unwrap();
    rows.sort_by(|a, b| a.id.cmp(&b.id));
    assert_eq!(rows.len(), expected_docs.len(), "document count");
    for (row, exp) in rows.iter().zip(expected_docs) {
        assert_eq!(row.id, exp["id"].as_str().unwrap(), "id");
        assert_eq!(row.title, exp["title"].as_str().unwrap(), "title");
        assert_eq!(
            row.mime_type,
            exp["mime_type"].as_str().unwrap(),
            "mime_type"
        );
        assert_eq!(
            row.file_name,
            exp["file_name"].as_str().unwrap(),
            "file_name"
        );
        assert_eq!(
            row.file_size,
            exp["file_size"].as_i64().unwrap(),
            "file_size"
        );
    }

    assert_eq!(
        get_schema_version(&conn).unwrap(),
        expected["schema_version"].as_i64().unwrap(),
        "schema version"
    );

    // Attachment bytes must decrypt to exactly the frozen plaintext.
    for exp in expected_docs {
        let id = exp["id"].as_str().unwrap();
        let kv = contents
            .files
            .iter()
            .find(|k| k.key == id)
            .unwrap_or_else(|| panic!("attachment {id} present"));
        assert!(
            kv.value.len() >= aes_gcm::IV_LENGTH,
            "attachment {id} length"
        );
        let iv = &kv.value[..aes_gcm::IV_LENGTH];
        let ct = &kv.value[aes_gcm::IV_LENGTH..];
        let plain = aes_gcm::decrypt_bytes(ct, &master_key, iv)
            .unwrap_or_else(|| panic!("attachment {id} decrypts"));
        assert_eq!(
            hex::encode(Sha256::digest(&plain)),
            exp["sha256"].as_str().unwrap(),
            "attachment {id} plaintext sha256"
        );
    }

    // Wrong credentials must fail without yielding any plaintext.
    assert!(
        import(container, "definitely-not-the-password", &kdf).is_err(),
        "wrong password must be rejected"
    );
}

#[test]
fn legacy_v1_fixture_imports_and_reads_exactly() {
    verify_fixture(V1_CONTAINER, V1_EXPECTED);
}

#[test]
fn legacy_v2_fixture_imports_and_reads_exactly() {
    verify_fixture(V2_CONTAINER, V2_EXPECTED);
}

/// CI gate: every fixture listed in `manifest.json` must exist, be unmodified
/// (SHA-256 match), and have its `expected.json` present. A missing or altered
/// fixture fails the build instead of quietly weakening coverage.
#[test]
fn fixtures_present_and_unmodified() {
    let manifest: serde_json::Value = serde_json::from_str(MANIFEST).unwrap();
    let root = std::path::Path::new(FIXTURE_ROOT);
    let fixtures = manifest["fixtures"].as_array().unwrap();
    assert!(!fixtures.is_empty(), "manifest lists no fixtures");

    for f in fixtures {
        let rel = f["path"].as_str().unwrap();
        let bytes =
            std::fs::read(root.join(rel)).unwrap_or_else(|e| panic!("missing fixture {rel}: {e}"));
        assert_eq!(
            hex::encode(Sha256::digest(&bytes)),
            f["sha256"].as_str().unwrap(),
            "fixture {rel} was modified"
        );

        let expected = f["expected"].as_str().unwrap();
        assert!(
            root.join(expected).exists(),
            "fixture {rel} is missing its expected file {expected}"
        );
    }
}
