use clap::Args;
use std::path::PathBuf;

#[derive(Args)]
pub struct OpenArgs {
    /// Vault directory
    pub dir: PathBuf,
    /// Password
    #[arg(short, long)]
    pub password: String,
    /// Document ID
    pub id: String,
}

pub fn run(args: OpenArgs) -> anyhow::Result<()> {
    let (conn, mk) = crate::commands::util::resolve_vault(&args.dir, &args.password)?;

    let doc = vault_native::db::queries::get_document(&conn, &args.id)?
        .ok_or_else(|| anyhow::anyhow!("Document '{}' not found", args.id))?;

    let data = vault_native::db::storage::export_document_file(
        &conn,
        &args.dir,
        &doc.id,
        Some(&mk),
    )
    .ok_or_else(|| anyhow::anyhow!("File data not found for document '{}'", doc.id))?;

    let tmp_dir = tempfile::TempDir::new()?;
    let tmp_path = tmp_dir.path().join(&doc.file_name);
    std::fs::write(&tmp_path, &data)?;

    println!("Opened {} at {}", doc.file_name, tmp_path.display());

    if let Err(e) = open::that(&tmp_path) {
        eprintln!("Could not open with system viewer: {}", e);
        println!("File saved at: {}", tmp_path.display());
    }

    // Keep temp dir alive for 120 seconds (matches GUI behavior)
    let _ = tmp_dir;
    std::thread::spawn(|| {
        std::thread::sleep(std::time::Duration::from_secs(120));
    });
    std::thread::sleep(std::time::Duration::from_secs(2));

    Ok(())
}
