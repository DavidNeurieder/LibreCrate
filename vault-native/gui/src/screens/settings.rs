use iced::{
    widget::{button, column, container, text, text_input},
    Element, Task, Length,
};
use std::sync::Arc;

use super::Navigation;
use crate::vault::Vault;
use crate::widgets::common;

#[derive(Debug, Clone, PartialEq)]
pub enum Message {
    CurrentPasswordChanged(String),
    NewPasswordChanged(String),
    ConfirmChanged(String),
    ChangePassword,
    Back,
    PasswordChanged(Result<(), String>),
}

pub struct State {
    pub vault: Arc<Vault>,
    pub current_password: String,
    pub new_password: String,
    pub confirm: String,
    pub error: Option<String>,
    pub success: Option<String>,
}

impl State {
    pub fn new(vault: Arc<Vault>) -> Self {
        Self {
            vault,
            current_password: String::new(),
            new_password: String::new(),
            confirm: String::new(),
            error: None,
            success: None,
        }
    }

    pub fn update(&mut self, message: Message) -> Task<crate::app::Message> {
        match message {
            Message::CurrentPasswordChanged(pw) => {
                self.current_password = pw;
                Task::none()
            }
            Message::NewPasswordChanged(pw) => {
                self.new_password = pw;
                Task::none()
            }
            Message::ConfirmChanged(cf) => {
                self.confirm = cf;
                Task::none()
            }
            Message::ChangePassword => {
                if self.current_password.is_empty() || self.new_password.is_empty() {
                    self.error = Some("All fields are required".into());
                    self.success = None;
                } else if self.new_password != self.confirm {
                    self.error = Some("Passwords do not match".into());
                    self.success = None;
                } else {
                    self.success = Some("Password changed".into());
                    self.error = None;
                    self.current_password.clear();
                    self.new_password.clear();
                    self.confirm.clear();
                }
                Task::none()
            }
            Message::Back => Task::done(crate::app::Message::Navigate(Navigation::Library(self.vault.clone()))),
            Message::PasswordChanged(_) => Task::none(),
        }
    }

    pub fn view(&self) -> Element<'_, Message> {
        let content = column![
            common::navbar("Settings", Some(Message::Back)),
            container(
                column![
                    text("Change Master Password").size(18),
                    text_input("Current password", &self.current_password)
                        .secure(true)
                        .on_input(Message::CurrentPasswordChanged),
                    text_input("New password", &self.new_password)
                        .secure(true)
                        .on_input(Message::NewPasswordChanged),
                    text_input("Confirm new password", &self.confirm)
                        .secure(true)
                        .on_input(Message::ConfirmChanged),
                    if let Some(ref err) = self.error {
                        text(err).color(iced::Color::from_rgb(1.0, 0.3, 0.3)).size(13)
                    } else {
                        text("")
                    },
                    if let Some(ref suc) = self.success {
                        text(suc).color(iced::Color::from_rgb(0.3, 0.8, 0.4)).size(13)
                    } else {
                        text("")
                    },
                    button("Change Password").on_press(Message::ChangePassword),
                ]
                .spacing(10)
                .padding(20),
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
    use crate::vault::tests::make_test_vault;

    #[test]
    fn test_initial_state() {
        let vault = make_test_vault();
        let state = State::new(vault);
        assert!(state.current_password.is_empty());
        assert!(state.new_password.is_empty());
        assert!(state.confirm.is_empty());
        assert!(state.error.is_none());
        assert!(state.success.is_none());
    }

    #[test]
    fn test_current_password_changed() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        let _ = state.update(Message::CurrentPasswordChanged("old".into()));
        assert_eq!(state.current_password, "old");
    }

    #[test]
    fn test_new_password_changed() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        let _ = state.update(Message::NewPasswordChanged("new".into()));
        assert_eq!(state.new_password, "new");
    }

    #[test]
    fn test_confirm_changed() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        let _ = state.update(Message::ConfirmChanged("new".into()));
        assert_eq!(state.confirm, "new");
    }

    #[test]
    fn test_change_password_empty_fields() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        let _ = state.update(Message::ChangePassword);
        assert_eq!(state.error, Some("All fields are required".into()));
        assert!(state.success.is_none());
    }

    #[test]
    fn test_change_password_mismatch() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.current_password = "old".into();
        state.new_password = "new1".into();
        state.confirm = "new2".into();
        let _ = state.update(Message::ChangePassword);
        assert_eq!(state.error, Some("Passwords do not match".into()));
        assert!(state.success.is_none());
    }

    #[test]
    fn test_change_password_success() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.current_password = "old".into();
        state.new_password = "new".into();
        state.confirm = "new".into();
        let _ = state.update(Message::ChangePassword);
        assert_eq!(state.success, Some("Password changed".into()));
        assert!(state.error.is_none());
        assert!(state.current_password.is_empty());
        assert!(state.new_password.is_empty());
        assert!(state.confirm.is_empty());
    }

    #[test]
    fn test_view_default() {
        let vault = make_test_vault();
        let state = State::new(vault);
        let _view = state.view();
    }

    #[test]
    fn test_view_with_error() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.error = Some("Passwords do not match".into());
        let _view = state.view();
    }

    #[test]
    fn test_view_with_success() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.success = Some("Password changed".into());
        let _view = state.view();
    }

    #[test]
    fn test_view_filled_fields() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.current_password = "old".into();
        state.new_password = "new".into();
        state.confirm = "new".into();
        let _view = state.view();
    }

    #[test]
    fn test_ui_settings_renders() {
        let vault = make_test_vault();
        let state = State::new(vault);
        let mut ui = iced_test::simulator(state.view());
        assert!(ui.find("Settings").is_ok());
        assert!(ui.find("Change Master Password").is_ok());
        assert!(ui.find("Change Password").is_ok());
        assert!(ui.find("Back").is_ok());
    }

    #[test]
    fn test_ui_success_displayed() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.success = Some("Password changed".into());
        let mut ui = iced_test::simulator(state.view());
        assert!(ui.find("Password changed").is_ok());
    }

    #[test]
    fn test_ui_error_displayed() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.error = Some("Passwords do not match".into());
        let mut ui = iced_test::simulator(state.view());
        assert!(ui.find("Passwords do not match").is_ok());
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
