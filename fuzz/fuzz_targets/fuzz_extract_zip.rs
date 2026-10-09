#![no_main]
//! Fuzz the ZIP extraction step directly (the seam between decryption and
//! filesystem/persisted blob writes). Exercises the `zip` crate entry parser,
//! the entry-name sanitization and the entry-count / entry-size / cumulative
//! limits without paying an Argon2 cost per input.
//!
//! Run: `cargo +nightly fuzz run fuzz_extract_zip`

use libfuzzer_sys::fuzz_target;
use vault_native::format::import::{extract_zip, BackupLimits};

fuzz_target!(|data: &[u8]| {
    let limits = BackupLimits::default();
    let _ = extract_zip(data, &limits);
});