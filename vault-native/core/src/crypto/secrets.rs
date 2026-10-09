//! Secret material wrappers that zeroize when dropped.
//!
//! UniFFI records cannot carry `ZeroizeOnDrop` types, so these wrappers are
//! strictly internal. Across the FFI boundary we use plain byte arrays and
//! zero the temporary buffers we still control (and document that the Kotlin
//! side owns zeroization of `ByteArray` copies).
//!
//! Ownership chain:
//! `Password → Argon2 → DerivedKey → AES-KW → MasterKey`

use zeroize::{Zeroize, ZeroizeOnDrop};

/// A master key (normally 32 random bytes). Zeroized on drop.
#[derive(Zeroize, ZeroizeOnDrop)]
pub struct MasterKey(pub Vec<u8>);

impl MasterKey {
    pub fn as_slice(&self) -> &[u8] {
        &self.0
    }
}

/// A key derived from the vault password via Argon2id. Zeroized on drop.
#[derive(Zeroize, ZeroizeOnDrop)]
pub struct DerivedKey(pub Vec<u8>);

impl DerivedKey {
    pub fn as_slice(&self) -> &[u8] {
        &self.0
    }

    /// Transfer the raw bytes out, e.g. to cross an FFI boundary into a
    /// byte array. The caller owns zeroization from here on.
    pub fn into_inner_on_drop(self) -> Vec<u8> {
        let mut inner = std::mem::ManuallyDrop::new(self);
        std::mem::take(&mut inner.0)
    }
}

/// Zeroize a plain byte buffer in place (handy for FFI-boundary temporaries).
pub fn zeroize_bytes(data: &mut [u8]) {
    data.zeroize();
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The `Zeroize` derive must wipe the buffer when `zeroize()` is called;
    /// `ZeroizeOnDrop` wires the same wipe into `Drop`. We verify the wipe
    /// behavior deterministically through the safe API rather than reading
    /// freed memory.
    #[test]
    fn derived_key_zeroizes() {
        let original: Vec<u8> = (0..32).map(|i| i as u8).collect();
        let mut key = DerivedKey(original);
        assert_eq!(key.as_slice(), &(0..32).collect::<Vec<u8>>()[..]);
        key.zeroize();
        assert!(
            key.as_slice().iter().all(|&b| b == 0),
            "DerivedKey buffer not zeroized: {:02X?}",
            key.as_slice()
        );
    }

    #[test]
    fn master_key_zeroizes() {
        let original: Vec<u8> = (0..32).map(|i| (i * 7) as u8).collect();
        let mut key = MasterKey(original);
        key.zeroize();
        assert!(
            key.as_slice().iter().all(|&b| b == 0),
            "MasterKey buffer not zeroized: {:02X?}",
            key.as_slice()
        );
    }

    #[test]
    fn zeroize_bytes_helper_clears() {
        let mut data = vec![1, 2, 3, 4, 5];
        zeroize_bytes(&mut data);
        assert!(data.iter().all(|&b| b == 0));
    }
}
