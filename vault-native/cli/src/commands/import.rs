use clap::Args;
use std::path::PathBuf;

use crate::session::Session;

#[derive(Args)]
pub struct ImportArgs {
    /// Files to import
    #[arg(required = true)]
    pub files: Vec<PathBuf>,
}

pub fn run(session: &Session, args: ImportArgs) -> anyhow::Result<()> {
    let conn = session.open_db()?;

    let mut imported = 0u32;
    let mut errors: Vec<String> = Vec::new();

    for path in &args.files {
        match import_one(&conn, &session.vault_dir, &session.master_key, path) {
            Ok(_) => imported += 1,
            Err(e) => errors.push(format!("{}: {}", path.display(), e)),
        }
    }

    if imported > 0 {
        println!("Imported {} document(s)", imported);
    }
    for err in &errors {
        eprintln!("Error: {}", err);
    }
    if !errors.is_empty() {
        anyhow::bail!("{} file(s) failed to import", errors.len());
    }
    Ok(())
}

fn import_one(
    conn: &rusqlite::Connection,
    vault_dir: &std::path::Path,
    mk: &[u8],
    path: &std::path::Path,
) -> anyhow::Result<()> {
    let data = std::fs::read(path)?;
    let file_name = path.file_name().unwrap_or_default().to_string_lossy();
    let title = file_name.to_string();
    let mime = mime_guess::from_path(path)
        .first_or_octet_stream()
        .to_string();
    let id = uuid::Uuid::new_v4().to_string();
    let text = String::from_utf8_lossy(&data);

    vault_native::db::storage::import_document(
        conn,
        vault_dir,
        &id,
        &title,
        &data,
        &mime,
        "",
        "",
        Some(&text),
        Some(mk),
    )?;
    Ok(())
}
