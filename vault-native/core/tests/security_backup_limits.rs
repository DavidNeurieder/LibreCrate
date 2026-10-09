//! PR 3 regression suite: every allocation driven by untrusted backup input is
//! bounded before (or while) it happens.
//!
//! The bounds live in `BackupLimits` (defaults: 4 GiB package, 8 GiB total
//! decompressed, 100k entries, 4 GiB/entry, 256 KiB manifest, 1M documents).
//! The tests below exercise the cheap, directly-callable seams of the same
//! code paths `import` relies on, using deliberately tight limits so the
//! regressions fail fast.

use vault_native::error::Error;
use vault_native::format::import::{extract_zip, import, BackupLimits};
use vault_native::format::package;

fn make_zip(entries: &[(&str, &[u8])]) -> Vec<u8> {
    use std::io::Write;
    let mut buf = Vec::new();
    {
        let mut writer = zip::ZipWriter::new(std::io::Cursor::new(&mut buf));
        for (name, data) in entries {
            writer
                .start_file::<&str, ()>(name, zip::write::FileOptions::default())
                .unwrap();
            writer.write_all(data).unwrap();
        }
        writer.finish().unwrap();
    }
    buf
}

fn tight_limits() -> BackupLimits {
    BackupLimits {
        max_package_size: 1 << 20,
        max_uncompressed_size: 1 << 20,
        max_entries: 64,
        max_entry_size: 1 << 20,
        max_manifest_size: 64 * 1024,
        max_document_count: 8,
    }
}

/// `package::read` must refuse a header that claims a manifest larger than the
/// caller's cap — it can't trust the attacker-controlled length field.
#[test]
fn package_read_bounds_manifest_size() {
    let manifest = vault_native::format::manifest::VaultManifest {
        version: package::FORMAT_VERSION_V2,
        kdf: "argon2id".into(),
        salt: "c2FsdA==".into(),
        argon2_memory: 19456,
        argon2_iterations: 2,
        argon2_parallelism: 2,
        document_count: 0,
    };
    let data = package::write(&manifest, b"blob");
    assert!(package::read(&data, 4096).is_some());
    // Cap smaller than the actual manifest → None.
    assert!(package::read(&data, 4).is_none());
    // Header claiming a huge manifest is rejected without reading it.
    let mut spoofed = vec![0u8; package::MAGIC_LEN + 8];
    spoofed[..package::MAGIC_LEN].copy_from_slice(&package::MAGIC);
    spoofed[package::MAGIC_LEN..package::MAGIC_LEN + 4]
        .copy_from_slice(&package::FORMAT_VERSION_V2.to_le_bytes());
    spoofed[package::MAGIC_LEN + 4..package::MAGIC_LEN + 8]
        .copy_from_slice(&u32::MAX.to_le_bytes()); // ~4 GiB claimed manifest
    assert!(package::read(&spoofed, 256 * 1024).is_none());
    assert!(package::read(&spoofed, usize::MAX).is_none()); // incomplete data
}

/// `import` rejects any container whose manifest is over the default cap
/// before any KDF/crypto work is spent on it.
#[test]
fn import_rejects_oversized_manifest_header() {
    let kdf = vault_native::crypto::argon2::Argon2Params::default();
    // Manifest JSON padded with a huge (valid base64) salt so the on-disk
    // manifest comfortably exceeds the 256 KiB default cap.
    let huge_salt = "A".repeat(300 * 1024);
    let manifest_json = format!(
        "{{\"version\":2,\"kdf\":\"argon2id\",\"salt\":\"{huge_salt}\",\"argon2Memory\":19456,\"argon2Iterations\":2,\"argon2Parallelism\":2,\"documentCount\":0}}"
    );
    let mut header = Vec::new();
    header.extend_from_slice(&package::MAGIC);
    header.extend_from_slice(&package::FORMAT_VERSION_V2.to_le_bytes());
    header.extend_from_slice(&(manifest_json.len() as u32).to_le_bytes());
    header.extend_from_slice(manifest_json.as_bytes());
    header.extend_from_slice(&[0u8; 128]);
    assert!(
        header.len() > 256 * 1024,
        "test must exceed the manifest cap"
    );

    let result = import(&header, "pw", &kdf);
    assert!(
        matches!(result, Err(Error::Format(_))),
        "oversized manifest header must be rejected, got {result:?}"
    );
}

/// Entry-count limit: more ZIP entries than the cap must abort extraction.
#[test]
fn extract_zip_enforces_entry_count() {
    let zip = make_zip(&[("db/a.db", b"x"), ("db/b.db", b"y"), ("db/c.db", b"z")]);
    let mut limits = tight_limits();
    limits.max_entries = 2;
    let result = extract_zip(&zip, &limits);
    assert!(
        matches!(result, Err(Error::ResourceLimit(_))),
        "too many entries must be a resource error, got {result:?}"
    );
}

/// Per-entry size limit: an entry larger than the cap aborts extraction even
/// if the ZIP central directory lies about the size.
#[test]
fn extract_zip_enforces_entries_size() {
    let big = vec![0x42u8; 4096];
    let zip = make_zip(&[("files/a.bin", &big)]);
    let mut limits = tight_limits();
    limits.max_entry_size = 1024;
    let result = extract_zip(&zip, &limits);
    assert!(
        matches!(result, Err(Error::ResourceLimit(_))),
        "oversized entry must be a resource error, got {result:?}"
    );
}

/// Cumulative decompressed-size limit catches archive bombs entry by entry.
#[test]
fn extract_zip_enforces_total_uncompressed_size() {
    let a = vec![0x11u8; 2048];
    let b = vec![0x22u8; 2048];
    let zip = make_zip(&[("db/x", &a), ("db/y", &b)]);
    let mut limits = tight_limits();
    limits.max_uncompressed_size = 3072; // less than 2048 + 2048
    let result = extract_zip(&zip, &limits);
    assert!(
        matches!(result, Err(Error::ResourceLimit(_))),
        "cumulative size over limit must be rejected, got {result:?}"
    );
}

/// Document-count cap: the number of extracted files is policed independently
/// of the ZIP entry count.
#[test]
fn extract_zip_enforces_document_count() {
    let zip = make_zip(&[
        ("files/a.txt", b"1"),
        ("files/b.txt", b"2"),
        ("files/c.txt", b"3"),
    ]);
    let mut limits = tight_limits();
    limits.max_document_count = 2;
    let result = extract_zip(&zip, &limits);
    assert!(
        matches!(result, Err(Error::ResourceLimit(_))),
        "too many documents must be rejected, got {result:?}"
    );
}

/// A decrypted payload that is not a ZIP is rejected cleanly.
#[test]
fn extract_zip_rejects_garbage_payload() {
    let result = extract_zip(
        b"PK\x03\x04 definitely not a real zip ... garbage",
        &tight_limits(),
    );
    assert!(matches!(result, Err(Error::Compression(_))));
    let result = extract_zip(b"random bytes that are not a zip at all", &tight_limits());
    assert!(matches!(result, Err(Error::Compression(_))));
}

/// Extracting a well-formed payload under permissive limits yields the exact
/// entries (control test that extraction itself is intact).
#[test]
fn extract_zip_control_case() {
    let zip = make_zip(&[
        ("keys/secret.bin", b"k"),
        ("db/librecrate.db", b"db"),
        ("files/readme.txt", b"hello"),
    ]);
    let contents = extract_zip(&zip, &tight_limits()).unwrap();
    assert_eq!(contents.keys.len(), 1);
    assert_eq!(contents.keys[0].key, "secret.bin");
    assert_eq!(contents.keys[0].value, b"k");
    assert_eq!(contents.db_file.as_deref(), Some(&b"db"[..]));
    assert_eq!(contents.files.len(), 1);
    assert_eq!(contents.files[0].key, "readme.txt");
    assert_eq!(contents.files[0].value, b"hello");
}

/// Unknown container formats are refused before any extraction work.
#[test]
fn import_rejects_unknown_version() {
    let kdf = vault_native::crypto::argon2::Argon2Params::default();
    let manifest = vault_native::format::manifest::VaultManifest {
        version: 99,
        kdf: "argon2id".into(),
        salt: "c2FsdA==".into(),
        argon2_memory: kdf.memory_cost,
        argon2_iterations: kdf.iterations,
        argon2_parallelism: kdf.parallelism,
        document_count: 0,
    };
    let data = package::write_with_version(99, &manifest, &[0u8; 12]);
    let result = import(&data, "pw", &kdf);
    assert!(matches!(result, Err(Error::Format(_))));
}
