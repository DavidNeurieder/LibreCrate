use rusqlite::{params, Connection, Result};

/// A lightweight document row returned by query functions.
#[derive(Debug, Clone)]
pub struct DocumentRow {
    pub id: String,
    pub title: String,
    pub file_name: String,
    pub mime_type: String,
    pub file_path: String,
    pub file_size: i64,
    pub page_count: i32,
    pub author: String,
    pub description: String,
    pub thumbnail_path: Option<String>,
    pub imported_at: i64,
    pub last_opened_at: i64,
    pub modified_at: i64,
    pub is_favorite: bool,
    pub is_conflict: bool,
    pub conflict_with: Option<String>,
    pub collection_id: Option<String>,
    pub encryption_iv: Option<Vec<u8>>,
    pub current_page: i32,
    pub reading_position: Option<String>,
    pub barcode_format: Option<String>,
    pub barcode_value: Option<String>,
    pub content_hash: Option<String>,
}

impl Default for DocumentRow {
    fn default() -> Self {
        Self {
            id: String::new(),
            title: String::new(),
            file_name: String::new(),
            mime_type: String::new(),
            file_path: String::new(),
            file_size: 0,
            page_count: 0,
            author: String::new(),
            description: String::new(),
            thumbnail_path: None,
            imported_at: 0,
            last_opened_at: 0,
            modified_at: 0,
            is_favorite: false,
            is_conflict: false,
            conflict_with: None,
            collection_id: None,
            encryption_iv: None,
            current_page: 0,
            reading_position: None,
            barcode_format: None,
            barcode_value: None,
            content_hash: None,
        }
    }
}

#[derive(Debug, Clone)]
pub struct CollectionRow {
    pub id: String,
    pub name: String,
    pub icon: String,
    pub sort_order: i32,
    pub parent_id: Option<String>,
}

#[derive(Debug, Clone)]
pub struct TagRow {
    pub id: String,
    pub name: String,
    pub color: i64,
}

pub(crate) fn document_from_row(row: &rusqlite::Row) -> rusqlite::Result<DocumentRow> {
    Ok(DocumentRow {
        id: row.get(0)?,
        title: row.get(1)?,
        file_name: row.get(2)?,
        mime_type: row.get(3)?,
        file_path: row.get(4)?,
        file_size: row.get(5)?,
        page_count: row.get(6)?,
        author: row.get(7)?,
        description: row.get(8)?,
        thumbnail_path: row.get(9)?,
        imported_at: row.get(10)?,
        last_opened_at: row.get(11)?,
        modified_at: row.get(12)?,
        is_favorite: row.get::<_, i32>(13)? != 0,
        is_conflict: row.get::<_, i32>(14)? != 0,
        conflict_with: row.get(15)?,
        collection_id: row.get(16)?,
        encryption_iv: row.get(17)?,
        current_page: row.get(18)?,
        reading_position: row.get(19)?,
        barcode_format: row.get(20)?,
        barcode_value: row.get(21)?,
        content_hash: row.get(22)?,
    })
}

const DOCUMENT_COLUMNS: &str =
    "id, title, file_name, mime_type, file_path, file_size, page_count, \
     author, description, thumbnail_path, imported_at, last_opened_at, \
     modified_at, is_favorite, is_conflict, conflict_with, collection_id, \
     encryption_iv, current_page, reading_position, barcode_format, barcode_value, \
     content_hash";

pub fn list_documents(conn: &Connection) -> Result<Vec<DocumentRow>> {
    let sql = format!("SELECT {DOCUMENT_COLUMNS} FROM documents ORDER BY title");
    let mut stmt = conn.prepare(&sql)?;
    let rows = stmt
        .query_map([], |row| document_from_row(row))?
        .filter_map(|r| r.ok())
        .collect();
    Ok(rows)
}

pub fn list_documents_filtered(
    conn: &Connection,
    limit: i64,
    offset: i64,
    collection_id: Option<&str>,
    favorite_only: bool,
    tag_id: Option<&str>,
) -> Result<Vec<DocumentRow>> {
    let mut conditions: Vec<String> = Vec::new();
    let mut param_values: Vec<Box<dyn rusqlite::types::ToSql>> = Vec::new();

    if let Some(cid) = collection_id {
        conditions.push(format!("d.collection_id = ?{}", param_values.len() + 1));
        param_values.push(Box::new(cid.to_string()));
    }
    if favorite_only {
        conditions.push(format!("d.is_favorite = ?{}", param_values.len() + 1));
        param_values.push(Box::new(1i32));
    }
    if tag_id.is_some() {
        conditions.push(format!(
            "d.id IN (SELECT document_id FROM document_tags WHERE tag_id = ?{})",
            param_values.len() + 1
        ));
        param_values.push(Box::new(tag_id.unwrap().to_string()));
    }

    let where_clause = if conditions.is_empty() {
        String::new()
    } else {
        format!("WHERE {}", conditions.join(" AND "))
    };

    let sql = format!(
        "SELECT d.{DOCUMENT_COLUMNS} FROM documents d {where_clause} ORDER BY d.title LIMIT ?{limit_idx} OFFSET ?{offset_idx}",
        limit_idx = param_values.len() + 1,
        offset_idx = param_values.len() + 2,
    );
    param_values.push(Box::new(limit));
    param_values.push(Box::new(offset));

    let mut stmt = conn.prepare(&sql)?;

    let params_refs: Vec<&dyn rusqlite::types::ToSql> = param_values.iter().map(|p| p.as_ref()).collect();
    let rows = stmt
        .query_map(params_refs.as_slice(), |row| document_from_row(row))?
        .filter_map(|r| r.ok())
        .collect();
    Ok(rows)
}

pub fn get_document(conn: &Connection, id: &str) -> Result<Option<DocumentRow>> {
    let sql = format!("SELECT {DOCUMENT_COLUMNS} FROM documents WHERE id = ?");
    let mut stmt = conn.prepare(&sql)?;
    let mut rows = stmt.query(params![id])?;
    match rows.next()? {
        Some(row) => Ok(Some(document_from_row(row)?)),
        None => Ok(None),
    }
}

pub fn find_document_by_hash(conn: &Connection, hash: &str) -> Result<Option<DocumentRow>> {
    let sql = format!("SELECT {DOCUMENT_COLUMNS} FROM documents WHERE content_hash = ?");
    let mut stmt = conn.prepare(&sql)?;
    let mut rows = stmt.query(params![hash])?;
    match rows.next()? {
        Some(row) => Ok(Some(document_from_row(row)?)),
        None => Ok(None),
    }
}

pub fn add_document(conn: &Connection, doc: &DocumentRow) -> Result<()> {
    conn.execute(
        "INSERT INTO documents (id, title, file_name, mime_type, file_path, file_size, page_count,
         author, description, thumbnail_path, imported_at, last_opened_at, modified_at,
         is_favorite, is_conflict, conflict_with, collection_id, encryption_iv, current_page,
         reading_position, barcode_format, barcode_value, content_hash)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19,
         ?20, ?21, ?22, ?23)",
        params![
            doc.id, doc.title, doc.file_name, doc.mime_type, doc.file_path,
            doc.file_size, doc.page_count, doc.author, doc.description, doc.thumbnail_path,
            doc.imported_at, doc.last_opened_at, doc.modified_at,
            doc.is_favorite as i32, doc.is_conflict as i32, doc.conflict_with, doc.collection_id,
            doc.encryption_iv, doc.current_page,
            doc.reading_position, doc.barcode_format, doc.barcode_value, doc.content_hash,
        ],
    )?;
    Ok(())
}

pub fn delete_document(conn: &Connection, id: &str) -> Result<bool> {
    let affected = conn.execute("DELETE FROM documents WHERE id = ?", params![id])?;
    Ok(affected > 0)
}

pub fn update_document(conn: &Connection, id: &str, title: &str, is_favorite: bool) -> Result<bool> {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;
    let affected = conn.execute(
        "UPDATE documents SET title = ?1, is_favorite = ?2, modified_at = ?3, last_opened_at = ?3 WHERE id = ?4",
        params![title, is_favorite as i32, now, id],
    )?;
    Ok(affected > 0)
}

pub fn update_document_full(
    conn: &Connection,
    id: &str,
    title: &str,
    author: &str,
    description: &str,
    collection_id: Option<&str>,
    is_favorite: bool,
    is_conflict: bool,
    conflict_with: Option<&str>,
    current_page: i32,
    reading_position: Option<&str>,
) -> Result<bool> {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;
    let affected = conn.execute(
        "UPDATE documents SET title = ?1, author = ?2, description = ?3,
         collection_id = ?4, is_favorite = ?5, is_conflict = ?6,
         conflict_with = ?7, current_page = ?8, reading_position = ?9,
         modified_at = ?10
         WHERE id = ?11",
        params![
            title, author, description, collection_id,
            is_favorite as i32, is_conflict as i32, conflict_with,
            current_page, reading_position, now, id,
        ],
    )?;
    Ok(affected > 0)
}

pub fn set_reading_position(conn: &Connection, id: &str, position: &str) -> Result<bool> {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;
    let affected = conn.execute(
        "UPDATE documents SET reading_position = ?1, modified_at = ?2 WHERE id = ?3",
        params![position, now, id],
    )?;
    Ok(affected > 0)
}

pub fn set_current_page(conn: &Connection, id: &str, page: i32) -> Result<bool> {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;
    let affected = conn.execute(
        "UPDATE documents SET current_page = ?1, modified_at = ?2, last_opened_at = ?2 WHERE id = ?3",
        params![page, now, id],
    )?;
    Ok(affected > 0)
}

/// Add a document and index it into FTS5 in one call.
pub fn add_document_full(
    conn: &Connection,
    doc: &DocumentRow,
    text_content: Option<&str>,
) -> Result<()> {
    add_document(conn, doc)?;
    conn.execute(
        "INSERT INTO documents_fts(rowid, title, author, description, text_content)
         VALUES (last_insert_rowid(), ?1, ?2, ?3, ?4)",
        params![doc.title, doc.author, doc.description, text_content.unwrap_or("")],
    )?;
    Ok(())
}

// ---------------------------------------------------------------------------
// Collection CRUD
// ---------------------------------------------------------------------------

pub fn add_collection(conn: &Connection, col: &CollectionRow) -> Result<()> {
    conn.execute(
        "INSERT OR REPLACE INTO collections (id, name, icon, sort_order, parent_id)
         VALUES (?1, ?2, ?3, ?4, ?5)",
        params![col.id, col.name, col.icon, col.sort_order, col.parent_id],
    )?;
    Ok(())
}

pub fn get_collection(conn: &Connection, id: &str) -> Result<Option<CollectionRow>> {
    let mut stmt = conn.prepare(
        "SELECT id, name, icon, sort_order, parent_id FROM collections WHERE id = ?",
    )?;
    let mut rows = stmt.query(params![id])?;
    match rows.next()? {
        Some(row) => Ok(Some(CollectionRow {
            id: row.get(0)?,
            name: row.get(1)?,
            icon: row.get(2)?,
            sort_order: row.get(3)?,
            parent_id: row.get(4)?,
        })),
        None => Ok(None),
    }
}

pub fn list_collections(conn: &Connection) -> Result<Vec<CollectionRow>> {
    let mut stmt = conn.prepare(
        "SELECT id, name, icon, sort_order, parent_id FROM collections ORDER BY sort_order",
    )?;
    let rows = stmt
        .query_map([], |row| {
            Ok(CollectionRow {
                id: row.get(0)?,
                name: row.get(1)?,
                icon: row.get(2)?,
                sort_order: row.get(3)?,
                parent_id: row.get(4)?,
            })
        })?
        .filter_map(|r| r.ok())
        .collect();
    Ok(rows)
}

pub fn update_collection(conn: &Connection, id: &str, name: &str, icon: &str, sort_order: i32, parent_id: Option<&str>) -> Result<bool> {
    let affected = conn.execute(
        "UPDATE collections SET name = ?1, icon = ?2, sort_order = ?3, parent_id = ?4 WHERE id = ?5",
        params![name, icon, sort_order, parent_id, id],
    )?;
    Ok(affected > 0)
}

pub fn delete_collection(conn: &Connection, id: &str) -> Result<bool> {
    let affected = conn.execute("DELETE FROM collections WHERE id = ?", params![id])?;
    Ok(affected > 0)
}

// ---------------------------------------------------------------------------
// Tag CRUD + linking
// ---------------------------------------------------------------------------

pub fn add_tag(conn: &Connection, tag: &TagRow) -> Result<()> {
    conn.execute(
        "INSERT OR REPLACE INTO tags (id, name, color) VALUES (?1, ?2, ?3)",
        params![tag.id, tag.name, tag.color],
    )?;
    Ok(())
}

pub fn update_tag(conn: &Connection, id: &str, name: &str, color: i64) -> Result<bool> {
    let affected = conn.execute(
        "UPDATE tags SET name = ?1, color = ?2 WHERE id = ?3",
        params![name, color, id],
    )?;
    Ok(affected > 0)
}

pub fn delete_tag(conn: &Connection, id: &str) -> Result<bool> {
    // Remove links first
    conn.execute("DELETE FROM document_tags WHERE tag_id = ?", params![id])?;
    let affected = conn.execute("DELETE FROM tags WHERE id = ?", params![id])?;
    Ok(affected > 0)
}

pub fn link_document_tag(conn: &Connection, document_id: &str, tag_id: &str) -> Result<()> {
    conn.execute(
        "INSERT OR IGNORE INTO document_tags (document_id, tag_id) VALUES (?1, ?2)",
        params![document_id, tag_id],
    )?;
    Ok(())
}

pub fn unlink_document_tag(conn: &Connection, document_id: &str, tag_id: &str) -> Result<bool> {
    let affected = conn.execute(
        "DELETE FROM document_tags WHERE document_id = ?1 AND tag_id = ?2",
        params![document_id, tag_id],
    )?;
    Ok(affected > 0)
}

pub fn get_tags_for_document(conn: &Connection, document_id: &str) -> Result<Vec<TagRow>> {
    let mut stmt = conn.prepare(
        "SELECT t.id, t.name, t.color FROM tags t
         JOIN document_tags dt ON dt.tag_id = t.id
         WHERE dt.document_id = ?
         ORDER BY t.name",
    )?;
    let rows = stmt
        .query_map(params![document_id], |row| {
            Ok(TagRow {
                id: row.get(0)?,
                name: row.get(1)?,
                color: row.get(2)?,
            })
        })?
        .filter_map(|r| r.ok())
        .collect();
    Ok(rows)
}

pub fn get_documents_for_tag(conn: &Connection, tag_id: &str) -> Result<Vec<DocumentRow>> {
    let sql = format!(
        "SELECT d.{DOCUMENT_COLUMNS} FROM documents d
         JOIN document_tags dt ON dt.document_id = d.id
         WHERE dt.tag_id = ?
         ORDER BY d.title"
    );
    let mut stmt = conn.prepare(&sql)?;
    let rows = stmt
        .query_map(params![tag_id], |row| document_from_row(row))?
        .filter_map(|r| r.ok())
        .collect();
    Ok(rows)
}

pub fn list_tags(conn: &Connection) -> Result<Vec<TagRow>> {
    let mut stmt = conn.prepare("SELECT id, name, color FROM tags ORDER BY name")?;
    let rows = stmt
        .query_map([], |row| {
            Ok(TagRow {
                id: row.get(0)?,
                name: row.get(1)?,
                color: row.get(2)?,
            })
        })?
        .filter_map(|r| r.ok())
        .collect();
    Ok(rows)
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
    fn test_add_and_list_documents() {
        let conn = setup_db();
        let doc = DocumentRow {
            id: "test1".into(),
            title: "Test Doc".into(),
            file_name: "test.pdf".into(),
            mime_type: "application/pdf".into(),
            file_path: "files/test.pdf".into(),
            file_size: 1024,
            page_count: 5,
            author: "Tester".into(),
            description: "A test".into(),
            imported_at: 1000,
            last_opened_at: 1000,
            modified_at: 1000,
            ..Default::default()
        };
        add_document(&conn, &doc).unwrap();
        let docs = list_documents(&conn).unwrap();
        assert_eq!(docs.len(), 1);
        assert_eq!(docs[0].id, "test1");
        assert_eq!(docs[0].title, "Test Doc");

        let fetched = get_document(&conn, "test1").unwrap().unwrap();
        assert_eq!(fetched.file_size, 1024);
    }

    #[test]
    fn test_delete_document() {
        let conn = setup_db();
        let doc = DocumentRow {
            id: "del1".into(),
            title: "Delete Me".into(),
            file_name: "delete.txt".into(),
            mime_type: "text/plain".into(),
            file_path: "files/delete.txt".into(),
            imported_at: 0,
            last_opened_at: 0,
            modified_at: 0,
            ..Default::default()
        };
        add_document(&conn, &doc).unwrap();
        assert!(delete_document(&conn, "del1").unwrap());
        assert!(!delete_document(&conn, "nonexistent").unwrap());
        assert_eq!(list_documents(&conn).unwrap().len(), 0);
    }

    #[test]
    fn test_collection_crud() {
        let conn = setup_db();
        let col = CollectionRow {
            id: "col1".into(),
            name: "Test Collection".into(),
            icon: "folder".into(),
            sort_order: 0,
            parent_id: None,
        };
        add_collection(&conn, &col).unwrap();

        let cols = list_collections(&conn).unwrap();
        assert_eq!(cols.len(), 1);
        assert_eq!(cols[0].name, "Test Collection");

        let fetched = get_collection(&conn, "col1").unwrap().unwrap();
        assert_eq!(fetched.name, "Test Collection");

        update_collection(&conn, "col1", "Renamed", "book", 1, None).unwrap();
        let updated = get_collection(&conn, "col1").unwrap().unwrap();
        assert_eq!(updated.name, "Renamed");
        assert_eq!(updated.icon, "book");

        delete_collection(&conn, "col1").unwrap();
        assert!(get_collection(&conn, "col1").unwrap().is_none());
    }

    #[test]
    fn test_tag_crud_and_linking() {
        let conn = setup_db();
        let tag = TagRow {
            id: "tag1".into(),
            name: "Important".into(),
            color: 0xFF0000,
        };
        add_tag(&conn, &tag).unwrap();

        let tags = list_tags(&conn).unwrap();
        assert_eq!(tags.len(), 1);

        update_tag(&conn, "tag1", "Very Important", 0x00FF00).unwrap();
        let updated = list_tags(&conn).unwrap();
        assert_eq!(updated[0].name, "Very Important");

        let doc = DocumentRow {
            id: "doc1".into(),
            title: "Tagged Doc".into(),
            file_name: "tagged.txt".into(),
            mime_type: "text/plain".into(),
            file_path: "files/tagged.txt".into(),
            ..Default::default()
        };
        add_document(&conn, &doc).unwrap();

        link_document_tag(&conn, "doc1", "tag1").unwrap();
        let doc_tags = get_tags_for_document(&conn, "doc1").unwrap();
        assert_eq!(doc_tags.len(), 1);
        assert_eq!(doc_tags[0].name, "Very Important");

        let tag_docs = get_documents_for_tag(&conn, "tag1").unwrap();
        assert_eq!(tag_docs.len(), 1);
        assert_eq!(tag_docs[0].id, "doc1");

        unlink_document_tag(&conn, "doc1", "tag1").unwrap();
        assert!(get_tags_for_document(&conn, "doc1").unwrap().is_empty());

        delete_tag(&conn, "tag1").unwrap();
        assert!(list_tags(&conn).unwrap().is_empty());
    }

    #[test]
    fn test_list_documents_filtered() {
        let conn = setup_db();
        for i in 0..5 {
            let doc = DocumentRow {
                id: format!("doc{i}"),
                title: format!("Doc {i}"),
                file_name: format!("doc{i}.txt"),
                mime_type: "text/plain".into(),
                file_path: format!("files/doc{i}.txt"),
                is_favorite: i % 2 == 0,
                collection_id: if i < 3 { Some("col1".into()) } else { None },
                imported_at: i * 1000,
                last_opened_at: i * 1000,
                modified_at: i * 1000,
                ..Default::default()
            };
            add_document(&conn, &doc).unwrap();
        }

        let all = list_documents_filtered(&conn, 10, 0, None, false, None).unwrap();
        assert_eq!(all.len(), 5);

        let fav = list_documents_filtered(&conn, 10, 0, None, true, None).unwrap();
        assert_eq!(fav.len(), 3);

        let paged = list_documents_filtered(&conn, 2, 1, None, false, None).unwrap();
        assert_eq!(paged.len(), 2);
        assert_eq!(paged[0].title, "Doc 1");
        assert_eq!(paged[1].title, "Doc 2");

        let col_filtered = list_documents_filtered(&conn, 10, 0, Some("col1"), false, None).unwrap();
        assert_eq!(col_filtered.len(), 3);
    }

    #[test]
    fn test_rich_document_update() {
        let conn = setup_db();
        let doc = DocumentRow {
            id: "rich1".into(),
            title: "Original".into(),
            file_name: "rich.txt".into(),
            mime_type: "text/plain".into(),
            file_path: "files/rich.txt".into(),
            ..Default::default()
        };
        add_document(&conn, &doc).unwrap();

        update_document_full(
            &conn, "rich1", "Updated Title", "Author Name",
            "Updated Desc", Some("col1"), true, false, None, 42, Some("page42"),
        ).unwrap();

        let updated = get_document(&conn, "rich1").unwrap().unwrap();
        assert_eq!(updated.title, "Updated Title");
        assert_eq!(updated.author, "Author Name");
        assert_eq!(updated.description, "Updated Desc");
        assert_eq!(updated.collection_id, Some("col1".into()));
        assert!(updated.is_favorite);
        assert_eq!(updated.current_page, 42);
        assert_eq!(updated.reading_position, Some("page42".into()));

        set_reading_position(&conn, "rich1", "chapter5").unwrap();
        assert_eq!(get_document(&conn, "rich1").unwrap().unwrap().reading_position, Some("chapter5".into()));

        set_current_page(&conn, "rich1", 99).unwrap();
        assert_eq!(get_document(&conn, "rich1").unwrap().unwrap().current_page, 99);
    }
}
