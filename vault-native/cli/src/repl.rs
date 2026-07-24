use anyhow::Result;
use rustyline::error::ReadlineError;
use rustyline::DefaultEditor;

use crate::commands;
use crate::session::Session;

pub fn run() -> Result<()> {
    let vault_dir = prompt_string("Vault: ")?;
    if vault_dir.trim().is_empty() {
        anyhow::bail!("vault directory required");
    }
    let vault_dir = std::path::PathBuf::from(vault_dir);

    let password = prompt_password("Password: ")?;

    let session = Session::open(vault_dir, &password)?;

    println!(
        "\nLibreCrate v0.1.0 — interactive mode\nType 'help' for commands, 'quit' to exit.\n"
    );

    let mut rl = DefaultEditor::new()?;
    let _ = rl.load_history(".librecrate_history");

    loop {
        let readline = rl.readline("librecrate> ");
        match readline {
            Ok(line) => {
                let line = line.trim().to_string();
                if line.is_empty() {
                    continue;
                }
                let _ = rl.add_history_entry(&line);
                match dispatch(&session, &line) {
                    Ok(()) => {}
                    Err(e) => eprintln!("Error: {}", e),
                }
            }
            Err(ReadlineError::Interrupted) => {}
            Err(ReadlineError::Eof) => break,
            Err(e) => {
                eprintln!("Readline error: {}", e);
                break;
            }
        }
    }

    let _ = rl.save_history(".librecrate_history");
    Ok(())
}

fn dispatch(session: &Session, line: &str) -> Result<()> {
    let args = shlex::split(line).ok_or_else(|| anyhow::anyhow!("failed to parse input"))?;
    if args.is_empty() {
        return Ok(());
    }

    match args[0].as_str() {
        "quit" | "exit" => std::process::exit(0),
        "help" => {
            println!("{}", commands::help::HELP_TEXT);
        }
        "list" => {
            commands::list::run(session, commands::list::ListArgs)?;
        }
        "import" => {
            let files = args[1..].iter().map(std::path::PathBuf::from).collect();
            commands::import::run(session, commands::import::ImportArgs { files })?;
        }
        "open" => {
            if args.len() < 2 {
                anyhow::bail!("usage: open <document-id>");
            }
            commands::open::run(session, commands::open::OpenArgs { id: args[1].clone() })?;
        }
        "delete" => {
            if args.len() < 2 {
                anyhow::bail!("usage: delete <document-id>");
            }
            commands::delete::run(session, commands::delete::DeleteArgs { id: args[1].clone() })?;
        }
        "search" => {
            if args.len() < 2 {
                anyhow::bail!("usage: search <query>");
            }
            let query = args[1..].join(" ");
            commands::search::run(session, commands::search::SearchArgs { query })?;
        }
        "backup" => {
            let output = parse_flag(&args, "-o")?;
            commands::backup::run(
                session,
                commands::backup::BackupArgs {
                    output: std::path::PathBuf::from(output),
                },
            )?;
        }
        "restore" => {
            if args.len() < 2 {
                anyhow::bail!("usage: restore <backup-file> [-P <password>]");
            }
            let backup = std::path::PathBuf::from(&args[1]);
            let backup_password = parse_flag_opt(&args, "-P");
            commands::restore::run(
                session,
                commands::restore::RestoreArgs {
                    backup,
                    backup_password,
                },
            )?;
        }
        "init" => {
            if args.len() < 3 {
                anyhow::bail!("usage: init <dir> -p <password> [--from <source>]");
            }
            let dir = std::path::PathBuf::from(&args[1]);
            let password = parse_flag(&args, "-p")?;
            let from = parse_flag_opt(&args, "--from").map(std::path::PathBuf::from);
            commands::init::run(commands::init::InitArgs {
                dir,
                password,
                from,
            })?;
            println!("Switch to the new vault by restarting LibreCrate.");
        }
        other => {
            eprintln!("Unknown command: '{}'. Type 'help' for available commands.", other);
        }
    }

    Ok(())
}

fn parse_flag(args: &[String], flag: &str) -> Result<String> {
    let pos = args.iter().position(|a| a == flag);
    match pos {
        Some(i) if i + 1 < args.len() => Ok(args[i + 1].clone()),
        Some(_) => anyhow::bail!("flag '{}' requires a value", flag),
        None => anyhow::bail!("missing required flag '{}'", flag),
    }
}

fn parse_flag_opt(args: &[String], flag: &str) -> Option<String> {
    let pos = args.iter().position(|a| a == flag);
    pos.and_then(|i| args.get(i + 1).cloned())
}

fn prompt_string(prompt: &str) -> Result<String> {
    use std::io::Write;
    eprint!("{}", prompt);
    std::io::stderr().flush()?;
    let mut buf = String::new();
    std::io::stdin().read_line(&mut buf)?;
    Ok(buf.trim_end().to_string())
}

fn prompt_password(prompt: &str) -> Result<String> {
    use std::io::Write;
    eprint!("{}", prompt);
    std::io::stderr().flush()?;
    let mut buf = String::new();
    std::io::stdin().read_line(&mut buf)?;
    Ok(buf.trim_end().to_string())
}
