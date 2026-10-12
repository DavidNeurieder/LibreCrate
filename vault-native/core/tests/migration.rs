//! DB migration & interruption (data-integrity PR 5).
//!
//! Guarantees:
//!   * A v0-schema database (no `content_hash`, `user_version = 0`) migrates to
//!     the current version with **every document preserved**.
//!   * Migration is idempotent and leaves `PRAGMA integrity_check` /
//!     `foreign_key_check` clean.
//!   * A migration that cannot complete returns a controlled error (never a
//!     panic) and does not bump `user_version`, leaving the database openable.

mod common;

use common::{add_document, create_empty_vault, open_vault, snapshot_vault};
use vault_native::db::schema::{
    create_encrypted_db, get_schema_version, migrate_schema, open_encrypted, set_schema_version,
    CURRENT_SCHEMA_VERSION,
};

fn has_column(conn: &rusqlite::Connection, table: &str, column: &str) -> bool {
    conn.query_row(
        &format!("SELECT COUNT(*) FROM pragma_table_info('{table}') WHERE name = ?1"),
        rusqlite::params![column],
        |row| row.get::<_, i64>(0),
    )
    .unwrap()
        > 0
}

/// v0 → current preserves all documents and yields a consistent database.
#[test]
fn migrate_v0_to_current_preserves_documents() {
    let tmp = tempfile::tempdir().unwrap();
    let root = tmp.path();
    let mk = create_empty_vault(root, "migrate-pass");
    add_document(root, &mk, "d1", "One", "text/plain", b"one");
    add_document(
        root,
        &mk,
        "d2",
        "Two",
        "application/octet-stream",
        b"two bytes",
    );
    let before = snapshot_vault(root, "migrate-pass");
    assert_eq!(before.documents.len(), 2);

    // Downgrade to the v0 schema: drop content_hash, reset user_version to 0.
    {
        let (conn, _) = open_vault(root, "migrate-pass");
        conn.execute_batch("ALTER TABLE documents DROP COLUMN content_hash")
            .unwrap();
        set_schema_version(&conn, 0).unwrap();
        assert!(!has_column(&conn, "documents", "content_hash"));
        let count: i64 = conn
            .query_row("SELECT COUNT(*) FROM documents", [], |r| r.get(0))
            .unwrap();
        assert_eq!(count, 2, "documents must survive the downgrade");
    }

    // Migrate up, then verify idempotency and consistency.
    {
        let (conn, _) = open_vault(root, "migrate-pass");
        assert_eq!(get_schema_version(&conn).unwrap(), 0);

        migrate_schema(&conn).unwrap();
        assert_eq!(get_schema_version(&conn).unwrap(), CURRENT_SCHEMA_VERSION);
        assert!(has_column(&conn, "documents", "content_hash"));

        // Idempotent: re-running must not error or change the version.
        migrate_schema(&conn).unwrap();
        assert_eq!(get_schema_version(&conn).unwrap(), CURRENT_SCHEMA_VERSION);

        let integrity: String = conn
            .query_row("PRAGMA integrity_check", [], |r| r.get(0))
            .unwrap();
        assert_eq!(integrity, "ok");

        let mut fk = conn.prepare("PRAGMA foreign_key_check").unwrap();
        assert!(
            fk.query([]).unwrap().next().unwrap().is_none(),
            "foreign_key_check must be clean after migration"
        );
    }

    let after = snapshot_vault(root, "migrate-pass");
    assert_eq!(
        before.documents, after.documents,
        "migration must preserve every document and its decrypted attachment"
    );
    assert_eq!(after.schema_version, CURRENT_SCHEMA_VERSION);
}

/// A freshly created DB has all columns but `user_version = 0`; migrating just
/// stamps the version without a duplicate-column error.
#[test]
fn migrate_on_fresh_db_sets_version_and_harmless_to_repeat() {
    let mk = (0..32).collect::<Vec<u8>>();
    let tmp = tempfile::tempdir().unwrap();
    let path = tmp.path().join("fresh.db");
    let conn = create_encrypted_db(path.to_str().unwrap(), &mk).unwrap();

    assert_eq!(get_schema_version(&conn).unwrap(), 0);
    assert!(has_column(&conn, "documents", "content_hash"));

    migrate_schema(&conn).unwrap();
    assert_eq!(get_schema_version(&conn).unwrap(), CURRENT_SCHEMA_VERSION);

    migrate_schema(&conn).unwrap();
    assert_eq!(get_schema_version(&conn).unwrap(), CURRENT_SCHEMA_VERSION);
}

/// A migration that cannot complete fails cleanly and does not bump the version.
#[test]
fn failed_migration_is_controlled_and_recoverable() {
    let mk = (0..32).collect::<Vec<u8>>();
    let tmp = tempfile::tempdir().unwrap();
    let path = tmp.path().join("broken.db");

    {
        let conn = create_encrypted_db(path.to_str().unwrap(), &mk).unwrap();
        // Remove the migration target so the v0 -> v1 ALTER cannot run.
        conn.execute_batch("DROP TABLE documents").unwrap();
        set_schema_version(&conn, 0).unwrap();
    }

    let conn = open_encrypted(path.to_str().unwrap(), &mk).unwrap();
    let result = migrate_schema(&conn);
    assert!(
        result.is_err(),
        "migration into a missing table must error, not panic"
    );
    assert_eq!(
        get_schema_version(&conn).unwrap(),
        0,
        "a failed migration must not bump the version"
    );

    let integrity: String = conn
        .query_row("PRAGMA integrity_check", [], |r| r.get(0))
        .unwrap();
    assert_eq!(integrity, "ok");
}
