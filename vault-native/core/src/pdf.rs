use std::sync::{Arc, Mutex};

use mupdf::{Colorspace, Document as MuPdfDocument, Matrix, MetadataName, Page as MuPdfPage, TextExtractOptions};
#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum PdfError {
    #[error("Failed to open document: {msg}")]
    OpenFailed { msg: String },
    #[error("Failed to lock document")]
    LockFailed,
    #[error("Failed to query document: {msg}")]
    QueryFailed { msg: String },
    #[error("Failed to load page: {msg}")]
    LoadPageFailed { msg: String },
    #[error("Failed to render page: {msg}")]
    RenderFailed { msg: String },
    #[error("Failed to extract text: {msg}")]
    ExtractFailed { msg: String },
}

#[derive(uniffi::Record)]
pub struct PdfPageRender {
    pub data: Vec<u8>,
    pub width: i32,
    pub height: i32,
}

#[derive(uniffi::Record)]
pub struct PdfLocation {
    pub chapter: i32,
    pub page_in_chapter: i32,
}

struct DocumentState {
    doc: MuPdfDocument,
    /// Reflowable documents (EPUB, FB2, ...) are laid out chapter by chapter.
    /// Counting or loading pages through the absolute-page API lays out the
    /// whole book, so callers of large reflowable docs must use the
    /// chapter-based methods instead.
    reflowable: bool,
    /// Total page count. Cached at open for non-reflowable documents (cheap);
    /// -1 for reflowable docs until someone explicitly asks for the total
    /// (which lays out every chapter).
    page_count: i32,
}

unsafe impl Send for DocumentState {}

#[derive(uniffi::Object)]
pub struct PdfHandle {
    state: Mutex<DocumentState>,
}

#[uniffi::export]
impl PdfHandle {
    #[uniffi::constructor]
    pub fn open(path: String) -> Result<Arc<Self>, PdfError> {
        let doc = MuPdfDocument::open(path.as_str())
            .map_err(|e| PdfError::OpenFailed { msg: e.to_string() })?;
        let reflowable = doc
            .is_reflowable()
            .map_err(|e| PdfError::QueryFailed { msg: e.to_string() })?;
        let page_count = if reflowable {
            -1
        } else {
            doc.page_count()
                .map_err(|e| PdfError::QueryFailed { msg: e.to_string() })?
        };
        Ok(Arc::new(Self {
            state: Mutex::new(DocumentState {
                doc,
                reflowable,
                page_count,
            }),
        }))
    }

    /// Total page count. For reflowable documents this lays out every chapter
    /// and can be slow on large books; prefer the chapter-based methods there.
    pub fn page_count(&self) -> Result<i32, PdfError> {
        let mut guard = self.state.lock().map_err(|_| PdfError::LockFailed)?;
        if guard.page_count < 0 {
            guard.page_count = guard
                .doc
                .page_count()
                .map_err(|e| PdfError::QueryFailed { msg: e.to_string() })?;
        }
        Ok(guard.page_count)
    }

    /// Whether the document is reflowable (EPUB, FB2, ...) and therefore
    /// benefits from chapter-based lazy layout.
    pub fn is_reflowable(&self) -> Result<bool, PdfError> {
        let guard = self.state.lock().map_err(|_| PdfError::LockFailed)?;
        Ok(guard.reflowable)
    }

    /// Number of chapters. Non-reflowable documents report a single chapter.
    pub fn chapter_count(&self) -> Result<i32, PdfError> {
        let guard = self.state.lock().map_err(|_| PdfError::LockFailed)?;
        guard
            .doc
            .count_chapters()
            .map_err(|e| PdfError::QueryFailed { msg: e.to_string() })
    }

    /// Number of pages in one chapter. Only that chapter is laid out.
    pub fn chapter_page_count(&self, chapter: i32) -> Result<i32, PdfError> {
        let guard = self.state.lock().map_err(|_| PdfError::LockFailed)?;
        guard
            .doc
            .count_chapter_pages(chapter)
            .map_err(|e| PdfError::QueryFailed { msg: e.to_string() })
    }

    /// Render a page within a chapter without laying out other chapters.
    pub fn render_chapter_page(
        &self,
        chapter: i32,
        page_in_chapter: i32,
        target_width: i32,
    ) -> Result<PdfPageRender, PdfError> {
        let guard = self.state.lock().map_err(|_| PdfError::LockFailed)?;
        let page = guard
            .doc
            .load_chapter_page(chapter, page_in_chapter)
            .map_err(|e| PdfError::LoadPageFailed { msg: e.to_string() })?;
        render_page_object(&page, target_width)
    }

    /// Map an absolute page number to a chapter/page location. For reflowable
    /// documents this lays out chapters only up to the one containing the page.
    pub fn location_from_page_number(&self, page_number: i32) -> Result<PdfLocation, PdfError> {
        let guard = self.state.lock().map_err(|_| PdfError::LockFailed)?;
        guard
            .doc
            .location_from_page_number(page_number)
            .map(|loc| PdfLocation {
                chapter: loc.chapter as i32,
                page_in_chapter: loc.page_in_chapter as i32,
            })
            .map_err(|e| PdfError::QueryFailed { msg: e.to_string() })
    }

    /// Map a chapter/page location back to an absolute page number.
    pub fn page_number_from_location(&self, chapter: i32, page_in_chapter: i32) -> Result<i32, PdfError> {
        let guard = self.state.lock().map_err(|_| PdfError::LockFailed)?;
        guard
            .doc
            .page_number_from_location(chapter, page_in_chapter)
            .map_err(|e| PdfError::QueryFailed { msg: e.to_string() })
    }

    pub fn metadata(&self, key: String) -> Result<String, PdfError> {
        let guard = self.state.lock().map_err(|_| PdfError::LockFailed)?;
        let name = match key.as_str() {
            "format" => MetadataName::Format,
            "encryption" => MetadataName::Encryption,
            "author" => MetadataName::Author,
            "title" => MetadataName::Title,
            "producer" => MetadataName::Producer,
            "creator" => MetadataName::Creator,
            "creation_date" => MetadataName::CreationDate,
            "mod_date" => MetadataName::ModDate,
            "subject" => MetadataName::Subject,
            "keywords" => MetadataName::Keywords,
            _ => return Err(PdfError::QueryFailed { msg: format!("Unknown metadata key: {key}") }),
        };
        guard.doc.metadata(name).map_err(|e| PdfError::QueryFailed { msg: e.to_string() })
    }

    pub fn render_page(&self, page_index: i32, target_width: i32) -> Result<PdfPageRender, PdfError> {
        let guard = self.state.lock().map_err(|_| PdfError::LockFailed)?;

        // Only range-check when the total is already known without extra cost
        // (non-reflowable). For reflowable docs fz reports out-of-range loads
        // itself, and checking here would force a full layout on every render.
        if guard.page_count >= 0
            && (page_index < 0 || page_index >= guard.page_count)
        {
            return Err(PdfError::LoadPageFailed {
                msg: format!(
                    "Page index {page_index} out of range (0..{})",
                    guard.page_count
                ),
            });
        }

        let page = guard
            .doc
            .load_page(page_index)
            .map_err(|e| PdfError::LoadPageFailed { msg: e.to_string() })?;
        render_page_object(&page, target_width)
    }

    pub fn extract_text(&self, page_index: i32) -> Result<String, PdfError> {
        let guard = self.state.lock().map_err(|_| PdfError::LockFailed)?;

        let page = guard
            .doc
            .load_page(page_index)
            .map_err(|e| PdfError::LoadPageFailed { msg: e.to_string() })?;

        page.text(TextExtractOptions::default())
            .map_err(|e| PdfError::ExtractFailed { msg: e.to_string() })
    }

    /// Full-document case-insensitive search. For reflowable documents this
    /// walks chapters one at a time so it never triggers a full upfront layout;
    /// it still lays out the whole book over the course of the search. Returns
    /// absolute page numbers of matching pages.
    pub fn search_document(&self, query: String) -> Result<Vec<i32>, PdfError> {
        let q = query.trim().to_lowercase();
        if q.is_empty() {
            return Ok(Vec::new());
        }
        let guard = self.state.lock().map_err(|_| PdfError::LockFailed)?;

        let mut matches = Vec::new();
        let mut absolute = 0i32;
        if guard.reflowable {
            let chapters = guard
                .doc
                .count_chapters()
                .map_err(|e| PdfError::QueryFailed { msg: e.to_string() })?;
            for chapter in 0..chapters {
                let pages = guard
                    .doc
                    .count_chapter_pages(chapter)
                    .map_err(|e| PdfError::QueryFailed { msg: e.to_string() })?;
                for p in 0..pages {
                    let page = guard
                        .doc
                        .load_chapter_page(chapter, p)
                        .map_err(|e| PdfError::LoadPageFailed { msg: e.to_string() })?;
                    let text = page
                        .text(TextExtractOptions::default())
                        .map_err(|e| PdfError::ExtractFailed { msg: e.to_string() })?;
                    if text.to_lowercase().contains(&q) {
                        matches.push(absolute);
                    }
                    absolute += 1;
                }
            }
        } else {
            let total = guard
                .doc
                .page_count()
                .map_err(|e| PdfError::QueryFailed { msg: e.to_string() })?;
            for i in 0..total {
                let page = guard
                    .doc
                    .load_page(i)
                    .map_err(|e| PdfError::LoadPageFailed { msg: e.to_string() })?;
                let text = page
                    .text(TextExtractOptions::default())
                    .map_err(|e| PdfError::ExtractFailed { msg: e.to_string() })?;
                if text.to_lowercase().contains(&q) {
                    matches.push(i);
                }
            }
        }
        Ok(matches)
    }
}

fn render_page_object(page: &MuPdfPage, target_width: i32) -> Result<PdfPageRender, PdfError> {
    let bounds = page
        .bounds()
        .map_err(|e| PdfError::QueryFailed { msg: e.to_string() })?;
    let page_width = bounds.width();
    let _page_height = bounds.height();

    let scale = if target_width > 0 && page_width > 0.0 {
        target_width as f32 / page_width
    } else {
        150.0 / 72.0
    };

    let matrix = Matrix::new_scale(scale, scale);

    let pixmap = page
        .to_pixmap(&matrix, &Colorspace::device_rgb(), true, false)
        .map_err(|e| PdfError::RenderFailed { msg: e.to_string() })?;

    let width = pixmap.width() as i32;
    let height = pixmap.height() as i32;
    let data = pixmap.samples().to_vec();

    Ok(PdfPageRender { data, width, height })
}

/// Best-effort full-text extraction for import-time FTS indexing.
/// Returns `None` when the mime type has no extractable text, or extraction
/// fails (in which case importing should still succeed).
pub fn extract_document_text(path: &str, mime: &str) -> Option<String> {
    if mime.starts_with("text/") || mime.contains("markdown") {
        return std::fs::read_to_string(path).ok();
    }
    let is_mupdf = mime.contains("pdf")
        || mime.contains("epub")
        || mime.contains("mobipocket")
        || mime.contains("fictionbook");
    if !is_mupdf {
        return None;
    }
    let handle = PdfHandle::open(path.to_string()).ok()?;
    let page_count = handle.page_count().ok()?;
    if page_count <= 0 {
        return None;
    }
    let mut out = String::new();
    for page in 0..page_count {
        if let Ok(text) = handle.extract_text(page) {
            if !text.trim().is_empty() {
                out.push_str(&text);
                out.push('\n');
            }
        }
        // Matches Android's PdfDocumentProcessor: a `[PAGE=N]` marker after each
        // page lets search_with_all_matches enumerate per-page matches.
        out.push_str(&format!("[PAGE={}]\n", page + 1));
    }
    if out.trim().is_empty() {
        None
    } else {
        Some(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture(name: &str) -> std::path::PathBuf {
        std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../gui/test_resources")
            .join(name)
    }

    fn open_fixture(name: &str) -> Arc<PdfHandle> {
        PdfHandle::open(fixture(name).to_string_lossy().to_string()).unwrap()
    }

    #[test]
    fn test_extract_text_file() {
        let dir = tempfile::TempDir::new().unwrap();
        let path = dir.path().join("note.txt");
        std::fs::write(&path, "alpha beta gamma").unwrap();
        let text = extract_document_text(&path.to_string_lossy(), "text/plain").unwrap();
        assert!(text.contains("beta"));
    }

    #[test]
    fn test_extract_text_markdown_mime() {
        let dir = tempfile::TempDir::new().unwrap();
        let path = dir.path().join("note.md");
        std::fs::write(&path, "# Heading\nbody word").unwrap();
        let text = extract_document_text(&path.to_string_lossy(), "text/markdown").unwrap();
        assert!(text.contains("body word"));
    }

    #[test]
    fn test_extract_text_non_text_mime_returns_none() {
        let dir = tempfile::TempDir::new().unwrap();
        let path = dir.path().join("photo.png");
        std::fs::write(&path, [0u8; 32]).unwrap();
        assert!(extract_document_text(&path.to_string_lossy(), "image/png").is_none());
        assert!(extract_document_text(&path.to_string_lossy(), "application/vnd.comicbook+zip").is_none());
    }

    #[test]
    fn test_extract_text_missing_file_returns_none() {
        assert!(extract_document_text("/nonexistent/file.pdf", "application/pdf").is_none());
        assert!(extract_document_text("/nonexistent/file.txt", "text/plain").is_none());
    }

    #[test]
    fn test_pdf_is_not_reflowable_and_single_chapter() {
        let handle = open_fixture("search_3page.pdf");
        assert!(!handle.is_reflowable().unwrap());
        assert_eq!(handle.chapter_count().unwrap(), 1);
        assert_eq!(handle.page_count().unwrap(), 3);
        assert_eq!(handle.chapter_page_count(0).unwrap(), 3);
    }

    #[test]
    fn test_epub_is_reflowable_with_chapters() {
        let handle = open_fixture("mini.epub");
        assert!(handle.is_reflowable().unwrap());
        assert!(handle.chapter_count().unwrap() >= 1);
        let pages_ch0 = handle.chapter_page_count(0).unwrap();
        assert!(pages_ch0 > 0);
        // Total page count is still reachable but is only computed when asked.
        let open_handle = open_fixture("mini.epub");
        assert!(open_handle.page_count().unwrap() >= pages_ch0);
    }

    #[test]
    fn test_render_chapter_page() {
        let handle = open_fixture("mini.epub");
        let r = handle.render_chapter_page(0, 0, 880).unwrap();
        assert!(r.width > 0 && r.height > 0 && !r.data.is_empty());
    }

    #[test]
    fn test_location_mapping_roundtrip() {
        let handle = open_fixture("mini.epub");
        let loc = handle.location_from_page_number(0).unwrap();
        assert_eq!(loc.chapter, 0);
        assert_eq!(loc.page_in_chapter, 0);
        assert_eq!(handle.page_number_from_location(0, 0).unwrap(), 0);
        let last = handle.page_count().unwrap() - 1;
        let loc_last = handle.location_from_page_number(last).unwrap();
        assert_eq!(
            handle.page_number_from_location(loc_last.chapter, loc_last.page_in_chapter).unwrap(),
            last
        );
    }

    #[test]
    fn test_multichapter_epub_chapters_and_lazy_open() {
        let handle = open_fixture("multi_chapter.epub");
        assert!(handle.is_reflowable().unwrap());
        assert_eq!(handle.chapter_count().unwrap(), 30);
        // Opening must not lay the whole book out: only the first chapter's
        // page count is needed, and rendering a single chapter page must not
        // pull in the rest of the book.
        let ch0_pages = handle.chapter_page_count(0).unwrap();
        assert!(ch0_pages > 0);
        let r = handle.render_chapter_page(0, 0, 880).unwrap();
        assert!(r.width > 0 && r.height > 0 && !r.data.is_empty());
    }

    #[test]
    fn test_multichapter_location_mapping_roundtrip() {
        let handle = open_fixture("multi_chapter.epub");
        let total = handle.page_count().unwrap();
        assert!(total > 30, "expected each chapter to span >= 1 page, got {total}");
        let mut last_chapter = -1;
        for p in 0..total {
            let loc = handle.location_from_page_number(p).unwrap();
            assert!(loc.chapter >= 0 && loc.chapter < 30);
            assert!(loc.chapter >= last_chapter, "chapters must be monotonic");
            last_chapter = loc.chapter;
            assert_eq!(
                handle.page_number_from_location(loc.chapter, loc.page_in_chapter).unwrap(),
                p,
                "roundtrip failed for page {p}"
            );
        }
        // Last page of the book lives in the final chapter.
        let last = handle.location_from_page_number(total - 1).unwrap();
        assert_eq!(last.chapter, 29);
    }

    #[test]
    fn test_multichapter_search_document() {
        let handle = open_fixture("multi_chapter.epub");
        let matches = handle
            .search_document("LibreCrateMulti 5-3".to_string())
            .unwrap();
        assert!(!matches.is_empty(), "expected a match in chapter 5");
        for p in matches {
            let loc = handle.location_from_page_number(p).unwrap();
            assert_eq!(loc.chapter, 5, "match on page {p} must live in chapter 5");
        }
        let none = handle
            .search_document("zzzz-no-such-string".to_string())
            .unwrap();
        assert!(none.is_empty());
    }

    #[test]
    fn test_search_document_finds_text() {
        let handle = open_fixture("mini.epub");
        let matches = handle.search_document("LibreCrateMiniEpub".to_string()).unwrap();
        assert!(!matches.is_empty(), "expected at least one matching page");

        let pdf = open_fixture("search_3page.pdf");
        let matches = pdf.search_document("Page 1".to_string()).unwrap();
        assert!(!matches.is_empty());
        let none = pdf.search_document("zzzzzz-no-such-string".to_string()).unwrap();
        assert!(none.is_empty());
    }
}


