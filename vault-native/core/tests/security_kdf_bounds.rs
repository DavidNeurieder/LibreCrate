//! PR 1 regression suite: Argon2id KDF ceilings are enforced on every entry
//! point that accepts untrusted parameters.
//!
//! `KdfPolicy::default()` is the absolute safety ceiling (64 MiB memory /
//! 10 iterations / 4 parallelism). A hostile backup manifest, `params.toml`,
//! or FFI call must never be able to drive unbounded memory or CPU inside
//! Argon2 — validation must happen before Argon2 executes.

use vault_native::crypto::argon2::{self, Argon2Params, KdfPolicy};
use vault_native::error::Error;

/// Desktop/GUI/CLI production parameters (kept deliberately below the cap).
fn desktop_params() -> Argon2Params {
    Argon2Params::new(19456, 2, 2, 32)
}

#[test]
fn default_params_are_within_policy() {
    assert!(argon2::validate_kdf_params(&desktop_params(), &KdfPolicy::default()).is_ok());
    assert!(argon2::validate_kdf_params(&Argon2Params::default(), &KdfPolicy::default()).is_ok());
}

#[test]
fn over_memory_limit_rejected() {
    // Cap is 64 MiB; one KiB over must be rejected before Argon2 runs.
    let p = Argon2Params::new(64 * 1024 + 1, 2, 2, 32);
    assert!(matches!(
        argon2::validate_kdf_params(&p, &KdfPolicy::default()),
        Err(Error::InvalidKdfParameters(_))
    ));
}

#[test]
fn over_iterations_rejected() {
    let p = Argon2Params::new(19456, 11, 2, 32);
    assert!(matches!(
        argon2::validate_kdf_params(&p, &KdfPolicy::default()),
        Err(Error::InvalidKdfParameters(_))
    ));
}

#[test]
fn over_parallelism_rejected() {
    let p = Argon2Params::new(19456, 2, 5, 32);
    assert!(matches!(
        argon2::validate_kdf_params(&p, &KdfPolicy::default()),
        Err(Error::InvalidKdfParameters(_))
    ));
}

#[test]
fn zero_values_rejected() {
    for p in [
        Argon2Params::new(0, 2, 2, 32),
        Argon2Params::new(19456, 0, 2, 32),
        Argon2Params::new(19456, 2, 0, 32),
    ] {
        assert!(
            matches!(
                argon2::validate_kdf_params(&p, &KdfPolicy::default()),
                Err(Error::InvalidKdfParameters(_))
            ),
            "zero-valued params must be rejected: {p:?}"
        );
    }
}

#[test]
fn at_cap_values_accepted() {
    // Exact cap values are allowed; a value one above is not.
    let at_cap = Argon2Params::new(64 * 1024, 10, 4, 32);
    assert!(argon2::validate_kdf_params(&at_cap, &KdfPolicy::default()).is_ok());
}

/// A malicious backup manifest must be rejected at `import` time, before the
/// KDF runs. This is the untrusted-source path (hostile archive).
#[test]
fn import_rejects_oversized_manifest_kdf_params() {
    use vault_native::format::import;
    use vault_native::format::manifest::VaultManifest;
    use vault_native::format::package;

    let kdf = desktop_params();
    let manifest = VaultManifest {
        version: 2,
        kdf: "argon2id".into(),
        salt: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=".into(),
        argon2_memory: 2_000_000, // ~2 GiB, way above the 64 MiB ceiling
        argon2_iterations: 100_000,
        argon2_parallelism: 64,
        document_count: 0,
    };
    let evil = package::write_with_version(package::FORMAT_VERSION_V2, &manifest, &[0u8; 12]);
    let result = import::import(&evil, "password", &kdf);
    assert!(
        matches!(result, Err(Error::InvalidKdfParameters(_))),
        "oversized manifest KDF params must be rejected before Argon2, got {result:?}"
    );
}

/// The FFI-facing `derive_key` helper performs the same check (it is the
/// shared entry point used by Android/GUI calls).
#[test]
fn ffi_derive_key_rejects_over_cap() {
    let salt = [7u8; 16];
    let res = vault_native::ffi::derive_key("pw".into(), salt.to_vec(), 64 * 1024 + 8, 2, 2);
    assert!(
        matches!(res, Err(Error::InvalidKdfParameters(_))),
        "FFI derive_key must refuse over-cap params, got {res:?}"
    );

    // verify_password and derive_backup_master_key take the same KDF args and
    // must not spend CPU on out-of-policy inputs.
    assert!(!vault_native::ffi::verify_password(
        "pw".into(),
        salt.to_vec(),
        vec![0u8; 48],
        64 * 1024 + 8,
        2,
        2
    ));
    assert!(matches!(
        vault_native::ffi::derive_backup_master_key(
            vec![0u8; 48],
            "pw".into(),
            salt.to_vec(),
            64 * 1024 + 8,
            2,
            2
        ),
        Err(Error::InvalidKdfParameters(_))
    ));
}
