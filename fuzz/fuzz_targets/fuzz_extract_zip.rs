#![no_main]
//! Fuzz the ZIP extraction step directly (the seam between decryption and
//! filesystem/persisted blob writes). Exercises the `zip` crate entry parser,
//! the entry-name sanitization and the entry-count / entry-size / cumulative
//! limits without paying an Argon2 cost per input.
//!
//! Invariant asserted on success: every extracted key is itself a safe relative
//! archive path, so nothing the reader keeps can escape the restore directory.
//!
//! Run: `cargo +nightly fuzz run fuzz_extract_zip [corpus-dir]`

use libfuzzer_sys::fuzz_target;
use vault_native::format::import::{extract_zip, BackupLimits};

fuzz_target!(|data: &[u8]| {
    let limits = BackupLimits::default();
    if let Ok(contents) = extract_zip(data, &limits) {
        for kv in contents.keys.iter().chain(contents.files.iter()) {
            assert!(
                vault_native::format::path::safe_archive_path(&kv.key).is_ok(),
                "extracted key is not a safe archive path: {:?}",
                kv.key
            );
        }
    }
});
