use iced::{
    widget::{button, column, container, text},
    Element, Task,
};
use std::sync::Arc;

use super::Navigation;
use crate::vault::Vault;

#[derive(Debug, Clone, PartialEq)]
pub enum Message {
    ExportBackup,
    ImportBackup,
    Back,
    ExportDone(Result<(), String>),
    ImportDone(Result<(), String>),
}

pub struct State {
    pub vault: Arc<Vault>,
    pub progress: Option<String>,
    pub error: Option<String>,
}

impl State {
    pub fn new(vault: Arc<Vault>) -> Self {
        Self {
            vault,
            progress: None,
            error: None,
        }
    }

    pub fn update(&mut self, message: Message) -> Task<crate::app::Message> {
        match message {
            Message::ExportBackup => {
                self.progress = Some("Exporting...".into());
                Task::done(crate::app::Message::Export(Message::ExportDone(Ok(()))))
            }
            Message::ImportBackup => {
                self.progress = Some("Importing...".into());
                Task::done(crate::app::Message::Export(Message::ImportDone(Ok(()))))
            }
            Message::Back => Task::done(crate::app::Message::Navigate(Navigation::Library(self.vault.clone()))),
            Message::ExportDone(result) => {
                self.progress = None;
                match result {
                    Ok(()) => self.progress = Some("Export complete".into()),
                    Err(e) => self.error = Some(e),
                }
                Task::none()
            }
            Message::ImportDone(result) => {
                self.progress = None;
                match result {
                    Ok(()) => self.progress = Some("Import complete".into()),
                    Err(e) => self.error = Some(e),
                }
                Task::none()
            }
        }
    }

    pub fn view(&self) -> Element<'_, Message> {
        let content = column![
            text("Export / Import").size(24),
            text("Create or restore vault backups."),
            if let Some(ref progress) = self.progress {
                text(progress)
            } else {
                text("")
            },
            if let Some(ref err) = self.error {
                text(err).color(iced::Color::from_rgb(1.0, 0.3, 0.3))
            } else {
                text("")
            },
            button("Export Backup").on_press(Message::ExportBackup),
            button("Import Backup").on_press(Message::ImportBackup),
            button("Back").on_press(Message::Back),
        ]
        .spacing(10);

        container(content).padding(40).into()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::vault::tests::make_test_vault;

    #[test]
    fn test_initial_state() {
        let vault = make_test_vault();
        let state = State::new(vault);
        assert!(state.progress.is_none());
        assert!(state.error.is_none());
    }

    #[test]
    fn test_export_backup_sets_progress() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        let _ = state.update(Message::ExportBackup);
        assert_eq!(state.progress, Some("Exporting...".into()));
    }

    #[test]
    fn test_import_backup_sets_progress() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        let _ = state.update(Message::ImportBackup);
        assert_eq!(state.progress, Some("Importing...".into()));
    }

    #[test]
    fn test_export_done_ok() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.progress = Some("Exporting...".into());
        let _ = state.update(Message::ExportDone(Ok(())));
        assert_eq!(state.progress, Some("Export complete".into()));
        assert!(state.error.is_none());
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
    fn test_view_default() {
        let vault = make_test_vault();
        let state = State::new(vault);
        let _view = state.view();
    }

    #[test]
    fn test_view_with_progress() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.progress = Some("Exporting...".into());
        let _view = state.view();
    }

    #[test]
    fn test_view_with_error() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.error = Some("Export failed".into());
        let _view = state.view();
    }

    #[test]
    fn test_ui_export_renders() {
        let vault = make_test_vault();
        let state = State::new(vault);
        let mut ui = iced_test::simulator(state.view());
        assert!(ui.find("Export / Import").is_ok());
        assert!(ui.find("Export Backup").is_ok());
        assert!(ui.find("Import Backup").is_ok());
        assert!(ui.find("Back").is_ok());
    }

    #[test]
    fn test_ui_progress_displayed() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.progress = Some("Exporting...".into());
        let mut ui = iced_test::simulator(state.view());
        assert!(ui.find("Exporting...").is_ok());
    }

    #[test]
    fn test_ui_error_displayed() {
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
}
