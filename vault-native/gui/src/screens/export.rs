use iced::{
    widget::{button, column, container, row, text, text_input},
    Element, Task, Length,
};
use std::path::PathBuf;
use std::sync::Arc;

use super::Navigation;
use crate::vault::Vault;
use crate::widgets::common;

#[derive(Debug, Clone, PartialEq)]
pub enum PendingOp {
    Export,
    Import,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Message {
    ExportBackup,
    ImportBackup,
    Back,
    FileSelected(PathBuf),
    FileSelectionCancelled,
    BackupPasswordChanged(String),
    ConfirmExport,
    ConfirmImport,
    CancelPending,
    ExportDone(Result<(), String>),
    ImportDone(Result<(), String>),
}

pub struct State {
    pub vault: Arc<Vault>,
    pub backup_password: String,
    pub pending_path: Option<PathBuf>,
    pub pending_op: Option<PendingOp>,
    pub progress: Option<String>,
    pub error: Option<String>,
}

impl State {
    pub fn new(vault: Arc<Vault>) -> Self {
        Self {
            vault,
            backup_password: String::new(),
            pending_path: None,
            pending_op: None,
            progress: None,
            error: None,
        }
    }

    pub fn update(&mut self, message: Message) -> Task<crate::app::Message> {
        match message {
            Message::ExportBackup => {
                self.error = None;
                Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || {
                            rfd::FileDialog::new()
                                .set_title("Save Backup")
                                .add_filter("LibreCrate Backup", &["lcb"])
                                .save_file()
                        })
                        .await
                        .ok()
                        .flatten()
                    },
                    |maybe_path| match maybe_path {
                        Some(path) => crate::app::Message::Export(Message::FileSelected(path)),
                        None => crate::app::Message::Export(Message::FileSelectionCancelled),
                    },
                )
            }
            Message::ImportBackup => {
                self.error = None;
                Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || {
                            rfd::FileDialog::new()
                                .set_title("Open Backup")
                                .add_filter("LibreCrate Backup", &["lcb"])
                                .add_filter("All Files", &["*"])
                                .pick_file()
                        })
                        .await
                        .ok()
                        .flatten()
                    },
                    |maybe_path| match maybe_path {
                        Some(path) => crate::app::Message::Export(Message::FileSelected(path)),
                        None => crate::app::Message::Export(Message::FileSelectionCancelled),
                    },
                )
            }
            Message::FileSelected(path) => {
                let op = if self.pending_op.is_some() {
                    self.pending_op.clone().unwrap()
                } else {
                    PendingOp::Export
                };
                self.pending_path = Some(path);
                self.pending_op = Some(op);
                Task::none()
            }
            Message::FileSelectionCancelled => {
                self.pending_path = None;
                self.pending_op = None;
                Task::none()
            }
            Message::BackupPasswordChanged(pw) => {
                self.backup_password = pw;
                Task::none()
            }
            Message::ConfirmExport => {
                let vault = self.vault.clone();
                let password = self.backup_password.clone();
                let path = match self.pending_path.take() {
                    Some(p) => p,
                    None => {
                        self.error = Some("No file path selected".into());
                        return Task::none();
                    }
                };
                self.pending_op = None;
                self.progress = Some("Exporting...".into());
                self.error = None;
                Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || -> Result<(), String> {
                            let data = vault
                                .export_backup(&password)
                                .map_err(|e| e.to_string())?;
                            std::fs::write(&path, &data).map_err(|e| e.to_string())?;
                            Ok(())
                        })
                        .await
                        .map_err(|e| e.to_string())?
                    },
                    |result| crate::app::Message::Export(Message::ExportDone(result)),
                )
            }
            Message::ConfirmImport => {
                let vault = self.vault.clone();
                let password = self.backup_password.clone();
                let path = match self.pending_path.take() {
                    Some(p) => p,
                    None => {
                        self.error = Some("No file path selected".into());
                        return Task::none();
                    }
                };
                self.pending_op = None;
                self.progress = Some("Importing...".into());
                self.error = None;
                Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || -> Result<(), String> {
                            let data =
                                std::fs::read(&path).map_err(|e| e.to_string())?;
                            vault
                                .merge_backup(&data, &password, &vault.password.clone())
                                .map_err(|e| e.to_string())?;
                            Ok(())
                        })
                        .await
                        .map_err(|e| e.to_string())?
                    },
                    |result| crate::app::Message::Export(Message::ImportDone(result)),
                )
            }
            Message::CancelPending => {
                self.pending_path = None;
                self.pending_op = None;
                self.backup_password.clear();
                Task::none()
            }
            Message::Back => {
                Task::done(crate::app::Message::Navigate(Navigation::Library(self.vault.clone())))
            }
            Message::ExportDone(result) => {
                self.progress = None;
                self.backup_password.clear();
                match result {
                    Ok(()) => self.progress = Some("Export complete".into()),
                    Err(e) => self.error = Some(e),
                }
                Task::none()
            }
            Message::ImportDone(result) => {
                self.progress = None;
                self.backup_password.clear();
                match result {
                    Ok(()) => self.progress = Some("Import complete".into()),
                    Err(e) => self.error = Some(e),
                }
                Task::none()
            }
        }
    }

    pub fn view(&self) -> Element<'_, Message> {
        let body: Element<'_, Message> = if self.pending_path.is_some() {
            let op_label = match self.pending_op {
                Some(PendingOp::Export) => "Export",
                Some(PendingOp::Import) => "Import",
                None => "",
            };
            let path_display = self
                .pending_path
                .as_ref()
                .map(|p| p.display().to_string())
                .unwrap_or_default();

            column![
                text(format!("{op_label} backup")).size(14),
                text(path_display).size(12).color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                text("Backup password").size(13),
                text_input("Backup password", &self.backup_password)
                    .secure(true)
                    .on_input(Message::BackupPasswordChanged)
                    .on_submit(Message::ConfirmExport)
                    .width(300),
                row![
                    button("Cancel").on_press(Message::CancelPending),
                    button(op_label).on_press(match self.pending_op {
                        Some(PendingOp::Export) => Message::ConfirmExport,
                        Some(PendingOp::Import) => Message::ConfirmImport,
                        _ => Message::CancelPending,
                    }),
                ]
                .spacing(10),
            ]
            .spacing(10)
            .padding(20)
            .into()
        } else {
            column![
                text("Create or restore vault backups.").size(14),
                button("Export Backup").on_press(Message::ExportBackup),
                button("Import Backup").on_press(Message::ImportBackup),
            ]
            .spacing(10)
            .padding(20)
            .into()
        };

        let content = column![
            common::navbar("Export / Import", Some(Message::Back)),
            container(
                column![
                    body,
                    if let Some(ref progress) = self.progress {
                        text(progress)
                            .size(14)
                            .color(iced::Color::from_rgb(0.3, 0.7, 0.3))
                    } else {
                        text("")
                    },
                    if let Some(ref err) = self.error {
                        text(err)
                            .color(iced::Color::from_rgb(1.0, 0.3, 0.3))
                            .size(13)
                    } else {
                        text("")
                    },
                ]
                .spacing(8),
            )
            .style(common::card_style())
            .width(Length::Fill),
        ]
        .spacing(0);

        container(content).width(Length::Fill).height(Length::Fill).into()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::vault::tests::{make_test_vault, make_test_vault_with_dir};

    #[test]
    fn test_initial_state() {
        let vault = make_test_vault();
        let state = State::new(vault);
        assert!(state.progress.is_none());
        assert!(state.error.is_none());
        assert!(state.backup_password.is_empty());
        assert!(state.pending_path.is_none());
        assert!(state.pending_op.is_none());
    }

    #[test]
    fn test_backup_password_changed() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        let _ = state.update(Message::BackupPasswordChanged("secret".into()));
        assert_eq!(state.backup_password, "secret");
    }

    #[test]
    fn test_file_selected_sets_pending() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        let path = PathBuf::from("/tmp/test.lcb");
        let _ = state.update(Message::FileSelected(path.clone()));
        assert_eq!(state.pending_path, Some(path));
    }

    #[test]
    fn test_file_selection_cancelled_clears_pending() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.pending_path = Some(PathBuf::from("/tmp/test.lcb"));
        state.pending_op = Some(PendingOp::Export);
        let _ = state.update(Message::FileSelectionCancelled);
        assert!(state.pending_path.is_none());
        assert!(state.pending_op.is_none());
    }

    #[test]
    fn test_cancel_pending_clears_state() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.pending_path = Some(PathBuf::from("/tmp/test.lcb"));
        state.pending_op = Some(PendingOp::Import);
        state.backup_password = "secret".into();
        let _ = state.update(Message::CancelPending);
        assert!(state.pending_path.is_none());
        assert!(state.pending_op.is_none());
        assert!(state.backup_password.is_empty());
    }

    #[test]
    fn test_confirm_export_no_path_sets_error() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        let _ = state.update(Message::ConfirmExport);
        assert_eq!(state.error, Some("No file path selected".into()));
    }

    #[test]
    fn test_confirm_import_no_path_sets_error() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        let _ = state.update(Message::ConfirmImport);
        assert_eq!(state.error, Some("No file path selected".into()));
    }

    #[test]
    fn test_export_done_ok() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.progress = Some("Exporting...".into());
        let _ = state.update(Message::ExportDone(Ok(())));
        assert_eq!(state.progress, Some("Export complete".into()));
        assert!(state.error.is_none());
        assert!(state.backup_password.is_empty());
    }

    #[test]
    fn test_export_done_error() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.progress = Some("Exporting...".into());
        let _ = state.update(Message::ExportDone(Err("timeout".into())));
        assert!(state.progress.is_none());
        assert_eq!(state.error, Some("timeout".into()));
    }

    #[test]
    fn test_import_done_ok() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.progress = Some("Importing...".into());
        let _ = state.update(Message::ImportDone(Ok(())));
        assert_eq!(state.progress, Some("Import complete".into()));
        assert!(state.error.is_none());
    }

    #[test]
    fn test_import_done_error() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.progress = Some("Importing...".into());
        let _ = state.update(Message::ImportDone(Err("bad file".into())));
        assert!(state.progress.is_none());
        assert_eq!(state.error, Some("bad file".into()));
    }

    #[test]
    fn test_view_default_shows_buttons() {
        let vault = make_test_vault();
        let state = State::new(vault);
        let mut ui = iced_test::simulator(state.view());
        assert!(ui.find("Export / Import").is_ok());
        assert!(ui.find("Export Backup").is_ok());
        assert!(ui.find("Import Backup").is_ok());
        assert!(ui.find("Back").is_ok());
        assert!(ui.find("Create or restore vault backups.").is_ok());
    }

    #[test]
    fn test_view_pending_shows_password_input() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.pending_path = Some(PathBuf::from("/tmp/test.lcb"));
        state.pending_op = Some(PendingOp::Export);
        let mut ui = iced_test::simulator(state.view());
        assert!(ui.find("Backup password").is_ok());
        assert!(ui.find("Cancel").is_ok());
    }

    #[test]
    fn test_view_with_progress() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.progress = Some("Exporting...".into());
        let mut ui = iced_test::simulator(state.view());
        assert!(ui.find("Exporting...").is_ok());
    }

    #[test]
    fn test_view_with_error() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.error = Some("Export failed".into());
        let mut ui = iced_test::simulator(state.view());
        assert!(ui.find("Export failed").is_ok());
    }

    #[test]
    fn test_ui_back_produces_message() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        let mut ui = iced_test::simulator(state.view());
        ui.click("Back").unwrap();
        let msgs: Vec<Message> = ui.into_messages().collect();
        assert!(msgs.contains(&Message::Back));
    }

    // -----------------------------------------------------------------------
    // Real document roundtrip tests (file-system based, like the GUI does)
    // -----------------------------------------------------------------------

    #[test]
    fn test_export_import_roundtrip_with_document() {
        let (vault, dir) = make_test_vault_with_dir();

        let path = dir.path().join("hello.txt");
        std::fs::write(&path, b"Hello, world!").unwrap();
        vault.import_file(&path).unwrap();

        let docs_before = vault.list_documents().unwrap();
        assert_eq!(docs_before.len(), 1);
        assert_eq!(docs_before[0].title, "hello.txt");

        // Export to a temp file (same as GUI does)
        let backup_path = dir.path().join("backup.lcb");
        let data = vault.export_backup("backuppass").unwrap();
        std::fs::write(&backup_path, &data).unwrap();

        // Read it back (same as GUI does)
        let data_read = std::fs::read(&backup_path).unwrap();
        assert_eq!(data, data_read);

        // Merge into a fresh vault
        let (vault2, _dir2) = make_test_vault_with_dir();
        let stats = vault2
            .merge_backup(&data_read, "backuppass", "testpass")
            .unwrap();
        assert_eq!(stats.documents_added, 1);

        let docs_after = vault2.list_documents().unwrap();
        assert_eq!(docs_after.len(), 1);
        assert_eq!(docs_after[0].title, "hello.txt");
        assert_eq!(docs_after[0].mime_type, "text/plain");
        assert!(docs_after[0].file_size > 0);
    }

    #[test]
    fn test_export_import_roundtrip_multiple_documents() {
        let (vault, dir) = make_test_vault_with_dir();

        for i in 0..3 {
            let path = dir.path().join(format!("doc_{i}.txt"));
            std::fs::write(&path, format!("content {i}")).unwrap();
            vault.import_file(&path).unwrap();
        }
        assert_eq!(vault.list_documents().unwrap().len(), 3);

        // Export to file
        let backup_path = dir.path().join("backup.lcb");
        let data = vault.export_backup("backuppass").unwrap();
        std::fs::write(&backup_path, &data).unwrap();

        // Read back and merge
        let data_read = std::fs::read(&backup_path).unwrap();
        let (vault2, _dir2) = make_test_vault_with_dir();
        let stats = vault2
            .merge_backup(&data_read, "backuppass", "testpass")
            .unwrap();
        assert_eq!(stats.documents_added, 3);
        assert_eq!(vault2.list_documents().unwrap().len(), 3);
    }

    #[test]
    fn test_export_import_wrong_password_fails() {
        let (vault, dir) = make_test_vault_with_dir();

        let path = dir.path().join("secret.txt");
        std::fs::write(&path, b"secret data").unwrap();
        vault.import_file(&path).unwrap();

        // Export with correct password
        let backup_path = dir.path().join("backup.lcb");
        let data = vault.export_backup("correctpass").unwrap();
        std::fs::write(&backup_path, &data).unwrap();

        // Try to import with wrong password
        let data_read = std::fs::read(&backup_path).unwrap();
        let (vault2, _dir2) = make_test_vault_with_dir();
        let result = vault2.merge_backup(&data_read, "wrongpass", "testpass");
        assert!(result.is_err());
    }

    #[test]
    fn test_export_import_empty_vault_roundtrip() {
        let (vault, dir) = make_test_vault_with_dir();

        let backup_path = dir.path().join("backup.lcb");
        let data = vault.export_backup("backuppass").unwrap();
        std::fs::write(&backup_path, &data).unwrap();

        let data_read = std::fs::read(&backup_path).unwrap();
        let (vault2, _dir2) = make_test_vault_with_dir();
        let stats = vault2
            .merge_backup(&data_read, "backuppass", "testpass")
            .unwrap();
        assert_eq!(stats.documents_added, 0);
        assert_eq!(vault2.list_documents().unwrap().len(), 0);
    }

    #[test]
    fn test_export_import_pdf_and_image() {
        let (vault, dir) = make_test_vault_with_dir();

        let pdf_path = dir.path().join("report.pdf");
        std::fs::write(&pdf_path, b"%PDF-1.4 fake pdf content").unwrap();
        vault.import_file(&pdf_path).unwrap();

        let img_path = dir.path().join("photo.png");
        std::fs::write(&img_path, b"fake png data").unwrap();
        vault.import_file(&img_path).unwrap();

        let docs_before = vault.list_documents().unwrap();
        assert_eq!(docs_before.len(), 2);

        // Export + read back + merge
        let backup_path = dir.path().join("backup.lcb");
        let data = vault.export_backup("backuppass").unwrap();
        std::fs::write(&backup_path, &data).unwrap();

        let data_read = std::fs::read(&backup_path).unwrap();
        let (vault2, _dir2) = make_test_vault_with_dir();
        let stats = vault2
            .merge_backup(&data_read, "backuppass", "testpass")
            .unwrap();
        assert_eq!(stats.documents_added, 2);

        let docs_after = vault2.list_documents().unwrap();
        assert_eq!(docs_after.len(), 2);
        let mimes: Vec<&str> = docs_after.iter().map(|d| d.mime_type.as_str()).collect();
        assert!(mimes.contains(&"application/pdf"));
        assert!(mimes.contains(&"image/png"));
    }
}
