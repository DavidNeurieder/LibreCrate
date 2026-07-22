pub mod collections;
pub mod export;
pub mod first_run;
pub mod library;
pub mod settings;
pub mod unlock;

use std::sync::Arc;

use crate::vault::Vault;
use vault_native::db::queries::DocumentRow;

#[derive(Debug, Clone)]
pub enum Navigation {
    FirstRun,
    Unlock,
    Library(Arc<Vault>),
    Settings,
    Export,
    Collections,
    Lock,
    OpenDocument(DocumentRow),
}
