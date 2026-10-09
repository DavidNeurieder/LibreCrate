use crate::crypto::aes_gcm;
use crate::crypto::argon2::{self, Argon2Params};
use crate::error::{Error, Result};
use crate::format::package;
use base64::Engine;
use std::io::Read;
use zip::ZipArchive;

use crate::types::KeyValue;

#[derive(Debug, uniffi::Record)]
pub struct ImportedContents {
    pub keys: Vec<KeyValue>,
    pub db_file: Option<Vec<u8>>,
    pub files: Vec<KeyValue>,
}

pub fn import(
    vault_data: &[u8],
    vault_password: &str,
    _kdf_params: &Argon2Params,
) -> Result<ImportedContents> {
    let limits = BackupLimits::default();
    let pkg = package::read(vault_data, limits.max_manifest_size as usize)
        .ok_or(Error::Format("invalid vault format".into()))?;

    // PR 3: never hold more than the bounded package size in memory.
    if vault_data.len() as u64 > limits.max_package_size {
        return Err(Error::ResourceLimit(format!(
            "vault package {} bytes exceeds limit {}",
            vault_data.len(),
            limits.max_package_size
        )));
    }

    // Project in time / version: only accept formats we understand. v1 backups
    // are still importable through this hardened path; v2 is the authenticated
    // envelope. Anything else is rejected before any crypto work.
    match pkg.version {
        package::FORMAT_VERSION_V1 | package::FORMAT_VERSION_V2 => {}
        other => {
            return Err(Error::Format(format!(
                "unsupported vault format version {other}"
            )));
        }
    }
    // The manifest version must agree with the container version (both are
    // attacker-controlled plaintext; the v2 path additionally authenticates the
    // manifest bytes via AAD below).
    if pkg.manifest.version != pkg.version {
        return Err(Error::Format(format!(
            "manifest version {} does not match container version {}",
            pkg.manifest.version, pkg.version
        )));
    }
    if pkg.manifest.kdf != "argon2id" {
        return Err(Error::Format(format!(
            "unsupported KDF '{}' in manifest",
            pkg.manifest.kdf
        )));
    }

    let salt_bytes = base64::engine::general_purpose::STANDARD
        .decode(&pkg.manifest.salt)
        .map_err(|_| Error::InvalidData("invalid base64 salt in manifest".into()))?;

    let kdf_params = Argon2Params::new(
        pkg.manifest.argon2_memory,
        pkg.manifest.argon2_iterations,
        pkg.manifest.argon2_parallelism,
        32,
    );

    // PR 1: enforce the KDF ceiling BEFORE running Argon2, so a malicious
    // manifest cannot request unlimited memory/iterations.
    crate::crypto::argon2::validate_kdf_params(&kdf_params, &argon2::KdfPolicy::default())?;

    let container_key = argon2::derive_key(vault_password, &salt_bytes, &kdf_params)
        .ok_or(Error::AuthenticationFailed)?;

    // Split IV + ciphertext
    if pkg.encrypted_blob.len() < aes_gcm::IV_LENGTH {
        return Err(Error::InvalidData("encrypted blob too short".into()));
    }
    let iv = &pkg.encrypted_blob[..aes_gcm::IV_LENGTH];
    let ciphertext = &pkg.encrypted_blob[aes_gcm::IV_LENGTH..];

    // v2 authenticates the manifest (KDF info, salt, counts) as GCM AAD; v1
    // backups predate the envelope and decrypt without AAD.
    let manifest_aad = if pkg.version == package::FORMAT_VERSION_V2 {
        Some(pkg.manifest.to_json().into_bytes())
    } else {
        None
    };
    let plain_zip = match manifest_aad {
        Some(aad) => aes_gcm::decrypt_bytes_with_aad(ciphertext, &container_key, iv, &aad)
            .ok_or(Error::AuthenticationFailed)?,
        None => aes_gcm::decrypt_bytes(ciphertext, &container_key, iv)
            .ok_or(Error::AuthenticationFailed)?,
    };

    let contents = extract_zip(&plain_zip, &limits)?;

    // Sanity: the manifest's document count must match what was actually
    // extracted (and stay within the resource cap). A mismatch is a sign of
    // tampered or corrupt metadata.
    if contents.files.len() as u32 != pkg.manifest.document_count {
        return Err(Error::Format(format!(
            "manifest document count {} does not match extracted {}",
            pkg.manifest.document_count,
            contents.files.len()
        )));
    }
    Ok(contents)
}

#[derive(Debug, Clone, Copy)]
pub struct BackupLimits {
    pub max_package_size: u64,
    pub max_uncompressed_size: u64,
    pub max_entries: usize,
    pub max_entry_size: u64,
    pub max_manifest_size: u64,
    pub max_document_count: u32,
}

impl Default for BackupLimits {
    fn default() -> Self {
        Self {
            max_package_size: 4 * 1024 * 1024 * 1024, // 4 GiB input cap
            max_uncompressed_size: 8 * 1024 * 1024 * 1024, // 8 GiB total decompressed
            max_entries: 100_000,
            max_entry_size: 4 * 1024 * 1024 * 1024, // 4 GiB single entry
            max_manifest_size: 256 * 1024,
            max_document_count: 1_000_000,
        }
    }
}

/// Extract archive entries from a decrypted backup ZIP, enforcing the
/// resource limits in `limits` on entry count, per-entry size and cumulative
/// size. Used by `import` with the default limits; tests and fuzz harnesses
/// call it directly with tighter limits.
pub fn extract_zip(data: &[u8], limits: &BackupLimits) -> Result<ImportedContents> {
    let cursor = std::io::Cursor::new(data);
    let mut archive = ZipArchive::new(cursor).map_err(|e| Error::Compression(e.to_string()))?;

    if archive.len() > limits.max_entries {
        return Err(Error::ResourceLimit(format!(
            "archive has {} entries, limit {}",
            archive.len(),
            limits.max_entries
        )));
    }

    let mut keys = Vec::new();
    let mut db_file = None;
    let mut files = Vec::new();
    let mut total_uncompressed: u64 = 0;

    for i in 0..archive.len() {
        let mut entry = archive
            .by_index(i)
            .map_err(|e| Error::Compression(e.to_string()))?;
        let name = entry.name().to_string();

        // PR 1/backup hardening: never trust ZIP metadata for sizes. Wrap the
        // reader in a bounded `Read` so we count actual bytes consumed and cap
        // each entry regardless of what the entry header claims.
        let mut content = Vec::new();
        {
            let mut limited = (&mut entry).take(limits.max_entry_size);
            limited
                .read_to_end(&mut content)
                .map_err(|e| Error::Compression(e.to_string()))?;
        }
        // If reading stopped exactly at the cap we can't tell whether more
        // bytes remain without attempting further reads; do a probe read.
        if content.len() as u64 >= limits.max_entry_size {
            return Err(Error::ResourceLimit(format!(
                "entry {name} exceeds size limit {}",
                limits.max_entry_size
            )));
        }

        total_uncompressed = total_uncompressed.saturating_add(content.len() as u64);
        if total_uncompressed > limits.max_uncompressed_size {
            return Err(Error::ResourceLimit(format!(
                "cumulative uncompressed size {} exceeds limit {}",
                total_uncompressed, limits.max_uncompressed_size
            )));
        }

        if let Some(stripped) = name.strip_prefix("keys/") {
            let _ = crate::format::path::safe_archive_path(stripped)?;
            keys.push(KeyValue {
                key: stripped.to_string(),
                value: content,
            });
        } else if name == "db/librecrate.db" {
            db_file = Some(content);
        } else if let Some(stripped) = name.strip_prefix("files/") {
            let _ = crate::format::path::safe_archive_path(stripped)?;
            files.push(KeyValue {
                key: stripped.to_string(),
                value: content,
            });
        }
    }

    if files.len() as u32 > limits.max_document_count {
        return Err(Error::ResourceLimit(format!(
            "document count {} exceeds limit {}",
            files.len(),
            limits.max_document_count
        )));
    }

    Ok(ImportedContents {
        keys,
        db_file,
        files,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::argon2::Argon2Params;
    use crate::format::export;
    use std::io::Write;

    #[test]
    fn test_import_rejects_wrong_password() {
        let password = "correct-pw";
        let kdf = Argon2Params::default();
        let exported = export::export(&[], None, password, &[], &kdf).unwrap();

        let result = import(&exported.data, "wrong-pw", &kdf);
        assert!(result.is_err());
    }

    #[test]
    fn test_import_empty_vault() {
        let password = "empty-test";
        let kdf = Argon2Params::default();
        let exported = export::export(&[], None, password, &[], &kdf).unwrap();
        let contents = import(&exported.data, password, &kdf).unwrap();
        assert!(contents.keys.is_empty());
        assert!(contents.db_file.is_none());
        assert!(contents.files.is_empty());
    }

    /// PR 8 backward-compatibility: a v1 container (pre-authenticated-envelope)
    /// must still import through the hardened path.
    #[test]
    fn test_import_v1_backup_still_works() {
        use crate::crypto::aes_gcm;
        use crate::format::manifest::VaultManifest;
        use crate::format::package;
        use base64::Engine;

        let password = "v1-password";
        let kdf = Argon2Params::default();
        let salt = crate::crypto::argon2::generate_salt();

        // Build the payload exactly like legacy v1 exports did: AES-GCM without
        // AAD.
        let zip_entries: Vec<(String, Vec<u8>)> = vec![
            ("keys/salt".into(), salt.clone()),
            ("db/librecrate.db".into(), b"legacy-db".to_vec()),
            ("files/legacy.pdf".into(), b"legacy-pdf".to_vec()),
        ];

        let mut buf = Vec::new();
        {
            let mut zip = zip::ZipWriter::new(std::io::Cursor::new(&mut buf));
            for (name, data) in &zip_entries {
                zip.start_file::<&str, ()>(name, zip::write::FileOptions::default())
                    .unwrap();
                zip.write_all(data).unwrap();
            }
            zip.finish().unwrap();
        }
        let container_key = crate::crypto::argon2::derive_key(password, &salt, &kdf).unwrap();
        let (iv, ct) = aes_gcm::encrypt_bytes(&buf, &container_key).unwrap();
        let blob: Vec<u8> = iv.into_iter().chain(ct).collect();

        let manifest = VaultManifest {
            version: package::FORMAT_VERSION_V1,
            kdf: "argon2id".into(),
            salt: base64::engine::general_purpose::STANDARD.encode(&salt),
            argon2_memory: kdf.memory_cost,
            argon2_iterations: kdf.iterations,
            argon2_parallelism: kdf.parallelism,
            document_count: 1,
        };
        let v1_data = package::write_with_version(package::FORMAT_VERSION_V1, &manifest, &blob);

        // Header sniffing: v2 exports are version 2, this build is version 1.
        assert_ne!(&v1_data[16..20], &2u32.to_le_bytes());
        assert_eq!(&v1_data[16..20], &1u32.to_le_bytes());

        let contents = import(&v1_data, password, &kdf).unwrap();
        assert_eq!(contents.files.len(), 1);
        assert_eq!(contents.files[0].key, "legacy.pdf");
        assert_eq!(contents.db_file.as_deref(), Some(&b"legacy-db"[..]));
        // Wrong password on a v1 backup still fails authentication.
        assert!(import(&v1_data, "wrong", &kdf).is_err());
    }

    /// PR 8: a tampered v2 header must fail authentication (the manifest is
    /// bound as GCM AAD), not partially import.
    #[test]
    fn test_import_v2_tampered_header_fails_auth() {
        let password = "tamper-test";
        let kdf = Argon2Params::default();
        let exported = export::export(
            &[crate::types::KeyValue {
                key: "doc.txt".into(),
                value: b"hello".to_vec(),
            }],
            None,
            password,
            &[],
            &kdf,
        )
        .unwrap();

        // Flip the digit of the plaintext header's "documentCount":N value so
        // the header stays valid JSON/parseable but re-serializes to different
        // bytes than the ones authenticated as GCM AAD.
        let needle = b"\"documentCount\":";
        let header_start = 16 + 4 + 4; // magic + version + manifest_len
        let rel = exported.data[header_start..]
            .windows(needle.len())
            .position(|w| w == needle)
            .expect("manifest JSON contains documentCount");
        let value_pos = header_start + rel + needle.len();
        assert_eq!(exported.data[value_pos], b'1', "documentCount=1 expected");
        let mut tampered = exported.data.clone();
        tampered[value_pos] ^= 0x01; // '1' -> '0', still a valid JSON number

        // The package header still parses, but authentication must fail.
        let result = import(&tampered, password, &kdf);
        assert!(
            matches!(result, Err(Error::AuthenticationFailed)),
            "tampered v2 header should fail authentication, got {result:?}"
        );
    }

    /// PR 8: unknown header versions are rejected outright.
    #[test]
    fn test_import_unknown_version_rejected() {
        use crate::format::manifest::VaultManifest;
        use crate::format::package;
        let kdf = Argon2Params::default();
        let manifest = VaultManifest {
            version: 99,
            kdf: "argon2id".into(),
            salt: base64::engine::general_purpose::STANDARD.encode([0u8; 16]),
            argon2_memory: kdf.memory_cost,
            argon2_iterations: kdf.iterations,
            argon2_parallelism: kdf.parallelism,
            document_count: 0,
        };
        let bad = package::write_with_version(99, &manifest, &[0u8; 12]);
        let result = import(&bad, "pw", &kdf);
        assert!(matches!(result, Err(Error::Format(_))));
    }
}
