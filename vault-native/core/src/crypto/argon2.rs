use argon2::{Algorithm, Argon2, Params, Version};
use rand::rngs::OsRng;

pub const DEFAULT_MEMORY_COST: u32 = 19456;
pub const DEFAULT_ITERATIONS: u32 = 2;
pub const DEFAULT_PARALLELISM: u32 = 2;
pub const DEFAULT_HASH_LENGTH: i32 = 32;

/// Absolute safety ceiling for Argon2 parameters accepted from untrusted
/// sources (backup manifests, `params.toml` in an imported backup, FFI calls).
///
/// These values are separate from the normal parameters (`Argon2Params`) and
/// are deliberately generous so legitimately-created vaults and backups are
/// never rejected — the goal is to stop a hostile archive from requesting
/// unbounded memory/CPU, not to tighten derived-vault policy.
///
/// Values live above both normal parameter sets:
///   - desktop/GUI/CLI: 19456 KiB / 2 iterations / 2 parallelism  (~27 ms/derivation)
///   - Android:         16384 KiB / 3 iterations / 2 parallelism  (~32 ms/derivation)
///
/// Measured on a mid-range x86_64 dev machine (release build, `cargo bench -p
/// vault-native --bench kdf`), the ceiling costs ~437 ms + 64 MiB per hostile
/// invocation — bounded, and ~16× the legitimate desktop cost, so abuse is
/// expensive while normal operation is unaffected.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct KdfPolicy {
    pub max_memory_kib: u32,
    pub max_iterations: u32,
    pub max_parallelism: u32,
}

impl Default for KdfPolicy {
    fn default() -> Self {
        Self {
            max_memory_kib: 64 * 1024, // 64 MiB
            max_iterations: 10,
            max_parallelism: 4,
        }
    }
}

/// Validate that untrusted KDF parameters are within the absolute ceiling
/// BEFORE Argon2 is executed. Zero values and obvious overflow are rejected
/// here so callers never reach `Params::new` with attacker-controlled inputs.
pub fn validate_kdf_params(params: &Argon2Params, policy: &KdfPolicy) -> crate::error::Result<()> {
    if params.memory_cost == 0 || params.iterations == 0 || params.parallelism == 0 {
        return Err(crate::error::Error::InvalidKdfParameters(
            "memory/iterations/parallelism must be non-zero".into(),
        ));
    }
    if params.memory_cost > policy.max_memory_kib {
        return Err(crate::error::Error::InvalidKdfParameters(format!(
            "memory cost {} KiB exceeds limit {} KiB",
            params.memory_cost, policy.max_memory_kib
        )));
    }
    if params.iterations > policy.max_iterations {
        return Err(crate::error::Error::InvalidKdfParameters(format!(
            "iterations {} exceed limit {}",
            params.iterations, policy.max_iterations
        )));
    }
    if params.parallelism > policy.max_parallelism {
        return Err(crate::error::Error::InvalidKdfParameters(format!(
            "parallelism {} exceeds limit {}",
            params.parallelism, policy.max_parallelism
        )));
    }
    Ok(())
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Argon2Params {
    pub memory_cost: u32,
    pub iterations: u32,
    pub parallelism: u32,
    pub hash_length: i32,
}

impl Default for Argon2Params {
    fn default() -> Self {
        Self {
            memory_cost: DEFAULT_MEMORY_COST,
            iterations: DEFAULT_ITERATIONS,
            parallelism: DEFAULT_PARALLELISM,
            hash_length: DEFAULT_HASH_LENGTH,
        }
    }
}

impl Argon2Params {
    pub fn new(memory_cost: u32, iterations: u32, parallelism: u32, hash_length: i32) -> Self {
        Self {
            memory_cost,
            iterations,
            parallelism,
            hash_length,
        }
    }
}

pub fn generate_salt() -> Vec<u8> {
    use rand::RngCore;
    let mut salt = vec![0u8; 16];
    OsRng.fill_bytes(&mut salt);
    salt
}

pub fn derive_key(password: &str, salt: &[u8], params: &Argon2Params) -> Option<Vec<u8>> {
    let p = Params::new(
        params.memory_cost,
        params.iterations,
        params.parallelism,
        Some(params.hash_length as usize),
    )
    .ok()?;
    let argon2 = Argon2::new(Algorithm::Argon2id, Version::V0x13, p);
    let mut key = vec![0u8; params.hash_length as usize];
    argon2
        .hash_password_into(password.as_bytes(), salt, &mut key)
        .ok()?;
    Some(key)
}

/// Derive an Argon2id key into a `DerivedKey` wrapper that zeroizes its heap
/// buffer when dropped. Every derivation that unlocks or unwraps key material
/// should use this so password-derived bytes don't linger in memory.
pub fn derive_key_and_zero(
    password: &str,
    salt: &[u8],
    params: &Argon2Params,
) -> Option<super::secrets::DerivedKey> {
    use super::secrets::DerivedKey;
    let p = Params::new(
        params.memory_cost,
        params.iterations,
        params.parallelism,
        Some(params.hash_length as usize),
    )
    .ok()?;
    let argon2 = Argon2::new(Algorithm::Argon2id, Version::V0x13, p);
    let mut key = DerivedKey(vec![0u8; params.hash_length as usize]);
    argon2
        .hash_password_into(password.as_bytes(), salt, &mut key.0)
        .ok()?;
    Some(key)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_derive_key_default_params() {
        let password = "test-password";
        let salt = b"0123456789abcdef";
        let key = derive_key(password, salt, &Argon2Params::default());
        assert!(key.is_some());
        assert_eq!(key.unwrap().len(), 32);
    }

    #[test]
    fn test_different_salts_different_keys() {
        let password = "same-password";
        let k1 = derive_key(password, b"saltsalt12345678", &Argon2Params::default());
        let k2 = derive_key(password, b"DIFFERENTsalt123", &Argon2Params::default());
        assert!(k1.is_some());
        assert!(k2.is_some());
        assert_ne!(k1, k2);
    }

    #[test]
    fn test_generate_salt_is_random() {
        let s1 = generate_salt();
        let s2 = generate_salt();
        assert_ne!(s1, s2);
        assert_eq!(s1.len(), 16);
    }
}
