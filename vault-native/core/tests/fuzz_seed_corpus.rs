//! Stable replay of the committed fuzz seed corpus (data-integrity PR 7).
//!
//! The `fuzz/` workspace needs nightly + libFuzzer and is deliberately outside
//! the stable build, so it never runs in normal CI. This suite feeds the *same*
//! committed seeds (`fuzz/seeds/<target>/`) through the *same* entry points on
//! stable Rust. That means:
//!
//!   * the corpus is exercised on every `cargo test -p vault-native` run, and
//!   * any input that ever crashes libFuzzer is trivially promoted to a
//!     deterministic regression here (drop the artifact into `fuzz/seeds/`).
//!
//! See `fuzz/README.md` and `fuzz/scripts/make_seeds.sh`.

use std::path::{Component, Path, PathBuf};
use vault_native::db::storage::generate_thumbnail;
use vault_native::error::Error;
use vault_native::format::import::{extract_zip, import, BackupLimits};
use vault_native::format::manifest::VaultManifest;
use vault_native::format::package;
use vault_native::format::path::safe_archive_path;

fn seeds_dir(target: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../fuzz/seeds")
        .join(target)
}

/// Every seed file for `target`, as (file name, bytes), sorted by name.
fn seed_files(target: &str) -> Vec<(String, Vec<u8>)> {
    let dir = seeds_dir(target);
    let entries = std::fs::read_dir(&dir).unwrap_or_else(|e| {
        panic!(
            "seed corpus {dir:?} is missing ({e}); run fuzz/scripts/make_seeds.sh \
             and commit fuzz/seeds/"
        )
    });
    let mut out: Vec<(String, Vec<u8>)> = entries
        .map(|entry| {
            let path = entry.unwrap().path();
            (
                path.file_name().unwrap().to_string_lossy().to_string(),
                std::fs::read(&path).unwrap(),
            )
        })
        .collect();
    assert!(!out.is_empty(), "seed corpus {dir:?} is empty");
    out.sort_by(|a, b| a.0.cmp(&b.0));
    out
}

/// Mirrors `fuzz_package_read`: the header parser must reject garbage and never
/// panic on arbitrary bytes.
#[test]
fn package_read_seeds_do_not_panic() {
    for (name, bytes) in seed_files("fuzz_package_read") {
        let _ = package::read(&bytes, 256 * 1024);
        let _ = name;
    }
}

/// Mirrors `fuzz_manifest_json`.
#[test]
fn manifest_json_seeds_do_not_panic() {
    for (_, bytes) in seed_files("fuzz_manifest_json") {
        if let Ok(s) = std::str::from_utf8(&bytes) {
            let _ = VaultManifest::from_json(s);
        }
    }
}

/// Mirrors `fuzz_extract_zip`; also asserts the sanitizer invariant: any key
/// that survives extraction is already a safe relative archive path.
#[test]
fn extract_zip_seeds_are_sanitized_and_do_not_panic() {
    for (name, bytes) in seed_files("fuzz_extract_zip") {
        if let Ok(contents) = extract_zip(&bytes, &BackupLimits::default()) {
            for kv in contents.keys.iter().chain(contents.files.iter()) {
                assert!(
                    safe_archive_path(&kv.key).is_ok(),
                    "{name}: extracted key {:?} is not a safe archive path",
                    kv.key
                );
            }
        }
    }
}

/// The zip-slip seed must be rejected deterministically at the extract seam.
#[test]
fn zip_slip_seed_is_rejected() {
    let bytes = std::fs::read(seeds_dir("fuzz_extract_zip").join("zip_slip.zip")).unwrap();
    let result = extract_zip(&bytes, &BackupLimits::default());
    assert!(
        matches!(result, Err(Error::InvalidArchivePath(_))),
        "zip-slip seed must be rejected, got {result:?}"
    );
}

/// Mirrors `fuzz_archive_path`; asserts an accepted name can never form anything
/// but normal relative components (no root/parent/cur/prefix).
#[test]
fn archive_path_seeds_never_escape() {
    for (name, bytes) in seed_files("fuzz_archive_path") {
        let Ok(s) = std::str::from_utf8(&bytes) else {
            continue;
        };
        if let Ok(safe) = safe_archive_path(s) {
            assert!(!safe.is_absolute(), "{name}: accepted an absolute path");
            assert!(
                safe.components().all(|c| matches!(c, Component::Normal(_))),
                "{name}: accepted a non-normal component in {safe:?}"
            );
        }
    }
}

/// Mirrors `fuzz_import` (full pipeline incl. Argon2/AES-GCM).
#[test]
fn import_seeds_do_not_panic() {
    let kdf = vault_native::crypto::argon2::Argon2Params::default();
    for (_, bytes) in seed_files("fuzz_import") {
        let _ = import(&bytes, "fuzz-password", &kdf);
    }
}

/// Mirrors `fuzz_thumbnail` across every declared MIME type.
#[test]
fn thumbnail_seeds_do_not_panic() {
    for (_, bytes) in seed_files("fuzz_thumbnail") {
        for mime in [
            "image/png",
            "image/jpeg",
            "image/gif",
            "image/webp",
            "image/bmp",
            "application/epub+zip",
            "application/vnd.comicbook+zip",
            "application/pdf",
        ] {
            let _ = generate_thumbnail(&bytes, mime);
        }
    }
}
