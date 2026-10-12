//! One-off, `#[ignore]`d generator for the frozen data-integrity fixtures.
//!
//! The committed bytes under `tests/fixtures/data-integrity/` are the source of
//! truth for backward-compatibility tests. This generator mints a fixture
//! exactly once; it is **never** run by an ordinary `cargo test` (it is
//! `#[ignore]`d) and it must **never** be re-run just to make a failing
//! compatibility test pass — a fixture records historical behavior
//! independently of today's code (see `specification/data-integrity-tests.md`).
//!
//! Run explicitly when a genuinely new historical fixture must be added:
//!
//! ```sh
//! cargo test -p vault-native --test make_fixtures -- --ignored --nocapture
//! ```
//!
//! It rewrites, under `tests/fixtures/data-integrity/`:
//!   - `legacy-v1/vault.librecrate-backup` + `legacy-v1/expected.json`
//!   - `legacy-v2/vault.librecrate-backup` + `legacy-v2/expected.json`
//!   - `manifest.json` (fixture SHA-256s for the guard test)
//!
//! Choice of mechanism: this lives as an ignored integration test rather than a
//! separate `tools/` crate so it reuses the pinned `vault-native` build
//! (SQLCipher/OpenSSL/MuPDF) instead of compiling a second copy. See the plan.

use base64::Engine;
use sha2::{Digest, Sha256};
use std::io::Write;
use std::path::{Path, PathBuf};
use vault_native::crypto::aes_gcm;
use vault_native::crypto::argon2::{self, Argon2Params};
use vault_native::db::queries::list_documents;
use vault_native::db::schema::{get_schema_version, open_encrypted};
use vault_native::db::storage::import_document;
use vault_native::format::export::create_vault_layout;
use vault_native::format::manifest::VaultManifest;
use vault_native::format::package;
use vault_native::types::KeyValue;
use vault_native::vault_ops::{serialize_vault_from_disk, VaultSnapshot};

const FIXTURE_DIR: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/data-integrity");

const V1_PASSWORD: &str = "fixture-password-v1";
const V2_PASSWORD: &str = "fixture-password-v2";

/// Synthetic, non-secret document set (both fixtures share it, with fresh
/// randomized crypto material so the two containers differ byte-for-byte).
struct FixtureDoc {
    id: &'static str,
    title: &'static str,
    mime: &'static str,
    content: &'static [u8],
}

const DOCS: &[FixtureDoc] = &[
    FixtureDoc {
        id: "legacy-doc-1",
        title: "Legacy Doc One",
        mime: "text/plain",
        content: b"legacy file one\n",
    },
    FixtureDoc {
        id: "legacy-doc-2",
        title: "Legacy Doc Two",
        mime: "application/octet-stream",
        // Deliberately binary so the fixture covers non-UTF-8 attachment bytes.
        content: &[0x00, 0x01, 0x02, 0x03, 0xff, 0xfe, 0x7f, 0x80, 0x0a],
    },
];

fn build_vault(dir: &Path, password: &str) -> Vec<u8> {
    let master_key = create_vault_layout(dir, password).unwrap();
    let db_path = dir.join("databases/librecrate.db");
    let conn = open_encrypted(db_path.to_str().unwrap(), &master_key).unwrap();
    for d in DOCS {
        import_document(
            &conn,
            dir,
            d.id,
            d.title,
            d.content,
            d.mime,
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

/// Reproduce the legacy v1 payload exactly: same ZIP namespaces as the current
/// exporter, but AES-GCM **without** AAD (`aes_gcm::encrypt_bytes`).
fn zip_payload(keys: &[KeyValue], db: &[u8], files: &[KeyValue]) -> Vec<u8> {
    let mut entries: Vec<(String, Vec<u8>)> = Vec::new();
    for kv in keys {
        entries.push((format!("keys/{}", kv.key), kv.value.clone()));
    }
    entries.push(("db/librecrate.db".into(), db.to_vec()));
    for kv in files {
        entries.push((format!("files/{}", kv.key), kv.value.clone()));
    }

    let mut buf = Vec::new();
    {
        let mut zip = zip::ZipWriter::new(std::io::Cursor::new(&mut buf));
        for (name, data) in &entries {
            zip.start_file::<&str, ()>(name, zip::write::FileOptions::default())
                .unwrap();
            zip.write_all(data).unwrap();
        }
        zip.finish().unwrap();
    }
    buf
}

/// Write a v1 (no-AAD) container and return its manifest.
fn write_v1(path: &Path, snapshot: &VaultSnapshot, password: &str) -> VaultManifest {
    let kdf = &snapshot.kdf_params;
    let salt = argon2::generate_salt();
    let container_key = argon2::derive_key(password, &salt, kdf).unwrap();
    let zip = zip_payload(&snapshot.keys, &snapshot.db_data, &snapshot.files);
    let (iv, ct) = aes_gcm::encrypt_bytes(&zip, &container_key).unwrap();
    let mut blob = iv;
    blob.extend_from_slice(&ct);

    let manifest = VaultManifest {
        version: package::FORMAT_VERSION_V1,
        kdf: "argon2id".into(),
        salt: base64::engine::general_purpose::STANDARD.encode(&salt),
        argon2_memory: kdf.memory_cost,
        argon2_iterations: kdf.iterations,
        argon2_parallelism: kdf.parallelism,
        document_count: snapshot.files.len() as u32,
    };
    let data = package::write_with_version(package::FORMAT_VERSION_V1, &manifest, &blob);
    std::fs::write(path, data).unwrap();
    manifest
}

/// Write a current (v2, AAD-bound) container via the real production exporter
/// and return the manifest it recorded.
fn write_v2(path: &Path, snapshot: &VaultSnapshot, password: &str) -> VaultManifest {
    let exported = vault_native::format::export::export(
        &snapshot.files,
        Some(&snapshot.db_data),
        password,
        &snapshot.keys,
        &snapshot.kdf_params,
    )
    .unwrap();
    std::fs::write(path, &exported.data).unwrap();
    package::read(&exported.data, 1024 * 1024).unwrap().manifest
}

/// Build the expected documents array from the *actual* vault DB, so the
/// fixture records reality (derived file_name, stored file_size, schema
/// version) rather than a re-derived guess.
fn expected_documents(dir: &Path, master_key: &[u8]) -> Vec<serde_json::Value> {
    let db_path = dir.join("databases/librecrate.db");
    let conn = open_encrypted(db_path.to_str().unwrap(), master_key).unwrap();
    let mut rows = list_documents(&conn).unwrap();
    rows.sort_by(|a, b| a.id.cmp(&b.id));

    rows.iter()
        .map(|r| {
            let content = DOCS
                .iter()
                .find(|d| d.id == r.id)
                .map(|d| d.content)
                .unwrap_or_default();
            let mut hasher = Sha256::new();
            hasher.update(content);
            serde_json::json!({
                "id": r.id,
                "title": r.title,
                "mime_type": r.mime_type,
                "file_name": r.file_name,
                "file_size": r.file_size,
                "sha256": hex::encode(hasher.finalize()),
            })
        })
        .collect()
}

#[allow(clippy::too_many_arguments)]
fn expected_json(
    fixture: &str,
    format_version: u32,
    container: &str,
    password: &str,
    kdf: &Argon2Params,
    manifest: &VaultManifest,
    schema_version: i64,
    documents: &[serde_json::Value],
) -> String {
    let value = serde_json::json!({
        "fixture": fixture,
        "librecrate_version": env!("CARGO_PKG_VERSION"),
        "format_version": format_version,
        "container": container,
        "password": password,
        "kdf": {
            "memory_cost": kdf.memory_cost,
            "iterations": kdf.iterations,
            "parallelism": kdf.parallelism,
        },
        "manifest_document_count": manifest.document_count,
        "schema_version": schema_version,
        "documents": documents,
    });
    let mut json = serde_json::to_string_pretty(&value).unwrap();
    json.push('\n');
    json
}

fn sha256_file(path: &Path) -> String {
    let bytes = std::fs::read(path).unwrap();
    hex::encode(Sha256::digest(&bytes))
}

#[test]
#[ignore = "one-off fixture generator; run explicitly with --ignored"]
fn generate_data_integrity_fixtures() {
    let root = PathBuf::from(FIXTURE_DIR);
    let v1_dir = root.join("legacy-v1");
    let v2_dir = root.join("legacy-v2");
    std::fs::create_dir_all(&v1_dir).unwrap();
    std::fs::create_dir_all(&v2_dir).unwrap();

    let v1_name = "vault.librecrate-backup";
    let v2_name = "vault.librecrate-backup";

    // --- v1: no-AAD legacy container -------------------------------------
    let tmp1 = tempfile::tempdir().unwrap();
    let mk1 = build_vault(tmp1.path(), V1_PASSWORD);
    let snap1 = serialize_vault_from_disk(tmp1.path()).unwrap();
    let schema1 = {
        let conn = open_encrypted(
            tmp1.path()
                .join("databases/librecrate.db")
                .to_str()
                .unwrap(),
            &mk1,
        )
        .unwrap();
        get_schema_version(&conn).unwrap()
    };
    let m1 = write_v1(&v1_dir.join(v1_name), &snap1, V1_PASSWORD);
    std::fs::write(
        v1_dir.join("expected.json"),
        expected_json(
            &format!("legacy-v1/{v1_name}"),
            package::FORMAT_VERSION_V1,
            "backup",
            V1_PASSWORD,
            &snap1.kdf_params,
            &m1,
            schema1,
            &expected_documents(tmp1.path(), &mk1),
        ),
    )
    .unwrap();

    // --- v2: current authenticated envelope ------------------------------
    let tmp2 = tempfile::tempdir().unwrap();
    let mk2 = build_vault(tmp2.path(), V2_PASSWORD);
    let snap2 = serialize_vault_from_disk(tmp2.path()).unwrap();
    let schema2 = {
        let conn = open_encrypted(
            tmp2.path()
                .join("databases/librecrate.db")
                .to_str()
                .unwrap(),
            &mk2,
        )
        .unwrap();
        get_schema_version(&conn).unwrap()
    };
    let m2 = write_v2(&v2_dir.join(v2_name), &snap2, V2_PASSWORD);
    std::fs::write(
        v2_dir.join("expected.json"),
        expected_json(
            &format!("legacy-v2/{v2_name}"),
            package::FORMAT_VERSION_V2,
            "backup",
            V2_PASSWORD,
            &snap2.kdf_params,
            &m2,
            schema2,
            &expected_documents(tmp2.path(), &mk2),
        ),
    )
    .unwrap();

    // --- manifest.json: fixture hashes for the guard test ----------------
    let manifest = serde_json::json!({
        "schema": 1,
        "generated_by": format!("vault-native {} (tests/make_fixtures.rs)", env!("CARGO_PKG_VERSION")),
        "fixtures": [
            {
                "path": format!("legacy-v1/{v1_name}"),
                "expected": "legacy-v1/expected.json",
                "format_version": package::FORMAT_VERSION_V1,
                "container": "backup",
                "purpose": "v1 container: AES-GCM payload with NO AAD (legacy envelope)",
                "sha256": sha256_file(&v1_dir.join(v1_name)),
            },
            {
                "path": format!("legacy-v2/{v2_name}"),
                "expected": "legacy-v2/expected.json",
                "format_version": package::FORMAT_VERSION_V2,
                "container": "backup",
                "purpose": "v2 container: manifest authenticated as GCM AAD",
                "sha256": sha256_file(&v2_dir.join(v2_name)),
            },
        ],
    });
    let mut json = serde_json::to_string_pretty(&manifest).unwrap();
    json.push('\n');
    std::fs::write(root.join("manifest.json"), json).unwrap();

    println!("wrote data-integrity fixtures under {FIXTURE_DIR}");
}
