use vault_native::crypto::aes_gcm;
use vault_native::crypto::aes_kw;
use vault_native::crypto::argon2::{self, Argon2Params};
use vault_native::db::schema::{create_all_tables, open_encrypted};
use vault_native::db::fts;
use vault_native::db::queries::{self, DocumentRow};
use vault_native::format::export;
use vault_native::format::import;
use vault_native::types::KeyValue;

fn make_master_key() -> Vec<u8> {
    (0..32).collect::<Vec<u8>>()
}

/// Phase 1 E2E: create encrypted DB → create tables → add docs → list → FTS search
#[test]
fn test_db_lifecycle() {
    let mk = make_master_key();
    let tmp = tempfile::TempDir::new().unwrap();
    let db_path = tmp.path().join("test.db");
    let path = db_path.to_str().unwrap();

    // Create encrypted DB with schema
    let conn = open_encrypted(path, &mk).unwrap();
    create_all_tables(&conn).unwrap();

    // Add documents
    let now = 1000i64;
    for i in 0..3 {
        let doc = DocumentRow {
            id: format!("doc-{}", i),
            title: format!("Document {}", i),
            file_name: format!("doc{}.pdf", i),
            mime_type: "application/pdf".into(),
            file_path: format!("files/doc{}.pdf", i),
            file_size: 1024 * (i + 1) as i64,
            page_count: (i + 1) as i32,
            author: "Author".into(),
            description: format!("Description {}", i),
            imported_at: now,
            last_opened_at: now,
            modified_at: now,
            is_favorite: i == 0,
            ..Default::default()
        };
        queries::add_document(&conn, &doc).unwrap();
    }

    // List and verify count
    let docs = queries::list_documents(&conn).unwrap();
    assert_eq!(docs.len(), 3);

    // Get by ID
    let doc = queries::get_document(&conn, "doc-1").unwrap().unwrap();
    assert_eq!(doc.title, "Document 1");

    // Delete
    assert!(queries::delete_document(&conn, "doc-2").unwrap());
    assert_eq!(queries::list_documents(&conn).unwrap().len(), 2);

    // Re-open and verify persistence
    drop(conn);
    let conn = open_encrypted(path, &mk).unwrap();
    assert_eq!(queries::list_documents(&conn).unwrap().len(), 2);
}

/// Phase 1 E2E: FTS5 search on encrypted DB
#[test]
fn test_fts_search_e2e() {
    let mk = make_master_key();
    let tmp = tempfile::TempDir::new().unwrap();
    let db_path = tmp.path().join("fts.db");
    let path = db_path.to_str().unwrap();

    let conn = open_encrypted(path, &mk).unwrap();
    create_all_tables(&conn).unwrap();

    for (id, title, content) in &[
        ("d1", "Quick brown fox", "The quick brown fox jumps over the lazy dog"),
        ("d2", "Lazy dog", "The lazy dog sleeps on the rug"),
        ("d3", "Red fox", "A red fox runs through the forest"),
    ] {
        conn.execute(
            "INSERT INTO documents (id, title, file_name, mime_type, file_path, file_size, page_count, author, description, imported_at, last_opened_at, modified_at, is_favorite, is_conflict, current_page, text_content)
             VALUES (?1, ?2, '', '', '', 0, 0, '', '', 0, 0, 0, 0, 0, 0, ?3)",
            rusqlite::params![id, title, content],
        ).unwrap();
    }

    // FTS index is populated automatically by the fts_after_insert trigger

    // Search for "fox"
    let results = fts::search(&conn, "fox").unwrap();
    assert_eq!(results.len(), 2);

    // Search for "lazy"
    let results = fts::search(&conn, "lazy").unwrap();
    assert_eq!(results.len(), 2);
}

/// Phase 1 E2E: full export/import roundtrip with encrypted DB + files
#[test]
fn test_vault_roundtrip_with_db() {
    let password = "test-password";
    let master_key = make_master_key();
    let kdf_params = Argon2Params::default();
    let tmp = tempfile::TempDir::new().unwrap();

    // Create encrypted DB with data
    let db_path = tmp.path().join("source.db");
    let conn = open_encrypted(db_path.to_str().unwrap(), &master_key).unwrap();
    create_all_tables(&conn).unwrap();
    let doc = DocumentRow {
        id: "e2e-doc".into(),
        title: "E2E Test".into(),
        file_name: "hello.txt".into(),
        mime_type: "text/plain".into(),
        file_path: "hello.txt".into(),
        file_size: 13,
        page_count: 1,
        author: "Tester".into(),
        description: "Integration test".into(),
        imported_at: 1000,
        last_opened_at: 1000,
        modified_at: 1000,
        ..Default::default()
    };
    queries::add_document(&conn, &doc).unwrap();
    drop(conn);

    // Read the encrypted DB bytes
    let db_data = std::fs::read(&db_path).unwrap();

    // Create a test file encrypted with AES-GCM
    let plaintext = b"Hello, world!";
    let (iv, ct) = aes_gcm::encrypt_bytes(plaintext, &master_key).unwrap();
    let encrypted_file: Vec<u8> = iv.iter().chain(ct.iter()).copied().collect();

    // Derive user key and wrap master key
    let salt = b"test-salt-1234567";
    let user_key = argon2::derive_key(password, salt, &kdf_params).unwrap();
    let wrapped_mk = aes_kw::wrap(&user_key, &master_key).unwrap();

    // Export vault
    let exported = export::export(
        &[KeyValue { key: "hello.txt".into(), value: encrypted_file }],
        Some(&db_data),
        password,
        &[
            KeyValue { key: "wrapped_master_key".into(), value: wrapped_mk.clone() },
            KeyValue { key: "salt".into(), value: salt.to_vec() },
        ],
        &kdf_params,
    )
    .unwrap();

    // Verify vault structure
    assert!(exported.data.starts_with(b"LIBCRATE_VAULT"));
    assert!(exported.data.len() > 100);

    // Import vault back
    let imported = import::import(&exported.data, password, &kdf_params).unwrap();
    assert!(imported.db_file.is_some());
    assert_eq!(imported.db_file.as_ref().unwrap().len() as u64, db_data.len() as u64);

    // Verify keys
    let imported_wmk = imported
        .keys
        .iter()
        .find(|kv| kv.key == "wrapped_master_key")
        .map(|kv| kv.value.clone())
        .unwrap();
    assert_eq!(imported_wmk, wrapped_mk);

    // Verify files and decrypt
    assert_eq!(imported.files.len(), 1);
    let fkv = &imported.files[0];
    assert_eq!(fkv.key, "hello.txt");

    let iv = &fkv.value[..aes_gcm::IV_LENGTH];
    let ct = &fkv.value[aes_gcm::IV_LENGTH..];
    let decrypted = aes_gcm::decrypt_bytes(ct, &master_key, iv).unwrap();
    assert_eq!(decrypted, b"Hello, world!");

    // Re-import DB and verify data
    let restored_path = tmp.path().join("restored.db");
    std::fs::write(&restored_path, imported.db_file.as_ref().unwrap()).unwrap();
    let conn = open_encrypted(restored_path.to_str().unwrap(), &master_key).unwrap();
    let docs = queries::list_documents(&conn).unwrap();
    assert_eq!(docs.len(), 1);
    assert_eq!(docs[0].title, "E2E Test");
}

/// Phase 0 conformance: verify the compat=4 raw-key format is correct by checking
/// that the page_size PRAGMA takes effect and that the DB round-trips correctly.
/// Raw-key mode bypasses the KDF, so cipher_compatibility settings affect only
/// page size and HMAC algorithm — the key difference vs. compat=1 is page_size
/// (4096 vs 1024 default) and HMAC algorithm. We verify page_size is 4096 as set.
#[test]
fn test_compat4_pragma_page_size() {
    let mk = make_master_key();
    let tmp = tempfile::TempDir::new().unwrap();
    let db_path = tmp.path().join("pagesize.db");
    let path = db_path.to_str().unwrap();

    let conn = open_encrypted(path, &mk).unwrap();
    create_all_tables(&conn).unwrap();

    // Verify page_size pragma is 4096 as set
    let page_size: i32 = conn
        .query_row("PRAGMA page_size", [], |row| row.get(0))
        .unwrap();
    assert_eq!(
        page_size, 4096,
        "page_size should be 4096 (compat=4 default), not 1024 (compat=1 default)"
    );

    // Verify kdf_iter pragma was set correctly (returns text)
    let kdf_iter: String = conn
        .query_row("PRAGMA kdf_iter", [], |row| row.get(0))
        .unwrap();
    assert_eq!(
        kdf_iter, "256000",
        "kdf_iter should be 256000 (compat=4 default)"
    );
}

/// Phase 0 conformance: explicitly document the exact PRAGMAs used for Android
/// interop. Verifies self-consistency with the zetetic-expected encoding (x'<hex>' raw key).
#[test]
fn test_compat4_raw_key_format() {
    let mk = make_master_key();
    let hex_key = hex::encode(&mk);
    let tmp = tempfile::TempDir::new().unwrap();
    let db_path = tmp.path().join("rawkey.db");
    let path = db_path.to_str().unwrap();

    // The exact PRAGMA sequence that both Rust core and Android app must agree on.
    // zetetic's SupportFactory hex-encodes the passphrase bytes and issues:
    //   PRAGMA key = x'<hex>'
    // which is the same raw-key mode the Rust core uses.
    {
        let conn = rusqlite::Connection::open(path).unwrap();
        for pragma in &[
            "PRAGMA cipher_compatibility = 4",
            "PRAGMA cipher_kdf_algorithm = sha512",
            "PRAGMA cipher_hmac_algorithm = sha512",
            "PRAGMA kdf_iter = 256000",
            "PRAGMA cipher_page_size = 4096",
        ] {
            let mut stmt = conn.prepare(pragma).unwrap();
            let _ = stmt.query([]).unwrap();
        }
        let mut stmt = conn
            .prepare(&format!("PRAGMA key = \"x'{hex_key}'\""))
            .unwrap();
        let _ = stmt.query([]).unwrap();

        // Verify
        conn.query_row("SELECT count(*) FROM sqlite_master", [], |_| Ok::<_, rusqlite::Error>(())).unwrap();
        create_all_tables(&conn).unwrap();
        conn.execute(
            "INSERT INTO documents (id, title, file_name, mime_type, file_path, file_size, page_count, author, description, imported_at, last_opened_at, modified_at, is_favorite, is_conflict, current_page)
             VALUES ('rk1', 'Raw Key Test', '', '', '', 0, 0, '', '', 0, 0, 0, 0, 0, 0)",
            [],
        ).unwrap();
    }

    // Re-open with the standard open_encrypted (should work)
    let conn = open_encrypted(path, &mk).unwrap();
    let docs = queries::list_documents(&conn).unwrap();
    assert_eq!(docs.len(), 1);
    assert_eq!(docs[0].title, "Raw Key Test");
}
