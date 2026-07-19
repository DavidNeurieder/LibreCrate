use clap::Args;
use std::path::PathBuf;

#[derive(Args)]
pub struct DocumentArgs {
    #[command(subcommand)]
    pub action: DocumentAction,
}

#[derive(clap::Subcommand)]
pub enum DocumentAction {
    /// List all documents
    List {
        /// Path to the encrypted database
        #[arg(short, long)]
        db: PathBuf,
        /// Master key (hex)
        #[arg(short, long)]
        key: String,
    },
    /// Get a document by ID
    Get {
        /// Path to the encrypted database
        #[arg(short, long)]
        db: PathBuf,
        /// Master key (hex)
        #[arg(short, long)]
        key: String,
        /// Document ID
        id: String,
    },
    /// Add a document
    Add {
        /// Path to the encrypted database
        #[arg(short, long)]
        db: PathBuf,
        /// Master key (hex)
        #[arg(short, long)]
        key: String,
        /// Title
        #[arg(short, long)]
        title: String,
        /// File to import
        #[arg(short, long)]
        file: PathBuf,
    },
    /// Update a document's title and/or favorite status
    Update {
        /// Path to the encrypted database
        #[arg(short, long)]
        db: PathBuf,
        /// Master key (hex)
        #[arg(short, long)]
        key: String,
        /// Document ID
        id: String,
        /// New title
        #[arg(short, long)]
        title: Option<String>,
        /// Set favorite
        #[arg(short, long)]
        favorite: Option<bool>,
    },
    /// Delete a document by ID
    Delete {
        /// Path to the encrypted database
        #[arg(short, long)]
        db: PathBuf,
        /// Master key (hex)
        #[arg(short, long)]
        key: String,
        /// Document ID
        id: String,
    },
}

pub fn run(args: DocumentArgs) -> anyhow::Result<()> {
    match args.action {
        DocumentAction::List { db, key } => {
            let mk = hex::decode(&key)?;
            let conn = vault_native::db::schema::open_encrypted(db.to_str().unwrap(), &mk)?;
            let docs = vault_native::db::queries::list_documents(&conn)?;
            println!("Documents ({}):", docs.len());
            for doc in &docs {
                let fav = if doc.is_favorite { " *" } else { "" };
                println!("  {}: {} ({}){}", doc.id, doc.title, doc.mime_type, fav);
            }
            Ok(())
        }
        DocumentAction::Get { db, key, id } => {
            let mk = hex::decode(&key)?;
            let conn = vault_native::db::schema::open_encrypted(db.to_str().unwrap(), &mk)?;
            match vault_native::db::queries::get_document(&conn, &id)? {
                Some(doc) => {
                    println!("ID:          {}", doc.id);
                    println!("Title:       {}", doc.title);
                    println!("File name:   {}", doc.file_name);
                    println!("MIME type:   {}", doc.mime_type);
                    println!("File path:   {}", doc.file_path);
                    println!("File size:   {}", doc.file_size);
                    println!("Page count:  {}", doc.page_count);
                    println!("Author:      {}", doc.author);
                    println!("Favorite:    {}", doc.is_favorite);
                    println!("Conflict:    {}", doc.is_conflict);
                    println!("Modified at: {}", doc.modified_at);
                    Ok(())
                }
                None => {
                    eprintln!("Document '{}' not found", id);
                    std::process::exit(1);
                }
            }
        }
        DocumentAction::Add {
            db,
            key,
            title,
            file,
        } => {
            let mk = hex::decode(&key)?;
            let conn = vault_native::db::schema::open_encrypted(db.to_str().unwrap(), &mk)?;
            let id = uuid::Uuid::new_v4().to_string();
            let file_bytes = std::fs::read(&file)?;
            let file_name = file.file_name().unwrap_or_default().to_string_lossy().to_string();
            let mime = mime_guess::from_path(&file_name)
                .first_or_octet_stream()
                .to_string();
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as i64;

            vault_native::db::queries::add_document(
                &conn,
                &vault_native::db::queries::DocumentRow {
                    id,
                    title,
                    file_name: file_name.clone(),
                    mime_type: mime,
                    file_path: file_name,
                    file_size: file_bytes.len() as i64,
                    page_count: 0,
                    author: String::new(),
                    description: String::new(),
                    thumbnail_path: None,
                    imported_at: now,
                    last_opened_at: now,
                    modified_at: now,
                    is_favorite: false,
                    is_conflict: false,
                    conflict_with: None,
                    collection_id: None,
                    encryption_iv: None,
                    current_page: 0,
                },
            )?;
            println!("Document added");
            Ok(())
        }
        DocumentAction::Update {
            db,
            key,
            id,
            title,
            favorite,
        } => {
            let mk = hex::decode(&key)?;
            let conn = vault_native::db::schema::open_encrypted(db.to_str().unwrap(), &mk)?;
            let existing = vault_native::db::queries::get_document(&conn, &id)?
                .ok_or_else(|| anyhow::anyhow!("Document '{}' not found", id))?;

            let new_title = title.unwrap_or(existing.title);
            let new_fav = favorite.unwrap_or(existing.is_favorite) as i32;
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as i64;

            conn.execute(
                "UPDATE documents SET title = ?1, is_favorite = ?2, modified_at = ?3 WHERE id = ?4",
                rusqlite::params![new_title, new_fav, now, id],
            )?;
            println!("Document updated");
            Ok(())
        }
        DocumentAction::Delete { db, key, id } => {
            let mk = hex::decode(&key)?;
            let conn = vault_native::db::schema::open_encrypted(db.to_str().unwrap(), &mk)?;
            if vault_native::db::queries::delete_document(&conn, &id)? {
                println!("Document deleted");
            } else {
                eprintln!("Document '{}' not found", id);
                std::process::exit(1);
            }
            Ok(())
        }
    }
}
