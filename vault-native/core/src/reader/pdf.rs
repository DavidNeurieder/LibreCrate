use super::{DocumentReader, ReaderError, ReaderMeta, RenderedPage};
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

    fn render_page(&self, page_index: u32, scale: f32) -> Result<RenderedPage, ReaderError> {
        let dpi = (150.0 * scale) as u32;
        let options = pdf_oxide::rendering::RenderOptions::with_dpi(dpi).as_raw();
        let img = pdf_oxide::rendering::render_page(&self.inner, page_index as usize, &options)
            .map_err(|e| ReaderError::RenderFailed(e.to_string()))?;
        Ok(RenderedPage {
            data: img.data,
            width: img.width,
            height: img.height,
        })
    }

    fn render_thumbnail(&self) -> Result<Vec<u8>, ReaderError> {
        let options = pdf_oxide::rendering::RenderOptions::with_dpi(72);
        let img = pdf_oxide::rendering::render_page(&self.inner, 0, &options)
            .map_err(|e| ReaderError::RenderFailed(e.to_string()))?;
        Ok(img.data)
    }

    fn metadata(&self) -> Result<ReaderMeta, ReaderError> {
        Ok(ReaderMeta {
            title: None,
            author: None,
            page_count: self.page_count,
        })
    }
}
