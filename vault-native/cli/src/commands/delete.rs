use clap::Args;
use std::path::PathBuf;

#[derive(Args)]
pub struct DeleteArgs {
    /// Vault directory
    pub dir: PathBuf,
    /// Password
    #[arg(short, long)]
    pub password: String,
    /// Document ID
    pub id: String,
}

pub fn run(args: DeleteArgs) -> anyhow::Result<()> {
    let (conn, _mk) = crate::commands::util::resolve_vault(&args.dir, &args.password)?;

    let doc = vault_native::db::queries::get_document(&conn, &args.id)?
        .ok_or_else(|| anyhow::anyhow!("Document '{}' not found", args.id))?;

    vault_native::db::storage::delete_document_full(&conn, &args.dir, &args.id)?;
    println!("Deleted {}", doc.title);
    Ok(())
}
