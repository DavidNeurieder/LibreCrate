use clap::Args;
use std::path::PathBuf;

#[derive(Args)]
pub struct BackupArgs {
    /// Vault directory
    pub dir: PathBuf,
    /// Password
    #[arg(short, long)]
    pub password: String,
    /// Output backup file
    #[arg(short, long)]
    pub output: PathBuf,
}

pub fn run(args: BackupArgs) -> anyhow::Result<()> {
    // Verify password by deriving the master key before exporting
    crate::commands::util::resolve_master_key(&args.dir, &args.password)?;

    let data = vault_native::vault_ops::export_vault_dir(&args.dir, &args.password)?;
    std::fs::write(&args.output, &data)?;
    println!("Backup exported to {}", args.output.display());
    Ok(())
}
