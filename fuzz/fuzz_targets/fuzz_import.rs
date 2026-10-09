#![no_main]
//! Fuzz the FULL import pipeline (container parse → KDF policy check →
//! envelope authentication → ZIP extraction → document-count cross-check).
//!
//! This is the slowest target: once an input passes the cheap header checks,
//! an Argon2id derivation runs at the default parameters (~tens of ms per
//! input). Prefer the focused targets (fuzz_package_read, fuzz_manifest_json,
//! fuzz_extract_zip, fuzz_archive_path) for coverage throughput, and use this
//! one to shake out cross-stage invariants.
//!
//! Run: `cargo +nightly fuzz run fuzz_import`

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let kdf = vault_native::crypto::argon2::Argon2Params::default();
    let _ = vault_native::format::import::import(data, "fuzz-password", &kdf);
});