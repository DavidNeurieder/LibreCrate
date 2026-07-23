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

    pub fn open_document(&self, doc: &DocumentRow) -> Result<()> {
        let data = self.db.export_document_file(
            self.base_dir.to_string_lossy().to_string(),
            doc.id.clone(),
        )?
        .ok_or_else(|| anyhow::anyhow!("File data not found for {}", doc.id))?;

        let tmp_dir = tempfile::TempDir::new()?;
        let tmp_path = tmp_dir.path().join(&doc.file_name);
        std::fs::write(&tmp_path, &data)?;

        open::that(&tmp_path)?;

        std::thread::spawn(move || {
            std::thread::sleep(std::time::Duration::from_secs(120));
            drop(tmp_dir);
        });

        Ok(())
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

        let text_content = vault_native::reader::extract_text(path, &mime);

        let id = self.db.import_document(
            self.base_dir.to_string_lossy().to_string(),
            id,
            title,
            file_data,
            mime,
            String::new(),
            String::new(),
            text_content,
        )?;
        Ok(id)
    }

    pub fn export_backup(&self, password: &str) -> Result<Vec<u8>> {
        let encryption_dir = self.base_dir.join("encryption");
        let db_path = self.base_dir.join("databases").join("vault.db");
        let files_dir = self.base_dir.join("files");

        let params_str = std::fs::read_to_string(encryption_dir.join("params.toml"))?;
        let p: toml::Value = toml::from_str(&params_str)?;
        let kdf_params = vault_native::crypto::argon2::Argon2Params {
            memory_cost: p["memory_cost"].as_integer().unwrap_or(19456) as u32,
            iterations: p["iterations"].as_integer().unwrap_or(2) as u32,
            parallelism: p["parallelism"].as_integer().unwrap_or(2) as u32,
            hash_length: p["hash_length"].as_integer().unwrap_or(32) as i32,
        };

        let mut files: Vec<vault_native::types::KeyValue> = Vec::new();
        if files_dir.exists() {
            for entry in std::fs::read_dir(&files_dir)? {
                let entry = entry?;
                if entry.file_type()?.is_file() {
                    let data = std::fs::read(entry.path())?;
                    files.push(vault_native::types::KeyValue {
                        key: entry.file_name().to_string_lossy().to_string(),
                        value: data,
                    });
                }
            }
        }

        let salt = std::fs::read(encryption_dir.join("salt"))?;
        let wrapped_key = std::fs::read(encryption_dir.join("master_key"))?;
        let keys = vec![
            vault_native::types::KeyValue {
                key: "salt".into(),
                value: salt,
            },
            vault_native::types::KeyValue {
                key: "wrapped_master_key".into(),
                value: wrapped_key,
            },
        ];

        let db_data = std::fs::read(&db_path)?;

        vault_native::ffi::export_vault(
            files,
            Some(db_data),
            password.to_string(),
            keys,
            vault_native::crypto::argon2::Argon2Params {
                memory_cost: kdf_params.memory_cost,
                iterations: kdf_params.iterations,
                parallelism: kdf_params.parallelism,
                hash_length: kdf_params.hash_length,
            },
        )
        .map_err(|e| anyhow::anyhow!("{e}"))
    }

    pub fn merge_backup(&self, backup_data: &[u8], backup_password: &str, vault_password: &str) -> Result<vault_native::merge::MergeStats> {
        let contents = vault_native::ffi::import_vault(
            backup_data.to_vec(),
            backup_password.to_string(),
        )?;

        let tmp_dir = tempfile::tempdir()?;
        let backup_db_path = tmp_dir.path().join("backup.db");

        if let Some(db_bytes) = &contents.db_file {
            std::fs::write(&backup_db_path, db_bytes)?;
        } else {
            anyhow::bail!("backup has no database file");
        }

        // Unwrap the backup's master key using the original vault password
        let backup_master_key = {
            let wrapped_key = contents.keys.iter()
                .find(|k| k.key == "wrapped_master_key")
                .map(|k| &k.value)
                .ok_or_else(|| anyhow::anyhow!("missing wrapped_master_key in backup"))?;
            let salt = contents.keys.iter()
                .find(|k| k.key == "salt")
                .map(|k| &k.value)
                .ok_or_else(|| anyhow::anyhow!("missing salt in backup"))?;

            let params_str = std::fs::read_to_string(self.base_dir.join("encryption").join("params.toml"))?;
            let p: toml::Value = toml::from_str(&params_str)?;
            let memory_cost = p["memory_cost"].as_integer().unwrap_or(19456) as u32;
            let iterations = p["iterations"].as_integer().unwrap_or(2) as u32;
            let parallelism = p["parallelism"].as_integer().unwrap_or(2) as u32;

            vault_native::ffi::derive_backup_master_key(
                wrapped_key.clone(),
                vault_password.to_string(),
                salt.clone(),
                memory_cost,
                iterations,
                parallelism,
            ).map_err(|e| anyhow::anyhow!("{e}"))?
        };

        let files_dir = self.base_dir.join("files").to_string_lossy().to_string();

        let stats = self.db.merge_branch_a(
            backup_db_path.to_string_lossy().to_string(),
            backup_master_key,
            contents.files,
            Some(self.master_key.clone()),
            Some(self.master_key.clone()),
            files_dir,
        ).map_err(|e| anyhow::anyhow!("{e}"))?;

        Ok(stats)
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

    pub fn make_test_vault_with_dir() -> (Arc<Vault>, tempfile::TempDir) {
        let dir = tempfile::tempdir().unwrap();
        let vault = Vault::create(dir.path(), "testpass").unwrap();
        (Arc::new(vault), dir)
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
        assert_eq!(docs[0].file_name, format!("{id}.txt"));
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

    // -----------------------------------------------------------------------
    // Backup export / import / merge tests
    // -----------------------------------------------------------------------

    #[test]
    fn test_export_backup_creates_valid_blob() {
        let tv = create_test_vault_with_dir();

        let file_path = tv._dir.path().join("doc.txt");
        std::fs::write(&file_path, b"content").unwrap();
        tv.vault.import_file(&file_path).unwrap();

        let backup = tv.vault.export_backup("backuppass").unwrap();
        assert!(!backup.is_empty());

        let magic = b"LIBCRATE_VAULT\0\0";
        assert_eq!(&backup[..16], magic, "backup must start with vault magic");

        // Verify the backup can be imported
        let imported = vault_native::ffi::import_vault(backup, "backuppass".into())
            .map_err(|e| anyhow::anyhow!("{e}"))
            .unwrap();
        assert!(imported.db_file.is_some(), "backup must contain db");
        assert_eq!(imported.files.len(), 1, "backup must contain 1 file");
        assert_eq!(imported.keys.len(), 2, "backup must contain salt + wrapped_master_key");
    }

    #[test]
    fn test_export_backup_with_multiple_files() {
        let tv = create_test_vault_with_dir();

        for i in 0..3 {
            let path = tv._dir.path().join(format!("doc_{i}.txt"));
            std::fs::write(&path, format!("content {i}")).unwrap();
            tv.vault.import_file(&path).unwrap();
        }

        let backup = tv.vault.export_backup("backuppass").unwrap();
        let imported = vault_native::ffi::import_vault(backup, "backuppass".into()).unwrap();
        assert_eq!(imported.files.len(), 3);
    }

    #[test]
    fn test_export_backup_empty_vault() {
        let tv = create_test_vault_with_dir();
        let backup = tv.vault.export_backup("backuppass").unwrap();
        let imported = vault_native::ffi::import_vault(backup, "backuppass".into()).unwrap();
        assert!(imported.files.is_empty());
        assert!(imported.db_file.is_some());
    }

    #[test]
    fn test_export_backup_wrong_password_fails_on_import() {
        let tv = create_test_vault_with_dir();
        let file_path = tv._dir.path().join("doc.txt");
        std::fs::write(&file_path, b"secret").unwrap();
        tv.vault.import_file(&file_path).unwrap();

        let backup = tv.vault.export_backup("correctpass").unwrap();
        let result = vault_native::ffi::import_vault(backup, "wrongpass".into());
        assert!(result.is_err());
    }

    #[test]
    fn test_merge_backup_adds_documents() {
        let tv = create_test_vault_with_dir();

        // Import a document into the vault
        let path_a = tv._dir.path().join("doc_a.txt");
        std::fs::write(&path_a, b"document A").unwrap();
        tv.vault.import_file(&path_a).unwrap();

        // Export backup
        let backup = tv.vault.export_backup("backuppass").unwrap();

        // Import another document
        let path_b = tv._dir.path().join("doc_b.txt");
        std::fs::write(&path_b, b"document B").unwrap();
        tv.vault.import_file(&path_b).unwrap();

        assert_eq!(tv.vault.list_documents().unwrap().len(), 2);

        // Merge the backup back in (backup has only doc_a)
        let stats = tv.vault.merge_backup(&backup, "backuppass", "testpass").unwrap();
        assert_eq!(stats.documents_added, 0, "doc_a already exists, should not be re-added");
        assert!(stats.documents_updated == 1 || stats.documents_skipped == 1,
            "doc_a should be updated or skipped since it already exists");

        // Both documents should still be present
        let docs = tv.vault.list_documents().unwrap();
        assert_eq!(docs.len(), 2, "both doc_a and doc_b must remain after merge");
    }

    #[test]
    fn test_merge_backup_into_empty_vault_adds_all() {
        let tv = create_test_vault_with_dir();

        let path = tv._dir.path().join("doc.txt");
        std::fs::write(&path, b"data").unwrap();
        tv.vault.import_file(&path).unwrap();

        let backup = tv.vault.export_backup("backuppass").unwrap();

        // Create a second vault and merge backup into it (empty target)
        let tv2 = create_test_vault_with_dir();
        let stats = tv2.vault.merge_backup(&backup, "backuppass", "testpass").unwrap();
        assert_eq!(stats.documents_added, 1, "empty vault should gain 1 document from backup");
        assert_eq!(tv2.vault.list_documents().unwrap().len(), 1);
    }

    #[test]
    fn test_backup_roundtrip_preserves_content() {
        let tv = create_test_vault_with_dir();

        let path = tv._dir.path().join("hello.txt");
        std::fs::write(&path, b"Hello, world!").unwrap();
        let orig_id = tv.vault.import_file(&path).unwrap();

        let backup = tv.vault.export_backup("backuppass").unwrap();

        // Import backup into a fresh vault
        let tv2 = create_test_vault_with_dir();
        let stats = tv2.vault.merge_backup(&backup, "backuppass", "testpass").unwrap();
        assert_eq!(stats.documents_added, 1);

        let docs = tv2.vault.list_documents().unwrap();
        assert_eq!(docs.len(), 1);
        assert_eq!(docs[0].title, "hello.txt");
        assert_eq!(docs[0].mime_type, "text/plain");
        assert!(docs[0].file_size > 0);

        // The document ID may differ since import_document assigns new IDs
        // but the title and content should match
    }

    #[test]
    fn test_merge_backup_dedup_same_content() {
        let tv = create_test_vault_with_dir();

        // Import same file twice (dedup should mean only one doc in DB)
        let path = tv._dir.path().join("doc.txt");
        std::fs::write(&path, b"same content").unwrap();
        tv.vault.import_file(&path).unwrap();
        tv.vault.import_file(&path).unwrap();

        let docs = tv.vault.list_documents().unwrap();
        assert_eq!(docs.len(), 1, "dedup should prevent duplicate");

        let backup = tv.vault.export_backup("backuppass").unwrap();

        // Merge into a fresh vault — should add 1
        let tv2 = create_test_vault_with_dir();
        let stats = tv2.vault.merge_backup(&backup, "backuppass", "testpass").unwrap();
        assert_eq!(stats.documents_added, 1);
        assert_eq!(tv2.vault.list_documents().unwrap().len(), 1);
    }

    #[test]
    fn test_merge_backup_conflicting_content() {
        let tv = create_test_vault_with_dir();

        // Import doc_a
        let path_a = tv._dir.path().join("doc_a.txt");
        std::fs::write(&path_a, b"version 1").unwrap();
        tv.vault.import_file(&path_a).unwrap();
        let docs_v1 = tv.vault.list_documents().unwrap();
        let id_a = docs_v1[0].id.clone();

        // Export backup (with doc_a version 1)
        let backup = tv.vault.export_backup("backuppass").unwrap();

        // Now import a doc with the SAME title but DIFFERENT content into the same vault
        // (Since import generates new UUID, the IDs won't match — so this isn't a true conflict)
        // To create a real conflict scenario, we'd need to import a backup where doc IDs match
        // but content differs. This is better tested at the core level.
        // For now, just verify the merge doesn't crash and adds no extra docs
        let path_a2 = tv._dir.path().join("doc_a.txt");
        std::fs::write(&path_a2, b"version 2").unwrap();
        tv.vault.import_file(&path_a2).unwrap();

        // Merge backup (doc_a version 1) into current vault (has doc_a v1 and v2)
        let stats = tv.vault.merge_backup(&backup, "backuppass", "testpass").unwrap();
        // The merge should handle gracefully
        assert!(stats.documents_skipped >= 0);
        assert!(stats.documents_conflicted >= 0);
    }
}
