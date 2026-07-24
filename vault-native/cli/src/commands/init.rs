use clap::Args;
use std::path::PathBuf;

#[derive(Args)]
pub struct InitArgs {
    /// Directory to create the vault in
    pub dir: PathBuf,
    /// Password
    #[arg(short, long)]
    pub password: String,
    /// Optional source directory to bulk-import documents from
    #[arg(short, long)]
    pub from: Option<PathBuf>,
}

pub fn run(args: InitArgs) -> anyhow::Result<()> {
    let mk = vault_native::format::export::create_vault_layout(&args.dir, &args.password)?;

    let mut doc_count = 0u32;
    if let Some(ref src) = args.from {
        let db_path = args.dir.join("databases").join("librecrate.db");
        let conn = vault_native::db::schema::open_encrypted(
            db_path.to_str().ok_or_else(|| anyhow::anyhow!("invalid vault path"))?,
            &mk,
        )?;

        let files = crate::commands::util::walk_files(src)?;
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
                &args.dir,
                &id,
                &title,
                &data,
                &mime,
                "",
                "",
                Some(&text),
                Some(&mk),
            )?;
            doc_count += 1;
        }
    }

    if doc_count > 0 {
        println!("Vault created at {} ({} documents)", args.dir.display(), doc_count);
    } else {
        println!("Vault created at {}", args.dir.display());
    }
    Ok(())
}
