use clap::Args;

use crate::session::Session;

#[derive(Args)]
pub struct ListArgs;

pub fn run(session: &Session, _args: ListArgs) -> anyhow::Result<()> {
    let conn = session.open_db()?;
    let docs = vault_native::db::queries::list_documents(&conn)?;

    if docs.is_empty() {
        println!("No documents");
    } else {
        println!("Documents ({}):", docs.len());
        for doc in &docs {
            let fav = if doc.is_favorite { " *" } else { "" };
            let size = human_size(doc.file_size as u64);
            println!("  {:<12} {:<30} {:<20} {}{}", doc.id, doc.title, doc.mime_type, size, fav);
        }
    }
    Ok(())
}

fn human_size(bytes: u64) -> String {
    if bytes >= 1_048_576 {
        format!("{:.1} MB", bytes as f64 / 1_048_576.0)
    } else if bytes >= 1024 {
        format!("{:.1} KB", bytes as f64 / 1024.0)
    } else {
        format!("{} B", bytes)
    }
}
