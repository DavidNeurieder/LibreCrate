use std::sync::{Arc, Mutex};

// ---------------------------------------------------------------------------
// Error type
// ---------------------------------------------------------------------------

#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum FfiError {
    #[error("Crypto error: {0}")]
    Crypto(String),
    #[error("Database error: {0}")]
    Database(String),
    #[error("IO error: {0}")]
    Io(String),
    #[error("Authentication failed")]
    AuthenticationFailed,
    #[error("Invalid data: {0}")]
    InvalidData(String),
    #[error("KDF error: {0}")]
    Kdf(String),
    #[error("Format error: {0}")]
    Format(String),
    #[error("Not found: {0}")]
    NotFound(String),
}

type FfiResult<T> = Result<T, FfiError>;

fn map_err(e: crate::error::Error) -> FfiError {
    match e {
        crate::error::Error::Crypto(s) => FfiError::Crypto(s),
        crate::error::Error::Database(e) => FfiError::Database(e.to_string()),
        crate::error::Error::Io(e) => FfiError::Io(e.to_string()),
        crate::error::Error::AuthenticationFailed => FfiError::AuthenticationFailed,
        crate::error::Error::InvalidData(s) => FfiError::InvalidData(s),
        crate::error::Error::Kdf(s) => FfiError::Kdf(s),
        crate::error::Error::Format(s) => FfiError::Format(s),
        crate::error::Error::Compression(s) => FfiError::Format(s),
        crate::error::Error::MissingKey(s) => FfiError::NotFound(s),
    }
}

// ---------------------------------------------------------------------------
// FFI record types
// ---------------------------------------------------------------------------

#[derive(uniffi::Record)]
pub struct DocumentFfi {
    pub id: String,
    pub title: String,
    pub file_name: String,
    pub mime_type: String,
    pub file_path: String,
    pub file_size: i64,
    pub page_count: i32,
    pub author: String,
    pub description: String,
    pub thumbnail_path: Option<String>,
    pub imported_at: i64,
    pub last_opened_at: i64,
    pub modified_at: i64,
    pub is_favorite: bool,
    pub is_conflict: bool,
    pub conflict_with: Option<String>,
    pub collection_id: Option<String>,
    pub encryption_iv: Option<Vec<u8>>,
    pub current_page: i32,
}

impl From<crate::db::queries::DocumentRow> for DocumentFfi {
    fn from(d: crate::db::queries::DocumentRow) -> Self {
        Self {
            id: d.id,
            title: d.title,
            file_name: d.file_name,
            mime_type: d.mime_type,
            file_path: d.file_path,
            file_size: d.file_size,
            page_count: d.page_count,
            author: d.author,
            description: d.description,
            thumbnail_path: d.thumbnail_path,
            imported_at: d.imported_at,
            last_opened_at: d.last_opened_at,
            modified_at: d.modified_at,
            is_favorite: d.is_favorite,
            is_conflict: d.is_conflict,
            conflict_with: d.conflict_with,
            collection_id: d.collection_id,
            encryption_iv: d.encryption_iv,
            current_page: d.current_page,
        }
    }
}

impl From<DocumentFfi> for crate::db::queries::DocumentRow {
    fn from(d: DocumentFfi) -> Self {
        Self {
            id: d.id,
            title: d.title,
            file_name: d.file_name,
            mime_type: d.mime_type,
            file_path: d.file_path,
            file_size: d.file_size,
            page_count: d.page_count,
            author: d.author,
            description: d.description,
            thumbnail_path: d.thumbnail_path,
            imported_at: d.imported_at,
            last_opened_at: d.last_opened_at,
            modified_at: d.modified_at,
            is_favorite: d.is_favorite,
            is_conflict: d.is_conflict,
            conflict_with: d.conflict_with,
            collection_id: d.collection_id,
            encryption_iv: d.encryption_iv,
            current_page: d.current_page,
        }
    }
}

#[derive(uniffi::Record)]
pub struct CollectionFfi {
    pub id: String,
    pub name: String,
    pub icon: String,
    pub sort_order: i32,
    pub parent_id: Option<String>,
}

impl From<crate::db::queries::CollectionRow> for CollectionFfi {
    fn from(c: crate::db::queries::CollectionRow) -> Self {
        Self {
            id: c.id,
            name: c.name,
            icon: c.icon,
            sort_order: c.sort_order,
            parent_id: c.parent_id,
        }
    }
}

#[derive(uniffi::Record)]
pub struct TagFfi {
    pub id: String,
    pub name: String,
    pub color: i64,
}

impl From<crate::db::queries::TagRow> for TagFfi {
    fn from(t: crate::db::queries::TagRow) -> Self {
        Self {
            id: t.id,
            name: t.name,
            color: t.color,
        }
    }
}

#[derive(uniffi::Record)]
pub struct SearchResultFfi {
    pub rank: f64,
    pub id: String,
    pub title: String,
}

impl From<crate::db::fts::FtsResult> for SearchResultFfi {
    fn from(r: crate::db::fts::FtsResult) -> Self {
        Self {
            rank: r.rank,
            id: r.id,
            title: r.title,
        }
    }
}

#[derive(uniffi::Record)]
pub struct MergeStatsFfi {
    pub documents_added: u32,
    pub documents_updated: u32,
    pub documents_conflicted: u32,
    pub documents_skipped: u32,
    pub collections_added: u32,
    pub tags_added: u32,
}

impl From<crate::merge::MergeStats> for MergeStatsFfi {
    fn from(m: crate::merge::MergeStats) -> Self {
        Self {
            documents_added: m.documents_added,
            documents_updated: m.documents_updated,
            documents_conflicted: m.documents_conflicted,
            documents_skipped: m.documents_skipped,
            collections_added: m.collections_added,
            tags_added: m.tags_added,
        }
    }
}

#[derive(uniffi::Record)]
pub struct Argon2ParamsFfi {
    pub memory_cost: u32,
    pub iterations: u32,
    pub parallelism: u32,
    pub hash_length: i32,
}

impl From<crate::crypto::argon2::Argon2Params> for Argon2ParamsFfi {
    fn from(p: crate::crypto::argon2::Argon2Params) -> Self {
        Self {
            memory_cost: p.memory_cost,
            iterations: p.iterations,
            parallelism: p.parallelism,
            hash_length: p.hash_length as i32,
        }
    }
}

impl From<Argon2ParamsFfi> for crate::crypto::argon2::Argon2Params {
    fn from(p: Argon2ParamsFfi) -> Self {
        Self::new(p.memory_cost, p.iterations, p.parallelism, p.hash_length as usize)
    }
}

#[derive(uniffi::Record)]
pub struct KeyValueFfi {
    pub key: String,
    pub value: Vec<u8>,
}

#[derive(uniffi::Record)]
pub struct ImportedContentsFfi {
    pub keys: Vec<KeyValueFfi>,
    pub db_file: Option<Vec<u8>>,
    pub files: Vec<KeyValueFfi>,
}

#[derive(uniffi::Record)]
pub struct EncryptedDataFfi {
    pub iv: Vec<u8>,
    pub ciphertext: Vec<u8>,
}

// ---------------------------------------------------------------------------
// DbHandle — managed SQLCipher connection
// ---------------------------------------------------------------------------

#[derive(uniffi::Object)]
pub struct DbHandle {
    inner: Arc<Mutex<rusqlite::Connection>>,
}

#[uniffi::export]
impl DbHandle {
    #[uniffi::constructor]
    pub fn open_encrypted(path: String, master_key: Vec<u8>) -> FfiResult<Self> {
        let conn = crate::db::schema::open_encrypted(&path, &master_key)
            .map_err(|e| FfiError::Database(e.to_string()))?;
        Ok(Self { inner: Arc::new(Mutex::new(conn)) })
    }

    #[uniffi::constructor]
    pub fn open_plain(path: String) -> FfiResult<Self> {
        let conn = crate::db::schema::open_plain(&path)
            .map_err(|e| FfiError::Database(e.to_string()))?;
        Ok(Self { inner: Arc::new(Mutex::new(conn)) })
    }

    #[uniffi::constructor]
    pub fn create_encrypted(path: String, master_key: Vec<u8>) -> FfiResult<Self> {
        let conn = crate::db::schema::create_encrypted_db(&path, &master_key)
            .map_err(|e| FfiError::Database(e.to_string()))?;
        Ok(Self { inner: Arc::new(Mutex::new(conn)) })
    }

    pub fn list_documents(&self) -> FfiResult<Vec<DocumentFfi>> {
        let conn = self.inner.lock().map_err(|e| FfiError::Database(e.to_string()))?;
        let docs = crate::db::queries::list_documents(&conn)
            .map_err(|e| FfiError::Database(e.to_string()))?;
        Ok(docs.into_iter().map(DocumentFfi::from).collect())
    }

    pub fn get_document(&self, id: String) -> FfiResult<Option<DocumentFfi>> {
        let conn = self.inner.lock().map_err(|e| FfiError::Database(e.to_string()))?;
        let doc = crate::db::queries::get_document(&conn, &id)
            .map_err(|e| FfiError::Database(e.to_string()))?;
        Ok(doc.map(DocumentFfi::from))
    }

    pub fn add_document(&self, doc: DocumentFfi) -> FfiResult<()> {
        let conn = self.inner.lock().map_err(|e| FfiError::Database(e.to_string()))?;
        crate::db::queries::add_document(&conn, &crate::db::queries::DocumentRow::from(doc))
            .map_err(|e| FfiError::Database(e.to_string()))
    }

    pub fn delete_document(&self, id: String) -> FfiResult<bool> {
        let conn = self.inner.lock().map_err(|e| FfiError::Database(e.to_string()))?;
        crate::db::queries::delete_document(&conn, &id)
            .map_err(|e| FfiError::Database(e.to_string()))
    }

    pub fn update_document(&self, id: String, title: String, is_favorite: bool) -> FfiResult<bool> {
        let conn = self.inner.lock().map_err(|e| FfiError::Database(e.to_string()))?;
        crate::db::queries::update_document(&conn, &id, &title, is_favorite)
            .map_err(|e| FfiError::Database(e.to_string()))
    }

    pub fn add_document_full(
        &self,
        doc: DocumentFfi,
        text_content: Option<String>,
    ) -> FfiResult<()> {
        let conn = self.inner.lock().map_err(|e| FfiError::Database(e.to_string()))?;
        crate::db::queries::add_document_full(
            &conn,
            &crate::db::queries::DocumentRow::from(doc),
            text_content.as_deref(),
        )
        .map_err(|e| FfiError::Database(e.to_string()))
    }

    pub fn list_collections(&self) -> FfiResult<Vec<CollectionFfi>> {
        let conn = self.inner.lock().map_err(|e| FfiError::Database(e.to_string()))?;
        let cols = crate::db::queries::list_collections(&conn)
            .map_err(|e| FfiError::Database(e.to_string()))?;
        Ok(cols.into_iter().map(CollectionFfi::from).collect())
    }

    pub fn list_tags(&self) -> FfiResult<Vec<TagFfi>> {
        let conn = self.inner.lock().map_err(|e| FfiError::Database(e.to_string()))?;
        let tags = crate::db::queries::list_tags(&conn)
            .map_err(|e| FfiError::Database(e.to_string()))?;
        Ok(tags.into_iter().map(TagFfi::from).collect())
    }

    pub fn search_documents(&self, query: String) -> FfiResult<Vec<SearchResultFfi>> {
        let conn = self.inner.lock().map_err(|e| FfiError::Database(e.to_string()))?;
        let results = crate::db::fts::search(&conn, &query)
            .map_err(|e| FfiError::Database(e.to_string()))?;
        Ok(results.into_iter().map(SearchResultFfi::from).collect())
    }

    pub fn rebuild_fts_index(&self) -> FfiResult<()> {
        let conn = self.inner.lock().map_err(|e| FfiError::Database(e.to_string()))?;
        crate::db::fts::rebuild_index(&conn)
            .map_err(|e| FfiError::Database(e.to_string()))
    }

    pub fn import_document(
        &self,
        base_dir: String,
        id: String,
        title: String,
        file_data: Vec<u8>,
        mime_type: String,
        author: String,
        description: String,
        text_content: Option<String>,
    ) -> FfiResult<String> {
        let conn = self.inner.lock().map_err(|e| FfiError::Database(e.to_string()))?;
        let base = std::path::Path::new(&base_dir);
        crate::db::storage::import_document(
            &conn, base, &id, &title, &file_data, &mime_type,
            &author, &description, text_content.as_deref(),
        )
        .map_err(|e| FfiError::Database(e.to_string()))
    }

    pub fn export_document_file(&self, base_dir: String, id: String) -> FfiResult<Option<Vec<u8>>> {
        let conn = self.inner.lock().map_err(|e| FfiError::Database(e.to_string()))?;
        let base = std::path::Path::new(&base_dir);
        Ok(crate::db::storage::export_document_file(&conn, base, &id))
    }

    pub fn delete_document_full(&self, base_dir: String, id: String) -> FfiResult<bool> {
        let conn = self.inner.lock().map_err(|e| FfiError::Database(e.to_string()))?;
        let base = std::path::Path::new(&base_dir);
        crate::db::storage::delete_document_full(&conn, base, &id)
            .map_err(|e| FfiError::Database(e.to_string()))
    }

    pub fn merge_branch_a(
        &self,
        backup_db_path: String,
        backup_master_key: Vec<u8>,
        files: Vec<KeyValueFfi>,
        backup_key: Option<Vec<u8>>,
        local_key: Option<Vec<u8>>,
        files_dir: String,
    ) -> FfiResult<MergeStatsFfi> {
        let conn = self.inner.lock().map_err(|e| FfiError::Database(e.to_string()))?;
        let file_pairs: Vec<(String, Vec<u8>)> = files.into_iter()
            .map(|kv| (kv.key, kv.value))
            .collect();
        let stats = crate::merge::branch_a_merge(
            &backup_db_path,
            &backup_master_key,
            &conn,
            &file_pairs,
            backup_key.as_deref(),
            local_key.as_deref(),
            std::path::Path::new(&files_dir),
        )
        .map_err(map_err)?;
        Ok(MergeStatsFfi::from(stats))
    }
}

// ---------------------------------------------------------------------------
// Free functions — Crypto
// ---------------------------------------------------------------------------

#[uniffi::export]
pub fn generate_salt() -> Vec<u8> {
    crate::crypto::argon2::generate_salt()
}

#[uniffi::export]
pub fn derive_key(
    password: String,
    salt: Vec<u8>,
    memory_cost: u32,
    iterations: u32,
    parallelism: u32,
) -> FfiResult<Vec<u8>> {
    let params = crate::crypto::argon2::Argon2Params::new(memory_cost, iterations, parallelism, 32);
    crate::crypto::argon2::derive_key(&password, &salt, &params)
        .ok_or(FfiError::Kdf("key derivation failed".into()))
}

#[uniffi::export]
pub fn encrypt_bytes(data: Vec<u8>, key: Vec<u8>) -> FfiResult<EncryptedDataFfi> {
    crate::crypto::aes_gcm::encrypt_bytes(&data, &key)
        .map(|(iv, ct)| EncryptedDataFfi { iv, ciphertext: ct })
        .ok_or(FfiError::Crypto("encryption failed".into()))
}

#[uniffi::export]
pub fn decrypt_bytes(data: EncryptedDataFfi, key: Vec<u8>) -> FfiResult<Vec<u8>> {
    crate::crypto::aes_gcm::decrypt_bytes(&data.ciphertext, &key, &data.iv)
        .ok_or(FfiError::Crypto("decryption failed".into()))
}

#[uniffi::export]
pub fn generate_master_key() -> Vec<u8> {
    crate::crypto::aes_kw::generate_master_key()
}

#[uniffi::export]
pub fn wrap_key(kek: Vec<u8>, plaintext: Vec<u8>) -> FfiResult<Vec<u8>> {
    crate::crypto::aes_kw::wrap(&kek, &plaintext)
        .ok_or(FfiError::Crypto("key wrap failed".into()))
}

#[uniffi::export]
pub fn unwrap_key(wrapped: Vec<u8>, kek: Vec<u8>) -> FfiResult<Vec<u8>> {
    crate::crypto::aes_kw::unwrap(&wrapped, &kek)
        .ok_or(FfiError::Crypto("key unwrap failed".into()))
}

#[uniffi::export]
pub fn generate_aes_key() -> Vec<u8> {
    crate::crypto::aes_gcm::generate_key()
}

// ---------------------------------------------------------------------------
// Free functions — KDF
// ---------------------------------------------------------------------------

#[uniffi::export]
pub fn verify_password(
    password: String,
    salt: Vec<u8>,
    wrapped_key: Vec<u8>,
    memory_cost: u32,
    iterations: u32,
    parallelism: u32,
) -> bool {
    let params = crate::crypto::argon2::Argon2Params::new(memory_cost, iterations, parallelism, 32);
    crate::kdf::verify_password(&password, &salt, &wrapped_key, &params)
}

#[uniffi::export]
pub fn derive_backup_master_key(
    wrapped_key: Vec<u8>,
    password: String,
    salt: Vec<u8>,
    memory_cost: u32,
    iterations: u32,
    parallelism: u32,
) -> FfiResult<Vec<u8>> {
    let params = crate::crypto::argon2::Argon2Params::new(memory_cost, iterations, parallelism, 32);
    crate::kdf::derive_backup_master_key(&wrapped_key, &password, &salt, &params)
        .map_err(map_err)
}

// ---------------------------------------------------------------------------
// Free functions — Vault format
// ---------------------------------------------------------------------------

#[uniffi::export]
pub fn export_vault(
    files: Vec<KeyValueFfi>,
    db_file: Option<Vec<u8>>,
    vault_password: String,
    keys: Vec<KeyValueFfi>,
    kdf_params: Argon2ParamsFfi,
) -> FfiResult<Vec<u8>> {
    let file_pairs: Vec<(String, Vec<u8>)> = files.into_iter()
        .map(|kv| (kv.key, kv.value))
        .collect();
    let key_pairs: Vec<(String, Vec<u8>)> = keys.into_iter()
        .map(|kv| (kv.key, kv.value))
        .collect();

    let exported = crate::format::export::export(
        &file_pairs,
        db_file.as_deref(),
        &vault_password,
        &key_pairs,
        &crate::crypto::argon2::Argon2Params::from(kdf_params),
    )
    .map_err(map_err)?;

    Ok(exported.data)
}

#[uniffi::export]
pub fn import_vault(vault_data: Vec<u8>, vault_password: String) -> FfiResult<ImportedContentsFfi> {
    let default_params = crate::crypto::argon2::Argon2Params::default();
    let contents = crate::format::import::import(&vault_data, &vault_password, &default_params)
        .map_err(map_err)?;

    Ok(ImportedContentsFfi {
        keys: contents.keys.into_iter()
            .map(|(k, v)| KeyValueFfi { key: k, value: v })
            .collect(),
        db_file: contents.db_file,
        files: contents.files.into_iter()
            .map(|(k, v)| KeyValueFfi { key: k, value: v })
            .collect(),
    })
}

#[uniffi::export]
pub fn create_vault_layout(dir: String, password: String) -> FfiResult<Vec<u8>> {
    crate::format::export::create_vault_layout(std::path::Path::new(&dir), &password)
        .map_err(map_err)
}

#[uniffi::export]
pub fn branch_b_fresh_install(
    contents: ImportedContentsFfi,
    password: String,
    db_data: Vec<u8>,
    encryption_dir: String,
    database_dir: String,
    files_dir: String,
) -> FfiResult<()> {
    let inner = crate::format::import::ImportedContents {
        keys: contents.keys.into_iter()
            .map(|kv| (kv.key, kv.value))
            .collect(),
        db_file: contents.db_file,
        files: contents.files.into_iter()
            .map(|kv| (kv.key, kv.value))
            .collect(),
    };

    crate::merge::branch_b_fresh_install(
        &inner,
        &password,
        &db_data,
        std::path::Path::new(&encryption_dir),
        std::path::Path::new(&database_dir),
        std::path::Path::new(&files_dir),
    )
    .map_err(map_err)
}
