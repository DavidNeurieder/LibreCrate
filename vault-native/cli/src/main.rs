use clap::{Parser, Subcommand};

mod commands;

#[derive(Parser)]
#[command(name = "librecrate", about = "LibreCrate CLI")]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Create a new vault directory
    Init(commands::init::InitArgs),
    /// Import documents into a vault
    Import(commands::import::ImportArgs),
    /// List documents in a vault
    List(commands::list::ListArgs),
    /// Open a document with the system viewer
    Open(commands::open::OpenArgs),
    /// Delete a document from a vault
    Delete(commands::delete::DeleteArgs),
    /// Full-text search across documents
    Search(commands::search::SearchArgs),
    /// Export an encrypted backup of a vault
    Backup(commands::backup::BackupArgs),
    /// Restore from a backup (merges into existing vault)
    Restore(commands::restore::RestoreArgs),
}

fn main() -> anyhow::Result<()> {
    let cli = Cli::parse();
    match cli.command {
        Commands::Init(args) => commands::init::run(args),
        Commands::Import(args) => commands::import::run(args),
        Commands::List(args) => commands::list::run(args),
        Commands::Open(args) => commands::open::run(args),
        Commands::Delete(args) => commands::delete::run(args),
        Commands::Search(args) => commands::search::run(args),
        Commands::Backup(args) => commands::backup::run(args),
        Commands::Restore(args) => commands::restore::run(args),
    }
}
