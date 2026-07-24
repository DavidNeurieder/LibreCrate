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
    /// Create a vault from a folder of plain documents
    Create(commands::create::CreateArgs),
    /// Export a vault to a directory of plaintext files
    Export(commands::export::ExportArgs),
    /// Merge two vault files into one
    Merge(commands::merge::MergeArgs),
    /// Export a vault directory as an encrypted backup file
    BackupExport(commands::backup_export::BackupExportArgs),
    /// Inspect a vault file manifest
    Inspect(commands::inspect::InspectArgs),
    /// Vault file operations (init)
    Vault(commands::vault::VaultArgs),
    /// Cryptographic operations
    Crypto(commands::crypto::CryptoArgs),
    /// Document management operations
    Document(commands::document::DocumentArgs),
    /// Full-text search operations
    Search(commands::search::SearchArgs),
    /// Performance benchmarks
    Bench(commands::bench::BenchArgs),
}

fn main() -> anyhow::Result<()> {
    let cli = Cli::parse();
    match cli.command {
        Commands::Create(args) => commands::create::run(args),
        Commands::Export(args) => commands::export::run(args),
        Commands::Merge(args) => commands::merge::run(args),
        Commands::BackupExport(args) => commands::backup_export::run(args),
        Commands::Inspect(args) => commands::inspect::run(args),
        Commands::Vault(args) => commands::vault::run(args),
        Commands::Crypto(args) => commands::crypto::run(args),
        Commands::Document(args) => commands::document::run(args),
        Commands::Search(args) => commands::search::run(args),
        Commands::Bench(args) => commands::bench::run(args),
    }
}
