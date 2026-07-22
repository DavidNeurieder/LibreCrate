use rusqlite::{params, Connection, Result};

pub fn rebuild_index(conn: &Connection) -> Result<()> {
    conn.execute("INSERT INTO documents_fts(documents_fts) VALUES('rebuild')", [])?;
    Ok(())
}

#[derive(Debug, uniffi::Record)]
pub struct FtsResult {
    pub rank: f64,
    pub id: String,
    pub title: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FtsSnippetResult {
    pub rank: f64,
    pub id: String,
    pub title: String,
    pub snippet: String,
}

#[derive(Debug, uniffi::Record)]
pub struct FtsAllMatchesResult {
    pub rank: f64,
    pub id: String,
    pub title: String,
    pub snippet: String,
    pub highlighted: String,
}

#[derive(Debug, uniffi::Record)]
pub struct PageMatch {
    pub snippet: String,
    pub page_number: i32,
}

#[derive(Debug, uniffi::Record)]
pub struct MultiMatchResult {
    pub rank: f64,
    pub id: String,
    pub title: String,
    pub first_snippet: String,
    pub additional_matches: Vec<PageMatch>,
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

/// Search documents using FTS5 with both snippet() and highlight().
/// Returns one result per document with the primary snippet and all
/// page-level matches extracted from the full highlighted text.
pub fn search_with_all_matches(conn: &Connection, query: &str) -> Result<Vec<FtsAllMatchesResult>> {
    let mut stmt = conn.prepare(
        "SELECT d.id, d.title, f.rank,
                snippet(documents_fts, 3, '<b>', '</b>', '...', 64),
                highlight(documents_fts, 3, '<b>', '</b>')
         FROM documents_fts f
         JOIN documents d ON d.rowid = f.rowid
         WHERE f.documents_fts MATCH ?1
         ORDER BY f.rank
         LIMIT 100",
    )?;
    let results = stmt
        .query_map(params![query], |row| {
            Ok(FtsAllMatchesResult {
                id: row.get(0)?,
                title: row.get(1)?,
                rank: row.get(2)?,
                snippet: row.get(3)?,
                highlighted: row.get(4)?,
            })
        })?
        .filter_map(|r| r.ok())
        .collect();
    Ok(results)
}

/// Extract per-page matches from FTS5 highlight() output.
/// Splits by [PAGE=N] and [SECTION=N] markers, keeping only
/// segments that contain `<b>` highlighted terms.
pub fn extract_page_matches(highlighted: &str) -> Vec<PageMatch> {
    const MAX_MATCHES: usize = 50;
    let mut result = Vec::new();
    let mut pos = 0;
    let mut current_page: i32 = 0;

    while pos < highlighted.len() && result.len() < MAX_MATCHES {
        let rest = &highlighted[pos..];

        let page_start = rest.find("[PAGE=");
        let section_start = rest.find("[SECTION=");

        let (marker_offset, marker_len) = match (page_start, section_start) {
            (Some(p), Some(s)) if p < s => (p, 6),
            (Some(_), Some(s)) => (s, 9),
            (Some(p), None) => (p, 6),
            (None, Some(s)) => (s, 9),
            (None, None) => {
                let segment = rest;
                if segment.contains("<b>") {
                    result.push(PageMatch {
                        page_number: current_page,
                        snippet: truncate_around_match(segment, 80),
                    });
                }
                break;
            }
        };

        let segment = &rest[..marker_offset];
        if !segment.is_empty() && segment.contains("<b>") {
            result.push(PageMatch {
                page_number: current_page,
                snippet: truncate_around_match(segment, 80),
            });
        }

        let num_start = marker_offset + marker_len;
        let num_end = rest[num_start..].find(']').unwrap_or(rest.len() - num_start);
        current_page = rest[num_start..num_start + num_end].parse().unwrap_or(current_page);

        pos += num_start + num_end + 1;
    }

    result
}

fn truncate_around_match(text: &str, context: usize) -> String {
    if let Some(pos) = text.find("<b>") {
        let start = pos.saturating_sub(context);
        let end = (pos + 200).min(text.len());
        let mut s = String::new();
        if start > 0 {
            s.push_str("...");
        }
        s.push_str(&text[start..end]);
        if end < text.len() {
            s.push_str("...");
        }
        s
    } else {
        let len = text.len().min(context + 100);
        let mut s: String = text.chars().take(len).collect();
        if len < text.len() {
            s.push_str("...");
        }
        s
    }
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

    fn insert_doc(conn: &Connection, id: &str, title: &str, text: &str) {
        conn.execute(
            "INSERT INTO documents (id, title, file_name, mime_type, file_path, file_size, page_count, author, description, imported_at, last_opened_at, modified_at, is_favorite, is_conflict, current_page, text_content)
             VALUES (?1, ?2, ?3, ?4, ?5, 0, 0, '', '', 0, 0, 0, 0, 0, 0, ?6)",
            params![id, title, "file.txt", "text/plain", "", text],
        ).unwrap();
    }

    #[test]
    fn test_fts_search() {
        let conn = setup_db();
        insert_doc(&conn, "doc1", "The quick brown fox", "This is a document about the quick brown fox");
        insert_doc(&conn, "doc2", "Lazy dog", "The lazy dog sleeps all day");
        // FTS index is populated automatically by the fts_after_insert trigger

        let results = search(&conn, "fox").unwrap();
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].id, "doc1");
        assert_eq!(results[0].title, "The quick brown fox");
        assert!(results[0].rank.is_finite());
    }

    #[test]
    fn test_extract_page_matches_single() {
        let text = "Some intro text <b>fox</b> here.[PAGE=1]Page one <b>fox</b> content.[PAGE=2]No match here.";
        let matches = extract_page_matches(text);
        assert_eq!(matches.len(), 2);
        assert_eq!(matches[0].page_number, 0);
        assert!(matches[0].snippet.contains("fox"));
        assert_eq!(matches[1].page_number, 1);
        assert!(matches[1].snippet.contains("fox"));
    }

    #[test]
    fn test_extract_page_matches_no_markers() {
        let text = "Just a plain <b>fox</b> sighting.";
        let matches = extract_page_matches(text);
        assert_eq!(matches.len(), 1);
        assert_eq!(matches[0].page_number, 0);
    }

    #[test]
    fn test_extract_page_matches_with_sections() {
        let text = "[SECTION=1]First section with <b>fox</b>.[SECTION=2]Second section <b>fox</b> here.[SECTION=3]No match.";
        let matches = extract_page_matches(text);
        assert_eq!(matches.len(), 2);
        assert_eq!(matches[0].page_number, 1);
        assert_eq!(matches[1].page_number, 2);
    }

    #[test]
    fn test_search_with_all_matches() {
        let conn = setup_db();
        insert_doc(
            &conn,
            "doc1",
            "Animal Facts",
            "[PAGE=1]The quick brown <b>fox</b> jumps.[PAGE=2]The lazy <b>fox</b> sleeps.",
        );
        // FTS index is populated automatically by the fts_after_insert trigger

        // FTS5 minimum term length is 3, so use three+ char terms
        let results = search_with_all_matches(&conn, "fox").unwrap();
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].id, "doc1");
        assert!(results[0].snippet.contains("<b>"));

        let page_matches = extract_page_matches(&results[0].highlighted);
        assert!(page_matches.len() >= 2, "expected at least 2 page matches, got {}", page_matches.len());
    }
}

