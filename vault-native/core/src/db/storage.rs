use crate::crypto::aes_gcm;
use crate::db::queries::{self, DocumentRow};
use rusqlite::Connection;
use sha2::{Digest, Sha256};
use std::io::Read;
use std::path::Path;

/// Maximum width for generated thumbnails (in pixels).
const THUMBNAIL_MAX_WIDTH: u32 = 200;

// ---------------------------------------------------------------------------
// Parser hardening limits (PR 11)
//
// Thumbnail generation is reachable from *untrusted* document contents
// (backups, imported files). Every read and decode below is bounded so a
// hostile EPUB/CBZ/image/PDF cannot exhaust memory or CPU.
// ---------------------------------------------------------------------------

/// Hard ceiling on the raw source bytes handed to any thumbnail generator.
const MAX_THUMBNAIL_SOURCE_SIZE: u64 = 64 * 1024 * 1024; // 64 MiB

/// Cap on decoded image dimensions (pixels per axis). Blocks decompression
/// bombs: a tiny PNG/JPEG would otherwise decode into gigabytes of pixels.
const MAX_THUMBNAIL_SOURCE_DIMENSION: u32 = 8192;

/// Cap on XML/metadata entries read from document archives (bytes).
const MAX_ARCHIVE_METADATA_SIZE: u64 = 1024 * 1024; // 1 MiB

/// Cap on cover/first-page images pulled from document archives (bytes).
const MAX_ARCHIVE_IMAGE_SIZE: u64 = 16 * 1024 * 1024; // 16 MiB

/// Cap on entries scanned inside an archive for cover/page discovery.
const MAX_ARCHIVE_ENTRY_SCAN: usize = 20_000;

/// Read a ZIP entry, refusing entries that exceed `cap` (bytes actually read).
fn read_entry_limited(reader: &mut impl Read, cap: u64) -> Option<Vec<u8>> {
    let mut content = Vec::new();
    let mut limited = reader.take(cap + 1);
    limited.read_to_end(&mut content).ok()?;
    if content.len() as u64 > cap {
        return None;
    }
    Some(content)
}

/// Resize raw image bytes to a JPEG thumbnail capped at `max_width`.
/// Dimensions are checked BEFORE decoding so pixel-bomb images are skipped
/// without allocating their full decoded buffer.
fn resize_image_to_jpeg(data: &[u8], max_width: u32) -> Option<Vec<u8>> {
    // Header-only dimension pass: refuses pixel-bomb images before any raster
    // allocation. A second reader then decodes (re-reading the header is
    // cheap).
    let (w, h) = {
        let probe = image::ImageReader::new(std::io::Cursor::new(data))
            .with_guessed_format()
            .ok()?;
        probe.into_dimensions().ok()?
    };
    if w == 0 || h == 0 || w > MAX_THUMBNAIL_SOURCE_DIMENSION || h > MAX_THUMBNAIL_SOURCE_DIMENSION
    {
        return None;
    }
    let reader = image::ImageReader::new(std::io::Cursor::new(data))
        .with_guessed_format()
        .ok()?;
    let img = reader.decode().ok()?;
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

/// Render page 1 of a PDF to JPEG using the Rust MuPDF wrapper.
fn generate_thumbnail_pdf(data: &[u8]) -> Option<Vec<u8>> {
    if data.len() as u64 > MAX_THUMBNAIL_SOURCE_SIZE {
        return None;
    }
    let tmp_dir = tempfile::tempdir().ok()?;
    let pdf_path = tmp_dir.path().join("input.pdf");
    std::fs::write(&pdf_path, data).ok()?;

    let handle = crate::pdf::PdfHandle::open(pdf_path.to_str()?.to_string()).ok()?;
    let rendered = handle.render_page(0, 200).ok()?;

    let img =
        image::RgbaImage::from_raw(rendered.width as u32, rendered.height as u32, rendered.data)?;
    let mut jpeg_buf = std::io::Cursor::new(Vec::new());
    let encoder = image::codecs::jpeg::JpegEncoder::new_with_quality(&mut jpeg_buf, 90);
    img.write_with_encoder(encoder).ok()?;
    Some(jpeg_buf.into_inner())
}

/// Extract the cover image from an EPUB (ZIP) archive.
fn generate_thumbnail_epub(data: &[u8]) -> Option<Vec<u8>> {
    let cursor = std::io::Cursor::new(data);
    let mut archive = zip::ZipArchive::new(cursor).ok()?;
    if archive.len() > MAX_ARCHIVE_ENTRY_SCAN {
        return None;
    }

    let container_xml = {
        let mut f = archive.by_name("META-INF/container.xml").ok()?;
        let bytes = read_entry_limited(&mut f, MAX_ARCHIVE_METADATA_SIZE)?;
        String::from_utf8(bytes).ok()?
    };
    let rootfile_path = parse_container_rootfile(&container_xml)?;

    let opf_content = {
        let mut f = archive.by_name(&rootfile_path).ok()?;
        let bytes = read_entry_limited(&mut f, MAX_ARCHIVE_METADATA_SIZE)?;
        String::from_utf8(bytes).ok()?
    };
    let cover_href = parse_opf_cover(&opf_content)?;

    let opf_dir = std::path::Path::new(&rootfile_path)
        .parent()
        .unwrap_or(std::path::Path::new(""));
    let cover_path = opf_dir.join(&cover_href).to_string_lossy().to_string();

    // The target is attacker-controlled XML content — keep it a normal
    // relative archive name (the ZIP lookup is literal, but reject anything
    // with `..`/absolute components defensively before using it).
    if crate::format::path::safe_archive_path(&cover_path).is_err() {
        return None;
    }

    let mut f = archive.by_name(&cover_path).ok()?;
    read_entry_limited(&mut f, MAX_ARCHIVE_IMAGE_SIZE)
}

/// Extract the first page image from a CBZ (comic ZIP) archive.
fn generate_thumbnail_cbz(data: &[u8]) -> Option<Vec<u8>> {
    let cursor = std::io::Cursor::new(data);
    let mut archive = zip::ZipArchive::new(cursor).ok()?;

    let image_exts = [".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp"];
    let mut images: Vec<String> = archive
        .file_names()
        .take(MAX_ARCHIVE_ENTRY_SCAN)
        .filter_map(|name| name.ok())
        .filter(|name| {
            let lower = name.to_lowercase();
            !lower.starts_with("__MACOSX") && image_exts.iter().any(|ext| lower.ends_with(ext))
        })
        .map(|s| s.to_string())
        .collect();
    if archive.len() > MAX_ARCHIVE_ENTRY_SCAN {
        // Reached the scan cap — too many entries to trust the cover pick.
        return None;
    }
    images.sort();

    let first = images.first()?;
    let mut f = archive.by_name(first).ok()?;
    read_entry_limited(&mut f, MAX_ARCHIVE_IMAGE_SIZE)
}

/// Generate a JPEG thumbnail for a document.
///
/// Supported formats:
/// - `image/*` — decoded and resized directly
/// - `application/pdf` — page 1 rendered via Rust MuPDF
/// - `application/epub+zip` — cover image extracted from the EPUB archive
/// - `application/vnd.comicbook+zip` — first page image from the CBZ archive
///
/// Returns `None` when the format is unsupported or generation fails.
pub fn generate_thumbnail(data: &[u8], mime_type: &str) -> Option<Vec<u8>> {
    // PR 11: never feed unbounded source bytes to any decoder.
    if data.len() as u64 > MAX_THUMBNAIL_SOURCE_SIZE {
        return None;
    }
    let raw = match mime_type {
        "application/pdf" => generate_thumbnail_pdf(data)?,
        "application/epub+zip" => generate_thumbnail_epub(data)?,
        "application/vnd.comicbook+zip" | "application/x-cbr" => generate_thumbnail_cbz(data)?,
        mt if mt.starts_with("image/") => data.to_vec(),
        _ => return None,
    };
    resize_image_to_jpeg(&raw, THUMBNAIL_MAX_WIDTH)
}

/// Save a thumbnail blob at `base_dir/files/<id>.thumb`.
pub fn store_thumbnail(
    base_dir: &Path,
    id: &str,
    data: &[u8],
    key: Option<&[u8]>,
) -> std::io::Result<()> {
    let path = base_dir.join("files").join(format!("{id}.thumb"));
    write_thumbnail_blob(&path, data, key)
}

/// Encrypt (when a key is supplied) and write a thumbnail blob to `path`.
fn write_thumbnail_blob(path: &Path, data: &[u8], key: Option<&[u8]>) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let blob = if let Some(k) = key {
        let (iv, ct) = aes_gcm::encrypt_bytes(data, k).unwrap_or_else(|| (vec![], vec![]));
        if iv.is_empty() {
            return Err(std::io::Error::other("encryption failed"));
        }
        let mut out = iv;
        out.extend_from_slice(&ct);
        out
    } else {
        data.to_vec()
    };
    std::fs::write(path, blob)
}

/// Generate thumbnails for documents that do not have one on disk yet.
///
/// Import/restore paths call this after document blobs are in place so documents
/// that arrive without a `.thumb` file (older backups, backups from clients that
/// did not create thumbnails, or merges that only copied the document blob) get
/// one. Existing thumbnails are never overwritten.
///
/// `files_dir` is the vault's `files/` directory. Generation is best-effort:
/// unsupported formats, undecodable files and write failures are skipped, and
/// the number of thumbnails actually written is returned.
pub fn generate_missing_thumbnails(
    conn: &Connection,
    files_dir: &Path,
    key: Option<&[u8]>,
) -> usize {
    let docs = match queries::list_documents(conn) {
        Ok(docs) => docs,
        Err(_) => return 0,
    };

    let mut generated = 0;
    for doc in docs {
        // Document ids come from backups, so treat them as untrusted: only
        // single-component names may be used for a thumbnail file.
        let thumb_name = format!("{}.thumb", doc.id);
        if crate::format::path::safe_archive_path(&thumb_name).is_err() || thumb_name.contains('/')
        {
            continue;
        }
        let thumb_path = files_dir.join(&thumb_name);
        if thumb_path.exists() {
            continue;
        }

        let Some(file_data) = load_document_file(files_dir, &doc, key) else {
            continue;
        };
        let Some(thumb_data) = generate_thumbnail(&file_data, &doc.mime_type) else {
            continue;
        };
        if write_thumbnail_blob(&thumb_path, &thumb_data, key).is_ok() {
            generated += 1;
        }
    }
    generated
}

/// Read and decrypt a document blob for thumbnail generation.
///
/// Uses the `file_path` recorded in the DB first (it may be absolute after a
/// merge), then the conventional `files/<file_name>` and `files/<id>` locations.
fn load_document_file(files_dir: &Path, doc: &DocumentRow, key: Option<&[u8]>) -> Option<Vec<u8>> {
    let stored_path = Path::new(&doc.file_path);
    let mut candidates = Vec::new();
    if stored_path.is_absolute() {
        candidates.push(stored_path.to_path_buf());
    }
    if let Some(name) = stored_path.file_name() {
        candidates.push(files_dir.join(name));
    }
    candidates.push(files_dir.join(&doc.id));

    let raw = candidates
        .iter()
        .find_map(|path| std::fs::read(path).ok())?;
    match key {
        Some(k) => {
            let iv = doc.encryption_iv.as_deref()?;
            if raw.len() > aes_gcm::IV_LENGTH {
                // Legacy vaults stored plaintext blobs with a dummy IV; fall
                // back to the raw bytes when decryption fails.
                aes_gcm::decrypt_bytes(&raw[aes_gcm::IV_LENGTH..], k, iv).or(Some(raw))
            } else {
                Some(raw)
            }
        }
        None => Some(raw),
    }
}

/// Load a thumbnail blob from `base_dir/files/<id>.thumb`.
pub fn load_thumbnail(base_dir: &Path, id: &str, key: Option<&[u8]>) -> Option<Vec<u8>> {
    let path = base_dir.join("files").join(format!("{id}.thumb"));
    let raw = if path.exists() {
        std::fs::read(&path).ok()?
    } else {
        return None;
    };
    if let Some(k) = key {
        if raw.len() < aes_gcm::IV_LENGTH {
            return None;
        }
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
#[allow(clippy::too_many_arguments)]
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
        let (real_iv, ct) = aes_gcm::encrypt_bytes(file_data, k).ok_or_else(|| {
            rusqlite::Error::ToSqlConversionFailure(Box::new(std::io::Error::other(
                "encryption failed",
            )))
        })?;
        let mut out = real_iv.clone();
        out.extend_from_slice(&ct);
        (out, real_iv)
    } else {
        // No key — store plaintext with random garbage IV (legacy behavior)
        let dummy_iv: Vec<u8> = (0..aes_gcm::IV_LENGTH)
            .map(|_| rand::random::<u8>())
            .collect();
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

    save_file(base_dir, id, &stored_data)
        .map_err(|e| rusqlite::Error::ToSqlConversionFailure(Box::new(e)))?;

    if let Some(thumb_data) = generate_thumbnail(file_data, mime_type) {
        let _ = store_thumbnail(base_dir, id, &thumb_data, key);
    }

    Ok(id.to_string())
}

/// Export a document's file blob from storage (decrypted if key is provided).
pub fn export_document_file(
    conn: &Connection,
    base_dir: &Path,
    id: &str,
    key: Option<&[u8]>,
) -> Option<Vec<u8>> {
    let doc = queries::get_document(conn, id).ok()??;
    let raw = load_file(base_dir, &doc.id)?;
    if let Some(k) = key {
        let iv = doc.encryption_iv.as_deref()?;
        if raw.len() < aes_gcm::IV_LENGTH {
            return None;
        }
        let ct = &raw[aes_gcm::IV_LENGTH..];
        aes_gcm::decrypt_bytes(ct, k, iv)
    } else {
        Some(raw)
    }
}

/// Delete a document: remove file blob, delete DB row.
/// FTS index is cleaned up automatically by the fts_after_delete trigger.
pub fn delete_document_full(
    conn: &Connection,
    base_dir: &Path,
    id: &str,
) -> rusqlite::Result<bool> {
    delete_file(base_dir, id);
    queries::delete_document(conn, id)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::db::schema::create_encrypted_db;
    use std::io::Write;

    #[test]
    fn test_import_export_roundtrip_with_key() {
        let mk = (0..32).collect::<Vec<u8>>();
        let tmp = tempfile::TempDir::new().unwrap();

        let db_path = tmp.path().join("databases/librecrate.db");
        std::fs::create_dir_all(db_path.parent().unwrap()).unwrap();
        let conn = create_encrypted_db(db_path.to_str().unwrap(), &mk).unwrap();

        let data = b"Hello, world!".to_vec();
        let doc_id = import_document(
            &conn,
            tmp.path(),
            "test-doc-1",
            "Test Doc",
            &data,
            "text/plain",
            "Author",
            "Description",
            Some("hello world content"),
            Some(&mk),
        )
        .unwrap();
        assert_eq!(doc_id, "test-doc-1");

        // Verify file exists (as encrypted blob)
        let file_path = tmp.path().join("files/test-doc-1");
        assert!(file_path.exists());
        let raw = std::fs::read(&file_path).unwrap();
        assert!(raw.len() > data.len()); // iv + ciphertext > plaintext
        let db_doc = queries::get_document(&conn, "test-doc-1").unwrap().unwrap();
        assert_eq!(
            &raw[..aes_gcm::IV_LENGTH],
            db_doc.encryption_iv.as_deref().unwrap()
        );

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
            &conn,
            tmp.path(),
            "test-plain",
            "Test Doc",
            &data,
            "text/plain",
            "Author",
            "Description",
            None,
            None,
        )
        .unwrap();

        // File stored as plaintext
        let raw = std::fs::read(tmp.path().join("files/test-plain")).unwrap();
        assert_eq!(raw, data);

        // Export back
        let exported = export_document_file(&conn, tmp.path(), "test-plain", None).unwrap();
        assert_eq!(exported, data);
    }

    // -----------------------------------------------------------------------
    // PR 11 — thumbnail parser hardening
    // -----------------------------------------------------------------------

    #[test]
    fn thumbnail_rejects_oversized_source() {
        // 65 MiB source is refused without touching a decoder.
        let big = vec![0u8; (MAX_THUMBNAIL_SOURCE_SIZE + 1) as usize];
        assert!(generate_thumbnail(&big, "image/png").is_none());
        assert!(generate_thumbnail(&big, "application/pdf").is_none());
        assert!(generate_thumbnail(&big, "application/epub+zip").is_none());
    }

    #[test]
    fn thumbnail_rejects_pixel_bomb_dimensions() {
        // 9000 px wide — past the 8192 axis cap. Decoding this would allocate
        // ~240 MB if it reached the raster; the header-only dimension check
        // must stop it before then.
        let mut png = image::RgbaImage::new(9000, 20);
        for px in png.pixels_mut() {
            *px = image::Rgba([0, 0, 0, 255]);
        }
        let mut bytes = Vec::new();
        image::DynamicImage::ImageRgba8(png)
            .write_to(
                &mut std::io::Cursor::new(&mut bytes),
                image::ImageFormat::Png,
            )
            .unwrap();
        assert_eq!(MAX_THUMBNAIL_SOURCE_DIMENSION, 8192);
        assert!(generate_thumbnail(&bytes, "image/png").is_none());
    }

    #[test]
    fn thumbnail_small_image_still_works() {
        let mut img = image::RgbaImage::new(120, 80);
        for (x, y, px) in img.enumerate_pixels_mut() {
            *px = image::Rgba([(x % 256) as u8, (y % 256) as u8, 128, 255]);
        }
        let mut bytes = Vec::new();
        image::DynamicImage::ImageRgba8(img)
            .write_to(
                &mut std::io::Cursor::new(&mut bytes),
                image::ImageFormat::Png,
            )
            .unwrap();
        let thumb = generate_thumbnail(&bytes, "image/png").expect("valid small png");
        assert_eq!(thumb[..2], [0xff, 0xd8]); // JPEG magic
    }

    #[test]
    fn thumbnail_epub_rejects_oversized_metadata() {
        // A container.xml larger than the metadata cap must abort thumbnail
        // generation (bounds the XML string read).
        let mut zip = Vec::new();
        {
            let mut writer = zip::ZipWriter::new(std::io::Cursor::new(&mut zip));
            let huge = vec![b'x'; (MAX_ARCHIVE_METADATA_SIZE + 1) as usize];
            writer
                .start_file::<&str, ()>(
                    "META-INF/container.xml",
                    zip::write::FileOptions::default(),
                )
                .unwrap();
            writer.write_all(&huge).unwrap();
            writer.finish().unwrap();
        }
        assert!(generate_thumbnail(&zip, "application/epub+zip").is_none());
    }

    #[test]
    fn thumbnail_cbz_rejects_oversized_page() {
        // A first-page image larger than the cap must abort generation
        // instead of ballooning memory.
        let mut zip = Vec::new();
        {
            let mut writer = zip::ZipWriter::new(std::io::Cursor::new(&mut zip));
            let huge = vec![0u8; (MAX_ARCHIVE_IMAGE_SIZE + 1) as usize];
            writer
                .start_file::<&str, ()>("0001.png", zip::write::FileOptions::default())
                .unwrap();
            writer.write_all(&huge).unwrap();
            writer.finish().unwrap();
        }
        assert!(generate_thumbnail(&zip, "application/vnd.comicbook+zip").is_none());
    }

    fn tiny_png() -> Vec<u8> {
        let mut img = image::RgbaImage::new(40, 30);
        for (x, y, px) in img.enumerate_pixels_mut() {
            *px = image::Rgba([(x % 256) as u8, (y % 256) as u8, 100, 255]);
        }
        let mut bytes = Vec::new();
        image::DynamicImage::ImageRgba8(img)
            .write_to(
                &mut std::io::Cursor::new(&mut bytes),
                image::ImageFormat::Png,
            )
            .unwrap();
        bytes
    }

    #[test]
    fn generate_missing_thumbnails_fills_missing_and_skips_existing() {
        let mk = (0..32).collect::<Vec<u8>>();
        let tmp = tempfile::TempDir::new().unwrap();
        let db_path = tmp.path().join("databases/librecrate.db");
        std::fs::create_dir_all(db_path.parent().unwrap()).unwrap();
        let conn = create_encrypted_db(db_path.to_str().unwrap(), &mk).unwrap();

        let doc_id = import_document(
            &conn,
            tmp.path(),
            "image-doc",
            "Image Doc",
            &tiny_png(),
            "image/png",
            "",
            "",
            None,
            Some(&mk),
        )
        .unwrap();

        // Import already wrote a thumbnail; remove it to simulate a backup that
        // did not carry one.
        let thumb_path = tmp.path().join("files").join(format!("{doc_id}.thumb"));
        assert!(thumb_path.exists());
        std::fs::remove_file(&thumb_path).unwrap();

        let files_dir = tmp.path().join("files");
        assert_eq!(generate_missing_thumbnails(&conn, &files_dir, Some(&mk)), 1);
        assert!(thumb_path.exists());

        let thumb = load_thumbnail(tmp.path(), &doc_id, Some(&mk)).unwrap();
        assert_eq!(&thumb[..2], [0xff, 0xd8]); // JPEG magic

        // Existing thumbnails are not regenerated.
        assert_eq!(generate_missing_thumbnails(&conn, &files_dir, Some(&mk)), 0);

        // Unsupported documents without a thumbnail are skipped, not failed.
        import_document(
            &conn,
            tmp.path(),
            "text-doc",
            "Text Doc",
            b"plain text".as_slice(),
            "text/plain",
            "",
            "",
            None,
            Some(&mk),
        )
        .unwrap();
        assert_eq!(generate_missing_thumbnails(&conn, &files_dir, Some(&mk)), 0);
    }

    #[test]
    fn generate_missing_thumbnails_rejects_unsafe_ids() {
        let mk = (0..32).collect::<Vec<u8>>();
        let tmp = tempfile::TempDir::new().unwrap();
        let db_path = tmp.path().join("databases/librecrate.db");
        std::fs::create_dir_all(db_path.parent().unwrap()).unwrap();
        let conn = create_encrypted_db(db_path.to_str().unwrap(), &mk).unwrap();

        // A hostile id must not be able to write outside files/.
        queries::add_document(
            &conn,
            &DocumentRow {
                id: "../escape".into(),
                title: "Escape".into(),
                file_name: "escape".into(),
                mime_type: "image/png".into(),
                file_path: "files/escape".into(),
                ..Default::default()
            },
        )
        .unwrap();
        std::fs::create_dir_all(tmp.path().join("files")).unwrap();
        std::fs::write(tmp.path().join("files/escape"), tiny_png()).unwrap();

        assert_eq!(
            generate_missing_thumbnails(&conn, &tmp.path().join("files"), Some(&mk)),
            0
        );
        assert!(!tmp.path().join("escape.thumb").exists());
        assert!(!tmp.path().join("files/../escape.thumb").exists());
    }
}
