#![no_main]
//! Fuzz the archive-path sanitizer. Any string a malicious archive can put in
//! a file name is handed to `safe_archive_path`; it must reject traversal
//! vectors on every platform without panicking.
//!
//! Run: `cargo +nightly fuzz run fuzz_archive_path`

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    if let Ok(s) = std::str::from_utf8(data) {
        let _ = vault_native::format::path::safe_archive_path(s);
    }
});