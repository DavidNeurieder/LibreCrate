use aes::cipher::generic_array::GenericArray;
use aes::cipher::{BlockDecrypt, BlockEncrypt, KeyInit};
use aes::{Aes128, Aes192, Aes256};

const DEFAULT_IV: [u8; 8] = [0xA6; 8];

pub fn generate_master_key() -> Vec<u8> {
    use rand::RngCore;
    let mut key = vec![0u8; 32];
    rand::rngs::OsRng.fill_bytes(&mut key);
    key
}

fn select_cipher(key: &[u8]) -> Option<AesCipher> {
    match key.len() {
        16 => Aes128::new_from_slice(key).ok().map(AesCipher::Aes128),
        24 => Aes192::new_from_slice(key).ok().map(AesCipher::Aes192),
        32 => Aes256::new_from_slice(key).ok().map(AesCipher::Aes256),
        _ => None,
    }
}

enum AesCipher {
    Aes128(Aes128),
    Aes192(Aes192),
    Aes256(Aes256),
}

impl AesCipher {
    fn encrypt_block(&self, block: &mut GenericArray<u8, aes::cipher::typenum::U16>) {
        match self {
            Self::Aes128(c) => c.encrypt_block(block),
            Self::Aes192(c) => c.encrypt_block(block),
            Self::Aes256(c) => c.encrypt_block(block),
        }
    }

    fn decrypt_block(&self, block: &mut GenericArray<u8, aes::cipher::typenum::U16>) {
        match self {
            Self::Aes128(c) => c.decrypt_block(block),
            Self::Aes192(c) => c.decrypt_block(block),
            Self::Aes256(c) => c.decrypt_block(block),
        }
    }
}

pub fn wrap(kek: &[u8], plaintext: &[u8]) -> Option<Vec<u8>> {
    if !plaintext.len().is_multiple_of(8) || plaintext.len() < 16 {
        return None;
    }
    let cipher = select_cipher(kek)?;

    let n = plaintext.len() / 8;
    let mut buf = vec![0u8; (n + 1) * 8];
    buf[..8].copy_from_slice(&DEFAULT_IV);
    buf[8..].copy_from_slice(plaintext);

    for j in 0..6 {
        for i in 0..n {
            let offset = (i + 1) * 8;
            let mut block = GenericArray::default();
            block.as_mut_slice()[..8].copy_from_slice(&buf[..8]);
            block.as_mut_slice()[8..].copy_from_slice(&buf[offset..offset + 8]);
            cipher.encrypt_block(&mut block);

            let t = j * n + i + 1;
            buf[..8].copy_from_slice(&block.as_slice()[..8]);
            buf[offset..offset + 8].copy_from_slice(&block.as_slice()[8..]);

            for (k, byte) in buf.iter_mut().take(8).enumerate() {
                *byte ^= ((t >> (56 - k * 8)) & 0xFF) as u8;
            }
        }
    }
    Some(buf)
}

pub fn unwrap(wrapped: &[u8], kek: &[u8]) -> Option<Vec<u8>> {
    if !wrapped.len().is_multiple_of(8) || wrapped.len() < 24 {
        return None;
    }
    let cipher = select_cipher(kek)?;

    let n = (wrapped.len() / 8) - 1;
    let mut buf = wrapped.to_vec();

    for j in (0..6).rev() {
        for i in (0..n).rev() {
            let offset = (i + 1) * 8;
            let t = j * n + i + 1;

            for (k, byte) in buf.iter_mut().take(8).enumerate() {
                *byte ^= ((t >> (56 - k * 8)) & 0xFF) as u8;
            }

            let mut block = GenericArray::default();
            block.as_mut_slice()[..8].copy_from_slice(&buf[..8]);
            block.as_mut_slice()[8..].copy_from_slice(&buf[offset..offset + 8]);
            cipher.decrypt_block(&mut block);

            buf[..8].copy_from_slice(&block.as_slice()[..8]);
            buf[offset..offset + 8].copy_from_slice(&block.as_slice()[8..]);
        }
    }

    if buf[..8] != DEFAULT_IV {
        return None;
    }
    Some(buf[8..].to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_wrap_unwrap_roundtrip() {
        let key = hex::decode("00112233445566778899AABBCCDDEEFF000102030405060708090A0B0C0D0E0F")
            .unwrap();
        let plaintext = hex::decode("00112233445566778899AABBCCDDEEFF").unwrap();
        let wrapped = wrap(&key, &plaintext).unwrap();
        let unwrapped = unwrap(&wrapped, &key).unwrap();
        assert_eq!(unwrapped, plaintext);
    }

    #[test]
    fn test_wrong_key_fails() {
        let key = hex::decode("00112233445566778899AABBCCDDEEFF000102030405060708090A0B0C0D0E0F")
            .unwrap();
        let wrong_key =
            hex::decode("FFEEDDCCBBAA99887766554433221100FFEEDDCCBBAA99887766554433221100")
                .unwrap();
        let plaintext = hex::decode("00112233445566778899AABBCCDDEEFF").unwrap();
        let wrapped = wrap(&key, &plaintext).unwrap();
        assert!(unwrap(&wrapped, &wrong_key).is_none());
    }

    #[test]
    fn test_aes192_kek() {
        let kek = hex::decode("000102030405060708090A0B0C0D0E0F1011121314151617")
            .unwrap();
        let plaintext = hex::decode("00112233445566778899AABBCCDDEEFF").unwrap();
        let wrapped = wrap(&kek, &plaintext).unwrap();
        assert_eq!(wrapped.len(), 24);
        let unwrapped = unwrap(&wrapped, &kek).unwrap();
        assert_eq!(unwrapped, plaintext);
    }

    #[test]
    fn test_aes128_kek() {
        let kek = hex::decode("000102030405060708090A0B0C0D0E0F").unwrap();
        let plaintext = hex::decode("00112233445566778899AABBCCDDEEFF").unwrap();
        let wrapped = wrap(&kek, &plaintext).unwrap();
        assert_eq!(wrapped.len(), 24);
        let unwrapped = unwrap(&wrapped, &kek).unwrap();
        assert_eq!(unwrapped, plaintext);
    }

    #[test]
    fn test_aes256_kek() {
        let kek = hex::decode("00112233445566778899AABBCCDDEEFF000102030405060708090A0B0C0D0E0F")
            .unwrap();
        let plaintext = hex::decode("00112233445566778899AABBCCDDEEFF").unwrap();
        let wrapped = wrap(&kek, &plaintext).unwrap();
        assert_eq!(wrapped.len(), 24);
        let unwrapped = unwrap(&wrapped, &kek).unwrap();
        assert_eq!(unwrapped, plaintext);
    }
}
