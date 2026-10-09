use crate::format::manifest::VaultManifest;

pub const MAGIC: [u8; 16] = *b"LIBCRATE_VAULT\0\0";
pub const MAGIC_LEN: usize = 16;
pub const VERSION_LEN: usize = 4;
pub const MANIFEST_LEN_LEN: usize = 4;

/// The only vault container formats LibreCrate writes/understands.
/// New exports always use v2 (the authenticated envelope). v1 backups remain
/// importable through the hardened Milestone-1 path.
pub const FORMAT_VERSION_V1: u32 = 1;
pub const FORMAT_VERSION_V2: u32 = 2;
pub const FORMAT_VERSION: u32 = FORMAT_VERSION_V2;

pub struct VaultPackage {
    pub version: u32,
    pub manifest: VaultManifest,
    pub encrypted_blob: Vec<u8>,
}

/// Read and validate the vault container header.
///
/// The header is attacker-controlled plaintext metadata, so every length that
/// drives allocations is bounded here: the magic must match, the manifest must
/// be fully present, the manifest JSON must be under `max_manifest_size`, and
/// the emergent blob length is checked.
pub fn read(data: &[u8], max_manifest_size: usize) -> Option<VaultPackage> {
    if data.len() < MAGIC_LEN + VERSION_LEN + MANIFEST_LEN_LEN {
        return None;
    }
    if data[..MAGIC_LEN] != MAGIC {
        return None;
    }
    let version = u32::from_le_bytes(data[MAGIC_LEN..MAGIC_LEN + VERSION_LEN].try_into().ok()?);
    let manifest_len = u32::from_le_bytes(
        data[MAGIC_LEN + VERSION_LEN..MAGIC_LEN + VERSION_LEN + MANIFEST_LEN_LEN]
            .try_into()
            .ok()?,
    ) as usize;

    if manifest_len > max_manifest_size {
        return None;
    }

    let manifest_start = MAGIC_LEN + VERSION_LEN + MANIFEST_LEN_LEN;
    let manifest_end = manifest_start.saturating_add(manifest_len);
    if data.len() < manifest_end {
        return None;
    }

    let manifest_str = std::str::from_utf8(&data[manifest_start..manifest_end]).ok()?;
    let manifest = VaultManifest::from_json(manifest_str)?;
    let encrypted_blob = data[manifest_end..].to_vec();

    Some(VaultPackage {
        version,
        manifest,
        encrypted_blob,
    })
}

pub fn write(manifest: &VaultManifest, encrypted_blob: &[u8]) -> Vec<u8> {
    write_with_version(FORMAT_VERSION, manifest, encrypted_blob)
}

/// Serialize a vault container with an explicit header version.
///
/// `FORMAT_VERSION_V1` is used to build legacy containers for
/// backward-compatibility tests; production writes always use v2.
pub fn write_with_version(
    version: u32,
    manifest: &VaultManifest,
    encrypted_blob: &[u8],
) -> Vec<u8> {
    let manifest_json = manifest.to_json();
    let manifest_bytes = manifest_json.as_bytes();
    let manifest_len = manifest_bytes.len() as u32;

    let mut out = Vec::with_capacity(
        MAGIC_LEN + VERSION_LEN + MANIFEST_LEN_LEN + manifest_bytes.len() + encrypted_blob.len(),
    );
    out.extend_from_slice(&MAGIC);
    out.extend_from_slice(&version.to_le_bytes());
    out.extend_from_slice(&manifest_len.to_le_bytes());
    out.extend_from_slice(manifest_bytes);
    out.extend_from_slice(encrypted_blob);
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::format::manifest::VaultManifest;

    fn sample_manifest() -> VaultManifest {
        VaultManifest {
            version: 1,
            kdf: "argon2id".into(),
            salt: "dGVzdA==".into(),
            argon2_memory: 19456,
            argon2_iterations: 2,
            argon2_parallelism: 2,
            document_count: 5,
        }
    }

    #[test]
    fn test_read_write_roundtrip() {
        let manifest = sample_manifest();
        let blob = b"encrypted-data-here".to_vec();
        let data = write(&manifest, &blob);
        let pkg = read(&data, 256 * 1024).unwrap();
        assert_eq!(pkg.version, FORMAT_VERSION);
        assert_eq!(pkg.manifest, manifest);
        assert_eq!(pkg.encrypted_blob, blob);
    }

    #[test]
    fn test_bad_magic_rejected() {
        let data = b"NOT_THE_MAGIC_BYTES_HERE".to_vec();
        assert!(read(&data, 256 * 1024).is_none());
    }

    #[test]
    fn test_too_short_rejected() {
        let data = b"short".to_vec();
        assert!(read(&data, 256 * 1024).is_none());
    }

    #[test]
    fn test_oversized_manifest_rejected() {
        let manifest = sample_manifest();
        let data = write(&manifest, b"blob");
        // Tiny cap rejects the legitimate manifest
        assert!(read(&data, 8).is_none());
    }
}
