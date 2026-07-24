use std::path::Path;
use std::process::Command;

fn librecrate_bin() -> std::path::PathBuf {
    Path::new(env!("CARGO_BIN_EXE_librecrate")).to_path_buf()
}

fn create_sample_dir(files: &[(&str, &str)]) -> tempfile::TempDir {
    let dir = tempfile::TempDir::new().unwrap();
    for (rel, content) in files {
        let path = dir.path().join(rel);
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).unwrap();
        }
        std::fs::write(&path, content).unwrap();
    }
    dir
}

fn run_cmd(args: &[&str]) -> (bool, String, String) {
    let output = Command::new(librecrate_bin())
        .args(args)
        .output()
        .unwrap();
    (
        output.status.success(),
        String::from_utf8_lossy(&output.stdout).to_string(),
        String::from_utf8_lossy(&output.stderr).to_string(),
    )
}

#[test]
fn test_init_empty_vault() {
    let vault = tempfile::tempdir().unwrap();
    let (ok, stdout, _) = run_cmd(&[
        "init", vault.path().to_str().unwrap(), "-p", "test",
    ]);
    assert!(ok, "init failed");
    assert!(vault.path().join("encryption").join("wrapped_master_key").exists());
    assert!(vault.path().join("databases").join("librecrate.db").exists());
    assert!(stdout.contains("Vault created"));
}

#[test]
fn test_init_with_source() {
    let src = create_sample_dir(&[("a.txt", "hello"), ("b.txt", "world")]);
    let vault = tempfile::tempdir().unwrap();
    let (ok, stdout, _) = run_cmd(&[
        "init", vault.path().to_str().unwrap(), "-p", "test",
        "--from", src.path().to_str().unwrap(),
    ]);
    assert!(ok);
    assert!(stdout.contains("2 documents"));
}

#[test]
fn test_import_and_list() {
    let vault = tempfile::tempdir().unwrap();
    let file1 = tempfile::NamedTempFile::new().unwrap();
    std::fs::write(file1.path(), b"content1").unwrap();
    let file2 = tempfile::NamedTempFile::new().unwrap();
    std::fs::write(file2.path(), b"content2").unwrap();

    run_cmd(&["init", vault.path().to_str().unwrap(), "-p", "pw"]);

    let (ok, stdout, _) = run_cmd(&[
        "import", vault.path().to_str().unwrap(), "-p", "pw",
        file1.path().to_str().unwrap(),
        file2.path().to_str().unwrap(),
    ]);
    assert!(ok, "import failed");
    assert!(stdout.contains("2 document(s)"));

    let (ok, stdout, _) = run_cmd(&[
        "list", vault.path().to_str().unwrap(), "-p", "pw",
    ]);
    assert!(ok);
    assert!(stdout.contains("Documents (2):"));
}

#[test]
fn test_delete_document() {
    let vault = tempfile::tempdir().unwrap();
    let file = tempfile::NamedTempFile::new().unwrap();
    std::fs::write(file.path(), b"data").unwrap();

    run_cmd(&["init", vault.path().to_str().unwrap(), "-p", "pw"]);
    let (ok, _, _) = run_cmd(&[
        "import", vault.path().to_str().unwrap(), "-p", "pw",
        file.path().to_str().unwrap(),
    ]);
    assert!(ok);

    // Get the document ID from list
    let (_, stdout, _) = run_cmd(&[
        "list", vault.path().to_str().unwrap(), "-p", "pw",
    ]);
    let id = stdout.lines().nth(1).unwrap().split_whitespace().next().unwrap();

    let (ok, stdout, _) = run_cmd(&[
        "delete", vault.path().to_str().unwrap(), "-p", "pw", id,
    ]);
    assert!(ok);
    assert!(stdout.contains("Deleted"));

    let (ok, stdout, _) = run_cmd(&[
        "list", vault.path().to_str().unwrap(), "-p", "pw",
    ]);
    assert!(ok);
    assert!(stdout.contains("No documents"));
}

#[test]
fn test_search_finds_content() {
    let vault = tempfile::tempdir().unwrap();
    let file = tempfile::NamedTempFile::new().unwrap();
    std::fs::write(file.path(), b"the quick brown fox").unwrap();

    run_cmd(&["init", vault.path().to_str().unwrap(), "-p", "pw"]);
    run_cmd(&[
        "import", vault.path().to_str().unwrap(), "-p", "pw",
        file.path().to_str().unwrap(),
    ]);

    let (ok, stdout, _) = run_cmd(&[
        "search", vault.path().to_str().unwrap(), "-p", "pw", "fox",
    ]);
    assert!(ok);
    assert!(stdout.contains("Results (1):"));
}

#[test]
fn test_backup_and_restore() {
    let vault_a = tempfile::tempdir().unwrap();
    let vault_b = tempfile::tempdir().unwrap();
    let backup_file = tempfile::NamedTempFile::new().unwrap();

    let src = create_sample_dir(&[("doc.txt", "content")]);

    // Init vault_a with a document
    run_cmd(&[
        "init", vault_a.path().to_str().unwrap(), "-p", "pw",
        "--from", src.path().to_str().unwrap(),
    ]);

    // Init vault_b empty
    run_cmd(&["init", vault_b.path().to_str().unwrap(), "-p", "pw"]);

    // Backup vault_a
    let (ok, stdout, _) = run_cmd(&[
        "backup", vault_a.path().to_str().unwrap(), "-p", "pw",
        "-o", backup_file.path().to_str().unwrap(),
    ]);
    assert!(ok);
    assert!(stdout.contains("Backup exported"));

    // Restore into vault_b
    let (ok, stdout, _) = run_cmd(&[
        "restore", vault_b.path().to_str().unwrap(), "-p", "pw",
        backup_file.path().to_str().unwrap(),
    ]);
    assert!(ok);
    assert!(stdout.contains("docs added: 1"));

    // Verify vault_b has the document
    let (ok, stdout, _) = run_cmd(&[
        "list", vault_b.path().to_str().unwrap(), "-p", "pw",
    ]);
    assert!(ok);
    assert!(stdout.contains("Documents (1):"));
}

#[test]
fn test_backup_wrong_password() {
    let vault = tempfile::tempdir().unwrap();
    let backup_file = tempfile::NamedTempFile::new().unwrap();
    run_cmd(&["init", vault.path().to_str().unwrap(), "-p", "correct"]);

    let (ok, _, stderr) = run_cmd(&[
        "backup", vault.path().to_str().unwrap(), "-p", "wrong",
        "-o", backup_file.path().to_str().unwrap(),
    ]);
    assert!(!ok);
    assert!(stderr.contains("AuthenticationFailed") || stderr.contains("Wrong password"));
}

#[test]
fn test_open_document() {
    let vault = tempfile::tempdir().unwrap();
    let file = tempfile::NamedTempFile::new().unwrap();
    std::fs::write(file.path(), b"test content").unwrap();

    run_cmd(&["init", vault.path().to_str().unwrap(), "-p", "pw"]);
    run_cmd(&[
        "import", vault.path().to_str().unwrap(), "-p", "pw",
        file.path().to_str().unwrap(),
    ]);

    // Get the document ID
    let (_, stdout, _) = run_cmd(&[
        "list", vault.path().to_str().unwrap(), "-p", "pw",
    ]);
    let id = stdout.lines().nth(1).unwrap().split_whitespace().next().unwrap();

    let (ok, stdout, _) = run_cmd(&[
        "open", vault.path().to_str().unwrap(), "-p", "pw", id,
    ]);
    assert!(ok);
    assert!(stdout.contains("Opened"));
}

#[test]
fn test_wrong_password_fails() {
    let vault = tempfile::tempdir().unwrap();
    run_cmd(&["init", vault.path().to_str().unwrap(), "-p", "correct"]);

    let (ok, _, _) = run_cmd(&[
        "list", vault.path().to_str().unwrap(), "-p", "wrong",
    ]);
    assert!(!ok);
}
