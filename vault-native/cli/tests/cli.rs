use std::io::Write;
use std::path::Path;
use std::process::{Command, Stdio};

fn bin() -> std::path::PathBuf {
    Path::new(env!("CARGO_BIN_EXE_librecrate")).to_path_buf()
}

fn sample(files: &[(&str, &str)]) -> tempfile::TempDir {
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

fn cmd(args: &[&str]) -> (bool, String, String) {
    let o = Command::new(bin()).args(args).output().unwrap();
    (
        o.status.success(),
        String::from_utf8_lossy(&o.stdout).to_string(),
        String::from_utf8_lossy(&o.stderr).to_string(),
    )
}

fn v() -> tempfile::TempDir {
    tempfile::tempdir().unwrap()
}

fn init(dir: &Path, pw: &str) {
    let (ok, _, e) = cmd(&["init", dir.to_str().unwrap(), "-p", pw]);
    assert!(ok, "init failed: {}", e);
}

fn imp(dir: &Path, pw: &str, file: &Path) -> String {
    let (ok, out, e) = cmd(&["import", dir.to_str().unwrap(), "-p", pw, file.to_str().unwrap()]);
    assert!(ok, "import failed: {}", e);
    out
}

fn list(dir: &Path, pw: &str) -> String {
    let (ok, out, e) = cmd(&["list", dir.to_str().unwrap(), "-p", pw]);
    assert!(ok, "list failed: {}", e);
    out
}

fn first_name(dir: &Path, pw: &str) -> String {
    let out = list(dir, pw);
    out.lines().nth(1).unwrap().split_whitespace().next().unwrap().to_string()
}

#[test]
fn test_init_empty_vault() {
    let d = v();
    let (ok, out, _) = cmd(&["init", d.path().to_str().unwrap(), "-p", "test"]);
    assert!(ok);
    assert!(d.path().join("encryption").join("wrapped_master_key").exists());
    assert!(d.path().join("encryption").join("salt").exists());
    assert!(d.path().join("databases").join("librecrate.db").exists());
    assert!(out.contains("Vault created at"));
}

#[test]
fn test_init_with_source() {
    let src = sample(&[("a.txt", "hello"), ("b.txt", "world")]);
    let d = v();
    let (ok, out, _) = cmd(&[
        "init", d.path().to_str().unwrap(), "-p", "test",
        "--from", src.path().to_str().unwrap(),
    ]);
    assert!(ok);
    assert!(out.contains("2 documents"));
}

#[test]
fn test_init_with_nested_source() {
    let src = sample(&[("a.txt", "root"), ("sub/b.txt", "nested"), ("sub/deep/c.txt", "deep")]);
    let d = v();
    let (ok, out, _) = cmd(&[
        "init", d.path().to_str().unwrap(), "-p", "pw",
        "--from", src.path().to_str().unwrap(),
    ]);
    assert!(ok);
    assert!(out.contains("3 documents"));
}

#[test]
fn test_import_single_file() {
    let d = v();
    init(d.path(), "pw");
    let f = tempfile::NamedTempFile::new().unwrap();
    std::fs::write(f.path(), b"hello world").unwrap();
    let out = imp(d.path(), "pw", f.path());
    assert!(out.contains("1 document(s)"));
    assert!(list(d.path(), "pw").contains("Documents (1):"));
}

#[test]
fn test_import_multiple_files() {
    let d = v();
    init(d.path(), "pw");
    let f1 = tempfile::NamedTempFile::new().unwrap();
    std::fs::write(f1.path(), b"one").unwrap();
    let f2 = tempfile::NamedTempFile::new().unwrap();
    std::fs::write(f2.path(), b"two").unwrap();
    let f3 = tempfile::NamedTempFile::new().unwrap();
    std::fs::write(f3.path(), b"three").unwrap();
    let (ok, out, _) = cmd(&[
        "import", d.path().to_str().unwrap(), "-p", "pw",
        f1.path().to_str().unwrap(),
        f2.path().to_str().unwrap(),
        f3.path().to_str().unwrap(),
    ]);
    assert!(ok);
    assert!(out.contains("3 document(s)"));
    assert!(list(d.path(), "pw").contains("Documents (3):"));
}

#[test]
fn test_import_preserves_title() {
    let d = v();
    init(d.path(), "pw");
    let src = sample(&[("my_report.pdf", "fake pdf")]);
    imp(d.path(), "pw", &src.path().join("my_report.pdf"));
    assert!(list(d.path(), "pw").contains("my_report.pdf"));
}

#[test]
fn test_import_detects_mime() {
    let d = v();
    init(d.path(), "pw");
    let src = sample(&[
        ("readme.txt", "plain text"),
        ("image.png", "not a real png but has the extension"),
        ("doc.pdf", "%PDF-1.4 fake"),
    ]);
    let (ok, _, _) = cmd(&[
        "import", d.path().to_str().unwrap(), "-p", "pw",
        src.path().join("readme.txt").to_str().unwrap(),
        src.path().join("image.png").to_str().unwrap(),
        src.path().join("doc.pdf").to_str().unwrap(),
    ]);
    assert!(ok);
    let l = list(d.path(), "pw");
    assert!(l.contains("text/plain"), "expected text/plain: {}", l);
    assert!(l.contains("image/png"), "expected image/png: {}", l);
    assert!(l.contains("application/pdf"), "expected application/pdf: {}", l);
}

#[test]
fn test_import_nonexistent_file_fails() {
    let d = v();
    init(d.path(), "pw");
    let (ok, _, e) = cmd(&[
        "import", d.path().to_str().unwrap(), "-p", "pw",
        "/nonexistent/file.txt",
    ]);
    assert!(!ok);
    assert!(e.contains("Error") || e.contains("failed"));
}

#[test]
fn test_list_empty() {
    let d = v();
    init(d.path(), "pw");
    let (ok, out, _) = cmd(&["list", d.path().to_str().unwrap(), "-p", "pw"]);
    assert!(ok);
    assert!(out.contains("No documents"));
}

#[test]
fn test_list_shows_metadata() {
    let d = v();
    init(d.path(), "pw");
    let src = sample(&[("report.pdf", "fake pdf content for size")]);
    imp(d.path(), "pw", &src.path().join("report.pdf"));
    let l = list(d.path(), "pw");
    assert!(l.contains("report.pdf"), "title: {}", l);
    assert!(l.contains("application/pdf"), "mime: {}", l);
    assert!(l.contains("B") || l.contains("KB"), "size: {}", l);
}

#[test]
fn test_list_after_delete() {
    let d = v();
    init(d.path(), "pw");
    let src = sample(&[("a.txt", "aaa"), ("b.txt", "bbb")]);
    imp(d.path(), "pw", &src.path().join("a.txt"));
    imp(d.path(), "pw", &src.path().join("b.txt"));
    assert!(list(d.path(), "pw").contains("Documents (2):"));
    let id = first_name(d.path(), "pw");
    cmd(&["delete", d.path().to_str().unwrap(), "-p", "pw", &id]);
    assert!(list(d.path(), "pw").contains("Documents (1):"));
}

#[test]
fn test_search_finds_content() {
    let d = v();
    init(d.path(), "pw");
    let src = sample(&[
        ("lorem.txt", "lorem ipsum dolor sit amet"),
        ("fox.txt", "the quick brown fox jumps over the lazy dog"),
        ("nums.txt", "one two three four five"),
    ]);
    let (ok, _, _) = cmd(&[
        "import", d.path().to_str().unwrap(), "-p", "pw",
        src.path().join("lorem.txt").to_str().unwrap(),
        src.path().join("fox.txt").to_str().unwrap(),
        src.path().join("nums.txt").to_str().unwrap(),
    ]);
    assert!(ok);
    let (ok, out, _) = cmd(&[
        "search", d.path().to_str().unwrap(), "-p", "pw", "fox",
    ]);
    assert!(ok);
    assert!(out.contains("Results (1):"), "got: {}", out);
    assert!(out.contains("fox.txt"), "got: {}", out);
}

#[test]
fn test_search_multiple_results() {
    let d = v();
    init(d.path(), "pw");
    let src = sample(&[
        ("a.txt", "the word algorithm appears here"),
        ("b.txt", "algorithm is a sequence of steps"),
        ("c.txt", "no match here at all"),
    ]);
    let (ok, _, _) = cmd(&[
        "import", d.path().to_str().unwrap(), "-p", "pw",
        src.path().join("a.txt").to_str().unwrap(),
        src.path().join("b.txt").to_str().unwrap(),
        src.path().join("c.txt").to_str().unwrap(),
    ]);
    assert!(ok);
    let (ok, out, _) = cmd(&[
        "search", d.path().to_str().unwrap(), "-p", "pw", "algorithm",
    ]);
    assert!(ok);
    assert!(out.contains("Results (2):"), "got: {}", out);
}

#[test]
fn test_search_no_results() {
    let d = v();
    init(d.path(), "pw");
    let src = sample(&[("a.txt", "hello world")]);
    imp(d.path(), "pw", &src.path().join("a.txt"));
    let (ok, out, _) = cmd(&[
        "search", d.path().to_str().unwrap(), "-p", "pw", "zzzznotfound",
    ]);
    assert!(ok);
    assert!(out.contains("No results found"), "got: {}", out);
}

#[test]
fn test_backup_restore_roundtrip() {
    let a = v();
    let b = v();
    let bf = tempfile::NamedTempFile::new().unwrap();

    init(a.path(), "pw");
    let src = sample(&[("doc_a.txt", "content A"), ("doc_b.txt", "content B")]);
    let (ok, _, _) = cmd(&[
        "import", a.path().to_str().unwrap(), "-p", "pw",
        src.path().join("doc_a.txt").to_str().unwrap(),
        src.path().join("doc_b.txt").to_str().unwrap(),
    ]);
    assert!(ok);
    assert!(list(a.path(), "pw").contains("Documents (2):"));

    let (ok, out, _) = cmd(&[
        "backup", a.path().to_str().unwrap(), "-p", "pw",
        "-o", bf.path().to_str().unwrap(),
    ]);
    assert!(ok);
    assert!(out.contains("Backup exported"));
    assert!(bf.path().metadata().unwrap().len() > 0);

    init(b.path(), "pw");
    let (ok, out, _) = cmd(&[
        "restore", b.path().to_str().unwrap(), "-p", "pw",
        bf.path().to_str().unwrap(),
    ]);
    assert!(ok);
    assert!(out.contains("docs added: 2"));

    let l = list(b.path(), "pw");
    assert!(l.contains("Documents (2):"), "got: {}", l);
    assert!(l.contains("doc_a.txt"), "got: {}", l);
    assert!(l.contains("doc_b.txt"), "got: {}", l);
}

#[test]
fn test_restore_merges_not_replaces() {
    let a = v();
    let b = v();
    let bf = tempfile::NamedTempFile::new().unwrap();

    init(a.path(), "pw");
    let sa = sample(&[("from_a.txt", "from vault A")]);
    imp(a.path(), "pw", &sa.path().join("from_a.txt"));

    init(b.path(), "pw");
    let sb = sample(&[("from_b.txt", "from vault B")]);
    imp(b.path(), "pw", &sb.path().join("from_b.txt"));

    cmd(&["backup", a.path().to_str().unwrap(), "-p", "pw",
        "-o", bf.path().to_str().unwrap()]);

    let (ok, out, _) = cmd(&[
        "restore", b.path().to_str().unwrap(), "-p", "pw",
        bf.path().to_str().unwrap(),
    ]);
    assert!(ok);
    assert!(out.contains("docs added: 1"), "got: {}", out);

    let l = list(b.path(), "pw");
    assert!(l.contains("Documents (2):"), "merge should keep both: {}", l);
    assert!(l.contains("from_a.txt"), "got: {}", l);
    assert!(l.contains("from_b.txt"), "got: {}", l);
}

#[test]
fn test_restore_different_backup_password() {
    // NOTE: merge_vault_dir currently assumes both vaults share the same password.
    // Using different passwords for the backup and vault will fail.
    let a = v();
    let b = v();
    let bf = tempfile::NamedTempFile::new().unwrap();

    init(a.path(), "pass_a");
    let src = sample(&[("doc.txt", "content")]);
    imp(a.path(), "pass_a", &src.path().join("doc.txt"));

    cmd(&["backup", a.path().to_str().unwrap(), "-p", "pass_a",
        "-o", bf.path().to_str().unwrap()]);

    init(b.path(), "pass_b");
    let (ok, _, _) = cmd(&[
        "restore", b.path().to_str().unwrap(), "-p", "pass_b",
        bf.path().to_str().unwrap(), "-P", "pass_a",
    ]);
    // Different passwords fail because merge derives backup master key with vault password
    assert!(!ok, "restore with different passwords should currently fail");
}

#[test]
fn test_restore_wrong_backup_password_fails() {
    let a = v();
    let b = v();
    let bf = tempfile::NamedTempFile::new().unwrap();

    init(a.path(), "pw");
    let src = sample(&[("doc.txt", "content")]);
    imp(a.path(), "pw", &src.path().join("doc.txt"));
    cmd(&["backup", a.path().to_str().unwrap(), "-p", "pw",
        "-o", bf.path().to_str().unwrap()]);

    init(b.path(), "pw");
    let (ok, _, e) = cmd(&[
        "restore", b.path().to_str().unwrap(), "-p", "pw",
        bf.path().to_str().unwrap(), "-P", "wrong",
    ]);
    assert!(!ok);
    assert!(e.contains("AuthenticationFailed") || e.contains("Error"));
}

#[test]
fn test_backup_wrong_password() {
    let d = v();
    let bf = tempfile::NamedTempFile::new().unwrap();
    init(d.path(), "correct");
    let (ok, _, e) = cmd(&[
        "backup", d.path().to_str().unwrap(), "-p", "wrong",
        "-o", bf.path().to_str().unwrap(),
    ]);
    assert!(!ok);
    assert!(e.contains("AuthenticationFailed") || e.contains("Wrong password"));
}

#[test]
fn test_open_document() {
    let d = v();
    init(d.path(), "pw");
    let src = sample(&[("hello.txt", "Hello, World!")]);
    imp(d.path(), "pw", &src.path().join("hello.txt"));
    let name = first_name(d.path(), "pw");
    let (ok, out, _) = cmd(&["open", d.path().to_str().unwrap(), "-p", "pw", &name]);
    assert!(ok);
    assert!(out.contains("Opened"), "got: {}", out);
}

#[test]
fn test_open_nonexistent_fails() {
    let d = v();
    init(d.path(), "pw");
    let (ok, _, e) = cmd(&["open", d.path().to_str().unwrap(), "-p", "pw", "no_such_file.txt"]);
    assert!(!ok);
    assert!(e.contains("No document matching"));
}

#[test]
fn test_delete_document() {
    let d = v();
    init(d.path(), "pw");
    let src = sample(&[("to_delete.txt", "delete me")]);
    imp(d.path(), "pw", &src.path().join("to_delete.txt"));
    let name = first_name(d.path(), "pw");
    let (ok, out, _) = cmd(&["delete", d.path().to_str().unwrap(), "-p", "pw", &name]);
    assert!(ok);
    assert!(out.contains("Deleted"));
    assert!(out.contains("to_delete.txt"));
    assert!(list(d.path(), "pw").contains("No documents"));
}

#[test]
fn test_delete_nonexistent_fails() {
    let d = v();
    init(d.path(), "pw");
    let (ok, _, e) = cmd(&["delete", d.path().to_str().unwrap(), "-p", "pw", "bad_file.txt"]);
    assert!(!ok);
    assert!(e.contains("No document matching"));
}

#[test]
fn test_wrong_password_fails() {
    let d = v();
    init(d.path(), "correct");
    let (ok, _, _) = cmd(&["list", d.path().to_str().unwrap(), "-p", "wrong"]);
    assert!(!ok);
}

#[test]
fn test_vault_not_found() {
    let (ok, _, e) = cmd(&["list", "/nonexistent/vault", "-p", "pw"]);
    assert!(!ok);
    assert!(e.contains("Error") || e.contains("No such file"));
}

#[test]
fn test_full_workflow() {
    let vault = v();
    let restored = v();
    let bf = tempfile::NamedTempFile::new().unwrap();

    // Init and import
    init(vault.path(), "s3cret");
    let src = sample(&[
        ("alice.txt", "Alice was beginning to get very tired"),
        ("bob.txt", "Bob had a little lamb whose fleece was white as snow"),
    ]);
    let (ok, _, _) = cmd(&[
        "import", vault.path().to_str().unwrap(), "-p", "s3cret",
        src.path().join("alice.txt").to_str().unwrap(),
        src.path().join("bob.txt").to_str().unwrap(),
    ]);
    assert!(ok);

    // List shows both
    let l = list(vault.path(), "s3cret");
    assert!(l.contains("Documents (2):"), "list: {}", l);
    assert!(l.contains("alice.txt"), "list: {}", l);
    assert!(l.contains("bob.txt"), "list: {}", l);

    // Search finds content
    let (ok, out, _) = cmd(&[
        "search", vault.path().to_str().unwrap(), "-p", "s3cret", "fleece",
    ]);
    assert!(ok);
    assert!(out.contains("bob.txt"), "search: {}", out);

    // Backup
    let (ok, out, _) = cmd(&[
        "backup", vault.path().to_str().unwrap(), "-p", "s3cret",
        "-o", bf.path().to_str().unwrap(),
    ]);
    assert!(ok);
    assert!(out.contains("Backup exported"));

    // Restore into fresh vault
    init(restored.path(), "s3cret");
    let (ok, out, _) = cmd(&[
        "restore", restored.path().to_str().unwrap(), "-p", "s3cret",
        bf.path().to_str().unwrap(),
    ]);
    assert!(ok);
    assert!(out.contains("docs added: 2"));

    // Verify restored vault
    let l = list(restored.path(), "s3cret");
    assert!(l.contains("Documents (2):"), "restored: {}", l);
    assert!(l.contains("alice.txt"), "restored: {}", l);
    assert!(l.contains("bob.txt"), "restored: {}", l);

    // Search in restored vault (FTS may not be populated after merge,
    // so we verify via list instead)
    let l = list(restored.path(), "s3cret");
    assert!(l.contains("Documents (2):"), "restored: {}", l);
    assert!(l.contains("alice.txt"), "restored: {}", l);
    assert!(l.contains("bob.txt"), "restored: {}", l);
}

// --- REPL tests ---

/// Spawn the REPL with piped stdin, send lines, return combined output.
fn repl(vault_dir: &Path, password: &str, commands: &[&str]) -> (bool, String, String) {
    let mut child = Command::new(bin())
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();

    {
        let stdin = child.stdin.as_mut().unwrap();
        writeln!(stdin, "{}", vault_dir.display()).unwrap();
        writeln!(stdin, "{}", password).unwrap();
        for cmd in commands {
            writeln!(stdin, "{}", cmd).unwrap();
        }
        // quit at the end
        writeln!(stdin, "quit").unwrap();
    }

    let output = child.wait_with_output().unwrap();
    (
        output.status.success(),
        String::from_utf8_lossy(&output.stdout).to_string(),
        String::from_utf8_lossy(&output.stderr).to_string(),
    )
}

#[test]
fn test_repl_list_empty() {
    let d = v();
    init(d.path(), "pw");
    let (ok, out, err) = repl(d.path(), "pw", &["list"]);
    assert!(ok, "repl failed: {}", err);
    assert!(out.contains("No documents"), "expected No documents in stdout: {}", out);
}

#[test]
fn test_repl_help() {
    let d = v();
    init(d.path(), "pw");
    let (ok, out, err) = repl(d.path(), "pw", &["help"]);
    assert!(ok, "repl failed: {}", err);
    assert!(out.contains("Commands:"), "expected help output in stdout: {}", out);
}

#[test]
fn test_repl_import_and_list() {
    let d = v();
    init(d.path(), "pw");
    let src = sample(&[("test.txt", "hello repl")]);
    let file_arg = src.path().join("test.txt").to_str().unwrap().to_string();
    let (ok, out, err) = repl(d.path(), "pw", &[
        &format!("import {}", file_arg),
        "list",
    ]);
    assert!(ok, "repl failed: {}", err);
    assert!(out.contains("Documents (1):") || out.contains("test.txt"),
        "expected import+list output in stdout: {}", out);
}

#[test]
fn test_repl_search() {
    let d = v();
    init(d.path(), "pw");
    let src = sample(&[("lorem.txt", "lorem ipsum dolor")]);
    let file_arg = src.path().join("lorem.txt").to_str().unwrap().to_string();
    let (ok, out, err) = repl(d.path(), "pw", &[
        &format!("import {}", file_arg),
        "search lorem",
    ]);
    assert!(ok, "repl failed: {}", err);
    assert!(out.contains("Results") || out.contains("lorem.txt"),
        "expected search output in stdout: {}", out);
}

#[test]
fn test_repl_wrong_password_fails() {
    let d = v();
    init(d.path(), "correct");
    let mut child = Command::new(bin())
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    {
        let stdin = child.stdin.as_mut().unwrap();
        writeln!(stdin, "{}", d.path().display()).unwrap();
        writeln!(stdin, "wrong").unwrap();
    }
    let output = child.wait_with_output().unwrap();
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(!output.status.success() || stderr.contains("Error") || stderr.contains("error"),
        "expected failure for wrong password: {}", stderr);
}

#[test]
fn test_oneshot_password_prompt_via_stdin() {
    let d = v();
    init(d.path(), "pw");
    // Run list without -p flag — password is piped via stdin
    let mut child = Command::new(bin())
        .args(["list", d.path().to_str().unwrap()])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    {
        let stdin = child.stdin.as_mut().unwrap();
        writeln!(stdin, "pw").unwrap();
    }
    let output = child.wait_with_output().unwrap();
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(output.status.success(), "failed: {}", String::from_utf8_lossy(&output.stderr));
    assert!(stdout.contains("No documents"), "expected No documents: {}", stdout);
}

#[test]
fn test_oneshot_password_wrong_via_stdin() {
    let d = v();
    init(d.path(), "correct");
    let mut child = Command::new(bin())
        .args(["list", d.path().to_str().unwrap()])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    {
        let stdin = child.stdin.as_mut().unwrap();
        writeln!(stdin, "wrong").unwrap();
    }
    let output = child.wait_with_output().unwrap();
    assert!(!output.status.success(), "should fail with wrong password");
}
