pub mod export;
pub mod export_docs;
pub mod first_run;
pub mod library;
pub mod pdf;
pub mod settings;
pub mod unlock;

use std::sync::Arc;

use crate::vault::Vault;
use vault_native::db::queries::DocumentRow;

#[derive(Debug, Clone)]
pub enum Navigation {
    FirstRun,
    Library(Arc<Vault>),
    Settings(Arc<Vault>),
    Export(Arc<Vault>),
    ExportDocs(Arc<Vault>),
    OpenDocument(DocumentRow, Arc<Vault>),
    OpenPdf(DocumentRow, Arc<Vault>),
    PdfExit(Arc<Vault>),
}
