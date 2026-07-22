use anyhow::Result;
use std::path::Path;
use vault_native::db::queries::DocumentRow;

pub trait DocumentOpener: Send + Sync {
    fn open(&self, document: &DocumentRow, base_dir: &Path) -> Result<()>;
}

pub struct SystemOpener;

impl DocumentOpener for SystemOpener {
    fn open(&self, doc: &DocumentRow, base_dir: &Path) -> Result<()> {
        let path = base_dir.join(&doc.file_path);
        if !path.exists() {
            return Err(anyhow::anyhow!("File not found: {}", path.display()));
        }
        open::that(&path).map_err(|e| anyhow::anyhow!("Failed to open: {}", e))?;
        Ok(())
    }
}
