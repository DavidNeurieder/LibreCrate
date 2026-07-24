use std::path::Path;
use vault_native::crypto::argon2::Argon2Params;
use vault_native::kdf;

/// Resolve the master key from a vault directory and password.
pub fn resolve_master_key(vault_dir: &Path, password: &str) -> anyhow::Result<Vec<u8>> {
    let enc = vault_dir.join("encryption");
    let salt = std::fs::read(enc.join("salt"))?;
    let wrapped = std::fs::read(enc.join("wrapped_master_key"))
        .or_else(|_| std::fs::read(enc.join("master_key")))?;
    let params = match std::fs::read_to_string(enc.join("params.toml")) {
        Ok(s) => {
            let p: toml::Value = toml::from_str(&s)?;
            Argon2Params {
                memory_cost: p.get("memory_cost").and_then(|v| v.as_integer()).unwrap_or(19456) as u32,
                iterations: p.get("iterations").and_then(|v| v.as_integer()).unwrap_or(2) as u32,
                parallelism: p.get("parallelism").and_then(|v| v.as_integer()).unwrap_or(2) as u32,
                hash_length: p.get("hash_length").and_then(|v| v.as_integer()).unwrap_or(32) as i32,
            }
        }
        Err(_) => Argon2Params::default(),
    };
    let mk = kdf::derive_backup_master_key(&wrapped, password, &salt, &params)?;
    Ok(mk)
}

/// Recursively walk a directory, returning (absolute, relative) pairs.
pub fn walk_files(dir: &Path) -> anyhow::Result<Vec<(std::path::PathBuf, std::path::PathBuf)>> {
    let mut files = Vec::new();
    if !dir.exists() {
        return Ok(files);
    }
    walk_dir_recursive(dir, dir, &mut files)?;
    Ok(files)
}

fn walk_dir_recursive(
    base: &Path,
    dir: &Path,
    files: &mut Vec<(std::path::PathBuf, std::path::PathBuf)>,
) -> anyhow::Result<()> {
    for entry in std::fs::read_dir(dir)? {
        let entry = entry?;
        let path = entry.path();
        if path.is_file() {
            let rel = path.strip_prefix(base).unwrap_or(&path).to_path_buf();
            files.push((path, rel));
        } else if path.is_dir() {
            walk_dir_recursive(base, &path, files)?;
        }
    }
    Ok(())
}
