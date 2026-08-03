use anyhow::Result;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use vault_native::db::fts::FtsSnippetResult;
use vault_native::db::queries::DocumentRow;
use vault_native::ffi::DbHandle;

#[derive(Clone)]
pub struct Vault {
    pub db: Arc<DbHandle>,
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
        let db_path = dir.join("databases").join("librecrate.db");

        let salt = std::fs::read(encryption_dir.join("salt"))?;
        let wrapped_key = std::fs::read(encryption_dir.join("wrapped_master_key"))
            .or_else(|_| std::fs::read(encryption_dir.join("master_key")))?;
        let params = vault_native::vault_ops::parse_kdf_params_from_toml(
            &std::fs::read_to_string(encryption_dir.join("params.toml"))?,
        )?;

        let kek = vault_native::ffi::derive_key(
            password.to_string(),
            salt,
            params.memory_cost,
            params.iterations,
            params.parallelism,
        )?;

        let master_key = vault_native::ffi::unwrap_key(wrapped_key, kek)?;
        let db = DbHandle::open_encrypted(db_path.to_str().unwrap().to_string(), master_key)?;

        Ok(Self {
            db: Arc::new(db),
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
        let memory_cost = 19 * 1024u32;
        let iterations = 2u32;
        let parallelism = 2u32;

        let kek = vault_native::ffi::derive_key(
            password.to_string(),
            salt.clone(),
            memory_cost,
            iterations,
            parallelism,
        )?;

        let wrapped_key = vault_native::ffi::wrap_key(kek, master_key.clone())?;

        std::fs::write(encryption_dir.join("salt"), &salt)?;
        std::fs::write(encryption_dir.join("wrapped_master_key"), &wrapped_key)?;
        std::fs::write(
            encryption_dir.join("params.toml"),
            format!(
                "memory_cost = {memory_cost}\niterations = {iterations}\nparallelism = {parallelism}\nhash_length = 32\n",
            ),
        )?;

        let db_path = db_dir.join("librecrate.db");
        let db = DbHandle::create_encrypted(
            db_path.to_str().unwrap().to_string(),
            master_key,
        )?;

        Ok(Self {
            db: Arc::new(db),
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

    pub fn delete_document(&self, id: &str) -> Result<()> {
        self.db.delete_document_full(
            self.base_dir.to_string_lossy().to_string(),
            id.to_string(),
        )?;
        Ok(())
    }

    pub fn rename_document(&self, id: &str, new_title: &str) -> Result<()> {
        self.db.update_document_title(id.to_string(), new_title.to_string())?;
        Ok(())
    }

    pub fn load_thumbnail(&self, id: &str) -> Option<Vec<u8>> {
        self.db
            .load_thumbnail(self.base_dir.to_string_lossy().to_string(), id.to_string())
            .ok()
            .flatten()
    }

    pub fn import_file(&self, path: &Path) -> Result<String> {
        let file_data = std::fs::read(path)?;
        let file_name = path
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_default();
        let title = file_name.clone();
        let mut mime = mime_guess2::from_path(&path)
            .first_or_octet_stream()
            .to_string();
        if mime == "application/octet-stream"
            && path.extension().is_some_and(|e| e.eq_ignore_ascii_case("fb2"))
        {
            mime = "application/x-fictionbook+xml".to_string();
        }
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

    pub fn export_backup(&self, password: &str) -> Result<Vec<u8>> {
        Ok(vault_native::vault_ops::export_vault_dir(
            &self.base_dir,
            password,
        )?)
    }

    /// Full restore from backup — replaces the vault entirely (Branch B).
    /// Matches Android's `restore_to_layout` behavior.
    /// After this call, the vault on disk belongs to whoever created the backup.
    /// The caller must re-open the vault with the appropriate password.
    pub fn restore_backup(&self, backup_data: &[u8], backup_password: &str) -> Result<()> {
        Ok(vault_native::vault_ops::restore_backup_to_dir(
            backup_data,
            backup_password,
            &self.base_dir,
        )?)
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
        assert_eq!(imported.keys.len(), 3, "backup must contain salt + master_key + params.toml");
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
    fn test_delete_document_removes_from_vault() {
        let tv = create_test_vault_with_dir();

        let path = tv._dir.path().join("to_delete.txt");
        std::fs::write(&path, b"delete me").unwrap();
        let id = tv.vault.import_file(&path).unwrap();

        let docs = tv.vault.list_documents().unwrap();
        assert_eq!(docs.len(), 1);
        assert_eq!(docs[0].id, id);

        tv.vault.delete_document(&id).unwrap();

        let docs = tv.vault.list_documents().unwrap();
        assert_eq!(docs.len(), 0);
    }

    #[test]
    fn test_delete_document_file_removed_from_disk() {
        let tv = create_test_vault_with_dir();

        let path = tv._dir.path().join("to_delete.txt");
        std::fs::write(&path, b"delete me").unwrap();
        let id = tv.vault.import_file(&path).unwrap();

        // Find the stored file path
        let docs = tv.vault.list_documents().unwrap();
        let stored_path = tv._dir.path().join(&docs[0].file_path);
        assert!(stored_path.exists());

        tv.vault.delete_document(&id).unwrap();

        assert!(!stored_path.exists());
    }

    #[test]
    fn test_delete_nonexistent_document_is_noop() {
        let vault = create_test_vault();
        // Should not error — delete_document_full returns Ok(false) for nonexistent IDs
        let result = vault.delete_document("nonexistent_id");
        assert!(result.is_ok());
    }

    // -----------------------------------------------------------------------
    // Phone <-> GUI backup compatibility, exercised through the REAL GUI code
    // (Vault::open / Vault::export_backup) against phone-produced data.
    // -----------------------------------------------------------------------

    const PHONE_MEMORY_COST: u32 = 16_384;
    const PHONE_ITERATIONS: u32 = 3;
    const PHONE_PARALLELISM: u32 = 2;
    const PHONE_PASSWORD: &str = "phone-vault-pass";

    fn phone_params() -> vault_native::crypto::argon2::Argon2Params {
        vault_native::crypto::argon2::Argon2Params::new(
            PHONE_MEMORY_COST,
            PHONE_ITERATIONS,
            PHONE_PARALLELISM,
            32,
        )
    }

    /// The exact `params.toml` written by `buildParamsToml()` on the phone.
    fn phone_params_toml() -> &'static str {
        "memory_cost = 16384\niterations = 3\nparallelism = 2\nhash_length = 32\n"
    }

    /// Build the phone-style vault layout exactly as `RustKeyManager` +
    /// `VaultRepository` produce it. Returns the master key.
    fn build_phone_vault(root: &Path, password: &str) -> Vec<u8> {
        use vault_native::crypto::aes_kw;
        use vault_native::crypto::argon2;
        use vault_native::db::schema::create_encrypted_db;
        use vault_native::db::storage::import_document;

        let enc_dir = root.join("encryption");
        let db_dir = root.join("databases");
        let files_dir = root.join("files");
        std::fs::create_dir_all(&enc_dir).unwrap();
        std::fs::create_dir_all(&db_dir).unwrap();
        std::fs::create_dir_all(&files_dir).unwrap();

        let salt = argon2::generate_salt();
        let master_key = aes_kw::generate_master_key();
        let kek = argon2::derive_key(password, &salt, &phone_params()).unwrap();
        let wrapped = aes_kw::wrap(&kek, &master_key).unwrap();

        std::fs::write(enc_dir.join("salt"), &salt).unwrap();
        std::fs::write(enc_dir.join("wrapped_master_key"), &wrapped).unwrap();
        std::fs::write(enc_dir.join("params.toml"), phone_params_toml()).unwrap();

        let db_path = db_dir.join("librecrate.db");
        let conn = create_encrypted_db(db_path.to_str().unwrap(), &master_key).unwrap();
        for (id, title, content) in [
            ("phone-doc-1", "Phone Doc One", &b"phone file one"[..]),
            ("phone-doc-2", "Phone Doc Two", &b"phone file two"[..]),
        ] {
            import_document(
                &conn,
                root,
                id,
                title,
                content,
                "text/plain",
                "",
                "",
                None,
                Some(&master_key),
            )
            .unwrap();
        }
        drop(conn);

        master_key
    }

    #[test]
    fn test_phone_vault_unlocks_in_real_gui() {
        let dir = tempfile::tempdir().unwrap();
        let _master_key = build_phone_vault(dir.path(), PHONE_PASSWORD);

        // The phone vault (params.toml 16384/3/2) must unlock through the real
        // GUI code path — this is what failed with "key unwrap failed" before.
        let vault = Vault::open(dir.path(), PHONE_PASSWORD).unwrap();

        let docs = vault.list_documents().unwrap();
        assert_eq!(docs.len(), 2);
        assert_eq!(docs[0].id, "phone-doc-1");
        assert_eq!(docs[0].title, "Phone Doc One");
        assert_eq!(docs[1].id, "phone-doc-2");
        assert_eq!(docs[1].title, "Phone Doc Two");

        let file = vault
            .db
            .export_document_file(vault.base_dir.to_string_lossy().to_string(), "phone-doc-1".into())
            .unwrap()
            .expect("stored file must be present");
        assert_eq!(file, b"phone file one");
    }

    #[test]
    fn test_phone_vault_wrong_password_fails_to_open() {
        let dir = tempfile::tempdir().unwrap();
        build_phone_vault(dir.path(), PHONE_PASSWORD);
        assert!(Vault::open(dir.path(), "wrong").is_err());
    }

    #[test]
    fn test_phone_vault_with_stale_desktop_params_fails_to_open() {
        // Regression guard for the original bug: a leftover desktop
        // `params.toml` (19456/2/2) next to phone-wrapped keys must NOT
        // silently unlock.
        let dir = tempfile::tempdir().unwrap();
        build_phone_vault(dir.path(), PHONE_PASSWORD);
        std::fs::write(
            dir.path().join("encryption").join("params.toml"),
            "memory_cost = 19456\niterations = 2\nparallelism = 2\nhash_length = 32\n",
        )
        .unwrap();
        assert!(Vault::open(dir.path(), PHONE_PASSWORD).is_err());
    }

    #[test]
    fn test_gui_backup_restores_into_phone_layout_and_unlocks() {
        let tv = create_test_vault_with_dir();
        let file_path = tv._dir.path().join("desktop-doc.txt");
        std::fs::write(&file_path, b"desktop file content").unwrap();
        tv.vault.import_file(&file_path).unwrap();

        let backup = tv.vault.export_backup("backuppass").unwrap();

        // Restore into a phone-style layout via the shared function the app calls.
        let phone_dir = tempfile::tempdir().unwrap();
        let enc = phone_dir.path().join("encryption");
        let db = phone_dir.path().join("databases");
        let files = phone_dir.path().join("files");
        std::fs::create_dir_all(&enc).unwrap();
        std::fs::create_dir_all(&db).unwrap();
        std::fs::create_dir_all(&files).unwrap();
        vault_native::vault_ops::restore_backup_to_dirs(&backup, "backuppass", &enc, &db, &files)
            .unwrap();

        // The restore is self-describing: it carries desktop defaults.
        let params = vault_native::vault_ops::parse_kdf_params_from_toml(
            &std::fs::read_to_string(enc.join("params.toml")).unwrap(),
        )
        .unwrap();
        assert_eq!(params.memory_cost, 19_456);
        assert_eq!(params.iterations, 2);
        assert_eq!(params.parallelism, 2);

        // Real GUI unlock of the restored phone-layout vault. The backup keeps
        // the original wrapped master key, so the vault opens with the ORIGINAL
        // vault password ("testpass"), not the backup password.
        let opened = Vault::open(phone_dir.path(), "testpass").unwrap();
        let docs = opened.list_documents().unwrap();
        assert_eq!(docs.len(), 1);
        assert_eq!(docs[0].title, "desktop-doc.txt");
        let file = opened
            .db
            .export_document_file(
                opened.base_dir.to_string_lossy().to_string(),
                docs[0].id.clone(),
            )
            .unwrap()
            .expect("stored file must be present");
        assert_eq!(file, b"desktop file content");

        // The backup password is only used to decrypt the manifest, not to lock
        // the restored vault.
        assert!(Vault::open(phone_dir.path(), "backuppass").is_err());

        // Regression guard: pretending this is an OLD phone vault (hardcoded
        // 16384/3/2 params.toml) must fail to unlock the desktop-wrapped key.
        std::fs::write(enc.join("params.toml"), phone_params_toml()).unwrap();
        assert!(Vault::open(phone_dir.path(), "testpass").is_err());
    }
}
