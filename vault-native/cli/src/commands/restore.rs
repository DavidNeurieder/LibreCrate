use clap::Args;
use std::path::PathBuf;

#[derive(Args)]
pub struct RestoreArgs {
    /// Vault directory
    pub dir: PathBuf,
    /// Password for the vault
    #[arg(short, long)]
    pub password: String,
    /// Backup file to restore from
    pub backup: PathBuf,
    /// Password for the backup (defaults to vault password)
    #[arg(short = 'P', long)]
    pub backup_password: Option<String>,
}

pub fn run(args: RestoreArgs) -> anyhow::Result<()> {
    let backup_data = std::fs::read(&args.backup)?;
    let backup_pass = args.backup_password.as_deref().unwrap_or(&args.password);

    let stats = vault_native::vault_ops::merge_vault_dir(
        &args.dir,
        &backup_data,
        backup_pass,
        &args.password,
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
