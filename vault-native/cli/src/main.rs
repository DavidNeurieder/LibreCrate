use clap::{Parser, Subcommand};
use std::path::PathBuf;

mod commands;
mod repl;
mod session;

use session::Session;

#[derive(Parser)]
#[command(name = "librecrate", about = "LibreCrate CLI")]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Create a new vault directory
    Init(InitOneShot),
    /// Import documents into a vault
    Import(ImportOneShot),
    /// List documents in a vault
    List(ListOneShot),
    /// Open a document with the system viewer
    Open(OpenOneShot),
    /// Delete a document from a vault
    Delete(DeleteOneShot),
    /// Full-text search across documents
    Search(SearchOneShot),
    /// Export an encrypted backup of a vault
    Backup(BackupOneShot),
    /// Restore from a backup (merges into existing vault)
    Restore(RestoreOneShot),
}

// One-shot arg wrappers — include dir + password for CLI use

#[derive(Parser)]
struct InitOneShot {
    dir: PathBuf,
    #[arg(short, long)]
    password: String,
    #[arg(short, long)]
    from: Option<PathBuf>,
}

#[derive(Parser)]
struct ImportOneShot {
    dir: PathBuf,
    #[arg(short, long)]
    password: String,
    #[arg(required = true)]
    files: Vec<PathBuf>,
}

#[derive(Parser)]
struct ListOneShot {
    dir: PathBuf,
    #[arg(short, long)]
    password: String,
}

#[derive(Parser)]
struct OpenOneShot {
    dir: PathBuf,
    #[arg(short, long)]
    password: String,
    id: String,
}

#[derive(Parser)]
struct DeleteOneShot {
    dir: PathBuf,
    #[arg(short, long)]
    password: String,
    id: String,
}

#[derive(Parser)]
struct SearchOneShot {
    dir: PathBuf,
    #[arg(short, long)]
    password: String,
    query: String,
}

#[derive(Parser)]
struct BackupOneShot {
    dir: PathBuf,
    #[arg(short, long)]
    password: String,
    #[arg(short, long)]
    output: PathBuf,
}

#[derive(Parser)]
struct RestoreOneShot {
    dir: PathBuf,
    #[arg(short, long)]
    password: String,
    backup: PathBuf,
    #[arg(short = 'P', long)]
    backup_password: Option<String>,
}

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    if args.len() <= 1 {
        return repl::run();
    }

    let cli = Cli::parse();
    match cli.command {
        Commands::Init(args) => commands::init::run(commands::init::InitArgs {
            dir: args.dir,
            password: args.password,
            from: args.from,
        }),
        Commands::Import(args) => {
            let session = Session::open(args.dir, &args.password)?;
            commands::import::run(&session, commands::import::ImportArgs { files: args.files })
        }
        Commands::List(args) => {
            let session = Session::open(args.dir, &args.password)?;
            commands::list::run(&session, commands::list::ListArgs)
        }
        Commands::Open(args) => {
            let session = Session::open(args.dir, &args.password)?;
            commands::open::run(&session, commands::open::OpenArgs { id: args.id })
        }
        Commands::Delete(args) => {
            let session = Session::open(args.dir, &args.password)?;
            commands::delete::run(&session, commands::delete::DeleteArgs { id: args.id })
        }
        Commands::Search(args) => {
            let session = Session::open(args.dir, &args.password)?;
            commands::search::run(&session, commands::search::SearchArgs { query: args.query })
        }
        Commands::Backup(args) => {
            let session = Session::open(args.dir, &args.password)?;
            commands::backup::run(&session, commands::backup::BackupArgs { output: args.output })
        }
        Commands::Restore(args) => {
            let session = Session::open(args.dir, &args.password)?;
            commands::restore::run(
                &session,
                commands::restore::RestoreArgs {
                    backup: args.backup,
                    backup_password: args.backup_password,
                },
            )
        }
    }
}
