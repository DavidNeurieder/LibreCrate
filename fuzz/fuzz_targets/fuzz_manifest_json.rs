#![no_main]
//! Fuzz the manifest JSON decoder. The manifest is attacker-controlled
//! plaintext in the container header; it must never panic on arbitrary JSON
//! and never accept values outside enforced ranges (callers validate the
//! parsed KDF fields against `KdfPolicy` before use).
//!
//! Run: `cargo +nightly fuzz run fuzz_manifest_json`

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    if let Ok(s) = std::str::from_utf8(data) {
        let _ = vault_native::format::manifest::VaultManifest::from_json(s);
    }
});