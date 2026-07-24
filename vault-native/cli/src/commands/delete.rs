use clap::Args;

use crate::session::Session;

#[derive(Args)]
pub struct DeleteArgs {
    /// Document ID
    pub id: String,
}

pub fn run(session: &Session, args: DeleteArgs) -> anyhow::Result<()> {
    let conn = session.open_db()?;

    let doc = vault_native::db::queries::get_document(&conn, &args.id)?
        .ok_or_else(|| anyhow::anyhow!("Document '{}' not found", args.id))?;

    vault_native::db::storage::delete_document_full(&conn, &session.vault_dir, &args.id)?;
    println!("Deleted {}", doc.title);
    Ok(())
}
