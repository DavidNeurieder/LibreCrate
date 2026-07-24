use std::path::Path;
use vault_native::crypto::argon2::Argon2Params;
use vault_native::kdf;

/// Resolve the master key from either a hex key string or a vault directory + password.
/// When `key_hex` is `Some`, it's decoded directly.
/// When `password` is `Some`, the vault dir's `encryption/` folder is read and the key derived.
/// Handles both `wrapped_master_key` and legacy `master_key` file names.
pub fn resolve_master_key(
    vault_dir: &Path,
    key_hex: Option<&str>,
    password: Option<&str>,
) -> anyhow::Result<Vec<u8>> {
    match (key_hex, password) {
        (Some(kh), _) => Ok(hex::decode(kh)?),
        (_, Some(pw)) => {
            let enc = vault_dir.join("encryption");
            let salt = std::fs::read(enc.join("salt"))?;
            let wrapped = std::fs::read(enc.join("wrapped_master_key"))
                .or_else(|_| std::fs::read(enc.join("master_key")))?;
            let params_toml = std::fs::read_to_string(enc.join("params.toml"))
                .ok()
                .and_then(|s| {
                    let p: toml::Value = toml::from_str(&s).ok()?;
                    Some(Argon2Params {
                        memory_cost: p.get("memory_cost")?.as_integer()? as u32,
                        iterations: p.get("iterations")?.as_integer()? as u32,
                        parallelism: p.get("parallelism")?.as_integer()? as u32,
                        hash_length: p.get("hash_length")?.as_integer()? as i32,
                    })
                })
                .unwrap_or_else(Argon2Params::default);
            let mk = kdf::derive_backup_master_key(&wrapped, pw, &salt, &params_toml)?;
            Ok(mk)
        }
        (None, None) => anyhow::bail!("Either --key or --password must be provided"),
    }
}
