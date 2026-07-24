use clap::Args;
use std::path::PathBuf;

#[derive(Args)]
pub struct SearchArgs {
    /// Vault directory
    pub dir: PathBuf,
    /// Password
    #[arg(short, long)]
    pub password: String,
    /// Search query
    pub query: String,
}

pub fn run(args: SearchArgs) -> anyhow::Result<()> {
    let (conn, _mk) = crate::commands::util::resolve_vault(&args.dir, &args.password)?;
    let results = vault_native::db::fts::search(&conn, &args.query)?;

    if results.is_empty() {
        println!("No results found");
    } else {
        println!("Results ({}):", results.len());
        for r in &results {
            println!("  {:<12} {} (rank={:.4})", r.id, r.title, r.rank);
        }
    }
    Ok(())
}
