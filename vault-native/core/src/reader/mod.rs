use std::path::Path;

pub mod text;
#[cfg(feature = "pdf")]
pub mod pdf;

/// Data from rendering a single page.
pub struct RenderedPage {
    pub data: Vec<u8>,
    pub width: u32,
    pub height: u32,
}

/// Basic document metadata.
pub struct ReaderMeta {
    pub title: Option<String>,
    pub author: Option<String>,
    pub page_count: u32,
}

#[derive(Debug)]
pub enum ReaderError {
    UnsupportedFormat(String),
    OpenFailed(String),
    ParseFailed(String),
    NotSupported(String),
    RenderFailed(String),
    ExtractFailed(String),
    Io(std::io::Error),
}

impl std::fmt::Display for ReaderError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::UnsupportedFormat(m) => write!(f, "unsupported format: {m}"),
            Self::OpenFailed(m) => write!(f, "failed to open: {m}"),
            Self::ParseFailed(m) => write!(f, "parse failed: {m}"),
            Self::NotSupported(op) => write!(f, "operation not supported: {op}"),
            Self::RenderFailed(m) => write!(f, "render failed: {m}"),
            Self::ExtractFailed(m) => write!(f, "text extraction failed: {m}"),
            Self::Io(e) => write!(f, "I/O error: {e}"),
        }
    }
}

impl std::error::Error for ReaderError {}

impl From<std::io::Error> for ReaderError {
    fn from(e: std::io::Error) -> Self {
        Self::Io(e)
    }
}

/// Trait for all document format readers.
///
/// New formats implement this trait and register themselves in [`open`].
pub trait DocumentReader: Send {
    fn page_count(&self) -> Result<u32, ReaderError>;
    fn extract_text(&self, page_index: u32) -> Result<String, ReaderError>;

    fn extract_all_text(&self) -> Result<String, ReaderError> {
        let n = self.page_count()?;
        let mut pages = Vec::with_capacity(n as usize);
        for i in 0..n {
            if let Ok(text) = self.extract_text(i) {
                pages.push(text);
            }
        }
        Ok(pages.join("\n\n"))
    }

    fn render_page(&self, _page_index: u32, _scale: f32) -> Result<RenderedPage, ReaderError> {
        Err(ReaderError::NotSupported("render_page".into()))
    }

    fn metadata(&self) -> Result<ReaderMeta, ReaderError> {
        Ok(ReaderMeta {
            title: None,
            author: None,
            page_count: self.page_count()?,
        })
    }
}

/// Open a document by path and MIME type.
///
/// Returns a boxed [`DocumentReader`] that can extract text, render pages, etc.
/// Returns [`ReaderError::UnsupportedFormat`] if the MIME type has no reader.
pub fn open(path: &Path, mime: &str) -> Result<Box<dyn DocumentReader>, ReaderError> {
    match mime {
        #[cfg(feature = "pdf")]
        "application/pdf" => pdf::PdfReader::open(path).map(|r| Box::new(r) as _),
        m if m.starts_with("text/") => text::TextReader::open(path).map(|r| Box::new(r) as _),
        _ => Err(ReaderError::UnsupportedFormat(mime.into())),
    }
}

/// Convenience: open a document and extract all text for FTS indexing.
///
/// Returns `None` silently for any error (unreadable, unsupported, etc.).
pub fn extract_text(path: &Path, mime: &str) -> Option<String> {
    let reader = open(path, mime).ok()?;
    reader.extract_all_text().ok().filter(|s| !s.is_empty())
}
