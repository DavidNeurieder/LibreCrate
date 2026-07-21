use clap::Args;
use std::path::PathBuf;
use vault_native::crypto::argon2::Argon2Params;

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
    let kdf = Argon2Params::default();
    let tmp_a = tempfile::TempDir::new()?;
    let tmp_b = tempfile::TempDir::new()?;

    // Import both vaults
    let data_a = std::fs::read(&args.vault_a)?;
    let contents_a =
        vault_native::format::import::import(&data_a, &args.password, &kdf)?;
    crate::commands::util::write_contents(tmp_a.path(), &contents_a)?;

    let data_b = std::fs::read(&args.vault_b)?;
    let contents_b =
        vault_native::format::import::import(&data_b, &args.password, &kdf)?;
    crate::commands::util::write_contents(tmp_b.path(), &contents_b)?;

    // Derive master keys
    let mk_a = crate::commands::password::resolve_master_key(
        tmp_a.path(),
        None,
        Some(&args.password),
    )?;
    let mk_b = crate::commands::password::resolve_master_key(
        tmp_b.path(),
        None,
        Some(&args.password),
    )?;

    // Open A's DB and merge B into it
    let a_db_path = tmp_a.path().join("databases").join("librecrate.db");
    let b_db_path = tmp_b.path().join("databases").join("librecrate.db");
    let conn = vault_native::db::schema::open_encrypted(
        a_db_path.to_str().unwrap(),
        &mk_a,
    )?;

    let stats = vault_native::merge::branch_a_merge(
        b_db_path.to_str().unwrap(),
        &mk_b,
        &conn,
        &contents_b.files,
        None,
        None,
        &tmp_a.path().join("files"),
    )?;
    drop(conn);

    // Copy B's file blobs into A's files dir
    // (branch_a_merge skips file copy when keys are None for unencrypted model)
    for kv in &contents_b.files {
        let target = tmp_a.path().join("files").join(&kv.key);
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(&target, &kv.value)?;
    }

    // Re-export A as merged vault
    let enc_dir = tmp_a.path().join("encryption");
    let db_dir = tmp_a.path().join("databases");
    let files_dir = tmp_a.path().join("files");

    let keys = crate::commands::util::read_dir_files(&enc_dir)?;
    let db_data = std::fs::read(db_dir.join("librecrate.db"))?;
    let vault_files = crate::commands::util::read_dir_files(&files_dir)?;

    let exported = vault_native::format::export::export(
        &vault_files,
        Some(&db_data),
        &args.password,
        &keys,
        &kdf,
    )?;
    std::fs::write(&args.output, &exported.data)?;

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
