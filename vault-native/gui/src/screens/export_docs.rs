use iced::{
    widget::{button, checkbox, column, container, row, scrollable, text, text_input},
    Element, Length, Task,
};
use std::io::Write;
use std::sync::Arc;

use crate::vault::Vault;
use crate::widgets::common;
use vault_native::db::queries::DocumentRow;

#[derive(Debug, Clone)]
pub enum Message {
    Back,
    SearchChanged(String),
    ToggleSelectAll,
    ToggleDocument(String),
    ExportSelected,
    ExportComplete(Result<String, String>),
    DocumentsLoaded(Result<Vec<DocumentRow>, String>),
}

pub struct State {
    pub vault: Arc<Vault>,
    pub documents: Vec<DocumentRow>,
    pub selected: Vec<String>,
    pub search_query: String,
    pub loading: bool,
    pub exporting: bool,
    pub error: Option<String>,
    pub success: Option<String>,
}

impl State {
    pub fn new(vault: Arc<Vault>) -> (Self, Task<crate::app::Message>) {
        let state = Self {
            vault,
            documents: Vec::new(),
            selected: Vec::new(),
            search_query: String::new(),
            loading: true,
            exporting: false,
            error: None,
            success: None,
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
            |result| crate::app::Message::ExportDocs(Message::DocumentsLoaded(result)),
        )
    }

    fn filtered_documents(&self) -> Vec<&DocumentRow> {
        if self.search_query.is_empty() {
            self.documents.iter().collect()
        } else {
            let q = self.search_query.to_lowercase();
            self.documents
                .iter()
                .filter(|d| d.title.to_lowercase().contains(&q) || d.file_name.to_lowercase().contains(&q))
                .collect()
        }
    }

    pub fn update(&mut self, message: Message) -> Task<crate::app::Message> {
        match message {
            Message::Back => {
                Task::done(crate::app::Message::Navigate(crate::screens::Navigation::Library(self.vault.clone())))
            }
            Message::SearchChanged(q) => {
                self.search_query = q;
                Task::none()
            }
            Message::ToggleSelectAll => {
                let filtered: Vec<String> = self.filtered_documents().iter().map(|d| d.id.clone()).collect();
                if self.selected.len() == filtered.len() {
                    self.selected.clear();
                } else {
                    self.selected = filtered;
                }
                Task::none()
            }
            Message::ToggleDocument(id) => {
                if let Some(pos) = self.selected.iter().position(|s| *s == id) {
                    self.selected.remove(pos);
                } else {
                    self.selected.push(id);
                }
                Task::none()
            }
            Message::ExportSelected => {
                if self.selected.is_empty() {
                    self.error = Some("No documents selected".into());
                    return Task::none();
                }
                self.exporting = true;
                self.error = None;
                self.success = None;

                let vault = self.vault.clone();
                let ids = self.selected.clone();
                Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || -> Result<String, String> {
                            let docs = vault.list_documents().map_err(|e| e.to_string())?;
                            let selected: Vec<&DocumentRow> = docs.iter().filter(|d| ids.contains(&d.id)).collect();

                            let save_path = rfd::FileDialog::new()
                                .set_title("Export Documents")
                                .add_filter("ZIP Archive", &["zip"])
                                .save_file()
                                .ok_or_else(|| "No file selected".to_string())?;

                            let file = std::fs::File::create(&save_path).map_err(|e| e.to_string())?;
                            let mut zip = zip::ZipWriter::new(file);
                            let options = zip::write::SimpleFileOptions::default()
                                .compression_method(zip::CompressionMethod::Deflated);

                            let mut exported = 0usize;
                            for doc in &selected {
                                match vault.db.export_document_file(
                                    vault.base_dir.to_string_lossy().to_string(),
                                    doc.id.clone(),
                                ) {
                                    Ok(Some(data)) => {
                                        let name = &doc.file_name;
                                        let final_name = if selected.iter().filter(|d| d.file_name == *name).count() > 1 {
                                            format!("{}_{}", doc.id, name)
                                        } else {
                                            name.clone()
                                        };
                                        zip.start_file(&final_name, options).map_err(|e| e.to_string())?;
                                        zip.write_all(&data).map_err(|e| e.to_string())?;
                                        exported += 1;
                                    }
                                    _ => {}
                                }
                            }

                            zip.finish().map_err(|e| e.to_string())?;
                            Ok(format!("Exported {} documents to {}", exported, save_path.display()))
                        })
                        .await
                        .map_err(|e| e.to_string())?
                    },
                    |result| crate::app::Message::ExportDocs(Message::ExportComplete(result)),
                )
            }
            Message::ExportComplete(Ok(msg)) => {
                self.exporting = false;
                self.success = Some(msg);
                Task::none()
            }
            Message::ExportComplete(Err(e)) => {
                self.exporting = false;
                self.error = Some(e);
                Task::none()
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
        }
    }

    pub fn view(&self) -> Element<'_, Message> {
        let navbar = crate::widgets::common::navbar("Export Documents", Some(Message::Back));

        let body: Element<'_, Message> = if self.loading {
            container(text("Loading documents...").size(16))
                .center_x(Length::Fill)
                .center_y(Length::Fill)
                .width(Length::Fill)
                .height(Length::Fill)
                .into()
        } else if self.exporting {
            container(text("Exporting...").size(16))
                .center_x(Length::Fill)
                .center_y(Length::Fill)
                .width(Length::Fill)
                .height(Length::Fill)
                .into()
        } else {
            let filtered: Vec<(String, String, String)> = self.documents
                .iter()
                .filter(|d| {
                    if self.search_query.is_empty() { return true; }
                    let q = self.search_query.to_lowercase();
                    d.title.to_lowercase().contains(&q) || d.file_name.to_lowercase().contains(&q)
                })
                .map(|d| (d.id.clone(), d.title.clone(), d.file_name.clone()))
                .collect();
            let all_filtered_ids: Vec<String> = filtered.iter().map(|(id, _, _)| id.clone()).collect();
            let all_selected = !all_filtered_ids.is_empty() && all_filtered_ids.iter().all(|id| self.selected.contains(id));

            let search = text_input("Search documents...", &self.search_query)
                .on_input(Message::SearchChanged)
                .width(Length::Fill);

            let select_all = checkbox(all_selected)
                .label("Select All")
                .on_toggle(|_| Message::ToggleSelectAll);

            let list = filtered.iter().fold(
                column![].spacing(4),
                |col, (id, title, file_name)| {
                    let is_checked = self.selected.contains(id);
                    let item = row![
                        checkbox(is_checked)
                            .on_toggle({
                                let id = id.clone();
                                move |_| Message::ToggleDocument(id.clone())
                            }),
                        column![
                            text(title.clone()).size(13),
                            text(file_name.clone()).size(11).color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                        ]
                        .spacing(2),
                    ]
                    .spacing(8)
                    .align_y(iced::Alignment::Center);
                    col.push(
                        container(item)
                            .padding(iced::Padding::new(8.0).left(12.0).right(12.0))
                            .width(Length::Fill)
                            .style(common::card_style()),
                    )
                },
            );

            let selected_count = self.selected.len();
            let export_button = button(
                text(format!("Export {} document{} as ZIP", selected_count, if selected_count == 1 { "" } else { "s" })).size(13),
            )
            .on_press(Message::ExportSelected)
            .padding(iced::Padding::new(8.0).left(16.0).right(16.0));

            let mut status_row = row![export_button].spacing(10);

            if let Some(ref err) = self.error {
                status_row = status_row.push(text(err).size(12).color(iced::Color::from_rgb(1.0, 0.4, 0.4)));
            }
            if let Some(ref msg) = self.success {
                status_row = status_row.push(text(msg).size(12).color(iced::Color::from_rgb(0.4, 1.0, 0.4)));
            }

            column![
                navbar,
                search,
                select_all,
                scrollable(list).height(Length::Fill),
                status_row,
            ]
            .spacing(8)
            .padding(iced::Padding::new(12.0).left(16.0).right(16.0))
            .width(Length::Fill)
            .height(Length::Fill)
            .into()
        };

        container(body).width(Length::Fill).height(Length::Fill).into()
    }
}
