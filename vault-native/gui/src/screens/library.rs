use iced::{
    widget::{button, column, container, row, scrollable, text, text_input, Column, Row},
    Element, Task, Length,
};
use std::sync::Arc;

use super::Navigation;
use crate::vault::Vault;
use vault_native::db::fts::FtsSnippetResult;
use vault_native::db::queries::DocumentRow;

#[derive(Debug, Clone)]
pub enum Message {
    SearchChanged(String),
    Search,
    ClearSearch,
    ToggleFavorite(String),
    OpenDocument(String),
    NavigateToSettings,
    NavigateToExport,
    NavigateToCollections,
    Import,
    Imported(Result<String, String>),
    DocumentsLoaded(Result<Vec<DocumentRow>, String>),
    SearchResultsLoaded(Vec<FtsSnippetResult>),
    DropResult(Result<usize, String>),
}

pub struct State {
    pub vault: Arc<Vault>,
    pub documents: Vec<DocumentRow>,
    pub search_query: String,
    pub search_results: Option<Vec<FtsSnippetResult>>,
    pub loading: bool,
    pub error: Option<String>,
}

impl State {
    pub fn new(vault: Arc<Vault>) -> (Self, Task<crate::app::Message>) {
        let state = Self {
            vault,
            documents: Vec::new(),
            search_query: String::new(),
            search_results: None,
            loading: true,
            error: None,
        };
        let task = state.reload();
        (state, task)
    }

    fn reload(&self) -> Task<crate::app::Message> {
        let vault = self.vault.clone();
        Task::perform(
            async move {
                tokio::task::spawn_blocking(move || vault.list_documents().map_err(|e| e.to_string()))
                    .await
                    .map_err(|e| e.to_string())?
            },
            |result| crate::app::Message::Library(Message::DocumentsLoaded(result)),
        )
    }

    pub fn update(&mut self, message: Message) -> Task<crate::app::Message> {
        match message {
            Message::SearchChanged(query) => {
                self.search_query = query;
                Task::none()
            }
            Message::Search => {
                if self.search_query.is_empty() {
                    self.search_results = None;
                    return Task::none();
                }
                let vault = self.vault.clone();
                let query = self.search_query.clone();
                Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || {
                            vault.search_with_snippet(&query).map_err(|e| e.to_string())
                        })
                        .await
                        .map_err(|e| e.to_string())?
                    },
                    |result| match result {
                        Ok(results) => {
                            crate::app::Message::Library(Message::SearchResultsLoaded(results))
                        }
                        Err(e) => {
                            tracing::error!("Search failed: {e}");
                            crate::app::Message::Library(Message::SearchChanged(String::new()))
                        }
                    },
                )
            }
            Message::ClearSearch => {
                self.search_query.clear();
                self.search_results = None;
                Task::none()
            }
            Message::ToggleFavorite(id) => {
                let vault = self.vault.clone();
                Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || {
                            vault.toggle_favorite(id).ok();
                        })
                        .await
                        .ok();
                    },
                    |_| {
                        crate::app::Message::Library(Message::SearchChanged(String::new()))
                    },
                )
            }
            Message::OpenDocument(id) => {
                if let Some(doc) = self.documents.iter().find(|d| d.id == id) {
                    return Task::done(crate::app::Message::Navigate(Navigation::OpenDocument(
                        doc.clone(),
                        self.vault.clone(),
                    )));
                }
                let vault = self.vault.clone();
                let vault2 = self.vault.clone();
                Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || {
                            vault.db.get_document(id).ok().flatten()
                        })
                        .await
                        .ok()
                        .flatten()
                    },
                    move |maybe_doc| {
                        if let Some(doc) = maybe_doc {
                            crate::app::Message::Navigate(Navigation::OpenDocument(doc, vault2))
                        } else {
                            crate::app::Message::Library(Message::SearchChanged(String::new()))
                        }
                    },
                )
            }
            Message::NavigateToSettings => {
                Task::done(crate::app::Message::Navigate(Navigation::Settings(self.vault.clone())))
            }
            Message::NavigateToExport => {
                Task::done(crate::app::Message::Navigate(Navigation::Export(self.vault.clone())))
            }
            Message::NavigateToCollections => {
                Task::done(crate::app::Message::Navigate(Navigation::Collections(self.vault.clone())))
            }
            Message::DocumentsLoaded(Ok(docs)) => {
                self.documents = docs;
                self.loading = false;
                Task::none()
            }
            Message::DocumentsLoaded(Err(e)) => {
                self.error = Some(e);
                self.loading = false;
                Task::none()
            }
            Message::SearchResultsLoaded(results) => {
                self.search_results = Some(results);
                Task::none()
            }
            Message::Import => {
                let vault = self.vault.clone();
                Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || -> Result<String, String> {
                            let file = rfd::FileDialog::new()
                                .set_title("Import Document")
                                .add_filter("Documents", &["pdf", "epub", "cbz", "cbr", "djvu"])
                                .add_filter("Images", &["png", "jpg", "jpeg", "gif", "webp"])
                                .add_filter("All Files", &["*"])
                                .pick_file();
                            match file {
                                Some(path) => vault.import_file(&path).map_err(|e| e.to_string()),
                                None => Err("No file selected".into()),
                            }
                        })
                        .await
                        .map_err(|e| e.to_string())?
                    },
                    |result| crate::app::Message::Library(Message::Imported(result)),
                )
            }
            Message::Imported(Ok(_)) => {
                self.loading = true;
                self.error = None;
                self.reload()
            }
            Message::Imported(Err(e)) => {
                self.error = Some(e);
                Task::none()
            }
            Message::DropResult(Ok(_count)) => {
                self.loading = true;
                self.error = None;
                self.reload()
            }
            Message::DropResult(Err(e)) => {
                self.error = Some(e);
                Task::none()
            }
        }
    }

    pub fn view(&self) -> Element<'_, Message> {
        let header = row![
            text("LibreCrate").size(20),
            text_input("Search documents...", &self.search_query)
                .on_input(Message::SearchChanged)
                .on_submit(Message::Search)
                .width(Length::Fill),
            button("⚙").on_press(Message::NavigateToSettings),
            button("Import").on_press(Message::Import),
            button("⬇").on_press(Message::NavigateToExport),
        ]
        .spacing(10)
        .padding(10);

        let body: Element<'_, Message> = if self.loading {
            text("Loading documents...").into()
        } else if let Some(ref err) = self.error {
            text(err).color(iced::Color::from_rgb(1.0, 0.3, 0.3)).into()
        } else if let Some(ref results) = self.search_results {
            if results.is_empty() {
                text("No results found.").into()
            } else {
                let list = results.iter().fold(
                    Column::new().spacing(10).padding(10),
                    |col, r| {
                        let snippet = r.snippet.replace("<b>", "").replace("</b>", "");
                        col.push(
                            column![
                                text(&r.title).size(14).width(140),
                                text(snippet).size(11).color(iced::Color::from_rgb(0.6, 0.64, 0.7)),
                                row![
                                    button("Open").on_press(Message::OpenDocument(r.id.clone())),
                                ]
                                .spacing(4),
                            ]
                            .spacing(4)
                            .padding(12),
                        )
                    },
                );
                scrollable(list).into()
            }
        } else if self.documents.is_empty() {
            text("No documents. Press Ctrl+I to import files.").into()
        } else {
            let grid = self.documents.chunks(3).fold(
                Column::new().spacing(10).padding(10),
                |col, chunk| {
                    col.push(
                        chunk
                            .iter()
                            .fold(Row::new().spacing(10), |row, doc| {
                                row.push(
                                    column![
                                        text(&doc.title).size(14).width(140),
                                        text(format!(
                                            "{} · {}",
                                            doc.file_size,
                                            doc.mime_type
                                        ))
                                        .size(11)
                                        .color(iced::Color::from_rgb(0.6, 0.64, 0.7)),
                                        row![
                                            button(if doc.is_favorite { "★" } else { "☆" })
                                                .on_press(Message::ToggleFavorite(doc.id.clone())),
                                            button("Open").on_press(Message::OpenDocument(doc.id.clone())),
                                        ]
                                        .spacing(4),
                                    ]
                                    .spacing(4)
                                    .padding(12),
                                )
                            }),
                    )
                },
            );
            scrollable(grid).into()
        };

        let content = column![header, body,];

        container(content).padding(10).into()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::vault::tests::make_test_vault;

    #[test]
    fn test_search_changed() {
        let vault = make_test_vault();
        let (mut state, _task) = State::new(vault);
        let _ = state.update(Message::SearchChanged("hello".into()));
        assert_eq!(state.search_query, "hello");
    }

    #[test]
    fn test_clear_search() {
        let vault = make_test_vault();
        let (mut state, _task) = State::new(vault);
        state.search_query = "test".into();
        state.search_results = Some(Vec::new());
        let _ = state.update(Message::ClearSearch);
        assert!(state.search_query.is_empty());
        assert!(state.search_results.is_none());
    }

    #[test]
    fn test_documents_loaded_success() {
        let vault = make_test_vault();
        let (mut state, _task) = State::new(vault);
        state.loading = true;
        let _ = state.update(Message::DocumentsLoaded(Ok(Vec::new())));
        assert!(!state.loading);
        assert!(state.documents.is_empty());
    }

    #[test]
    fn test_documents_loaded_error() {
        let vault = make_test_vault();
        let (mut state, _task) = State::new(vault);
        state.loading = true;
        let _ = state.update(Message::DocumentsLoaded(Err("fail".into())));
        assert!(!state.loading);
        assert_eq!(state.error, Some("fail".into()));
    }

    #[test]
    fn test_navigation_messages_no_panic() {
        let vault = make_test_vault();
        let (mut state, _task) = State::new(vault);
        let _ = state.update(Message::NavigateToSettings);
        let _ = state.update(Message::NavigateToExport);
    }

    #[test]
    fn test_view_loading() {
        let vault = make_test_vault();
        let (state, _task) = State::new(vault);
        let _view = state.view();
    }

    #[test]
    fn test_view_empty_documents() {
        let vault = make_test_vault();
        let (mut state, _task) = State::new(vault);
        state.loading = false;
        let _view = state.view();
    }

    #[test]
    fn test_view_with_error() {
        let vault = make_test_vault();
        let (mut state, _task) = State::new(vault);
        state.loading = false;
        state.error = Some("Database error".into());
        let _view = state.view();
    }

    #[test]
    fn test_view_with_search_query() {
        let vault = make_test_vault();
        let (mut state, _task) = State::new(vault);
        state.loading = false;
        state.search_query = "test query".into();
        let _view = state.view();
    }

    #[test]
    fn test_ui_library_loading_shows_text() {
        let vault = make_test_vault();
        let (state, _task) = State::new(vault);
        let mut ui = iced_test::simulator(state.view());
        assert!(ui.find("Loading documents...").is_ok());
    }

    #[test]
    fn test_ui_library_empty_shows_hint() {
        let vault = make_test_vault();
        let (mut state, _task) = State::new(vault);
        state.loading = false;
        let mut ui = iced_test::simulator(state.view());
        assert!(ui.find("No documents. Press Ctrl+I to import files.").is_ok());
    }

    #[test]
    fn test_ui_library_error_shown() {
        let vault = make_test_vault();
        let (mut state, _task) = State::new(vault);
        state.loading = false;
        state.error = Some("Database error".into());
        let mut ui = iced_test::simulator(state.view());
        assert!(ui.find("Database error").is_ok());
    }

    #[test]
    fn test_ui_library_settings_button() {
        let vault = make_test_vault();
        let (mut state, _task) = State::new(vault);
        state.loading = false;
        let mut ui = iced_test::simulator(state.view());
        ui.click("⚙").unwrap();
        let msgs: Vec<Message> = ui.into_messages().collect();
        assert!(!msgs.is_empty());
        assert!(msgs.iter().any(|m| matches!(m, Message::NavigateToSettings)));
    }

    #[test]
    fn test_imported_ok_triggers_reload() {
        let vault = make_test_vault();
        let (mut state, _task) = State::new(vault);
        state.loading = false;
        let _ = state.update(Message::Imported(Ok("new-id".into())));
        assert!(state.loading);
    }

    #[test]
    fn test_imported_error_sets_error() {
        let vault = make_test_vault();
        let (mut state, _task) = State::new(vault);
        state.loading = false;
        let _ = state.update(Message::Imported(Err("import failed".into())));
        assert_eq!(state.error, Some("import failed".into()));
        assert!(!state.loading);
    }

    #[test]
    fn test_drop_result_ok_triggers_reload() {
        let vault = make_test_vault();
        let (mut state, _task) = State::new(vault);
        state.loading = false;
        let _ = state.update(Message::DropResult(Ok(3)));
        assert!(state.loading);
    }

    #[test]
    fn test_drop_result_error_sets_state() {
        let vault = make_test_vault();
        let (mut state, _task) = State::new(vault);
        state.loading = false;
        let _ = state.update(Message::DropResult(Err("drop failed".into())));
        assert_eq!(state.error, Some("drop failed".into()));
        assert!(!state.loading);
    }

    #[test]
    fn test_ui_library_import_button_present() {
        let vault = make_test_vault();
        let (state, _task) = State::new(vault);
        let mut ui = iced_test::simulator(state.view());
        assert!(ui.find("Import").is_ok());
    }

    #[test]
    fn test_ui_library_export_button() {
        let vault = make_test_vault();
        let (mut state, _task) = State::new(vault);
        state.loading = false;
        let mut ui = iced_test::simulator(state.view());
        ui.click("⬇").unwrap();
        let msgs: Vec<Message> = ui.into_messages().collect();
        assert!(msgs.iter().any(|m| matches!(m, Message::NavigateToExport)));
    }

    #[test]
    fn test_ui_search_placeholder() {
        let vault = make_test_vault();
        let (state, _task) = State::new(vault);
        let mut ui = iced_test::simulator(state.view());
        assert!(ui.find("Search documents...").is_ok());
    }

    #[test]
    fn test_ui_search_with_imported_documents() {
        let (vault, _dir) = crate::vault::tests::make_test_vault_with_dir();
        let file_path = _dir.path().join("hello.txt");
        std::fs::write(&file_path, b"Hello, world!").unwrap();
        vault.import_file(&file_path).unwrap();

        let docs = vault.list_documents().unwrap();

        let (mut state, _task) = State::new(vault);
        state.loading = false;
        state.documents = docs;

        let mut ui = iced_test::simulator(state.view());
        assert!(ui.find("hello.txt").is_ok());
    }

    #[test]
    fn test_search_finds_documents_by_title_and_content() {
        let (vault, _dir) = crate::vault::tests::make_test_vault_with_dir();

        vault
            .db
            .add_document_full(
                DocumentRow {
                    id: "doc_search_1".into(),
                    title: "Rust Programming".into(),
                    file_name: "rust.txt".into(),
                    mime_type: "text/plain".into(),
                    file_path: "files/doc_search_1".into(),
                    file_size: 50,
                    ..Default::default()
                },
                Some("Learn Rust ownership and borrowing".into()),
            )
            .unwrap();

        vault
            .db
            .add_document_full(
                DocumentRow {
                    id: "doc_search_2".into(),
                    title: "Italian Cooking".into(),
                    file_name: "cooking.txt".into(),
                    mime_type: "text/plain".into(),
                    file_path: "files/doc_search_2".into(),
                    file_size: 100,
                    ..Default::default()
                },
                Some("Pasta recipes and pizza dough".into()),
            )
            .unwrap();

        let (mut state, _task) = State::new(vault);
        state.loading = false;

        // Search by title
        state.search_query = "Rust".into();
        let results = state.vault.search_with_snippet("Rust").unwrap();
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].id, "doc_search_1");

        let _ = state.update(Message::SearchResultsLoaded(results));
        assert_eq!(state.search_results.as_ref().unwrap().len(), 1);

        // Search by content (FTS text_content match)
        state.search_query = "pizza".into();
        let results = state.vault.search_with_snippet("pizza").unwrap();
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].id, "doc_search_2");
        assert!(
            results[0].snippet.to_lowercase().contains("pizza"),
            "snippet should contain 'pizza', got: {}",
            results[0].snippet
        );

        let _ = state.update(Message::SearchResultsLoaded(results));
        assert_eq!(state.search_results.as_ref().unwrap().len(), 1);

        // No matches for nonexistent term
        state.search_query = "nonexistent".into();
        let results = state.vault.search_with_snippet("nonexistent").unwrap();
        assert!(results.is_empty());

        let _ = state.update(Message::SearchResultsLoaded(results));
        assert_eq!(state.search_results.as_ref().unwrap().len(), 0);

        // Clear resets state
        state.search_query = "Rust".into();
        state.search_results = Some(vec![FtsSnippetResult {
            rank: 1.0,
            id: "doc_search_1".into(),
            title: "Rust Programming".into(),
            snippet: "".into(),
        }]);
        let _ = state.update(Message::ClearSearch);
        assert!(state.search_query.is_empty());
        assert!(state.search_results.is_none());

        // Empty query does not dispatch
        let _task = state.update(Message::Search);
    }
}
