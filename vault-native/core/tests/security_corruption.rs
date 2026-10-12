//! Robustness regression suite (security-hardening PR 4; extended by
//! data-integrity PR 3): corrupt, truncated, or tampered vault input must fail
//! cleanly — never panic, never partially import, never authenticate tampered
//! data, and never leak plaintext on failure.
//!
//! The tests here operate on the *container/decryption* layer. Destination-level
//! "a failed restore leaves the existing vault unchanged" assertions live in
//! `backup_roundtrip.rs` (data-integrity PR 4).

use base64::Engine;
use vault_native::crypto::aes_gcm;
use vault_native::crypto::argon2::Argon2Params;
use vault_native::db::queries;
use vault_native::db::schema::{create_encrypted_db, open_encrypted};
use vault_native::error::Error;
use vault_native::format::export;
use vault_native::format::import::import;
use vault_native::format::manifest::VaultManifest;
use vault_native::format::package;
use vault_native::types::KeyValue;

const PASSWORD: &str = "correct horse battery staple";

fn sample_export() -> Vec<u8> {
    let kdf = Argon2Params::default();
    export::export(
        &[
            KeyValue {
                key: "docs/a.txt".into(),
                value: b"alpha".to_vec(),
            },
            KeyValue {
                key: "docs/b.txt".into(),
                value: b"beta".to_vec(),
            },
        ],
        Some(b"db-file"),
        PASSWORD,
        &[KeyValue {
            key: "key.bin".into(),
            value: vec![1u8; 40],
        }],
        &kdf,
    )
    .unwrap()
    .data
}

/// Byte offset where the encrypted blob (IV || ciphertext || tag) begins:
/// magic(16) + version(4) + manifest_len(4) + manifest.
fn blob_start(data: &[u8]) -> usize {
    let manifest_len =
        u32::from_le_bytes(data[20..24].try_into().expect("manifest length")) as usize;
    16 + 4 + 4 + manifest_len
}

fn b64(bytes: &[u8]) -> String {
    base64::engine::general_purpose::STANDARD.encode(bytes)
}

/// A structurally valid v1 manifest, parameterized so individual fields can be
/// corrupted in isolation.
fn v1_manifest(
    version: u32,
    kdf: &str,
    salt: &str,
    memory: u32,
    iterations: u32,
    parallelism: u32,
) -> VaultManifest {
    VaultManifest {
        version,
        kdf: kdf.into(),
        salt: salt.into(),
        argon2_memory: memory,
        argon2_iterations: iterations,
        argon2_parallelism: parallelism,
        document_count: 0,
    }
}

#[test]
fn roundtrip_control() {
    let kdf = Argon2Params::default();
    let contents = import(&sample_export(), PASSWORD, &kdf).unwrap();
    assert_eq!(contents.files.len(), 2);
    assert_eq!(contents.db_file.as_deref(), Some(&b"db-file"[..]));
}

/// Wrong password must fail authentication — this is the badge-check on the
/// integrity of the envelope.
#[test]
fn wrong_password_fails_authentication() {
    let kdf = Argon2Params::default();
    let result = import(&sample_export(), "wrong password", &kdf);
    assert!(matches!(result, Err(Error::AuthenticationFailed)));
}

/// A flipped byte in the GCM authentication tag must be rejected, and the
/// original container must remain importable afterwards (no shared-state damage).
#[test]
fn auth_tag_tamper_fails_authentication() {
    let kdf = Argon2Params::default();
    let full = sample_export();
    let mut tampered = full.clone();
    let last = tampered.len() - 1;
    tampered[last] ^= 0x01;

    assert!(
        matches!(
            import(&tampered, PASSWORD, &kdf),
            Err(Error::AuthenticationFailed)
        ),
        "tampered auth tag must fail authentication"
    );
    assert!(
        import(&full, PASSWORD, &kdf).is_ok(),
        "untampered source must still import"
    );
}

/// Corrupting the IV (nonce) must fail authentication rather than decrypt to
/// garbage plaintext.
#[test]
fn iv_tamper_fails_authentication() {
    let kdf = Argon2Params::default();
    let full = sample_export();
    let mut tampered = full.clone();
    tampered[blob_start(&full)] ^= 0x01; // first IV byte

    assert!(
        matches!(
            import(&tampered, PASSWORD, &kdf),
            Err(Error::AuthenticationFailed)
        ),
        "tampered IV must fail authentication"
    );
}

/// Corrupting the ciphertext body must fail authentication (no unauthenticated
/// plaintext is ever returned).
#[test]
fn ciphertext_body_tamper_fails_authentication() {
    let kdf = Argon2Params::default();
    let full = sample_export();
    let mut tampered = full.clone();
    tampered[blob_start(&full) + aes_gcm::IV_LENGTH] ^= 0x01; // first ciphertext byte

    assert!(
        matches!(
            import(&tampered, PASSWORD, &kdf),
            Err(Error::AuthenticationFailed)
        ),
        "tampered ciphertext must fail authentication"
    );
}

/// Every truncation point of a valid backup must yield a clean error, not a
/// panic, and never a successful import.
#[test]
fn truncations_fail_cleanly() {
    let kdf = Argon2Params::default();
    let full = sample_export();
    let mut cuts = vec![0, 1, full.len() / 4, full.len() / 2, full.len() - 1];
    cuts.dedup();
    for cut in cuts {
        let truncated = &full[..cut];
        let result = import(truncated, PASSWORD, &kdf);
        if let Ok(contents) = &result {
            panic!("truncating at {cut} unexpectedly succeeded: {contents:?}");
        }
    }
    // Fully empty input.
    assert!(import(&[], "pw", &kdf).is_err());
}

/// Bit flips in the header and payload must never authenticate or import — at
/// most a ~2^-128 chance of a flip surviving the GCM tag silently, which is
/// the design guarantee we pin here.
#[test]
fn bitflips_fail_authentication_or_parse() {
    let kdf = Argon2Params::default();
    let full = sample_export();

    // Deterministic sweep: flip byte i of the payload for a spread of i.
    let mut flips = vec![20, 40, 60, 80, 100, 120, 200, 400, 800];
    flips.extend((0..8).map(|k| full.len() - 1 - 16 * k)); // tail ciphertext
    flips.retain(|&i| i < full.len());
    flips.dedup();
    for i in flips {
        let mut mutated = full.clone();
        mutated[i] ^= 0x01;
        let result = import(&mutated, PASSWORD, &kdf);
        assert!(
            matches!(
                result,
                Err(Error::AuthenticationFailed | Error::Format(_) | Error::InvalidData(_))
            ),
            "flip at byte {i} unexpectedly succeeded or panicked: {result:?}"
        );
    }
}

/// An encrypted blob shorter than a single IV is rejected as invalid data — the
/// container header is well-formed, so this specifically exercises the
/// `encrypted blob too short` guard rather than an earlier parse failure.
#[test]
fn encrypted_blob_shorter_than_iv_rejected() {
    let kdf = Argon2Params::default();
    let manifest = v1_manifest(
        package::FORMAT_VERSION_V1,
        "argon2id",
        &b64(&[7u8; 16]),
        kdf.memory_cost,
        kdf.iterations,
        kdf.parallelism,
    );
    let container = package::write_with_version(
        package::FORMAT_VERSION_V1,
        &manifest,
        &[0u8; aes_gcm::IV_LENGTH - 1],
    );

    assert!(
        matches!(
            import(&container, PASSWORD, &kdf),
            Err(Error::InvalidData(_))
        ),
        "sub-IV blob must be rejected as invalid data"
    );
}

/// A manifest whose `salt` is not valid base64 is rejected as invalid data
/// before any key derivation runs.
#[test]
fn invalid_base64_salt_rejected() {
    let kdf = Argon2Params::default();
    let manifest = v1_manifest(
        package::FORMAT_VERSION_V1,
        "argon2id",
        "not*valid*base64*",
        kdf.memory_cost,
        kdf.iterations,
        kdf.parallelism,
    );
    let container = package::write_with_version(package::FORMAT_VERSION_V1, &manifest, &[0u8; 16]);

    assert!(
        matches!(
            import(&container, PASSWORD, &kdf),
            Err(Error::InvalidData(_))
        ),
        "non-base64 salt must be rejected"
    );
}

/// An unsupported KDF identifier is rejected before any crypto work.
#[test]
fn unsupported_kdf_rejected() {
    let kdf = Argon2Params::default();
    let manifest = v1_manifest(
        package::FORMAT_VERSION_V1,
        "scrypt",
        &b64(&[7u8; 16]),
        kdf.memory_cost,
        kdf.iterations,
        kdf.parallelism,
    );
    let container = package::write_with_version(package::FORMAT_VERSION_V1, &manifest, &[0u8; 16]);

    assert!(
        matches!(import(&container, PASSWORD, &kdf), Err(Error::Format(_))),
        "unsupported KDF must be rejected"
    );
}

/// A manifest whose version disagrees with the container header is rejected —
/// both values are attacker-controlled plaintext.
#[test]
fn manifest_version_mismatch_rejected() {
    let kdf = Argon2Params::default();
    let manifest = v1_manifest(
        package::FORMAT_VERSION_V2, // manifest says v2 …
        "argon2id",
        &b64(&[7u8; 16]),
        kdf.memory_cost,
        kdf.iterations,
        kdf.parallelism,
    );
    let container = package::write_with_version(
        package::FORMAT_VERSION_V1, // … container says v1
        &manifest,
        &[0u8; 16],
    );

    assert!(
        matches!(import(&container, PASSWORD, &kdf), Err(Error::Format(_))),
        "manifest/container version mismatch must be rejected"
    );
}

/// Zeroed KDF parameters in the manifest are rejected by the policy ceiling
/// before Argon2 runs (no unbounded/undefined derivation).
#[test]
fn zero_kdf_params_rejected() {
    let kdf = Argon2Params::default();
    let manifest = v1_manifest(
        package::FORMAT_VERSION_V1,
        "argon2id",
        &b64(&[7u8; 16]),
        0, // memory_cost = 0
        kdf.iterations,
        kdf.parallelism,
    );
    let container = package::write_with_version(package::FORMAT_VERSION_V1, &manifest, &[0u8; 16]);

    assert!(
        matches!(
            import(&container, PASSWORD, &kdf),
            Err(Error::InvalidKdfParameters(_))
        ),
        "zero KDF parameters must be rejected"
    );
}

/// An unknown container version is rejected outright.
#[test]
fn unknown_container_version_rejected() {
    let kdf = Argon2Params::default();
    let manifest = v1_manifest(
        99,
        "argon2id",
        &b64(&[7u8; 16]),
        kdf.memory_cost,
        kdf.iterations,
        kdf.parallelism,
    );
    let container = package::write_with_version(99, &manifest, &[0u8; 16]);

    assert!(
        matches!(import(&container, PASSWORD, &kdf), Err(Error::Format(_))),
        "unknown container version must be rejected"
    );
}

/// Pure garbage (valid magic, meaningless rest) is rejected without panicking.
#[test]
fn garbage_after_valid_magic_fails_cleanly() {
    let kdf = Argon2Params::default();
    let mut junk = vec![0u8; 512];
    junk[..16].copy_from_slice(b"LIBCRATE_VAULT\0\0");
    let result = import(&junk, "pw", &kdf);
    assert!(result.is_err());
}

/// The manifest's document count is bound to the actual extracted file count;
/// a header lying about it is rejected (v1) — and for v2 it is additionally
/// authenticated via AAD.
#[test]
fn manifest_document_count_mismatch_rejected() {
    use std::io::Write;

    let password = "pw";
    let kdf = Argon2Params::default();
    let salt = vault_native::crypto::argon2::generate_salt();
    let zip = {
        let mut buf = Vec::new();
        {
            let mut writer = zip::ZipWriter::new(std::io::Cursor::new(&mut buf));
            for (name, data) in [
                ("files/a.txt", b"a".to_vec()),
                ("files/b.txt", b"b".to_vec()),
            ] {
                writer
                    .start_file::<&str, ()>(name, zip::write::FileOptions::default())
                    .unwrap();
                writer.write_all(&data).unwrap();
            }
            writer.finish().unwrap();
        }
        buf
    };
    let key = vault_native::crypto::argon2::derive_key(password, &salt, &kdf).unwrap();
    let (iv, ct) = aes_gcm::encrypt_bytes(&zip, &key).unwrap();
    let blob: Vec<u8> = iv.into_iter().chain(ct).collect();

    // v1 container: no AAD. Tells lies: claims 1 document, extracts 2.
    let manifest = VaultManifest {
        version: package::FORMAT_VERSION_V1,
        kdf: "argon2id".into(),
        salt: b64(&salt),
        argon2_memory: kdf.memory_cost,
        argon2_iterations: kdf.iterations,
        argon2_parallelism: kdf.parallelism,
        document_count: 1,
    };
    let v1 = package::write_with_version(package::FORMAT_VERSION_V1, &manifest, &blob);
    let result = import(&v1, password, &kdf);
    assert!(
        matches!(result, Err(Error::Format(_))),
        "lying document count must be rejected, got {result:?}"
    );
}

/// A database whose `documents` table is missing required columns must produce
/// a controlled query error, never a panic.
#[test]
fn db_missing_required_column_errors_not_panics() {
    let master_key = (0..32).collect::<Vec<u8>>();
    let tmp = tempfile::tempdir().unwrap();
    let db_path = tmp.path().join("partial.db");
    let conn = open_encrypted(db_path.to_str().unwrap(), &master_key).unwrap();

    // A `documents` table with only `id` — every other column `list_documents`
    // selects is missing.
    conn.execute_batch("CREATE TABLE documents (id TEXT PRIMARY KEY)")
        .unwrap();

    let result = queries::list_documents(&conn);
    assert!(
        result.is_err(),
        "missing required columns must surface as a controlled error"
    );
}

/// A corrupted SQLCipher database header must fail to open (controlled error),
/// and the failed open must not rewrite the file.
#[test]
fn corrupt_db_header_fails_to_open() {
    let master_key = (0..32).collect::<Vec<u8>>();
    let tmp = tempfile::tempdir().unwrap();
    let db_path = tmp.path().join("vault.db");
    {
        let _conn = create_encrypted_db(db_path.to_str().unwrap(), &master_key).unwrap();
    }

    let mut bytes = std::fs::read(&db_path).unwrap();
    for b in bytes.iter_mut().take(32) {
        *b ^= 0xff;
    }
    std::fs::write(&db_path, &bytes).unwrap();

    assert!(
        open_encrypted(db_path.to_str().unwrap(), &master_key).is_err(),
        "corrupt DB header must not open"
    );
    assert_eq!(
        std::fs::read(&db_path).unwrap(),
        bytes,
        "a failed open must not rewrite the database file"
    );
}
