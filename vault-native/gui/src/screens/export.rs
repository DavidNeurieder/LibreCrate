use iced::{
    widget::{button, column, container, text},
    Element, Task,
};

use super::Navigation;

#[derive(Debug, Clone)]
pub enum Message {
    ExportBackup,
    ImportBackup,
    Back,
    ExportDone(Result<(), String>),
    ImportDone(Result<(), String>),
}

pub struct State {
    pub progress: Option<String>,
    pub error: Option<String>,
}

impl State {
    pub fn new() -> Self {
        Self {
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
            Message::Back => Task::done(crate::app::Message::Navigate(Navigation::Unlock)),
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

    #[test]
    fn test_initial_state() {
        let state = State::new();
        assert!(state.progress.is_none());
        assert!(state.error.is_none());
    }

    #[test]
    fn test_export_backup_sets_progress() {
        let mut state = State::new();
        let _ = state.update(Message::ExportBackup);
        assert_eq!(state.progress, Some("Exporting...".into()));
    }

    #[test]
    fn test_import_backup_sets_progress() {
        let mut state = State::new();
        let _ = state.update(Message::ImportBackup);
        assert_eq!(state.progress, Some("Importing...".into()));
    }

    #[test]
    fn test_export_done_ok() {
        let mut state = State::new();
        state.progress = Some("Exporting...".into());
        let _ = state.update(Message::ExportDone(Ok(())));
        assert_eq!(state.progress, Some("Export complete".into()));
        assert!(state.error.is_none());
    }

    #[test]
    fn test_export_done_error() {
        let mut state = State::new();
        state.progress = Some("Exporting...".into());
        let _ = state.update(Message::ExportDone(Err("timeout".into())));
        assert!(state.progress.is_none());
        assert_eq!(state.error, Some("timeout".into()));
    }

    #[test]
    fn test_view_default() {
        let state = State::new();
        let _view = state.view();
    }

    #[test]
    fn test_view_with_progress() {
        let mut state = State::new();
        state.progress = Some("Exporting...".into());
        let _view = state.view();
    }

    #[test]
    fn test_view_with_error() {
        let mut state = State::new();
        state.error = Some("Export failed".into());
        let _view = state.view();
    }
}
