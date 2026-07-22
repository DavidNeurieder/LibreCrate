use keyring::Entry;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum KeychainError {
    #[error("keyring unavailable: {0}")]
    Unavailable(String),
    #[error("credential not found: {key}")]
    NotFound { key: String },
    #[error("keyring write failed: {0}")]
    Write(#[source] keyring::Error),
    #[error("keyring read failed: {0}")]
    Read(#[source] keyring::Error),
    #[error("keyring delete failed: {0}")]
    Delete(#[source] keyring::Error),
}

pub struct SecureStore {
    service: String,
    available: bool,
}

impl SecureStore {
    pub fn new(service: &str) -> Self {
        let available = Entry::new(service, "__probe__").is_ok();
        Self {
            service: service.to_string(),
            available,
        }
    }

    pub fn is_available(&self) -> bool {
        self.available
    }

    pub fn set(&self, key: &str, value: &str) -> Result<(), KeychainError> {
        if !self.available {
            return Err(KeychainError::Unavailable(
                "keyring not initialized".into(),
            ));
        }
        let entry = Entry::new(&self.service, key).map_err(KeychainError::Write)?;
        entry.set_password(value).map_err(KeychainError::Write)?;
        Ok(())
    }

    pub fn get(&self, key: &str) -> Result<Option<String>, KeychainError> {
        if !self.available {
            return Err(KeychainError::Unavailable(
                "keyring not initialized".into(),
            ));
        }
        let entry = Entry::new(&self.service, key).map_err(KeychainError::Read)?;
        match entry.get_password() {
            Ok(val) => Ok(Some(val)),
            Err(keyring::Error::NoEntry) => Ok(None),
            Err(e) => Err(KeychainError::Read(e)),
        }
    }

    pub fn delete(&self, key: &str) -> Result<(), KeychainError> {
        if !self.available {
            return Err(KeychainError::Unavailable(
                "keyring not initialized".into(),
            ));
        }
        let entry = Entry::new(&self.service, key).map_err(KeychainError::Delete)?;
        entry.delete_credential().map_err(KeychainError::Delete)?;
        Ok(())
    }
}
