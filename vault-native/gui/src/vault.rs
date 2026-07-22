use anyhow::Result;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use vault_native::db::fts::FtsSnippetResult;
use vault_native::db::queries::{CollectionRow, DocumentRow, TagRow};
use vault_native::ffi::DbHandle;

struct Argon2Params {
    memory_cost: u32,
    iterations: u32,
    parallelism: u32,
    hash_length: i32,
}

#[derive(Clone)]
pub struct Vault {
    pub db: Arc<DbHandle>,
    pub master_key: Vec<u8>,
    pub base_dir: PathBuf,
}

impl std::fmt::Debug for Vault {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Vault")
            .field("db", &"DbHandle(..)")
            .field("base_dir", &self.base_dir)
            .finish_non_exhaustive()
    }
}

impl Vault {
    pub fn open(dir: &Path, password: &str) -> Result<Self> {
        let encryption_dir = dir.join("encryption");
        let db_path = dir.join("databases").join("vault.db");

        let salt = std::fs::read(encryption_dir.join("salt"))?;
        let wrapped_key = std::fs::read(encryption_dir.join("master_key"))?;
        let params_val: toml::Value = toml::from_str(
            &std::fs::read_to_string(encryption_dir.join("params.toml"))?,
        )?;
        let params = Argon2Params {
            memory_cost: params_val["memory_cost"].as_integer().unwrap_or(19456) as u32,
            iterations: params_val["iterations"].as_integer().unwrap_or(2) as u32,
            parallelism: params_val["parallelism"].as_integer().unwrap_or(2) as u32,
            hash_length: params_val["hash_length"].as_integer().unwrap_or(32) as i32,
        };

        let kek = vault_native::ffi::derive_key(
            password.to_string(),
            salt,
            params.memory_cost,
            params.iterations,
            params.parallelism,
        )?;

        let master_key = vault_native::ffi::unwrap_key(wrapped_key, kek)?;
        let db = DbHandle::open_encrypted(db_path.to_str().unwrap().to_string(), master_key.clone())?;

        Ok(Self {
            db: Arc::new(db),
            master_key,
            base_dir: dir.to_path_buf(),
        })
    }

    pub fn create(dir: &Path, password: &str) -> Result<Self> {
        let encryption_dir = dir.join("encryption");
        let db_dir = dir.join("databases");
        let files_dir = dir.join("files");

        std::fs::create_dir_all(&encryption_dir)?;
        std::fs::create_dir_all(&db_dir)?;
        std::fs::create_dir_all(&files_dir)?;

        let salt = vault_native::ffi::generate_salt();
        let master_key = vault_native::ffi::generate_master_key();
        let params = Argon2Params {
            memory_cost: 19 * 1024,
            iterations: 2,
            parallelism: 2,
            hash_length: 32,
        };

        let kek = vault_native::ffi::derive_key(
            password.to_string(),
            salt.clone(),
            params.memory_cost,
            params.iterations,
            params.parallelism,
        )?;

        let wrapped_key = vault_native::ffi::wrap_key(kek, master_key.clone())?;

        std::fs::write(encryption_dir.join("salt"), &salt)?;
        std::fs::write(encryption_dir.join("master_key"), &wrapped_key)?;
        std::fs::write(
            encryption_dir.join("params.toml"),
            format!(
                "memory_cost = {}\niterations = {}\nparallelism = {}\nhash_length = {}\n",
                params.memory_cost, params.iterations, params.parallelism, params.hash_length
            ),
        )?;

        let db_path = db_dir.join("vault.db");
        let db = DbHandle::create_encrypted(
            db_path.to_str().unwrap().to_string(),
            master_key.clone(),
        )?;

        Ok(Self {
            db: Arc::new(db),
            master_key,
            base_dir: dir.to_path_buf(),
        })
    }

    pub fn list_documents(&self) -> Result<Vec<DocumentRow>> {
        Ok(self.db.list_documents()?)
    }

    pub fn search_with_snippet(&self, query: &str) -> Result<Vec<FtsSnippetResult>> {
        Ok(self.db.search_documents_with_snippet(query.to_string())?)
    }

    pub fn toggle_favorite(&self, id: String) -> Result<bool> {
        let doc = self.db.get_document(id.clone())?;
        match doc {
            Some(d) => Ok(self.db.update_document(id, d.title, !d.is_favorite)?),
            None => Ok(false),
        }
    }

    pub fn list_collections(&self) -> Result<Vec<CollectionRow>> {
        Ok(self.db.list_collections()?)
    }

    pub fn list_tags(&self) -> Result<Vec<TagRow>> {
        Ok(self.db.list_tags()?)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn create_test_vault() -> Vault {
        let dir = tempfile::tempdir().unwrap();
        Vault::create(dir.path(), "testpass").unwrap()
    }

    #[test]
    fn test_open_create_roundtrip() {
        let dir = tempfile::tempdir().unwrap();
        let _vault = Vault::create(dir.path(), "testpass").unwrap();
        let opened = Vault::open(dir.path(), "testpass").unwrap();
        assert_eq!(opened.base_dir, dir.path());
    }

    #[test]
    fn test_open_wrong_password_fails() {
        let dir = tempfile::tempdir().unwrap();
        Vault::create(dir.path(), "correct").unwrap();
        let result = Vault::open(dir.path(), "wrong");
        assert!(result.is_err());
    }

    #[test]
    fn test_list_documents_empty() {
        let vault = create_test_vault();
        let docs = vault.list_documents().unwrap();
        assert!(docs.is_empty());
    }

    #[test]
    fn test_toggle_favorite_nonexistent() {
        let vault = create_test_vault();
        let result = vault.toggle_favorite("nonexistent".into()).unwrap();
        assert!(!result);
    }

    #[test]
    fn test_search_empty() {
        let vault = create_test_vault();
        let results = vault.search_with_snippet("nothing").unwrap();
        assert!(results.is_empty());
    }

    #[test]
    fn test_list_collections_empty() {
        let vault = create_test_vault();
        let cols = vault.list_collections().unwrap();
        assert!(cols.is_empty());
    }

    #[test]
    fn test_list_tags_empty() {
        let vault = create_test_vault();
        let tags = vault.list_tags().unwrap();
        assert!(tags.is_empty());
    }
}
