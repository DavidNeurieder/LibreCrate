//! Robustness regression suite (PR 4): corrupt, truncated, or fuzzed vault
//! input must fail cleanly — never panic, never partially import, and never
//! authenticate tampered data.

use base64::Engine;
use vault_native::crypto::argon2::Argon2Params;
use vault_native::error::Error;
use vault_native::format::export;
use vault_native::format::import::import;
use vault_native::types::KeyValue;

fn sample_export() -> Vec<u8> {
    let kdf = Argon2Params::default();
    export::export(
        &[
            KeyValue {
                key: "docs/a.txt".into(),
                value: b"alpha".to_vec(),
            },
            KeyValue {
                key: "docs/b.txt".into(),
                value: b"beta".to_vec(),
            },
        ],
        Some(b"db-file"),
        "correct horse battery staple",
        &[KeyValue {
            key: "key.bin".into(),
            value: vec![1u8; 40],
        }],
        &kdf,
    )
    .unwrap()
    .data
}

#[test]
fn roundtrip_control() {
    let kdf = Argon2Params::default();
    let contents = import(&sample_export(), "correct horse battery staple", &kdf).unwrap();
    assert_eq!(contents.files.len(), 2);
    assert_eq!(contents.db_file.as_deref(), Some(&b"db-file"[..]));
}

/// Wrong password must fail authentication — this is the badge-check on the
/// integrity of the envelope.
#[test]
fn wrong_password_fails_authentication() {
    let kdf = Argon2Params::default();
    let result = import(&sample_export(), "wrong password", &kdf);
    assert!(matches!(result, Err(Error::AuthenticationFailed)));
}

/// Every truncation point of a valid backup must yield a clean error, not a
/// panic, and never a successful import.
#[test]
fn truncations_fail_cleanly() {
    let kdf = Argon2Params::default();
    let full = sample_export();
    let mut cuts = vec![0, 1, full.len() / 4, full.len() / 2, full.len() - 1];
    cuts.dedup();
    for cut in cuts {
        let truncated = &full[..cut];
        let result = import(truncated, "correct horse battery staple", &kdf);
        if let Ok(contents) = &result {
            panic!("truncating at {cut} unexpectedly succeeded: {contents:?}");
        }
    }
    // Fully empty input.
    assert!(import(&[], "pw", &kdf).is_err());
}

/// Bit flips in the header and payload must never authenticate or import — at
/// most a ~2^-128 chance of a flip surviving the GCM tag silently, which is
/// the design guarantee we pin here.
#[test]
fn bitflips_fail_authentication_or_parse() {
    let kdf = Argon2Params::default();
    let full = sample_export();

    // Deterministic sweep: flip byte i of the payload for a spread of i.
    let mut flips = vec![20, 40, 60, 80, 100, 120, 200, 400, 800];
    flips.extend((0..8).map(|k| full.len() - 1 - 16 * k)); // tail ciphertext
    flips.retain(|&i| i < full.len());
    flips.dedup();
    for i in flips {
        let mut mutated = full.clone();
        mutated[i] ^= 0x01;
        let result = import(&mutated, "correct horse battery staple", &kdf);
        assert!(
            matches!(
                result,
                Err(Error::AuthenticationFailed | Error::Format(_) | Error::InvalidData(_))
            ),
            "flip at byte {i} unexpectedly succeeded or panicked: {result:?}"
        );
    }
}

/// Pure garbage (valid magic, meaningless rest) is rejected without panicking.
#[test]
fn garbage_after_valid_magic_fails_cleanly() {
    let kdf = Argon2Params::default();
    let mut junk = vec![0u8; 512];
    junk[..16].copy_from_slice(b"LIBCRATE_VAULT\0\0");
    let result = import(&junk, "pw", &kdf);
    assert!(result.is_err());
}

/// The manifest's document count is bound to the actual extracted file count;
/// a header lying about it is rejected (v1) — and for v2 it is additionally
/// authenticated via AAD.
#[test]
fn manifest_document_count_mismatch_rejected() {
    use vault_native::crypto::aes_gcm;
    use vault_native::crypto::argon2;
    use vault_native::format::manifest::VaultManifest;
    use vault_native::format::package;

    let password = "pw";
    let kdf = Argon2Params::default();
    let salt = argon2::generate_salt();
    let zip = {
        use std::io::Write;
        let mut buf = Vec::new();
        {
            let mut writer = zip::ZipWriter::new(std::io::Cursor::new(&mut buf));
            for (name, data) in [
                ("files/a.txt", b"a".to_vec()),
                ("files/b.txt", b"b".to_vec()),
            ] {
                writer
                    .start_file::<&str, ()>(name, zip::write::FileOptions::default())
                    .unwrap();
                writer.write_all(&data).unwrap();
            }
            writer.finish().unwrap();
        }
        buf
    };
    let key = argon2::derive_key(password, &salt, &kdf).unwrap();
    let (iv, ct) = aes_gcm::encrypt_bytes(&zip, &key).unwrap();
    let blob: Vec<u8> = iv.into_iter().chain(ct).collect();
    // v1 container: no AAD. Tells lies: claims 1 document, extracts 2.
    let manifest = VaultManifest {
        version: package::FORMAT_VERSION_V1,
        kdf: "argon2id".into(),
        salt: base64::engine::general_purpose::STANDARD.encode(&salt),
        argon2_memory: kdf.memory_cost,
        argon2_iterations: kdf.iterations,
        argon2_parallelism: kdf.parallelism,
        document_count: 1,
    };
    let v1 = package::write_with_version(package::FORMAT_VERSION_V1, &manifest, &blob);
    let result = import(&v1, password, &kdf);
    assert!(
        matches!(result, Err(Error::Format(_))),
        "lying document count must be rejected, got {result:?}"
    );
}
