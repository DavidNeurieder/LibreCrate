pub const HELP_TEXT: &str = "\
Commands:
  import <files...>        Import documents into the vault
  list                     List all documents
  open <name>              Open a document with the system viewer
  delete <name>            Delete a document
  search <query>           Full-text search across documents
  backup -o <file>         Export an encrypted backup
  restore <file> [-P pw]   Restore from a backup (merges into vault)
  init <dir> -p <pw>       Create a new vault (switches session)
  help                     Show this help
  quit                     Exit";
