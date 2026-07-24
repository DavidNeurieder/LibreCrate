use std::path::PathBuf;

use crate::commands::util;

/// Holds vault state for the lifetime of a session.
/// The master key is derived once at construction and reused for all commands.
pub struct Session {
    pub vault_dir: PathBuf,
    pub password: String,
    pub master_key: Vec<u8>,
}

impl Session {
    /// Open an existing vault. Derives the master key once and validates the vault.
    pub fn open(vault_dir: PathBuf, password: &str) -> anyhow::Result<Self> {
        if !vault_dir.join("databases").join("librecrate.db").exists() {
            anyhow::bail!(
                "no vault found at {}",
                vault_dir.display()
            );
        }
        let master_key = util::resolve_master_key(&vault_dir, password)?;
        Ok(Self {
            vault_dir,
            password: password.to_string(),
            master_key,
        })
    }

    /// Open the encrypted database using the cached master key.
    pub fn open_db(&self) -> anyhow::Result<rusqlite::Connection> {
        let db_path = self.db_path();
        let conn = vault_native::db::schema::open_encrypted(
            db_path.to_str().ok_or_else(|| anyhow::anyhow!("invalid vault path"))?,
            &self.master_key,
        )?;
        Ok(conn)
    }

    /// Path to the encrypted database file.
    pub fn db_path(&self) -> PathBuf {
        self.vault_dir.join("databases").join("librecrate.db")
    }


}
