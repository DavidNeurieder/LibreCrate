//! PR 2 regression suite: archive-controlled path names can never escape the
//! restore directory.
//!
//! `safe_archive_path` is the single sanctioned entry point for turning a name
//! read out of a backup ZIP/DB into a file name. It rejects absolute paths,
//! `..`/`.` components, drive letters, repeated separators and backslashes.
//! `contained_join` is the defensive second check for directory writes.

use base64::Engine;
use vault_native::error::Error;
use vault_native::format::import::{import, ImportedContents};
use vault_native::format::path::{contained_join, safe_archive_path};
use vault_native::merge::branch_b_fresh_install;
use vault_native::types::KeyValue;

/// Names that must be rejected: every classic zip-slip/absolute-path vector.
#[test]
fn rejects_traversal_and_absolute_names() {
    for name in [
        "../foo",
        "../../foo",
        "a/../../b",
        "files/../../../outside",
        "a/../../../../../../etc/passwd",
        "/etc/passwd",
        "//double/leading",
        "files//absolute",
        "C:/Windows/system32",
        r"C:\Windows\system32",
        r"files\..\evil",
        r"\\server\share",
        "\\\\?\\C:\\x",
        ".",
        "..",
        "",
        "/",
        "a/./b",
        "a/..",
    ] {
        assert!(
            safe_archive_path(name).is_err(),
            "name {name:?} must be rejected"
        );
    }
}

#[test]
fn accepts_normal_relative_names() {
    for name in [
        "a.txt",
        "dir/file.pdf",
        "a/b/c/d.bin",
        "nested 1/ünïcödé.pdf",
    ] {
        assert!(
            safe_archive_path(name).is_ok(),
            "name {name:?} must be accepted"
        );
    }
}

#[test]
fn error_variant_is_invalid_archive_path() {
    assert!(matches!(
        safe_archive_path("../../evil"),
        Err(Error::InvalidArchivePath(_))
    ));
}

/// `contained_join` must never hand back a destination outside `base`, even
/// for paths that lexically normalize past the base directory.
#[test]
fn contained_join_never_escapes_base() {
    let base = std::env::temp_dir().join("librecrate-sec-join");
    std::fs::create_dir_all(&base).unwrap();
    let canonical = base.canonicalize().unwrap();

    // Valid relative files stay inside.
    for rel in ["a", "a/b.bin", "sub/deep/file.txt"] {
        let dest = contained_join(&base, std::path::Path::new(rel)).unwrap();
        assert!(dest.starts_with(&canonical), "{rel:?} escaped: {dest:?}");
    }
    // Anything that would land outside must error. (A lone "." normalizes to
    // `base` itself, which stays inside — the raw dot entry is already
    // rejected upstream by `safe_archive_path`.)
    for rel in [
        "..",
        "../x",
        "a/../../etc",
        "a/b/../../../../x",
        "sub/../..",
        "../../../../etc",
    ] {
        assert!(
            contained_join(&base, std::path::Path::new(rel)).is_err(),
            "{rel:?} must not escape base"
        );
    }
}

/// Build the in-memory ZIP for a v1-style backup payload.
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

/// A crafted v1 backup whose ZIP contains `files/../../evil.txt` must be
/// refused at import time with `InvalidArchivePath` — sanitization applies to
/// names pulled out of the (decrypted, authenticated) archive, before any
/// file can be written.
#[test]
fn zip_slip_entry_rejected_at_import() {
    use vault_native::crypto::aes_gcm;
    use vault_native::crypto::argon2::{self, Argon2Params};
    use vault_native::format::manifest::VaultManifest;
    use vault_native::format::package;

    let password = "pw";
    let kdf = Argon2Params::default();
    let salt = argon2::generate_salt();
    let zip_bytes = make_zip(&[
        ("keys/salt", &salt),
        ("db/librecrate.db", b"db"),
        ("files/../../evil.txt", b"pwned"),
    ]);

    let container_key = argon2::derive_key(password, &salt, &kdf).unwrap();
    let (iv, ct) = aes_gcm::encrypt_bytes(&zip_bytes, &container_key).unwrap();
    let blob: Vec<u8> = iv.into_iter().chain(ct).collect();

    let manifest = VaultManifest {
        version: package::FORMAT_VERSION_V1,
        kdf: "argon2id".into(),
        salt: base64::engine::general_purpose::STANDARD.encode(&salt),
        argon2_memory: kdf.memory_cost,
        argon2_iterations: kdf.iterations,
        argon2_parallelism: kdf.parallelism,
        document_count: 1,
    };
    let v1 = package::write_with_version(package::FORMAT_VERSION_V1, &manifest, &blob);

    let result = import(&v1, password, &kdf);
    assert!(
        matches!(result, Err(Error::InvalidArchivePath(_))),
        "zip-slip entry must be rejected at import, got {result:?}"
    );
}

/// Defense in depth: even if an `ImportedContents` were built directly with a
/// hostile name (bypassing `extract_zip`), the restore writer must refuse to
/// write it and must not create anything outside the target directories.
#[test]
fn restore_refuses_hostile_names_and_stays_in_dirs() {
    let tmp = tempfile::TempDir::new().unwrap();
    let encryption_dir = tmp.path().join("encryption");
    let database_dir = tmp.path().join("databases");
    let files_dir = tmp.path().join("files");
    let outside = tmp.path().join("evil.txt");

    let contents = ImportedContents {
        keys: vec![KeyValue {
            key: "key.bin".into(),
            value: b"k".to_vec(),
        }],
        db_file: Some(b"db-bytes".to_vec()),
        files: vec![KeyValue {
            key: "../evil.txt".into(),
            value: b"x".to_vec(),
        }],
    };
    let result = branch_b_fresh_install(
        &contents,
        "",
        contents.db_file.as_deref().unwrap(),
        &encryption_dir,
        &database_dir,
        &files_dir,
    );
    assert!(
        matches!(result, Err(Error::InvalidArchivePath(_))),
        "restore must refuse hostile name, got {result:?}"
    );
    assert!(
        !outside.exists(),
        "no file may be written outside files_dir"
    );
    // The safe key file still landed where it belongs.
    assert!(encryption_dir.join("key.bin").exists());
}
