use aes_gcm::aead::{Aead, KeyInit, OsRng};
use aes_gcm::{Aes256Gcm, Nonce};
use rand::RngCore;

pub const IV_LENGTH: usize = 12;
pub const GCM_TAG_LENGTH: usize = 16;
pub const KEY_LENGTH: usize = 32;

pub fn generate_key() -> Vec<u8> {
    let mut key = vec![0u8; KEY_LENGTH];
    OsRng.fill_bytes(&mut key);
    key
}

pub fn encrypt_bytes(data: &[u8], key: &[u8]) -> Option<(Vec<u8>, Vec<u8>)> {
    encrypt_bytes_with_aad(data, key, &[])
}

pub fn decrypt_bytes(encrypted: &[u8], key: &[u8], iv: &[u8]) -> Option<Vec<u8>> {
    decrypt_bytes_with_aad(encrypted, key, iv, &[])
}

/// AES-256-GCM encrypt with associated data (AAD). Used by the Backup v2
/// envelope so that header/KDF metadata is authenticated before the payload
/// is trusted.
pub fn encrypt_bytes_with_aad(data: &[u8], key: &[u8], aad: &[u8]) -> Option<(Vec<u8>, Vec<u8>)> {
    use aes_gcm::aead::Payload;
    let cipher = Aes256Gcm::new_from_slice(key).ok()?;
    let mut iv = vec![0u8; IV_LENGTH];
    OsRng.fill_bytes(&mut iv);
    let nonce = Nonce::from_slice(&iv);
    let ciphertext = cipher.encrypt(nonce, Payload { msg: data, aad }).ok()?;
    Some((iv, ciphertext))
}

pub fn decrypt_bytes_with_aad(
    encrypted: &[u8],
    key: &[u8],
    iv: &[u8],
    aad: &[u8],
) -> Option<Vec<u8>> {
    use aes_gcm::aead::Payload;
    let cipher = Aes256Gcm::new_from_slice(key).ok()?;
    let nonce = Nonce::from_slice(iv);
    cipher
        .decrypt(
            nonce,
            Payload {
                msg: encrypted,
                aad,
            },
        )
        .ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_encrypt_decrypt_roundtrip() {
        let key = generate_key();
        let plaintext = b"Hello, LibreCrate!";
        let (iv, ciphertext) = encrypt_bytes(plaintext, &key).unwrap();
        let decrypted = decrypt_bytes(&ciphertext, &key, &iv).unwrap();
        assert_eq!(decrypted, plaintext);
    }

    #[test]
    fn test_wrong_key_fails() {
        let key = generate_key();
        let wrong_key = generate_key();
        let plaintext = b"secret data";
        let (iv, ciphertext) = encrypt_bytes(plaintext, &key).unwrap();
        assert!(decrypt_bytes(&ciphertext, &wrong_key, &iv).is_none());
    }

    #[test]
    fn test_wrong_iv_fails() {
        let key = generate_key();
        let plaintext = b"test";
        let (mut iv, ciphertext) = encrypt_bytes(plaintext, &key).unwrap();
        iv[0] ^= 1; // corrupt IV
        assert!(decrypt_bytes(&ciphertext, &key, &iv).is_none());
    }

    #[test]
    fn test_zero_length_input() {
        let key = generate_key();
        let (iv, ciphertext) = encrypt_bytes(b"", &key).unwrap();
        let decrypted = decrypt_bytes(&ciphertext, &key, &iv).unwrap();
        assert_eq!(decrypted, b"");
    }
}
