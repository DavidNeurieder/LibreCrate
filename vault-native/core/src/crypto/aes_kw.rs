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
        let kek = hex::decode("000102030405060708090A0B0C0D0E0F1011121314151617").unwrap();
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

    // -----------------------------------------------------------------------
    // RFC 3394 — known-answer tests
    //
    // These compare wrap/unwrap output against the official RFC vectors, so a
    // non-compliant implementation cannot pass by only round-tripping its own
    // output.
    // -----------------------------------------------------------------------

    fn hex(s: &str) -> Vec<u8> {
        hex::decode(s).unwrap()
    }

    /// The six RFC 3394 §4 known-answer vectors. These pin exact ciphertexts
    /// against the official spec, so a non-compliant implementation cannot
    /// pass by only round-tripping its own output.
    #[test]
    fn rfc3394_section4_known_answer_vectors() {
        let vectors = [
            // (KEK, key data, expected ciphertext) — RFC 3394 §4.1 … §4.6
            (
                "000102030405060708090A0B0C0D0E0F",
                "00112233445566778899AABBCCDDEEFF",
                "1FA68B0A8112B447AEF34BD8FB5A7B829D3E862371D2CFE5",
            ),
            (
                "000102030405060708090A0B0C0D0E0F1011121314151617",
                "00112233445566778899AABBCCDDEEFF",
                "96778B25AE6CA435F92B5B97C050AED2468AB8A17AD84E5D",
            ),
            (
                "000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F",
                "00112233445566778899AABBCCDDEEFF",
                "64E8C3F9CE0F5BA263E9777905818A2A93C8191E7D6E8AE7",
            ),
            (
                "000102030405060708090A0B0C0D0E0F1011121314151617",
                "00112233445566778899AABBCCDDEEFF0001020304050607",
                "031D33264E15D33268F24EC260743EDCE1C6C7DDEE725A936BA814915C6762D2",
            ),
            (
                "000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F",
                "00112233445566778899AABBCCDDEEFF0001020304050607",
                "A8F9BC1612C68B3FF6E6F4FBE30E71E4769C8B80A32CB8958CD5D17D6B254DA1",
            ),
            (
                "000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F",
                "00112233445566778899AABBCCDDEEFF000102030405060708090A0B0C0D0E0F",
                "28C9F404C4B810F4CBCCB35CFB87F8263F5786E2D80ED326CBC7F0E71A99F43BFB988B9B7A02DD21",
            ),
        ];
        for (kek_hex, key_data_hex, expected_hex) in vectors {
            let kek = hex(kek_hex);
            let key_data = hex(key_data_hex);
            let expected = hex(expected_hex);
            assert_eq!(wrap(&kek, &key_data).unwrap(), expected);
            assert_eq!(unwrap(&expected, &kek).unwrap(), key_data);
        }
    }

    #[test]
    fn rfc3394_wrapped_with_wrong_kek_fails() {
        let wrapped = hex("1FA68B0A8112B447AEF34BD8FB5A7B829D3E862371D2CFE5");
        let wrong_kek = hex("000102030405060708090A0B0C0D0E10");
        assert!(unwrap(&wrapped, &wrong_kek).is_none());
    }

    #[test]
    fn malformed_inputs_rejected() {
        let kek = hex("000102030405060708090A0B0C0D0E0F");
        let key_data = hex("00112233445566778899AABBCCDDEEFF");

        // KEK of an unsupported length (e.g. 20 bytes).
        let bad_kek = hex("000102030405060708090A0B0C0D0E0F1011121314");
        assert!(wrap(&bad_kek, &key_data).is_none());
        assert!(unwrap(&key_data, &bad_kek).is_none());

        // Key data shorter than one 128-bit block / not a multiple of 8.
        assert!(wrap(&kek, &hex("00112233445566")).is_none());
        assert!(wrap(&kek, &hex("00112233445566778899AABBCCDD")).is_none()); // 14 bytes

        // Wrapped input too short or corrupt.
        assert!(unwrap(&hex("0011223344556677"), &kek).is_none()); // 8 bytes
        assert!(unwrap(&hex("00112233445566778899AABBCCDDEEFFFF"), &kek).is_none()); // bad IV inside

        // Empty inputs.
        assert!(wrap(&kek, &[]).is_none());
        assert!(unwrap(&[], &kek).is_none());
        assert!(wrap(&[], &key_data).is_none());
    }

    /// Every supported key length produces a deterministic, RFC-compliant
    /// wrapper for two, three and four 64-bit blocks. (RFC 3394 requires n≥2,
    /// i.e. key data of at least 128 bits.)
    #[test]
    fn all_key_lengths_wrap_blocks() {
        let keks = [
            hex("000102030405060708090A0B0C0D0E0F"), // AES-128
            hex("000102030405060708090A0B0C0D0E0F1011121314151617"), // AES-192
            hex("000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F"), // AES-256
        ];
        for kek in &keks {
            for n_blocks in 2..=4 {
                let plaintext: Vec<u8> = (0..n_blocks * 8).map(|i| i as u8).collect();
                let wrapped = wrap(kek, &plaintext).unwrap();
                assert_eq!(wrapped.len(), (n_blocks + 1) * 8);
                assert_eq!(unwrap(&wrapped, kek).unwrap(), plaintext);
            }
        }
    }
}
