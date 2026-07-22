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

impl PartialEq for Vault {
    fn eq(&self, other: &Self) -> bool {
        self.base_dir == other.base_dir
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

    pub fn import_file(&self, path: &Path) -> Result<String> {
        let file_data = std::fs::read(path)?;
        let file_name = path
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_default();
        let title = file_name.clone();
        let mime = mime_guess2::from_path(&path)
            .first_or_octet_stream()
            .to_string();
        let id = uuid::Uuid::new_v4().to_string();

        let id = self.db.import_document(
            self.base_dir.to_string_lossy().to_string(),
            id,
            title,
            file_data,
            mime,
            String::new(),
            String::new(),
            None,
        )?;
        Ok(id)
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use std::sync::Arc;

    /// Hold both Vault and its TempDir so the directory isn't dropped early.
    struct TestVault {
        _dir: tempfile::TempDir,
        vault: Vault,
    }

    impl TestVault {
        fn new() -> Self {
            let dir = tempfile::tempdir().unwrap();
            let vault = Vault::create(dir.path(), "testpass").unwrap();
            Self { _dir: dir, vault }
        }
    }

    pub fn make_test_vault() -> Arc<Vault> {
        let dir = tempfile::tempdir().unwrap();
        let vault = Vault::create(dir.path(), "testpass").unwrap();
        Arc::new(vault)
    }

    fn create_test_vault() -> Vault {
        TestVault::new().vault
    }

    fn create_test_vault_with_dir() -> TestVault {
        TestVault::new()
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

    #[test]
    fn test_import_txt_file() {
        let tv = create_test_vault_with_dir();
        let file_path = tv._dir.path().join("hello.txt");
        std::fs::write(&file_path, b"Hello, world!").unwrap();

        let id = tv.vault.import_file(&file_path).unwrap();
        assert!(!id.is_empty());

        let docs = tv.vault.list_documents().unwrap();
        assert_eq!(docs.len(), 1);
        assert_eq!(docs[0].title, "hello.txt");
        assert_eq!(docs[0].file_name, "hello.txt");
        assert!(docs[0].file_size > 0);
        assert_eq!(docs[0].mime_type, "text/plain");
    }

    // Test using Vault::create directly, then import_document
    #[test]
    fn test_vault_create_direct_insert() {
        let dir = tempfile::tempdir().unwrap();
        let vault = Vault::create(dir.path(), "testpass").unwrap();
        let id = uuid::Uuid::new_v4().to_string();
        let file_data = b"hello world".to_vec();

        vault.db.add_document_full(
            DocumentRow {
                id: id.clone(),
                title: "test.txt".into(),
                file_name: "test.txt".into(),
                mime_type: "text/plain".into(),
                file_path: format!("files/{id}"),
                file_size: 10,
                ..Default::default()
            },
            Some("content".to_string()),
        ).unwrap();

        let docs = vault.list_documents().unwrap();
        assert_eq!(docs.len(), 1);
    }

    // Direct test using DbHandle API
    #[test]
    fn test_direct_db_insert() {
        use vault_native::db::queries::DocumentRow;
        let mk = vault_native::ffi::generate_master_key();
        let dir = tempfile::tempdir().unwrap();
        let db_path = dir.path().join("test.db");

        let db = vault_native::ffi::DbHandle::create_encrypted(
            db_path.to_str().unwrap().to_string(),
            mk,
        ).unwrap();

        let id = uuid::Uuid::new_v4().to_string();
        db.add_document_full(
            DocumentRow {
                id: id.clone(),
                title: "hello.txt".into(),
                file_name: "hello.txt".into(),
                mime_type: "text/plain".into(),
                file_path: "files/test".into(),
                file_size: 13,
                ..Default::default()
            },
            Some("hello".to_string()),
        ).unwrap();

        let docs = db.list_documents().unwrap();
        assert_eq!(docs.len(), 1);
        assert_eq!(docs[0].title, "hello.txt");
    }

    #[test]
    fn test_import_pdf_file_guesses_mime() {
        let tv = create_test_vault_with_dir();
        let file_path = tv._dir.path().join("doc.pdf");
        let min_pdf = &b"%PDF-1.4 fake content for testing"[..];
        std::fs::write(&file_path, min_pdf).unwrap();

        let id = tv.vault.import_file(&file_path).unwrap();
        assert!(!id.is_empty());

        let docs = tv.vault.list_documents().unwrap();
        assert_eq!(docs.len(), 1);
        assert_eq!(docs[0].title, "doc.pdf");
        assert_eq!(docs[0].mime_type, "application/pdf");
    }

    #[test]
    fn test_import_image_file_guesses_mime() {
        let tv = create_test_vault_with_dir();
        let file_path = tv._dir.path().join("photo.png");
        std::fs::write(&file_path, b"not a real png").unwrap();

        let id = tv.vault.import_file(&file_path).unwrap();
        assert!(!id.is_empty());

        let docs = tv.vault.list_documents().unwrap();
        assert_eq!(docs.len(), 1);
        assert_eq!(docs[0].mime_type, "image/png");
    }

    #[test]
    fn test_import_multiple_files() {
        let tv = create_test_vault_with_dir();

        for i in 0..3 {
            let path = tv._dir.path().join(format!("doc_{i}.txt"));
            std::fs::write(&path, format!("content {i}")).unwrap();
            tv.vault.import_file(&path).unwrap();
        }

        let docs = tv.vault.list_documents().unwrap();
        assert_eq!(docs.len(), 3);
    }

    #[test]
    fn test_import_nonexistent_file_fails() {
        let vault = create_test_vault();
        let result = vault.import_file(Path::new("/nonexistent/file.pdf"));
        assert!(result.is_err());
    }
}
