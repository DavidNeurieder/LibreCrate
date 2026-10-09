#![no_main]
//! Fuzz the vault container header parser: magic, version, manifest length
//! bounds and manifest JSON. This is the first decoder any untrusted backup
//! hits, and every length it trusts must be validated.
//!
//! Run: `cargo +nightly fuzz run fuzz_package_read`

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    // 256 KiB matches BackupLimits::default().max_manifest_size.
    let _ = vault_native::format::package::read(data, 256 * 1024);
});