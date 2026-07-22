use iced::{
    widget::{button, column, container, text, text_input},
    Element, Task,
};

use super::Navigation;

#[derive(Debug, Clone)]
pub enum Message {
    CurrentPasswordChanged(String),
    NewPasswordChanged(String),
    ConfirmChanged(String),
    ChangePassword,
    Back,
    PasswordChanged(Result<(), String>),
}

pub struct State {
    pub current_password: String,
    pub new_password: String,
    pub confirm: String,
    pub error: Option<String>,
    pub success: Option<String>,
}

impl State {
    pub fn new() -> Self {
        Self {
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
            Message::Back => Task::done(crate::app::Message::Navigate(Navigation::Unlock)),
            Message::PasswordChanged(_) => Task::none(),
        }
    }

    pub fn view(&self) -> Element<'_, Message> {
        let content = column![
            text("Settings").size(24),
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
                text(err).color(iced::Color::from_rgb(1.0, 0.3, 0.3))
            } else {
                text("")
            },
            if let Some(ref suc) = self.success {
                text(suc).color(iced::Color::from_rgb(0.3, 0.8, 0.4))
            } else {
                text("")
            },
            button("Change Password").on_press(Message::ChangePassword),
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
        assert!(state.current_password.is_empty());
        assert!(state.new_password.is_empty());
        assert!(state.confirm.is_empty());
        assert!(state.error.is_none());
        assert!(state.success.is_none());
    }

    #[test]
    fn test_current_password_changed() {
        let mut state = State::new();
        let _ = state.update(Message::CurrentPasswordChanged("old".into()));
        assert_eq!(state.current_password, "old");
    }

    #[test]
    fn test_new_password_changed() {
        let mut state = State::new();
        let _ = state.update(Message::NewPasswordChanged("new".into()));
        assert_eq!(state.new_password, "new");
    }

    #[test]
    fn test_confirm_changed() {
        let mut state = State::new();
        let _ = state.update(Message::ConfirmChanged("new".into()));
        assert_eq!(state.confirm, "new");
    }

    #[test]
    fn test_change_password_empty_fields() {
        let mut state = State::new();
        let _ = state.update(Message::ChangePassword);
        assert_eq!(state.error, Some("All fields are required".into()));
        assert!(state.success.is_none());
    }

    #[test]
    fn test_change_password_mismatch() {
        let mut state = State::new();
        state.current_password = "old".into();
        state.new_password = "new1".into();
        state.confirm = "new2".into();
        let _ = state.update(Message::ChangePassword);
        assert_eq!(state.error, Some("Passwords do not match".into()));
        assert!(state.success.is_none());
    }

    #[test]
    fn test_change_password_success() {
        let mut state = State::new();
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
        let state = State::new();
        let _view = state.view();
    }

    #[test]
    fn test_view_with_error() {
        let mut state = State::new();
        state.error = Some("Passwords do not match".into());
        let _view = state.view();
    }

    #[test]
    fn test_view_with_success() {
        let mut state = State::new();
        state.success = Some("Password changed".into());
        let _view = state.view();
    }

    #[test]
    fn test_view_filled_fields() {
        let mut state = State::new();
        state.current_password = "old".into();
        state.new_password = "new".into();
        state.confirm = "new".into();
        let _view = state.view();
    }
}
