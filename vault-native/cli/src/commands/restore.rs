use clap::Args;
use std::path::PathBuf;

use crate::session::Session;

#[derive(Args)]
pub struct RestoreArgs {
    /// Backup file to restore from
    pub backup: PathBuf,
    /// Password for the backup (defaults to vault password)
    #[arg(short = 'P', long)]
    pub backup_password: Option<String>,
}

pub fn run(session: &Session, args: RestoreArgs) -> anyhow::Result<()> {
    let backup_data = std::fs::read(&args.backup)?;
    let backup_pass = args.backup_password.as_deref().unwrap_or(&session.password);

    let stats = vault_native::vault_ops::merge_vault_dir(
        &session.vault_dir,
        &backup_data,
        backup_pass,
        &session.password,
    )?;

    println!("Restored from backup");
    println!(
        "  docs added: {}, updated: {}, conflicts: {}, skipped: {}",
        stats.documents_added,
        stats.documents_updated,
        stats.documents_conflicted,
        stats.documents_skipped,
    );
    Ok(())
}
