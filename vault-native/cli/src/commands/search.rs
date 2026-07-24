use clap::Args;

use crate::session::Session;

#[derive(Args)]
pub struct SearchArgs {
    /// Search query
    pub query: String,
}

pub fn run(session: &Session, args: SearchArgs) -> anyhow::Result<()> {
    let conn = session.open_db()?;
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
