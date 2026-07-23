use std::sync::{Arc, Mutex};

// ---------------------------------------------------------------------------
// DbHandle — managed SQLCipher connection
// ---------------------------------------------------------------------------

#[derive(uniffi::Object)]
pub struct DbHandle {
    inner: Arc<Mutex<rusqlite::Connection>>,
    encryption_key: Option<Vec<u8>>,
}

#[uniffi::export]
impl DbHandle {
    #[uniffi::constructor]
    pub fn open_encrypted(
        path: String,
        master_key: Vec<u8>,
    ) -> Result<Self, crate::error::Error> {
        let conn = crate::db::schema::open_encrypted(&path, &master_key)?;
        Ok(Self {
            inner: Arc::new(Mutex::new(conn)),
            encryption_key: Some(master_key),
        })
    }

    #[uniffi::constructor]
    pub fn open_plain(path: String) -> Result<Self, crate::error::Error> {
        let conn = crate::db::schema::open_plain(&path)?;
        Ok(Self {
            inner: Arc::new(Mutex::new(conn)),
            encryption_key: None,
        })
    }

    #[uniffi::constructor]
    pub fn create_encrypted(
        path: String,
        master_key: Vec<u8>,
    ) -> Result<Self, crate::error::Error> {
        let conn = crate::db::schema::create_encrypted_db(&path, &master_key)?;
        Ok(Self {
            inner: Arc::new(Mutex::new(conn)),
            encryption_key: Some(master_key),
        })
    }

    pub fn list_documents(
        &self,
    ) -> Result<Vec<crate::db::queries::DocumentRow>, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::queries::list_documents(&conn)?)
    }

    pub fn get_document(
        &self,
        id: String,
    ) -> Result<Option<crate::db::queries::DocumentRow>, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::queries::get_document(&conn, &id)?)
    }

    pub fn find_document_by_hash(
        &self,
        hash: String,
    ) -> Result<Option<crate::db::queries::DocumentRow>, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::queries::find_document_by_hash(&conn, &hash)?)
    }

    pub fn add_document(
        &self,
        doc: crate::db::queries::DocumentRow,
    ) -> Result<(), crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::queries::add_document(&conn, &doc)?)
    }

    pub fn delete_document(
        &self,
        id: String,
    ) -> Result<bool, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::queries::delete_document(&conn, &id)?)
    }

    pub fn update_document(
        &self,
        id: String,
        title: String,
        is_favorite: bool,
    ) -> Result<bool, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::queries::update_document(
            &conn,
            &id,
            &title,
            is_favorite,
        )?)
    }

    pub fn add_document_full(
        &self,
        doc: crate::db::queries::DocumentRow,
        text_content: Option<String>,
    ) -> Result<(), crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::queries::add_document_full(
            &conn,
            &doc,
            text_content.as_deref(),
        )?)
    }

    pub fn list_collections(
        &self,
    ) -> Result<Vec<crate::db::queries::CollectionRow>, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::queries::list_collections(&conn)?)
    }

    pub fn list_tags(
        &self,
    ) -> Result<Vec<crate::db::queries::TagRow>, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::queries::list_tags(&conn)?)
    }

    pub fn search_documents(
        &self,
        query: String,
    ) -> Result<Vec<crate::db::fts::FtsResult>, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::fts::search(&conn, &query)?)
    }

    pub fn search_documents_with_snippet(
        &self,
        query: String,
    ) -> Result<Vec<crate::db::fts::FtsSnippetResult>, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::fts::search_with_snippet(&conn, &query)?)
    }

    pub fn search_documents_with_all_matches(
        &self,
        query: String,
    ) -> Result<Vec<crate::db::fts::MultiMatchResult>, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        let results = crate::db::fts::search_with_all_matches(&conn, &query)?;
        Ok(results
            .into_iter()
            .map(|r| {
                let page_matches = crate::db::fts::extract_page_matches(&r.highlighted);
                crate::db::fts::MultiMatchResult {
                    rank: r.rank,
                    id: r.id,
                    title: r.title,
                    first_snippet: r.snippet,
                    additional_matches: page_matches,
                }
            })
            .collect())
    }

    pub fn search_in_document(
        &self,
        document_id: String,
        query: String,
    ) -> Result<Vec<crate::db::fts::FtsSnippetResult>, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::fts::search_in_document(
            &conn,
            &document_id,
            &query,
        )?)
    }

    pub fn rebuild_fts_index(&self) -> Result<(), crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::fts::rebuild_index(&conn)?)
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
    ) -> Result<String, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        let base = std::path::Path::new(&base_dir);
        Ok(crate::db::storage::import_document(
            &conn,
            base,
            &id,
            &title,
            &file_data,
            &mime_type,
            &author,
            &description,
            text_content.as_deref(),
            self.encryption_key.as_deref(),
        )?)
    }

    pub fn export_document_file(
        &self,
        base_dir: String,
        id: String,
    ) -> Result<Option<Vec<u8>>, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        let base = std::path::Path::new(&base_dir);
        Ok(
            crate::db::storage::export_document_file(
                &conn,
                base,
                &id,
                self.encryption_key.as_deref(),
            ),
        )
    }

    pub fn delete_document_full(
        &self,
        base_dir: String,
        id: String,
    ) -> Result<bool, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        let base = std::path::Path::new(&base_dir);
        Ok(crate::db::storage::delete_document_full(
            &conn, base, &id,
        )?)
    }

    pub fn merge_branch_a(
        &self,
        backup_db_path: String,
        backup_master_key: Vec<u8>,
        files: Vec<crate::types::KeyValue>,
        backup_key: Option<Vec<u8>>,
        local_key: Option<Vec<u8>>,
        files_dir: String,
    ) -> Result<crate::merge::MergeStats, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        crate::merge::branch_a_merge(
            &backup_db_path,
            &backup_master_key,
            &conn,
            &files,
            backup_key.as_deref(),
            local_key.as_deref(),
            std::path::Path::new(&files_dir),
        )
    }

    pub fn list_documents_filtered(
        &self,
        limit: i64,
        offset: i64,
        collection_id: Option<String>,
        favorite_only: bool,
        tag_id: Option<String>,
    ) -> Result<Vec<crate::db::queries::DocumentRow>, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::queries::list_documents_filtered(
            &conn,
            limit,
            offset,
            collection_id.as_deref(),
            favorite_only,
            tag_id.as_deref(),
        )?)
    }

    pub fn update_document_full(
        &self,
        id: String,
        title: String,
        author: String,
        description: String,
        collection_id: Option<String>,
        is_favorite: bool,
        is_conflict: bool,
        conflict_with: Option<String>,
        current_page: i32,
        reading_position: Option<String>,
    ) -> Result<bool, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::queries::update_document_full(
            &conn,
            &id,
            &title,
            &author,
            &description,
            collection_id.as_deref(),
            is_favorite,
            is_conflict,
            conflict_with.as_deref(),
            current_page,
            reading_position.as_deref(),
        )?)
    }

    pub fn set_reading_position(
        &self,
        id: String,
        position: String,
    ) -> Result<bool, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::queries::set_reading_position(
            &conn,
            &id,
            &position,
        )?)
    }

    pub fn set_current_page(
        &self,
        id: String,
        page: i32,
    ) -> Result<bool, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::queries::set_current_page(
            &conn, &id, page,
        )?)
    }

    pub fn add_collection(
        &self,
        col: crate::db::queries::CollectionRow,
    ) -> Result<(), crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::queries::add_collection(&conn, &col)?)
    }

    pub fn get_collection(
        &self,
        id: String,
    ) -> Result<Option<crate::db::queries::CollectionRow>, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::queries::get_collection(&conn, &id)?)
    }

    pub fn update_collection(
        &self,
        id: String,
        name: String,
        icon: String,
        sort_order: i32,
        parent_id: Option<String>,
    ) -> Result<bool, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::queries::update_collection(
            &conn,
            &id,
            &name,
            &icon,
            sort_order,
            parent_id.as_deref(),
        )?)
    }

    pub fn delete_collection(
        &self,
        id: String,
    ) -> Result<bool, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::queries::delete_collection(
            &conn, &id,
        )?)
    }

    pub fn add_tag(
        &self,
        tag: crate::db::queries::TagRow,
    ) -> Result<(), crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::queries::add_tag(&conn, &tag)?)
    }

    pub fn update_tag(
        &self,
        id: String,
        name: String,
        color: i64,
    ) -> Result<bool, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::queries::update_tag(
            &conn, &id, &name, color,
        )?)
    }

    pub fn delete_tag(
        &self,
        id: String,
    ) -> Result<bool, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::queries::delete_tag(&conn, &id)?)
    }

    pub fn link_document_tag(
        &self,
        document_id: String,
        tag_id: String,
    ) -> Result<(), crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::queries::link_document_tag(
            &conn,
            &document_id,
            &tag_id,
        )?)
    }

    pub fn unlink_document_tag(
        &self,
        document_id: String,
        tag_id: String,
    ) -> Result<bool, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::queries::unlink_document_tag(
            &conn,
            &document_id,
            &tag_id,
        )?)
    }

    pub fn get_tags_for_document(
        &self,
        document_id: String,
    ) -> Result<Vec<crate::db::queries::TagRow>, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::queries::get_tags_for_document(
            &conn,
            &document_id,
        )?)
    }

    pub fn get_documents_for_tag(
        &self,
        tag_id: String,
    ) -> Result<Vec<crate::db::queries::DocumentRow>, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::queries::get_documents_for_tag(
            &conn, &tag_id,
        )?)
    }

    pub fn store_thumbnail(
        &self,
        base_dir: String,
        id: String,
        data: Vec<u8>,
    ) -> Result<(), crate::error::Error> {
        Ok(crate::db::storage::store_thumbnail(
            std::path::Path::new(&base_dir),
            &id,
            &data,
            self.encryption_key.as_deref(),
        )?)
    }

    pub fn load_thumbnail(
        &self,
        base_dir: String,
        id: String,
    ) -> Result<Option<Vec<u8>>, crate::error::Error> {
        Ok(crate::db::storage::load_thumbnail(
            std::path::Path::new(&base_dir),
            &id,
            self.encryption_key.as_deref(),
        ))
    }

    pub fn get_schema_version(&self) -> Result<i64, crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::schema::get_schema_version(&conn)?)
    }

    pub fn set_schema_version(&self, version: i64) -> Result<(), crate::error::Error> {
        let conn = self
            .inner
            .lock()
            .map_err(|e| crate::error::Error::Database(e.to_string()))?;
        Ok(crate::db::schema::set_schema_version(
            &conn, version,
        )?)
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
) -> Result<Vec<u8>, crate::error::Error> {
    let params =
        crate::crypto::argon2::Argon2Params::new(memory_cost, iterations, parallelism, 32);
    crate::crypto::argon2::derive_key(&password, &salt, &params)
        .ok_or(crate::error::Error::Kdf("key derivation failed".into()))
}

#[uniffi::export]
pub fn encrypt_bytes(
    data: Vec<u8>,
    key: Vec<u8>,
) -> Result<crate::types::EncryptedData, crate::error::Error> {
    crate::crypto::aes_gcm::encrypt_bytes(&data, &key)
        .map(|(iv, ciphertext)| crate::types::EncryptedData { iv, ciphertext })
        .ok_or(crate::error::Error::Crypto(
            "encryption failed".into(),
        ))
}

#[uniffi::export]
pub fn decrypt_bytes(
    data: crate::types::EncryptedData,
    key: Vec<u8>,
) -> Result<Vec<u8>, crate::error::Error> {
    crate::crypto::aes_gcm::decrypt_bytes(&data.ciphertext, &key, &data.iv)
        .ok_or(crate::error::Error::Crypto(
            "decryption failed".into(),
        ))
}

#[uniffi::export]
pub fn generate_master_key() -> Vec<u8> {
    crate::crypto::aes_kw::generate_master_key()
}

#[uniffi::export]
pub fn wrap_key(
    kek: Vec<u8>,
    plaintext: Vec<u8>,
) -> Result<Vec<u8>, crate::error::Error> {
    crate::crypto::aes_kw::wrap(&kek, &plaintext)
        .ok_or(crate::error::Error::Crypto("key wrap failed".into()))
}

#[uniffi::export]
pub fn unwrap_key(
    wrapped: Vec<u8>,
    kek: Vec<u8>,
) -> Result<Vec<u8>, crate::error::Error> {
    crate::crypto::aes_kw::unwrap(&wrapped, &kek)
        .ok_or(crate::error::Error::Crypto(
            "key unwrap failed".into(),
        ))
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
    let params =
        crate::crypto::argon2::Argon2Params::new(memory_cost, iterations, parallelism, 32);
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
) -> Result<Vec<u8>, crate::error::Error> {
    let params =
        crate::crypto::argon2::Argon2Params::new(memory_cost, iterations, parallelism, 32);
    crate::kdf::derive_backup_master_key(&wrapped_key, &password, &salt, &params)
}

// ---------------------------------------------------------------------------
// Free functions — Vault format
// ---------------------------------------------------------------------------

#[uniffi::export]
pub fn export_vault(
    files: Vec<crate::types::KeyValue>,
    db_file: Option<Vec<u8>>,
    vault_password: String,
    keys: Vec<crate::types::KeyValue>,
    kdf_params: crate::crypto::argon2::Argon2Params,
) -> Result<Vec<u8>, crate::error::Error> {
    let exported = crate::format::export::export(
        &files,
        db_file.as_deref(),
        &vault_password,
        &keys,
        &kdf_params,
    )?;

    Ok(exported.data)
}

#[uniffi::export]
pub fn import_vault(
    vault_data: Vec<u8>,
    vault_password: String,
) -> Result<crate::format::import::ImportedContents, crate::error::Error> {
    let default_params = crate::crypto::argon2::Argon2Params::default();
    crate::format::import::import(&vault_data, &vault_password, &default_params)
}

#[uniffi::export]
pub fn create_vault_layout(
    dir: String,
    password: String,
) -> Result<Vec<u8>, crate::error::Error> {
    crate::format::export::create_vault_layout(std::path::Path::new(&dir), &password)
}

#[uniffi::export]
pub fn restore_to_layout(
    contents: crate::format::import::ImportedContents,
    db_data: Vec<u8>,
    encryption_dir: String,
    database_dir: String,
    files_dir: String,
) -> Result<(), crate::error::Error> {
    crate::merge::branch_b_fresh_install(
        &contents,
        "",
        &db_data,
        std::path::Path::new(&encryption_dir),
        std::path::Path::new(&database_dir),
        std::path::Path::new(&files_dir),
    )
}

#[uniffi::export]
pub fn branch_b_fresh_install(
    contents: crate::format::import::ImportedContents,
    password: String,
    db_data: Vec<u8>,
    encryption_dir: String,
    database_dir: String,
    files_dir: String,
) -> Result<(), crate::error::Error> {
    crate::merge::branch_b_fresh_install(
        &contents,
        &password,
        &db_data,
        std::path::Path::new(&encryption_dir),
        std::path::Path::new(&database_dir),
        std::path::Path::new(&files_dir),
    )
}

// ---------------------------------------------------------------------------
// Document — format-agnostic reader
// ---------------------------------------------------------------------------

#[derive(uniffi::Object)]
pub struct Document {
    inner: std::sync::Mutex<Box<dyn crate::reader::DocumentReader>>,
}

#[uniffi::export]
impl Document {
    #[uniffi::constructor]
    pub fn open(path: String, mime_type: String) -> Result<Self, crate::error::Error> {
        let reader = crate::reader::open(std::path::Path::new(&path), &mime_type)?;
        Ok(Self {
            inner: std::sync::Mutex::new(reader),
        })
    }

    pub fn page_count(&self) -> Result<u32, crate::error::Error> {
        Ok(self.inner.lock().unwrap().page_count()?)
    }

    pub fn extract_text(&self, page_index: u32) -> Result<String, crate::error::Error> {
        Ok(self.inner.lock().unwrap().extract_text(page_index)?)
    }

    pub fn extract_all_text(&self) -> Result<String, crate::error::Error> {
        Ok(self.inner.lock().unwrap().extract_all_text()?)
    }

    pub fn render_page(&self, page_index: u32, scale: f32) -> Result<Vec<u8>, crate::error::Error> {
        let page = self.inner.lock().unwrap().render_page(page_index, scale)?;
        Ok(page.data)
    }
}
