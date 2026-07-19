use rusqlite::{params, Connection, Result};

pub fn rebuild_index(conn: &Connection) -> Result<()> {
    conn.execute("INSERT INTO documents_fts(documents_fts) VALUES('rebuild')", [])?;
    Ok(())
}

#[derive(Debug)]
pub struct FtsResult {
    pub rank: f64,
    pub id: String,
    pub title: String,
}

#[derive(Debug)]
pub struct FtsSnippetResult {
    pub rank: f64,
    pub id: String,
    pub title: String,
    pub snippet: String,
}

/// Search documents using FTS5. Returns document id/title ordered by relevance.
pub fn search(conn: &Connection, query: &str) -> Result<Vec<FtsResult>> {
    let mut stmt = conn.prepare(
        "SELECT d.id, d.title, f.rank
         FROM documents_fts f
         JOIN documents d ON d.rowid = f.rowid
         WHERE f.documents_fts MATCH ?1
         ORDER BY f.rank
         LIMIT 100",
    )?;
    let results = stmt
        .query_map(params![query], |row| {
            Ok(FtsResult {
                id: row.get(0)?,
                title: row.get(1)?,
                rank: row.get(2)?,
            })
        })?
        .filter_map(|r| r.ok())
        .collect();
    Ok(results)
}

/// Search documents using FTS5 with snippet() highlight. Returns snippet text alongside rank/id/title.
pub fn search_with_snippet(conn: &Connection, query: &str) -> Result<Vec<FtsSnippetResult>> {
    let mut stmt = conn.prepare(
        "SELECT d.id, d.title, f.rank,
                snippet(documents_fts, 3, '<b>', '</b>', '...', 64)
         FROM documents_fts f
         JOIN documents d ON d.rowid = f.rowid
         WHERE f.documents_fts MATCH ?1
         ORDER BY f.rank
         LIMIT 100",
    )?;
    let results = stmt
        .query_map(params![query], |row| {
            Ok(FtsSnippetResult {
                id: row.get(0)?,
                title: row.get(1)?,
                rank: row.get(2)?,
                snippet: row.get(3)?,
            })
        })?
        .filter_map(|r| r.ok())
        .collect();
    Ok(results)
}

/// Search within a single document's text_content using FTS5 highlight.
/// Returns matching snippet(s) with page numbers extracted from context.
pub fn search_in_document(conn: &Connection, document_id: &str, query: &str) -> Result<Vec<FtsSnippetResult>> {
    let mut stmt = conn.prepare(
        "SELECT d.id, d.title, f.rank,
                snippet(documents_fts, 3, '<b>', '</b>', '...', 64)
         FROM documents_fts f
         JOIN documents d ON d.rowid = f.rowid
         WHERE d.id = ?1 AND f.documents_fts MATCH ?2
         ORDER BY f.rank
         LIMIT 20",
    )?;
    let results = stmt
        .query_map(params![document_id, query], |row| {
            Ok(FtsSnippetResult {
                id: row.get(0)?,
                title: row.get(1)?,
                rank: row.get(2)?,
                snippet: row.get(3)?,
            })
        })?
        .filter_map(|r| r.ok())
        .collect();
    Ok(results)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::db::schema::create_all_tables;

    fn setup_db() -> Connection {
        let conn = Connection::open_in_memory().unwrap();
        create_all_tables(&conn).unwrap();
        conn
    }

    #[test]
    fn test_fts_search() {
        let conn = setup_db();
        conn.execute(
            "INSERT INTO documents (id, title, file_name, mime_type, file_path, file_size, page_count, author, description, imported_at, last_opened_at, modified_at, is_favorite, is_conflict, current_page, text_content)
             VALUES (?1, ?2, ?3, ?4, ?5, 0, 0, '', '', 0, 0, 0, 0, 0, 0, ?6)",
            params!["doc1", "The quick brown fox", "fox.txt", "text/plain", "", "This is a document about the quick brown fox"],
        ).unwrap();
        conn.execute(
            "INSERT INTO documents (id, title, file_name, mime_type, file_path, file_size, page_count, author, description, imported_at, last_opened_at, modified_at, is_favorite, is_conflict, current_page, text_content)
             VALUES (?1, ?2, ?3, ?4, ?5, 0, 0, '', '', 0, 0, 0, 0, 0, 0, ?6)",
            params!["doc2", "Lazy dog", "dog.txt", "text/plain", "", "The lazy dog sleeps all day"],
        ).unwrap();

        // Manually populate FTS index from documents table
        conn.execute(
            "INSERT INTO documents_fts(rowid, title, author, description, text_content)
             SELECT rowid, title, author, description, text_content FROM documents",
            [],
        ).unwrap();

        let results = search(&conn, "fox").unwrap();
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].id, "doc1");
        assert_eq!(results[0].title, "The quick brown fox");
        assert!(results[0].rank.is_finite());
    }
}
