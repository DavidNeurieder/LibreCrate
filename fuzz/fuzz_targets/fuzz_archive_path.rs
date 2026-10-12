#![no_main]
//! Fuzz the archive-path sanitizer. Any string a malicious archive can put in
//! a file name is handed to `safe_archive_path`; it must reject traversal
//! vectors on every platform without panicking.
//!
//! Invariant asserted on acceptance: a name that passes can only decompose into
//! normal relative components — never root / parent / cur / Windows prefix.
//!
//! Run: `cargo +nightly fuzz run fuzz_archive_path [corpus-dir]`

use libfuzzer_sys::fuzz_target;
use std::path::Component;

fuzz_target!(|data: &[u8]| {
    if let Ok(s) = std::str::from_utf8(data) {
        if let Ok(safe) = vault_native::format::path::safe_archive_path(s) {
            assert!(!safe.is_absolute(), "accepted an absolute path: {safe:?}");
            for component in safe.components() {
                assert!(
                    matches!(component, Component::Normal(_)),
                    "accepted a non-normal component {component:?} in {safe:?}"
                );
            }
        }
    }
});
