use std::sync::{Arc, Mutex};

use mupdf::{Colorspace, Document as MuPdfDocument, Matrix, MetadataName, TextExtractOptions};
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

struct SendDocument(MuPdfDocument);

unsafe impl Send for SendDocument {}

#[derive(uniffi::Object)]
pub struct PdfHandle {
    doc: Mutex<SendDocument>,
}

#[uniffi::export]
impl PdfHandle {
    #[uniffi::constructor]
    pub fn open(path: String) -> Result<Arc<Self>, PdfError> {
        let doc = MuPdfDocument::open(path.as_str())
            .map_err(|e| PdfError::OpenFailed { msg: e.to_string() })?;
        Ok(Arc::new(Self {
            doc: Mutex::new(SendDocument(doc)),
        }))
    }

    pub fn page_count(&self) -> Result<i32, PdfError> {
        let guard = self.doc.lock().map_err(|_| PdfError::LockFailed)?;
        guard.0.page_count().map_err(|e| PdfError::QueryFailed { msg: e.to_string() })
    }

    pub fn metadata(&self, key: String) -> Result<String, PdfError> {
        let guard = self.doc.lock().map_err(|_| PdfError::LockFailed)?;
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
        guard.0.metadata(name).map_err(|e| PdfError::QueryFailed { msg: e.to_string() })
    }

    pub fn render_page(&self, page_index: i32, target_width: i32) -> Result<PdfPageRender, PdfError> {
        let guard = self.doc.lock().map_err(|_| PdfError::LockFailed)?;

        let page_count = guard.0.page_count().map_err(|e| PdfError::QueryFailed { msg: e.to_string() })?;
        if page_index < 0 || page_index >= page_count {
            return Err(PdfError::LoadPageFailed {
                msg: format!("Page index {page_index} out of range (0..{page_count})"),
            });
        }

        let page = guard
            .0
            .load_page(page_index)
            .map_err(|e| PdfError::LoadPageFailed { msg: e.to_string() })?;

        let bounds = page.bounds().map_err(|e| PdfError::QueryFailed { msg: e.to_string() })?;
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

    pub fn extract_text(&self, page_index: i32) -> Result<String, PdfError> {
        let guard = self.doc.lock().map_err(|_| PdfError::LockFailed)?;

        let page = guard
            .0
            .load_page(page_index)
            .map_err(|e| PdfError::LoadPageFailed { msg: e.to_string() })?;

        page.text(TextExtractOptions::default())
            .map_err(|e| PdfError::ExtractFailed { msg: e.to_string() })
    }
}
