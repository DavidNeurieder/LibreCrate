use super::{DocumentReader, ReaderError, ReaderMeta};
use std::path::Path;

pub struct PdfReader {
    inner: pdf_oxide::PdfDocument,
    page_count: u32,
}

impl PdfReader {
    pub fn open(path: &Path) -> Result<Self, ReaderError> {
        let doc = pdf_oxide::PdfDocument::open(path)
            .map_err(|e| ReaderError::OpenFailed(e.to_string()))?;
        let page_count = doc
            .page_count()
            .map_err(|e| ReaderError::ParseFailed(e.to_string()))?;
        Ok(Self {
            inner: doc,
            page_count: page_count as u32,
        })
    }
}

impl DocumentReader for PdfReader {
    fn page_count(&self) -> Result<u32, ReaderError> {
        Ok(self.page_count)
    }

    fn extract_text(&self, page_index: u32) -> Result<String, ReaderError> {
        self.inner
            .extract_text(page_index as usize)
            .map_err(|e| ReaderError::ExtractFailed(e.to_string()))
    }

    fn extract_all_text(&self) -> Result<String, ReaderError> {
        self.inner
            .extract_all_text()
            .map_err(|e| ReaderError::ExtractFailed(e.to_string()))
    }

    fn metadata(&self) -> Result<ReaderMeta, ReaderError> {
        Ok(ReaderMeta {
            title: None,
            author: None,
            page_count: self.page_count,
        })
    }
}
