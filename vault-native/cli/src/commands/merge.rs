use clap::Args;
use std::path::PathBuf;

#[derive(Args)]
pub struct MergeArgs {
    /// First vault file (target — its master key is used for output)
    pub vault_a: PathBuf,
    /// Second vault file (backup — merged into A)
    pub vault_b: PathBuf,
    /// Password (must work for both vaults)
    #[arg(short, long)]
    pub password: String,
    /// Output vault file
    #[arg(short, long)]
    pub output: PathBuf,
}

pub fn run(args: MergeArgs) -> anyhow::Result<()> {
    // Import vault A to a temporary directory
    let tmp_a = tempfile::TempDir::new()?;
    let data_a = std::fs::read(&args.vault_a)?;
    let contents_a =
        vault_native::format::import::import(&data_a, &args.password, &vault_native::crypto::argon2::Argon2Params::default())?;
    crate::commands::util::write_contents(tmp_a.path(), &contents_a)?;

    // Vault B bytes are used directly as the backup data (same binary format)
    let vault_b_bytes = std::fs::read(&args.vault_b)?;

    // Merge vault B into vault A's temp directory
    let stats = vault_native::vault_ops::merge_vault_dir(
        tmp_a.path(),
        &vault_b_bytes,
        &args.password,
        &args.password,
    )?;

    // Re-export the merged vault
    let exported = vault_native::vault_ops::export_vault_dir(tmp_a.path(), &args.password)?;
    std::fs::write(&args.output, &exported)?;

    println!("Merged vault written to {}", args.output.display());
    println!(
        "  docs added: {}, updated: {}, conflicts: {}, skipped: {}",
        stats.documents_added,
        stats.documents_updated,
        stats.documents_conflicted,
        stats.documents_skipped,
    );
    Ok(())
}
