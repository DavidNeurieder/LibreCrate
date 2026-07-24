use clap::Args;
use std::path::PathBuf;

use crate::session::Session;

#[derive(Args)]
pub struct BackupArgs {
    /// Output backup file
    #[arg(short, long)]
    pub output: PathBuf,
}

pub fn run(session: &Session, args: BackupArgs) -> anyhow::Result<()> {
    let data = vault_native::vault_ops::export_vault_dir(&session.vault_dir, &session.password)?;
    std::fs::write(&args.output, &data)?;
    println!("Backup exported to {}", args.output.display());
    Ok(())
}
