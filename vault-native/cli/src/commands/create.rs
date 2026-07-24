use clap::Args;
use std::path::PathBuf;

#[derive(Args)]
pub struct CreateArgs {
    /// Source directory containing plain documents
    pub source: PathBuf,
    /// Password
    #[arg(short, long)]
    pub password: String,
    /// Output vault file
    #[arg(short, long)]
    pub output: PathBuf,
}

pub fn run(args: CreateArgs) -> anyhow::Result<()> {
    let tmp = tempfile::TempDir::new()?;
    let master_key =
        vault_native::format::export::create_vault_layout(tmp.path(), &args.password)?;

    let db_path = tmp.path().join("databases").join("librecrate.db");
    let conn = vault_native::db::schema::open_encrypted(
        db_path.to_str().unwrap(),
        &master_key,
    )?;

    let files = crate::commands::util::walk_files(&args.source)?;
    let mut doc_count = 0u32;
    for (abs_path, _rel) in &files {
        let data = std::fs::read(abs_path)?;
        let file_name = abs_path.file_name().unwrap_or_default().to_string_lossy();
        let title = file_name.to_string();
        let mime = mime_guess::from_path(abs_path)
            .first_or_octet_stream()
            .to_string();
        let id = uuid::Uuid::new_v4().to_string();
        let text = String::from_utf8_lossy(&data);

        vault_native::db::storage::import_document(
            &conn,
            tmp.path(),
            &id,
            &title,
            &data,
            &mime,
            "",
            "",
            Some(&text),
            Some(&master_key),
        )?;
        doc_count += 1;
    }
    drop(conn);

    let exported = vault_native::vault_ops::export_vault_dir(tmp.path(), &args.password)?;
    std::fs::write(&args.output, &exported)?;
    println!(
        "Vault written to {} ({} documents)",
        args.output.display(),
        doc_count
    );
    Ok(())
}
