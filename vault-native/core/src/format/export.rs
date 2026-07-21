use base64::Engine;
use crate::crypto::aes_gcm;
use crate::crypto::argon2::{self, Argon2Params};
use crate::error::{Error, Result};
use crate::format::manifest::VaultManifest;
use crate::format::package;
use std::io::Write;
use zip::ZipWriter;
use zip::write::FileOptions;
use crate::types::KeyValue;

pub struct ExportedVault {
    pub data: Vec<u8>,
}

pub fn export(
    files: &[KeyValue],
    db_file: Option<&[u8]>,
    vault_password: &str,
    keys: &[KeyValue],
    kdf_params: &Argon2Params,
) -> Result<ExportedVault> {
    let salt = argon2::generate_salt();
    let container_key =
        argon2::derive_key(vault_password, &salt, kdf_params).ok_or(Error::Kdf(
            "container key derivation failed".into(),
        ))?;

    // Build ZIP entries
    let mut zip_entries: Vec<(String, Vec<u8>)> = Vec::new();

    for kv in keys {
        zip_entries.push((format!("keys/{}", kv.key), kv.value.clone()));
    }

    if let Some(db) = db_file {
        zip_entries.push(("db/librecrate.db".into(), db.to_vec()));
    }

    for kv in files {
        zip_entries.push((format!("files/{}", kv.key), kv.value.clone()));
    }

    // Create plain ZIP blob
    let plain_zip = create_zip_blob(&zip_entries)?;

    // Encrypt ZIP with container key
    let (iv, ciphertext) =
        aes_gcm::encrypt_bytes(&plain_zip, &container_key).ok_or(Error::Crypto("encryption failed".into()))?;
    let encrypted_blob: Vec<u8> = iv.into_iter().chain(ciphertext).collect();

    let document_count = files.len() as u32;
    let manifest = VaultManifest {
        version: 1,
        kdf: "argon2id".into(),
        salt: base64::engine::general_purpose::STANDARD.encode(&salt),
        argon2_memory: kdf_params.memory_cost,
        argon2_iterations: kdf_params.iterations,
        argon2_parallelism: kdf_params.parallelism,
        document_count,
    };

    let data = package::write(&manifest, &encrypted_blob);
    Ok(ExportedVault { data })
}

fn create_zip_blob(entries: &[(String, Vec<u8>)]) -> Result<Vec<u8>> {
    let mut buf = Vec::new();
    {
        let mut zip = ZipWriter::new(std::io::Cursor::new(&mut buf));
        for (name, data) in entries {
            zip.start_file::<&str, ()>(name, FileOptions::default())
                .map_err(|e| Error::Compression(e.to_string()))?;
            zip.write_all(data)
                .map_err(|e| Error::Compression(e.to_string()))?;
        }
        zip.finish()
            .map_err(|e| Error::Compression(e.to_string()))?;
    }
    Ok(buf)
}

/// Bootstrap a vault layout at `dir`:
///   encryption/wrapped_master_key, encryption/salt,
///   databases/librecrate.db (encrypted with schema),
///   files/
/// Returns the generated master key.
pub fn create_vault_layout(dir: &std::path::Path, password: &str) -> Result<Vec<u8>> {
    use crate::crypto::aes_kw;

    let master_key = aes_kw::generate_master_key();
    let salt = argon2::generate_salt();
    let params = Argon2Params::default();
    let user_key =
        argon2::derive_key(password, &salt, &params)
            .ok_or(Error::Kdf("user key derivation failed".into()))?;
    let wrapped_master_key =
        aes_kw::wrap(&user_key, &master_key)
            .ok_or(Error::Crypto("master key wrap failed".into()))?;

    let enc_dir = dir.join("encryption");
    std::fs::create_dir_all(&enc_dir)?;
    std::fs::write(enc_dir.join("wrapped_master_key"), &wrapped_master_key)?;
    std::fs::write(enc_dir.join("salt"), &salt)?;

    let db_dir = dir.join("databases");
    let db_path = db_dir.join("librecrate.db");
    std::fs::create_dir_all(&db_dir)?;
    crate::db::schema::create_encrypted_db(
        db_path.to_str().ok_or(Error::InvalidData("path".into()))?,
        &master_key,
    )?;

    std::fs::create_dir_all(dir.join("files"))?;

    Ok(master_key)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::format::import;

    #[test]
    fn test_export_import_roundtrip() {
        let password = "test-password";
        let kdf_params = Argon2Params::default();
        let master_key = crate::crypto::aes_gcm::generate_key();
        let salted = b"test-salt-1234567";

        let user_key =
            crate::crypto::argon2::derive_key(password, salted, &kdf_params).unwrap();
        let wrapped_master_key =
            crate::crypto::aes_kw::wrap(&user_key, &master_key).unwrap();
        let db_data = b"fake-db-content".to_vec();
        let file_data = b"hello-world".to_vec();

        let exported = export(
            &[KeyValue { key: "test.txt".into(), value: file_data.clone() }],
            Some(&db_data),
            password,
            &[
                KeyValue { key: "wrapped_master_key".into(), value: wrapped_master_key.clone() },
                KeyValue { key: "salt".into(), value: salted.to_vec() },
            ],
            &kdf_params,
        )
        .unwrap();

        // Verify structure
        assert!(exported.data.len() > 16 + 4 + 4 + 10);
        assert_eq!(&exported.data[..16], b"LIBCRATE_VAULT\0\0");

        // Re-import and verify
        let imported = import::import(&exported.data, password, &kdf_params).unwrap();
        assert_eq!(imported.db_file, Some(db_data));
        assert_eq!(imported.files.len(), 1);
        assert_eq!(imported.files[0].value, file_data);

        let imported_wmk = imported
            .keys
            .iter()
            .find(|kv| kv.key == "wrapped_master_key")
            .map(|kv| kv.value.clone())
            .unwrap();

        let unwrapped =
            crate::crypto::aes_kw::unwrap(&imported_wmk, &user_key).unwrap();
        assert_eq!(unwrapped, master_key);
    }
}
