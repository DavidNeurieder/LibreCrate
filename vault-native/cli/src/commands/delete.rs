use clap::Args;

use crate::commands::util;
use crate::session::Session;

#[derive(Args)]
pub struct DeleteArgs {
    /// Document name (title)
    pub name: String,
}

pub fn run(session: &Session, args: DeleteArgs) -> anyhow::Result<()> {
    let conn = session.open_db()?;

    let doc = util::resolve_document(&conn, &args.name)?;

    vault_native::db::storage::delete_document_full(&conn, &session.vault_dir, &doc.id)?;
    println!("Deleted {}", doc.title);
    Ok(())
}
