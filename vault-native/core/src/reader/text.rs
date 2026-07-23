use super::{DocumentReader, ReaderError, ReaderMeta};
use std::path::Path;

pub struct TextReader {
    content: String,
}

impl TextReader {
    pub fn open(path: &Path) -> Result<Self, ReaderError> {
        let data = std::fs::read(path)?;
        let content =
            String::from_utf8(data).map_err(|e| ReaderError::ParseFailed(e.to_string()))?;
        Ok(Self { content })
    }
}

impl DocumentReader for TextReader {
    fn page_count(&self) -> Result<u32, ReaderError> {
        Ok(1)
    }

    fn extract_text(&self, _page_index: u32) -> Result<String, ReaderError> {
        Ok(self.content.clone())
    }

    fn metadata(&self) -> Result<ReaderMeta, ReaderError> {
        Ok(ReaderMeta {
            title: None,
            author: None,
            page_count: 1,
        })
    }
}
