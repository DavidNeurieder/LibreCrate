use iced::{
    widget::{button, column, container, image, pick_list, row, scrollable, text, text_input, Column, Row},
    Element, Task, Length,
};
use std::collections::HashMap;
use std::sync::Arc;

use super::Navigation;
use crate::vault::Vault;
use crate::widgets::document_card;
use vault_native::db::fts::FtsSnippetResult;
use vault_native::db::queries::DocumentRow;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SortOption {
    RecentlyOpened,
    NameAsc,
    NameDesc,
    NewestFirst,
    OldestFirst,
}

impl SortOption {
    const ALL: &'static [SortOption] = &[
        SortOption::RecentlyOpened,
        SortOption::NameAsc,
        SortOption::NameDesc,
        SortOption::NewestFirst,
        SortOption::OldestFirst,
    ];
}

impl std::fmt::Display for SortOption {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SortOption::RecentlyOpened => write!(f, "Recently Opened"),
            SortOption::NameAsc => write!(f, "Name A-Z"),
            SortOption::NameDesc => write!(f, "Name Z-A"),
            SortOption::NewestFirst => write!(f, "Newest First"),
            SortOption::OldestFirst => write!(f, "Oldest First"),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TypeFilter {
    All,
    Pdf,
    Epub,
    Comic,
    Image,
    Note,
    Pass,
}

impl TypeFilter {
    const ALL: &'static [TypeFilter] = &[
        TypeFilter::All,
        TypeFilter::Pdf,
        TypeFilter::Epub,
        TypeFilter::Comic,
        TypeFilter::Image,
        TypeFilter::Note,
        TypeFilter::Pass,
    ];

    fn matches_mime(self, mime: &str) -> bool {
        match self {
            TypeFilter::All => true,
            TypeFilter::Pdf => mime.contains("pdf"),
            TypeFilter::Epub => mime.contains("epub"),
            TypeFilter::Comic => mime.contains("comicbook") || mime.contains("cbz"),
            TypeFilter::Image => mime.starts_with("image/"),
            TypeFilter::Note => mime.contains("markdown") || mime.contains("text/plain"),
            TypeFilter::Pass => mime.contains("pkpass") || mime.contains("apple.pkpass"),
        }
    }
}

impl std::fmt::Display for TypeFilter {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            TypeFilter::All => write!(f, "All"),
            TypeFilter::Pdf => write!(f, "PDFs"),
            TypeFilter::Epub => write!(f, "Books"),
            TypeFilter::Comic => write!(f, "Comics"),
            TypeFilter::Image => write!(f, "Images"),
            TypeFilter::Note => write!(f, "Notes"),
            TypeFilter::Pass => write!(f, "Passes"),
        }
    }
}

#[derive(Debug, Clone)]
pub enum Message {
    SearchChanged(String),
    Search,
    ClearSearch,
    SortChanged(SortOption),
    FilterChanged(TypeFilter),
    ToggleFavorite(String),
    RequestDelete(String),
    ConfirmDelete(String),
    CancelDelete,
    DeleteDocument(String),
    OpenDocument(String),
    ShowDocumentInfo(String),
    HideDocumentInfo,
    RenameNameChanged(String),
    RenameDocument(String, String),
    ConfirmRename(String),
    NavigateToSettings,
    NavigateToExport,
    NavigateToExportDocs,
    NavigateToCollections,
    Import,
    Imported(Result<usize, String>),
    DocumentsLoaded(Result<Vec<DocumentRow>, String>),
    SearchResultsLoaded(Vec<FtsSnippetResult>),
    DropResult(Result<usize, String>),
    ThumbnailLoaded(String, image::Handle),
    Noop,
}

pub struct State {
    pub vault: Arc<Vault>,
    pub documents: Vec<DocumentRow>,
    pub search_query: String,
    pub search_results: Option<Vec<FtsSnippetResult>>,
    pub loading: bool,
    pub error: Option<String>,
    pub thumbnails: HashMap<String, image::Handle>,
    pub sort_option: SortOption,
    pub type_filter: TypeFilter,
    pub pending_delete_id: Option<String>,
    pub info_doc_id: Option<String>,
    pub rename_name: String,
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
            thumbnails: HashMap::new(),
            sort_option: SortOption::RecentlyOpened,
            type_filter: TypeFilter::All,
            pending_delete_id: None,
            info_doc_id: None,
            rename_name: String::new(),
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

    fn filtered_documents(&self) -> Vec<&DocumentRow> {
        self.documents
            .iter()
            .filter(|d| self.type_filter.matches_mime(&d.mime_type))
            .collect()
    }

    fn sorted_documents(docs: &mut Vec<DocumentRow>, sort: SortOption) {
        match sort {
            SortOption::RecentlyOpened => docs.sort_by(|a, b| b.last_opened_at.cmp(&a.last_opened_at)),
            SortOption::NameAsc => docs.sort_by(|a, b| a.title.to_lowercase().cmp(&b.title.to_lowercase())),
            SortOption::NameDesc => docs.sort_by(|a, b| b.title.to_lowercase().cmp(&a.title.to_lowercase())),
            SortOption::NewestFirst => docs.sort_by(|a, b| b.imported_at.cmp(&a.imported_at)),
            SortOption::OldestFirst => docs.sort_by(|a, b| a.imported_at.cmp(&b.imported_at)),
        }
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
            Message::SortChanged(sort) => {
                self.sort_option = sort;
                Self::sorted_documents(&mut self.documents, self.sort_option);
                Task::none()
            }
            Message::FilterChanged(filter) => {
                self.type_filter = filter;
                Task::none()
            }
            Message::ToggleFavorite(id) => {
                if let Some(doc) = self.documents.iter_mut().find(|d| d.id == id) {
                    doc.is_favorite = !doc.is_favorite;
                }
                let vault = self.vault.clone();
                std::thread::spawn(move || {
                    let _ = vault.toggle_favorite(id);
                });
                Task::none()
            }
            Message::RequestDelete(id) => {
                self.pending_delete_id = Some(id);
                Task::none()
            }
            Message::ConfirmDelete(id) => {
                self.pending_delete_id = None;
                self.documents.retain(|d| d.id != id);
                self.thumbnails.remove(&id);
                let vault = self.vault.clone();
                std::thread::spawn(move || {
                    let _ = vault.delete_document(&id);
                });
                Task::none()
            }
            Message::CancelDelete => {
                self.pending_delete_id = None;
                Task::none()
            }
            Message::DeleteDocument(id) => {
                self.documents.retain(|d| d.id != id);
                self.thumbnails.remove(&id);
                let vault = self.vault.clone();
                std::thread::spawn(move || {
                    let _ = vault.delete_document(&id);
                });
                Task::none()
            }
            Message::ShowDocumentInfo(id) => {
                if let Some(doc) = self.documents.iter().find(|d| d.id == id) {
                    self.info_doc_id = Some(id);
                    self.rename_name = doc.title.clone();
                }
                Task::none()
            }
            Message::HideDocumentInfo => {
                self.info_doc_id = None;
                Task::none()
            }
            Message::RenameNameChanged(name) => {
                self.rename_name = name;
                Task::none()
            }
            Message::RenameDocument(id, new_name) => {
                let trimmed = new_name.trim().to_string();
                if trimmed.is_empty() {
                    return Task::none();
                }
                if let Some(doc) = self.documents.iter_mut().find(|d| d.id == id) {
                    doc.title = trimmed.clone();
                }
                self.info_doc_id = None;
                let vault = self.vault.clone();
                let new_title = trimmed;
                std::thread::spawn(move || {
                    let _ = vault.rename_document(&id, &new_title);
                });
                Task::none()
            }
            Message::ConfirmRename(id) => {
                let new_name = self.rename_name.clone();
                self.rename_name.clear();
                Task::done(crate::app::Message::Library(Message::RenameDocument(id, new_name)))
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
            Message::NavigateToExportDocs => {
                Task::done(crate::app::Message::Navigate(Navigation::ExportDocs(self.vault.clone())))
            }
            Message::NavigateToCollections => {
                Task::done(crate::app::Message::Navigate(Navigation::Collections(self.vault.clone())))
            }
            Message::DocumentsLoaded(Ok(mut docs)) => {
                Self::sorted_documents(&mut docs, self.sort_option);
                self.documents = docs;
                self.loading = false;
                self.thumbnails.clear();
                let vault = self.vault.clone();
                let tasks: Vec<Task<crate::app::Message>> = self.documents.iter().map(|doc| {
                    let vault = vault.clone();
                    let id = doc.id.clone();
                    let id2 = id.clone();
                    Task::perform(
                        async move {
                            tokio::task::spawn_blocking(move || vault.load_thumbnail(&id2))
                                .await
                                .ok()
                                .flatten()
                        },
                        move |thumb| {
                            let msg = thumb
                                .map(|data| {
                                    let handle = image::Handle::from_bytes(data);
                                    crate::app::Message::Library(Message::ThumbnailLoaded(id, handle))
                                })
                                .unwrap_or(crate::app::Message::Library(Message::Noop));
                            msg
                        },
                    )
                }).collect();
                Task::batch(tasks)
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
            Message::ThumbnailLoaded(id, data) => {
                self.thumbnails.insert(id, data);
                Task::none()
            }
            Message::Noop => Task::none(),
            Message::Import => {
                let vault = self.vault.clone();
                Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || -> Result<usize, String> {
                            let files = rfd::FileDialog::new()
                                .set_title("Import Documents")
                                .add_filter("Documents", &["pdf", "epub", "cbz", "cbr", "djvu"])
                                .add_filter("Images", &["png", "jpg", "jpeg", "gif", "webp"])
                                .add_filter("All Files", &["*"])
                                .pick_files();
                            match files {
                                Some(paths) if !paths.is_empty() => {
                                    let mut count = 0usize;
                                    let mut first_error = None;
                                    for path in &paths {
                                        match vault.import_file(path) {
                                            Ok(_) => count += 1,
                                            Err(e) if first_error.is_none() => {
                                                first_error = Some(e.to_string());
                                            }
                                            _ => {}
                                        }
                                    }
                                    if count > 0 {
                                        Ok(count)
                                    } else {
                                        Err(first_error.unwrap_or_else(|| "Import failed".into()))
                                    }
                                }
                                _ => Ok(0),
                            }
                        })
                        .await
                        .map_err(|e| e.to_string())?
                    },
                    |result| crate::app::Message::Library(Message::Imported(result)),
                )
            }
            Message::Imported(Ok(count)) => {
                if count > 0 {
                    self.loading = true;
                    self.error = None;
                    self.reload()
                } else {
                    Task::none()
                }
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
        let sort_picker = pick_list(SortOption::ALL, Some(self.sort_option), Message::SortChanged)
            .width(Length::Fixed(140.0));

        let filter_row = TypeFilter::ALL.iter().fold(
            Row::new().spacing(6),
            |row, &f| {
                let is_active = self.type_filter == f;
                let label = text(format!("{f}")).size(12);
                let chip = if is_active {
                    button(label).style(|_theme, _status| button::Style {
                        background: Some(iced::Background::Color(iced::Color::from_rgb(0.25, 0.45, 0.7))),
                        text_color: iced::Color::WHITE,
                        border: iced::Border {
                            color: iced::Color::from_rgb(0.3, 0.5, 0.8),
                            width: 1.0,
                            radius: 12.0.into(),
                        },
                        ..Default::default()
                    })
                } else {
                    button(label).style(|_theme, _status| button::Style {
                        background: Some(iced::Background::Color(iced::Color::from_rgb(0.15, 0.15, 0.17))),
                        text_color: iced::Color::from_rgb(0.7, 0.72, 0.75),
                        border: iced::Border {
                            color: iced::Color::from_rgb(0.25, 0.25, 0.28),
                            width: 1.0,
                            radius: 12.0.into(),
                        },
                        ..Default::default()
                    })
                };
                row.push(chip.on_press(Message::FilterChanged(f)))
            },
        );

        let filter_bar = container(
            row![sort_picker, text("").width(Length::Fill), filter_row]
                .spacing(10)
                .align_y(iced::Alignment::Center),
        )
        .padding(iced::Padding::new(0.0).top(0.0).bottom(8.0).left(16.0).right(16.0))
        .width(Length::Fill);

        let toolbar = container(
            row![
                text("LibreCrate").size(20),
                text_input("Search documents...", &self.search_query)
                    .on_input(Message::SearchChanged)
                    .on_submit(Message::Search)
                    .width(Length::Fill),
                button("⚙").on_press(Message::NavigateToSettings),
                button("+").on_press(Message::Import),
                button("⬇").on_press(Message::NavigateToExport),
                button("ZIP").on_press(Message::NavigateToExportDocs),
            ]
            .spacing(10)
            .padding(12)
            .align_y(iced::Alignment::Center),
        )
        .width(Length::Fill)
        .style(|_| container::Style {
            border: iced::Border {
                color: iced::Color::from_rgb(0.2, 0.2, 0.22),
                width: 0.0,
                radius: 0.0.into(),
            },
            ..Default::default()
        });

        let body: Element<'_, Message> = if self.loading {
            container(text("Loading documents...").size(16))
                .center_x(Length::Fill)
                .center_y(Length::Fill)
                .width(Length::Fill)
                .height(Length::Fill)
                .into()
        } else if let Some(ref err) = self.error {
            container(
                column![
                    text("Error").size(18).color(iced::Color::from_rgb(1.0, 0.3, 0.3)),
                    text(err).size(14),
                ]
                .spacing(8)
                .align_x(iced::Alignment::Center),
            )
            .center_x(Length::Fill)
            .center_y(Length::Fill)
            .width(Length::Fill)
            .height(Length::Fill)
            .into()
        } else if let Some(ref results) = self.search_results {
            if results.is_empty() {
                container(text("No results found.").size(16))
                    .center_x(Length::Fill)
                    .center_y(Length::Fill)
                    .width(Length::Fill)
                    .height(Length::Fill)
                    .into()
            } else {
                let list = results.iter().fold(
                    Column::new().spacing(6).padding(16),
                    |col, r| {
                        let snippet = r.snippet.replace("<b>", "").replace("</b>", "");
                        col.push(
                            container(
                                column![
                                    text(&r.title).size(14).width(300),
                                    text(snippet)
                                        .size(11)
                                        .color(iced::Color::from_rgb(0.6, 0.64, 0.7)),
                                    button("Open")
                                        .on_press(Message::OpenDocument(r.id.clone())),
                                ]
                                .spacing(4)
                                .padding(12),
                            )
                            .style(crate::widgets::common::card_style()),
                        )
                    },
                );
                scrollable(list).into()
            }
        } else {
            let filtered = self.filtered_documents();
            if filtered.is_empty() {
                let hint = if self.type_filter != TypeFilter::All {
                    "No documents match this filter."
                } else {
                    "Press Ctrl+I or tap + to import files."
                };
                container(
                    column![
                        text("No documents yet").size(18),
                        text(hint).size(13),
                    ]
                    .spacing(8)
                    .align_x(iced::Alignment::Center),
                )
                .center_x(Length::Fill)
                .center_y(Length::Fill)
                .width(Length::Fill)
                .height(Length::Fill)
                .into()
            } else {
                let grid = filtered.chunks(4).fold(
                    Column::new().spacing(12).padding(16),
                    |col, chunk| {
                        col.push(
                            chunk
                                .iter()
                                .fold(
                                    Row::new().spacing(12).width(Length::Fill),
                                    |row, doc| {
                                        let thumb = self.thumbnails.get(&doc.id);
                                        row.push(document_card::view(doc, thumb))
                                    },
                                )
                                .width(Length::Fill),
                        )
                    },
                );
                scrollable(grid).into()
            }
        };

        let mut content = column![toolbar, filter_bar, body];

        if let Some(ref doc_id) = self.info_doc_id {
            if let Some(doc) = self.documents.iter().find(|d| d.id == *doc_id) {
                let info_panel = self.info_panel(doc);
                content = content.push(info_panel);
            }
        }

        if let Some(ref delete_id) = self.pending_delete_id.clone() {
            let doc_name = self
                .documents
                .iter()
                .find(|d| d.id == *delete_id)
                .map(|d| d.title.clone())
                .unwrap_or_default();
            let did = delete_id.clone();
            let dname = doc_name.clone();
            let overlay = Self::delete_dialog(did, dname);
            return container(
                column![content, overlay],
            )
            .width(Length::Fill)
            .height(Length::Fill)
            .into();
        }

        container(content).width(Length::Fill).height(Length::Fill).into()
    }

    fn info_panel(&self, doc: &DocumentRow) -> Element<'_, Message> {
        let mime_display = doc.mime_type
            .split('/')
            .last()
            .unwrap_or(&doc.mime_type)
            .to_uppercase();

        let size_display = format_file_size(doc.file_size);

        let imported_display = if doc.imported_at > 0 {
            format_timestamp(doc.imported_at)
        } else {
            "Unknown".into()
        };

        let opened_display = if doc.last_opened_at > 0 {
            format_timestamp(doc.last_opened_at)
        } else {
            "Never".into()
        };

        let page_display = if doc.page_count > 0 {
            doc.page_count.to_string()
        } else {
            "-".into()
        };

        let content = column![
            row![
                text("Document Info").size(16),
                text("").width(Length::Fill),
                button(text("×").size(14))
                    .on_press(Message::HideDocumentInfo)
                    .style(|_theme, _status| button::Style {
                        text_color: iced::Color::from_rgb(0.7, 0.7, 0.7),
                        background: None,
                        border: iced::Border::default(),
                        ..Default::default()
                    }),
            ]
            .spacing(8)
            .align_y(iced::Alignment::Center),
            row![
                text("Title:").size(12).color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                text_input("Document title", &self.rename_name)
                    .on_input(Message::RenameNameChanged)
                    .on_submit(Message::ConfirmRename(doc.id.clone()))
                    .width(Length::Fill),
            ]
            .spacing(8)
            .align_y(iced::Alignment::Center),
            row![
                text("Type:").size(12).color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                text(mime_display).size(12),
            ].spacing(8),
            row![
                text("Size:").size(12).color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                text(size_display).size(12),
            ].spacing(8),
            row![
                text("Pages:").size(12).color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                text(page_display).size(12),
            ].spacing(8),
            row![
                text("Author:").size(12).color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                text(if doc.author.is_empty() { "-".into() } else { doc.author.clone() }).size(12),
            ].spacing(8),
            row![
                text("Imported:").size(12).color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                text(imported_display).size(12),
            ].spacing(8),
            row![
                text("Last opened:").size(12).color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                text(opened_display).size(12),
            ].spacing(8),
        ]
        .spacing(8)
        .padding(16);

        container(content)
            .width(Length::Fixed(360.0))
            .style(crate::widgets::common::card_style())
            .into()
    }

    fn delete_dialog(doc_id: String, doc_name: String) -> Element<'static, Message> {
        let dialog = column![
            text("Delete Document").size(16),
            text(format!("Are you sure you want to delete \"{}\"?", doc_name))
                .size(13)
                .width(Length::Fixed(320.0)),
            text("This action cannot be undone.").size(11)
                .color(iced::Color::from_rgb(0.7, 0.5, 0.5)),
            row![
                button(text("Cancel").size(13))
                    .on_press(Message::CancelDelete)
                    .style(|_theme, _status| button::Style {
                        text_color: iced::Color::from_rgb(0.7, 0.7, 0.7),
                        background: Some(iced::Background::Color(iced::Color::from_rgb(0.2, 0.2, 0.22))),
                        border: iced::Border {
                            color: iced::Color::from_rgb(0.3, 0.3, 0.35),
                            width: 1.0,
                            radius: 6.0.into(),
                        },
                        ..Default::default()
                    })
                    .padding(iced::Padding::new(6.0).left(16.0).right(16.0)),
                button(text("Delete").size(13).color(iced::Color::WHITE))
                    .on_press(Message::ConfirmDelete(doc_id))
                    .style(|_theme, _status| button::Style {
                        text_color: iced::Color::WHITE,
                        background: Some(iced::Background::Color(iced::Color::from_rgb(0.7, 0.2, 0.2))),
                        border: iced::Border {
                            color: iced::Color::from_rgb(0.8, 0.3, 0.3),
                            width: 1.0,
                            radius: 6.0.into(),
                        },
                        ..Default::default()
                    })
                    .padding(iced::Padding::new(6.0).left(16.0).right(16.0)),
            ]
            .spacing(10)
            .align_y(iced::Alignment::Center),
        ]
        .spacing(12)
        .padding(20)
        .width(Length::Fixed(380.0));

        container(
            container(dialog)
                .style(crate::widgets::common::card_style())
        )
        .center_x(Length::Fill)
        .center_y(Length::Fill)
        .width(Length::Fill)
        .height(Length::Fill)
        .style(|_| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgba(0.0, 0.0, 0.0, 0.5))),
            ..Default::default()
        })
        .into()
    }
}

fn format_file_size(bytes: i64) -> String {
    if bytes < 1024 {
        format!("{} B", bytes)
    } else if bytes < 1024 * 1024 {
        format!("{:.1} KB", bytes as f64 / 1024.0)
    } else {
        format!("{:.1} MB", bytes as f64 / (1024.0 * 1024.0))
    }
}

fn format_timestamp(ts: i64) -> String {
    if ts <= 0 {
        return "Unknown".into();
    }
    let secs = ts;
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64;
    let diff = now - secs;
    if diff < 0 {
        return "Just now".into();
    }
    if diff < 60 {
        format!("{}s ago", diff)
    } else if diff < 3600 {
        format!("{}m ago", diff / 60)
    } else if diff < 86400 {
        format!("{}h ago", diff / 3600)
    } else if diff < 604800 {
        format!("{}d ago", diff / 86400)
    } else {
        format!("{}w ago", diff / 604800)
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
        assert!(ui.find("No documents yet").is_ok());
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
        let _ = state.update(Message::Imported(Ok(1)));
        assert!(state.loading);
    }

    #[test]
    fn test_imported_ok_zero_does_nothing() {
        let vault = make_test_vault();
        let (mut state, _task) = State::new(vault);
        state.loading = false;
        let _ = state.update(Message::Imported(Ok(0)));
        assert!(!state.loading);
        assert!(state.error.is_none());
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
        assert!(ui.find("+").is_ok());
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

    #[test]
    fn test_delete_document_removes_from_state() {
        let (vault, _dir) = crate::vault::tests::make_test_vault_with_dir();
        let file_path = _dir.path().join("delete_me.txt");
        std::fs::write(&file_path, b"content").unwrap();
        vault.import_file(&file_path).unwrap();

        let docs = vault.list_documents().unwrap();
        assert_eq!(docs.len(), 1);
        let doc_id = docs[0].id.clone();

        let (mut state, _task) = State::new(vault.clone());
        state.loading = false;
        state.documents = docs;
        state.thumbnails.insert(doc_id.clone(), image::Handle::from_bytes(vec![0u8; 10]));

        let _ = state.update(Message::DeleteDocument(doc_id.clone()));
        assert_eq!(state.documents.len(), 0);
        assert!(state.thumbnails.is_empty());
    }

    #[test]
    fn test_delete_document_only_removes_target() {
        let (vault, _dir) = crate::vault::tests::make_test_vault_with_dir();

        let path_a = _dir.path().join("doc_a.txt");
        std::fs::write(&path_a, b"content a").unwrap();
        vault.import_file(&path_a).unwrap();

        let path_b = _dir.path().join("doc_b.txt");
        std::fs::write(&path_b, b"content b").unwrap();
        vault.import_file(&path_b).unwrap();

        let docs = vault.list_documents().unwrap();
        assert_eq!(docs.len(), 2);
        let id_a = docs.iter().find(|d| d.title == "doc_a.txt").unwrap().id.clone();

        let (mut state, _task) = State::new(vault.clone());
        state.loading = false;
        state.documents = docs;

        let _ = state.update(Message::DeleteDocument(id_a));
        assert_eq!(state.documents.len(), 1);
        assert_eq!(state.documents[0].title, "doc_b.txt");
    }
}
