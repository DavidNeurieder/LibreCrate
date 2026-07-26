use clap::Args;

use crate::commands::util;
use crate::session::Session;

#[derive(Args)]
pub struct OpenArgs {
    /// Document name (title)
    pub name: String,
}

pub fn run(session: &Session, args: OpenArgs) -> anyhow::Result<()> {
    let conn = session.open_db()?;

    let doc = util::resolve_document(&conn, &args.name)?;

    let data = vault_native::db::storage::export_document_file(
        &conn,
        &session.vault_dir,
        &doc.id,
        Some(&session.master_key),
    )
    .ok_or_else(|| anyhow::anyhow!("File data not found for document '{}'", doc.title))?;

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
