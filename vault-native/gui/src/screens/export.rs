use iced::{
    widget::{button, column, container, row, text},
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
    ToggleShowPassword,
    ConfirmExport,
    ConfirmImport,
    CancelPending,
    ExportDone(Result<(), String>),
    ImportDone(Result<(), String>),
}

pub struct State {
    pub vault: Arc<Vault>,
    pub backup_password: String,
    pub show_password: bool,
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
            show_password: false,
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
                self.pending_op = Some(PendingOp::Export);
                let date_str = chrono::Local::now().format("%Y%m%d").to_string();
                let default_name = format!("LibreCrate-{}.librecrate-backup", date_str);
                Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || {
                            rfd::FileDialog::new()
                                .set_title("Save Backup")
                                .add_filter("LibreCrate Backup", &["librecrate-backup"])
                                .set_file_name(&default_name)
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
                self.pending_op = Some(PendingOp::Import);
                Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || {
                            rfd::FileDialog::new()
                                .set_title("Open Backup")
                                .add_filter("LibreCrate Backup", &["librecrate-backup"])
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
                if self.pending_op.is_none() {
                    self.pending_op = Some(PendingOp::Export);
                }
                self.pending_path = Some(path);
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
            Message::ToggleShowPassword => {
                self.show_password = !self.show_password;
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
                                .restore_backup(&data, &password)
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
            let (title, description, action_label) = match self.pending_op {
                Some(PendingOp::Export) => (
                    "Encrypt Backup",
                    "Enter your vault password to encrypt this backup.",
                    "Export",
                ),
                Some(PendingOp::Import) => (
                    "Decrypt Backup",
                    "The passkey of the vault that created this backup is needed to decrypt it.",
                    "Import",
                ),
                None => ("", "", ""),
            };

            let path_display = self
                .pending_path
                .as_ref()
                .map(|p| p.display().to_string())
                .unwrap_or_default();

            column![
                text(title).size(16),
                text(description).size(12).color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                text(path_display).size(11).color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                text("Vault password").size(13),
                common::secure_field(
                    "Enter vault password",
                    &self.backup_password,
                    self.show_password,
                    Message::BackupPasswordChanged,
                    Message::ToggleShowPassword,
                    Some(match self.pending_op {
                        Some(PendingOp::Export) => Message::ConfirmExport,
                        Some(PendingOp::Import) => Message::ConfirmImport,
                        _ => Message::CancelPending,
                    }),
                ),
                row![
                    button("Cancel").on_press(Message::CancelPending),
                    button(action_label).on_press(match self.pending_op {
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
                text("Export your vault to a backup file, or restore from a backup.").size(14),
                button("Export Backup").on_press(Message::ExportBackup),
                button("Import Backup").on_press(Message::ImportBackup),
            ]
            .spacing(10)
            .padding(20)
            .into()
        };

        let content = column![
            common::navbar("Backup", None, Some(Message::Back)),
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
        assert!(!state.show_password);
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
    fn test_toggle_show_password() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        assert!(!state.show_password);
        let _ = state.update(Message::ToggleShowPassword);
        assert!(state.show_password);
        let _ = state.update(Message::ToggleShowPassword);
        assert!(!state.show_password);
    }

    #[test]
    fn test_file_selected_sets_pending() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        let path = PathBuf::from("/tmp/test.librecrate-backup");
        let _ = state.update(Message::FileSelected(path.clone()));
        assert_eq!(state.pending_path, Some(path));
    }

    #[test]
    fn test_file_selection_cancelled_clears_pending() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.pending_path = Some(PathBuf::from("/tmp/test.librecrate-backup"));
        state.pending_op = Some(PendingOp::Export);
        let _ = state.update(Message::FileSelectionCancelled);
        assert!(state.pending_path.is_none());
        assert!(state.pending_op.is_none());
    }

    #[test]
    fn test_cancel_pending_clears_state() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.pending_path = Some(PathBuf::from("/tmp/test.librecrate-backup"));
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
        assert!(ui.find("Backup").is_ok());
        assert!(ui.find("Export Backup").is_ok());
        assert!(ui.find("Import Backup").is_ok());
        assert!(ui.find("Back").is_ok());
        assert!(ui.find("Export your vault to a backup file, or restore from a backup.").is_ok());
    }

    #[test]
    fn test_view_pending_shows_password_input() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.pending_path = Some(PathBuf::from("/tmp/test.librecrate-backup"));
        state.pending_op = Some(PendingOp::Export);
        let mut ui = iced_test::simulator(state.view());
        assert!(ui.find("Vault password").is_ok());
        assert!(ui.find("Show").is_ok());
        assert!(ui.find("Cancel").is_ok());
    }

    #[test]
    fn test_view_pending_toggle_show_password() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.pending_path = Some(PathBuf::from("/tmp/test.librecrate-backup"));
        state.pending_op = Some(PendingOp::Export);
        let mut ui = iced_test::simulator(state.view());
        ui.click("Show").unwrap();
        let msgs: Vec<Message> = ui.into_messages().collect();
        assert!(msgs.contains(&Message::ToggleShowPassword));
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

    #[test]
    fn test_import_backup_sets_pending_op() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        // ImportBackup triggers a file dialog; simulate the flow by
        // manually setting pending_op as the handler does, then FileSelected
        state.pending_op = Some(PendingOp::Import);
        let _ = state.update(Message::FileSelected(PathBuf::from("/tmp/backup.librecrate-backup")));
        assert_eq!(state.pending_op, Some(PendingOp::Import));
    }

    #[test]
    fn test_export_backup_sets_pending_op() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.pending_op = Some(PendingOp::Export);
        let _ = state.update(Message::FileSelected(PathBuf::from("/tmp/backup.librecrate-backup")));
        assert_eq!(state.pending_op, Some(PendingOp::Export));
    }

    #[test]
    fn test_view_export_pending_shows_encrypt_title() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.pending_path = Some(PathBuf::from("/tmp/test.librecrate-backup"));
        state.pending_op = Some(PendingOp::Export);
        let mut ui = iced_test::simulator(state.view());
        assert!(ui.find("Encrypt Backup").is_ok());
    }

    #[test]
    fn test_view_import_pending_shows_decrypt_title() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.pending_path = Some(PathBuf::from("/tmp/test.librecrate-backup"));
        state.pending_op = Some(PendingOp::Import);
        let mut ui = iced_test::simulator(state.view());
        assert!(ui.find("Decrypt Backup").is_ok());
    }

    #[test]
    fn test_ui_enter_in_password_submits_export() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.pending_path = Some(PathBuf::from("/tmp/test.librecrate-backup"));
        state.pending_op = Some(PendingOp::Export);
        let mut ui = iced_test::simulator(state.view());
        ui.click("Enter vault password").unwrap();
        ui.tap_key(iced::keyboard::Key::Named(iced::keyboard::key::Named::Enter));
        let msgs: Vec<Message> = ui.into_messages().collect();
        assert!(msgs.contains(&Message::ConfirmExport));
    }

    #[test]
    fn test_ui_enter_in_password_submits_import() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.pending_path = Some(PathBuf::from("/tmp/test.librecrate-backup"));
        state.pending_op = Some(PendingOp::Import);
        let mut ui = iced_test::simulator(state.view());
        ui.click("Enter vault password").unwrap();
        ui.tap_key(iced::keyboard::Key::Named(iced::keyboard::key::Named::Enter));
        let msgs: Vec<Message> = ui.into_messages().collect();
        assert!(msgs.contains(&Message::ConfirmImport));
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
        let backup_path = dir.path().join("backup.librecrate-backup");
        let data = vault.export_backup("backuppass").unwrap();
        std::fs::write(&backup_path, &data).unwrap();

        // Read it back (same as GUI does)
        let data_read = std::fs::read(&backup_path).unwrap();
        assert_eq!(data, data_read);

        // Full restore into a fresh vault (Branch B, matching Android)
        let (vault2, dir2) = make_test_vault_with_dir();
        vault2.restore_backup(&data_read, "backuppass").unwrap();

        // Re-open with the original vault password (matching Android unlock flow)
        let vault2 = Vault::open(dir2.path(), "testpass").unwrap();

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
        let backup_path = dir.path().join("backup.librecrate-backup");
        let data = vault.export_backup("backuppass").unwrap();
        std::fs::write(&backup_path, &data).unwrap();

        // Read back and restore
        let data_read = std::fs::read(&backup_path).unwrap();
        let (vault2, dir2) = make_test_vault_with_dir();
        vault2.restore_backup(&data_read, "backuppass").unwrap();

        let vault2 = Vault::open(dir2.path(), "testpass").unwrap();
        assert_eq!(vault2.list_documents().unwrap().len(), 3);
    }

    #[test]
    fn test_export_import_wrong_password_fails() {
        let (vault, dir) = make_test_vault_with_dir();

        let path = dir.path().join("secret.txt");
        std::fs::write(&path, b"secret data").unwrap();
        vault.import_file(&path).unwrap();

        // Export with correct password
        let backup_path = dir.path().join("backup.librecrate-backup");
        let data = vault.export_backup("correctpass").unwrap();
        std::fs::write(&backup_path, &data).unwrap();

        // Try to import with wrong password
        let data_read = std::fs::read(&backup_path).unwrap();
        let (vault2, _dir2) = make_test_vault_with_dir();
        let result = vault2.restore_backup(&data_read, "wrongpass");
        assert!(result.is_err());
    }

    #[test]
    fn test_export_import_empty_vault_roundtrip() {
        let (vault, dir) = make_test_vault_with_dir();

        let backup_path = dir.path().join("backup.librecrate-backup");
        let data = vault.export_backup("backuppass").unwrap();
        std::fs::write(&backup_path, &data).unwrap();

        let data_read = std::fs::read(&backup_path).unwrap();
        let (vault2, dir2) = make_test_vault_with_dir();
        vault2.restore_backup(&data_read, "backuppass").unwrap();

        let vault2 = Vault::open(dir2.path(), "testpass").unwrap();
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

        // Export + read back + restore
        let backup_path = dir.path().join("backup.librecrate-backup");
        let data = vault.export_backup("backuppass").unwrap();
        std::fs::write(&backup_path, &data).unwrap();

        let data_read = std::fs::read(&backup_path).unwrap();
        let (vault2, dir2) = make_test_vault_with_dir();
        vault2.restore_backup(&data_read, "backuppass").unwrap();

        let vault2 = Vault::open(dir2.path(), "testpass").unwrap();
        let docs_after = vault2.list_documents().unwrap();
        assert_eq!(docs_after.len(), 2);
        let mimes: Vec<&str> = docs_after.iter().map(|d| d.mime_type.as_str()).collect();
        assert!(mimes.contains(&"application/pdf"));
        assert!(mimes.contains(&"image/png"));
    }
}
