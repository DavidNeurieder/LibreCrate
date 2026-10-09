use crate::crypto::aes_kw;
use crate::crypto::argon2::{self, Argon2Params};
use crate::crypto::secrets::DerivedKey;
use crate::error::{Error, Result};

/// Derive a user key from the vault password and salt (Argon2id).
/// This is used to wrap/unwrap the master key via AES-KW.
///
/// Returns a `DerivedKey` whose heap buffer is zeroized when dropped, so call
/// sites that only need the key transiently (unwrap, verify) don't leave
/// password-derived bytes in memory.
pub fn derive_user_key(password: &str, salt: &[u8], params: &Argon2Params) -> Option<DerivedKey> {
    argon2::derive_key_and_zero(password, salt, params)
}

/// Unwrap the master key from the wrapped key stored on disk.
pub fn derive_backup_master_key(
    wrapped_key: &[u8],
    password: &str,
    salt: &[u8],
    params: &Argon2Params,
) -> Result<Vec<u8>> {
    let user_key = derive_user_key(password, salt, params).ok_or(Error::AuthenticationFailed)?;
    aes_kw::unwrap(wrapped_key, &user_key.0).ok_or(Error::AuthenticationFailed)
}

/// Verify that a password can successfully unwrap the stored master key.
pub fn verify_password(
    password: &str,
    salt: &[u8],
    wrapped_key: &[u8],
    params: &Argon2Params,
) -> bool {
    derive_backup_master_key(wrapped_key, password, salt, params).is_ok()
}

/// Compute the local master key (same as backup master key for CLI, but could differ if
/// the device has its own wrapping — kept for interface compatibility).
pub fn get_local_master_key(
    password: &str,
    salt: &[u8],
    wrapped_key: &[u8],
    params: &Argon2Params,
) -> Option<Vec<u8>> {
    derive_backup_master_key(wrapped_key, password, salt, params).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_key() -> Vec<u8> {
        (0..32).map(|i| i as u8).collect()
    }

    #[test]
    fn test_derive_and_wrap_unwrap() {
        let master_key = make_key();
        let password = "test-password";
        let salt = b"saltsalt12345678";
        let params = Argon2Params::default();

        let user_key = derive_user_key(password, salt, &params).unwrap();
        let wrapped = aes_kw::wrap(&user_key.0, &master_key).unwrap();
        let unwrapped = derive_backup_master_key(&wrapped, password, salt, &params).unwrap();
        assert_eq!(unwrapped, master_key);
    }

    #[test]
    fn test_wrong_password_fails() {
        let master_key = make_key();
        let password = "correct";
        let wrong = "wrong";
        let salt = b"saltsalt12345678";
        let params = Argon2Params::default();

        let user_key = derive_user_key(password, salt, &params).unwrap();
        let wrapped = aes_kw::wrap(&user_key.0, &master_key).unwrap();
        assert!(derive_backup_master_key(&wrapped, wrong, salt, &params).is_err());
        assert!(!verify_password(wrong, salt, &wrapped, &params));
        assert!(verify_password(password, salt, &wrapped, &params));
    }
}
