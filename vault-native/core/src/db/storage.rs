use crate::crypto::aes_gcm;
use crate::db::queries::{self, DocumentRow};
use image::GenericImageView;
use rusqlite::Connection;
use sha2::{Digest, Sha256};
use std::io::Read;
use std::path::Path;

/// Maximum width for generated thumbnails (in pixels).
const THUMBNAIL_MAX_WIDTH: u32 = 200;

/// Resize raw image bytes to a JPEG thumbnail capped at `max_width`.
fn resize_image_to_jpeg(data: &[u8], max_width: u32) -> Option<Vec<u8>> {
    let img = image::load_from_memory(data).ok()?;
    let (w, h) = img.dimensions();
    if w == 0 || h == 0 {
        return None;
    }
    let new_w = max_width.min(w);
    let new_h = (h as f64 * new_w as f64 / w as f64).round() as u32;
    let thumb = img.resize_exact(new_w, new_h, image::imageops::FilterType::Lanczos3);
    let mut out = Vec::new();
    thumb
        .write_to(
            &mut std::io::Cursor::new(&mut out),
            image::ImageFormat::Jpeg,
        )
        .ok()?;
    Some(out)
}

/// Parse the root OPF path from an EPUB `META-INF/container.xml`.
fn parse_container_rootfile(xml: &str) -> Option<String> {
    let start = xml.find("full-path=\"")? + "full-path=\"".len();
    let end = xml[start..].find('"')?;
    Some(xml[start..start + end].to_string())
}

/// Extract the cover image href from an EPUB OPF manifest.
fn parse_opf_cover(opf: &str) -> Option<String> {
    // EPUB 3: <item ... properties="cover-image" .../>
    if let Some(pos) = opf.find("properties=\"cover-image\"") {
        let before = &opf[..pos];
        if let Some(item_start) = before.rfind("<item ") {
            let item = &opf[item_start..];
            if let Some(href_start) = item.find("href=\"") {
                let s = href_start + "href=\"".len();
                let e = item[s..].find('"')?;
                return Some(item[s..s + e].to_string());
            }
        }
    }
    // EPUB 2: <item id="cover-image" ...> or <item id="cover" ...>
    for id_attr in &["cover-image", "cover", "coverImage"] {
        let needle = format!("id=\"{id_attr}\"");
        if let Some(pos) = opf.find(&needle) {
            let after = &opf[pos..];
            if let Some(href_start) = after.find("href=\"") {
                let s = href_start + "href=\"".len();
                let e = after[s..].find('"')?;
                return Some(after[s..s + e].to_string());
            }
        }
    }
    None
}

/// Render page 1 of a PDF to JPEG via the system `pdftoppm` tool (poppler-utils).
fn generate_thumbnail_pdf(data: &[u8]) -> Option<Vec<u8>> {
    let tmp_dir = tempfile::tempdir().ok()?;
    let pdf_path = tmp_dir.path().join("input.pdf");
    std::fs::write(&pdf_path, data).ok()?;
    let prefix = tmp_dir.path().join("page");
    let status = std::process::Command::new("pdftoppm")
        .args(["-jpeg", "-r", "150", "-singlefile", "-f", "1", "-l", "1"])
        .arg(&pdf_path)
        .arg(&prefix)
        .output()
        .ok()?;
    if !status.status.success() {
        return None;
    }
    let jpg_path = tmp_dir.path().join("page.jpg");
    std::fs::read(&jpg_path).ok()
}

/// Extract the cover image from an EPUB (ZIP) archive.
fn generate_thumbnail_epub(data: &[u8]) -> Option<Vec<u8>> {
    let cursor = std::io::Cursor::new(data);
    let mut archive = zip::ZipArchive::new(cursor).ok()?;

    let container_xml = {
        let mut f = archive.by_name("META-INF/container.xml").ok()?;
        let mut s = String::new();
        f.read_to_string(&mut s).ok()?;
        s
    };
    let rootfile_path = parse_container_rootfile(&container_xml)?;

    let opf_content = {
        let mut f = archive.by_name(&rootfile_path).ok()?;
        let mut s = String::new();
        f.read_to_string(&mut s).ok()?;
        s
    };
    let cover_href = parse_opf_cover(&opf_content)?;

    let opf_dir = std::path::Path::new(&rootfile_path)
        .parent()
        .unwrap_or(std::path::Path::new(""));
    let cover_path = opf_dir.join(&cover_href).to_string_lossy().to_string();

    let mut f = archive.by_name(&cover_path).ok()?;
    let mut img = Vec::new();
    f.read_to_end(&mut img).ok()?;
    Some(img)
}

/// Extract the first page image from a CBZ (comic ZIP) archive.
fn generate_thumbnail_cbz(data: &[u8]) -> Option<Vec<u8>> {
    let cursor = std::io::Cursor::new(data);
    let mut archive = zip::ZipArchive::new(cursor).ok()?;

    let image_exts = [".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp"];
    let mut images: Vec<String> = archive
        .file_names()
        .filter(|name| {
            let lower = name.to_lowercase();
            !lower.starts_with("__MACOSX")
                && image_exts.iter().any(|ext| lower.ends_with(ext))
        })
        .map(|s| s.to_string())
        .collect();
    images.sort();

    let first = images.first()?;
    let mut f = archive.by_name(first).ok()?;
    let mut img = Vec::new();
    f.read_to_end(&mut img).ok()?;
    Some(img)
}

/// Generate a JPEG thumbnail for a document.
///
/// Supported formats:
/// - `image/*` — decoded and resized directly
/// - `application/pdf` — page 1 rendered via `pdftoppm`
/// - `application/epub+zip` — cover image extracted from the EPUB archive
/// - `application/vnd.comicbook+zip` — first page image from the CBZ archive
///
/// Returns `None` when the format is unsupported or generation fails.
pub fn generate_thumbnail(data: &[u8], mime_type: &str) -> Option<Vec<u8>> {
    let raw = match mime_type {
        "application/pdf" => generate_thumbnail_pdf(data)?,
        "application/epub+zip" => generate_thumbnail_epub(data)?,
        "application/vnd.comicbook+zip" | "application/x-cbr" => {
            generate_thumbnail_cbz(data)?
        }
        mt if mt.starts_with("image/") => data.to_vec(),
        _ => return None,
    };
    resize_image_to_jpeg(&raw, THUMBNAIL_MAX_WIDTH)
}

/// Save a thumbnail blob at `base_dir/files/<id>.thumb`.
pub fn store_thumbnail(base_dir: &Path, id: &str, data: &[u8], key: Option<&[u8]>) -> std::io::Result<()> {
    let path = base_dir.join("files").join(format!("{id}.thumb"));
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let blob = if let Some(k) = key {
        let (iv, ct) = aes_gcm::encrypt_bytes(data, k).unwrap_or_else(|| (vec![], vec![]));
        if iv.is_empty() { return Err(std::io::Error::new(std::io::ErrorKind::Other, "encryption failed")); }
        let mut out = iv;
        out.extend_from_slice(&ct);
        out
    } else {
        data.to_vec()
    };
    std::fs::write(&path, blob)
}

/// Load a thumbnail blob from `base_dir/files/<id>.thumb`.
pub fn load_thumbnail(base_dir: &Path, id: &str, key: Option<&[u8]>) -> Option<Vec<u8>> {
    let path = base_dir.join("files").join(format!("{id}.thumb"));
    let raw = if path.exists() { std::fs::read(&path).ok()? } else { return None };
    if let Some(k) = key {
        if raw.len() < aes_gcm::IV_LENGTH { return None; }
        let iv = &raw[..aes_gcm::IV_LENGTH];
        let ct = &raw[aes_gcm::IV_LENGTH..];
        aes_gcm::decrypt_bytes(ct, k, iv)
    } else {
        Some(raw)
    }
}

/// Save a file blob at `base_dir/files/<id>`.
pub fn save_file(base_dir: &Path, id: &str, data: &[u8]) -> std::io::Result<()> {
    let path = base_dir.join("files").join(id);
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(&path, data)
}

/// Load a file blob from `base_dir/files/<id>`.
pub fn load_file(base_dir: &Path, id: &str) -> Option<Vec<u8>> {
    let path = base_dir.join("files").join(id);
    if path.exists() {
        std::fs::read(&path).ok()
    } else {
        None
    }
}

/// Delete a file blob at `base_dir/files/<id>`.
pub fn delete_file(base_dir: &Path, id: &str) {
    let path = base_dir.join("files").join(id);
    let _ = std::fs::remove_file(&path);
}

/// Derive a `file_name` for a document. We prefer the original file name carried
/// in `title` (e.g. "book.epub") so downstream viewers can detect the type from the
/// extension. If `title` has no usable extension, fall back to one inferred from the
/// MIME type, and finally to the document `id`.
fn derive_file_name(id: &str, title: &str, mime_type: &str) -> String {
    let title_ext = std::path::Path::new(title)
        .extension()
        .and_then(|e| e.to_str())
        .map(|e| e.to_lowercase());
    if let Some(ext) = title_ext {
        if !ext.is_empty() && ext.chars().all(|c| c.is_ascii_alphanumeric()) {
            return format!("{id}.{ext}");
        }
    }
    if let Some(ext) = extension_for_mime(mime_type) {
        return format!("{id}.{ext}");
    }
    id.to_string()
}

/// Best-effort file extension for a MIME type (subset relevant to this app).
fn extension_for_mime(mime: &str) -> Option<&'static str> {
    match mime {
        "application/pdf" => Some("pdf"),
        "application/epub+zip" => Some("epub"),
        "application/vnd.apple.pkpass" => Some("pkpass"),
        "application/vnd.comicbook+zip" | "application/x-cbr" => Some("cbz"),
        "image/png" => Some("png"),
        "image/jpeg" => Some("jpg"),
        "image/gif" => Some("gif"),
        "image/webp" => Some("webp"),
        "image/bmp" => Some("bmp"),
        "text/markdown" | "text/plain" => Some("md"),
        _ => None,
    }
}

/// Import a document: store the file blob (encrypted if key is provided), insert DB row, and index into FTS5.
/// Returns the document ID.
pub fn import_document(
    conn: &Connection,
    base_dir: &Path,
    id: &str,
    title: &str,
    file_data: &[u8],
    mime_type: &str,
    author: &str,
    description: &str,
    text_content: Option<&str>,
    key: Option<&[u8]>,
) -> rusqlite::Result<String> {
    // Compute content hash for deduplication
    let content_hash = hex::encode(Sha256::digest(file_data));

    // Check for existing document with the same hash
    if let Some(existing) = queries::find_document_by_hash(conn, &content_hash)? {
        return Ok(existing.id);
    }

    let (stored_data, iv): (Vec<u8>, Vec<u8>) = if let Some(k) = key {
        let (real_iv, ct) = aes_gcm::encrypt_bytes(file_data, k)
            .ok_or_else(|| rusqlite::Error::ToSqlConversionFailure(
                Box::new(std::io::Error::new(std::io::ErrorKind::Other, "encryption failed"))
            ))?;
        let mut out = real_iv.clone();
        out.extend_from_slice(&ct);
        (out, real_iv)
    } else {
        // No key — store plaintext with random garbage IV (legacy behavior)
        let dummy_iv: Vec<u8> = (0..aes_gcm::IV_LENGTH).map(|_| rand::random::<u8>()).collect();
        (file_data.to_vec(), dummy_iv)
    };

    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;
    let file_path = format!("files/{id}");

    let doc = DocumentRow {
        id: id.to_string(),
        title: title.to_string(),
        file_name: derive_file_name(id, title, mime_type),
        mime_type: mime_type.to_string(),
        file_path,
        file_size: file_data.len() as i64,
        author: author.to_string(),
        description: description.to_string(),
        imported_at: now,
        last_opened_at: now,
        modified_at: now,
        encryption_iv: Some(iv),
        content_hash: Some(content_hash),
        ..Default::default()
    };

    queries::add_document_full(conn, &doc, text_content)?;

    save_file(base_dir, id, &stored_data).map_err(|e| {
        rusqlite::Error::ToSqlConversionFailure(Box::new(e))
    })?;

    if let Some(thumb_data) = generate_thumbnail(file_data, mime_type) {
        let _ = store_thumbnail(base_dir, id, &thumb_data, key);
    }

    Ok(id.to_string())
}

/// Export a document's file blob from storage (decrypted if key is provided).
pub fn export_document_file(conn: &Connection, base_dir: &Path, id: &str, key: Option<&[u8]>) -> Option<Vec<u8>> {
    let doc = queries::get_document(conn, id).ok()??;
    let raw = load_file(base_dir, &doc.id)?;
    if let Some(k) = key {
        let iv = doc.encryption_iv.as_deref()?;
        if raw.len() < aes_gcm::IV_LENGTH { return None; }
        let ct = &raw[aes_gcm::IV_LENGTH..];
        aes_gcm::decrypt_bytes(ct, k, iv)
    } else {
        Some(raw)
    }
}

/// Delete a document: remove file blob, delete DB row.
/// FTS index is cleaned up automatically by the fts_after_delete trigger.
pub fn delete_document_full(conn: &Connection, base_dir: &Path, id: &str) -> rusqlite::Result<bool> {
    delete_file(base_dir, id);
    queries::delete_document(conn, id)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::db::schema::create_encrypted_db;

    #[test]
    fn test_import_export_roundtrip_with_key() {
        let mk = (0..32).collect::<Vec<u8>>();
        let tmp = tempfile::TempDir::new().unwrap();

        let db_path = tmp.path().join("databases/librecrate.db");
        std::fs::create_dir_all(db_path.parent().unwrap()).unwrap();
        let conn = create_encrypted_db(db_path.to_str().unwrap(), &mk).unwrap();

        let data = b"Hello, world!".to_vec();
        let doc_id = import_document(
            &conn, tmp.path(), "test-doc-1",
            "Test Doc", &data, "text/plain",
            "Author", "Description", Some("hello world content"),
            Some(&mk),
        ).unwrap();
        assert_eq!(doc_id, "test-doc-1");

        // Verify file exists (as encrypted blob)
        let file_path = tmp.path().join("files/test-doc-1");
        assert!(file_path.exists());
        let raw = std::fs::read(&file_path).unwrap();
        assert!(raw.len() > data.len()); // iv + ciphertext > plaintext
        let db_doc = queries::get_document(&conn, "test-doc-1").unwrap().unwrap();
        assert_eq!(&raw[..aes_gcm::IV_LENGTH], db_doc.encryption_iv.as_deref().unwrap());

        // Verify list
        let docs = queries::list_documents(&conn).unwrap();
        assert_eq!(docs.len(), 1);

        // Export back (decrypted)
        let exported = export_document_file(&conn, tmp.path(), "test-doc-1", Some(&mk)).unwrap();
        assert_eq!(exported, data);

        // Without key — returns encrypted blob
        let raw_export = export_document_file(&conn, tmp.path(), "test-doc-1", None).unwrap();
        assert_ne!(raw_export, data); // encrypted, not plaintext

        // Verify FTS
        let results = crate::db::fts::search(&conn, "hello").unwrap();
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].id, "test-doc-1");

        // Delete
        delete_document_full(&conn, tmp.path(), "test-doc-1").unwrap();
        assert!(!file_path.exists());
        assert_eq!(queries::list_documents(&conn).unwrap().len(), 0);
    }

    #[test]
    fn test_import_export_roundtrip_no_key() {
        let tmp = tempfile::TempDir::new().unwrap();

        let db_path = tmp.path().join("databases/librecrate.db");
        std::fs::create_dir_all(db_path.parent().unwrap()).unwrap();
        let conn = crate::db::schema::open_plain(db_path.to_str().unwrap()).unwrap();
        crate::db::schema::create_all_tables(&conn).unwrap();

        let data = b"Hello, plaintext!".to_vec();
        let _doc_id = import_document(
            &conn, tmp.path(), "test-plain",
            "Test Doc", &data, "text/plain",
            "Author", "Description", None,
            None,
        ).unwrap();

        // File stored as plaintext
        let raw = std::fs::read(tmp.path().join("files/test-plain")).unwrap();
        assert_eq!(raw, data);

        // Export back
        let exported = export_document_file(&conn, tmp.path(), "test-plain", None).unwrap();
        assert_eq!(exported, data);
    }
}
