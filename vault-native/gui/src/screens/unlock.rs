use iced::{
    widget::{button, column, container, text, text_input},
    Element, Task,
};
use std::sync::Arc;

use super::Navigation;
use crate::config::Config;
use crate::vault::Vault;

#[derive(Debug, Clone, PartialEq)]
pub enum Message {
    PasswordChanged(String),
    Submit,
    VaultOpened(Result<Vault, String>),
    CreateNewVault,
}

pub struct State {
    pub password: String,
    pub error: Option<String>,
    pub loading: bool,
    pub vault_exists: bool,
}

impl State {
    pub fn new() -> Self {
        let config = Config::load();
        let vault_exists = config
            .vault_dir
            .as_ref()
            .map(|d| d.join("encryption").join("master_key").exists())
            .unwrap_or(false);

        Self {
            password: String::new(),
            error: None,
            loading: false,
            vault_exists,
        }
    }

    pub fn update(&mut self, message: Message) -> Task<crate::app::Message> {
        match message {
            Message::PasswordChanged(pw) => {
                self.password = pw;
                self.error = None;
                Task::none()
            }
            Message::Submit => {
                if self.password.is_empty() {
                    self.error = Some("Password is required".into());
                    return Task::none();
                }
                self.loading = true;
                self.error = None;
                let password = self.password.clone();
                let dir = Config::load()
                    .vault_dir
                    .unwrap_or_else(Config::vault_data_dir);
                Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || -> Result<Vault, String> {
                            Vault::open(&dir, &password).map_err(|e| e.to_string())
                        })
                        .await
                        .map_err(|e| e.to_string())?
                    },
                    |result| match result {
                        Ok(vault) => {
                            crate::app::Message::Navigate(Navigation::Library(Arc::new(vault)))
                        }
                        Err(e) => {
                            crate::app::Message::Unlock(Message::VaultOpened(Err(e)))
                        }
                    },
                )
            }
            Message::VaultOpened(Ok(vault)) => {
                self.loading = false;
                Task::done(crate::app::Message::Navigate(Navigation::Library(
                    Arc::new(vault),
                )))
            }
            Message::VaultOpened(Err(e)) => {
                self.loading = false;
                self.error = Some(e);
                Task::none()
            }
            Message::CreateNewVault => {
                Task::done(crate::app::Message::Navigate(Navigation::FirstRun))
            }
        }
    }

    pub fn view(&self) -> Element<'_, Message> {
        let content: Element<'_, Message> = if self.vault_exists {
            column![
                text("LibreCrate").size(28),
                text("Enter your master password to unlock."),
                text_input("Master password", &self.password)
                    .secure(true)
                    .on_input(Message::PasswordChanged)
                    .on_submit(Message::Submit),
                if let Some(ref err) = self.error {
                    text(err).color(iced::Color::from_rgb(1.0, 0.3, 0.3))
                } else {
                    text("")
                },
                button("Unlock").on_press(Message::Submit),
            ]
            .spacing(10)
            .into()
        } else {
            column![
                text("LibreCrate").size(28),
                text("No vault found. Create one to get started."),
                button("Create New Vault").on_press(Message::CreateNewVault),
            ]
            .spacing(10)
            .into()
        };

        container(content)
            .center_x(400)
            .center_y(400)
            .padding(40)
            .into()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_initial_state_no_vault() {
        let state = State::new();
        assert!(state.password.is_empty());
        assert!(state.error.is_none());
        assert!(!state.loading);
    }

    #[test]
    fn test_password_changed() {
        let mut state = State::new();
        let _ = state.update(Message::PasswordChanged("p4ss".into()));
        assert_eq!(state.password, "p4ss");
        assert!(state.error.is_none());
    }

    #[test]
    fn test_submit_empty_password() {
        let mut state = State::new();
        let _ = state.update(Message::Submit);
        assert_eq!(state.error, Some("Password is required".into()));
    }

    #[test]
    fn test_vault_opened_error() {
        let mut state = State::new();
        state.loading = true;
        let _ = state.update(Message::VaultOpened(Err("bad key".into())));
        assert!(!state.loading);
        assert_eq!(state.error, Some("bad key".into()));
    }

    #[test]
    fn test_view_no_vault() {
        let state = State::new();
        let _view = state.view();
    }

    #[test]
    fn test_view_with_password_input() {
        let mut state = State::new();
        state.password = "mypassword".into();
        let _view = state.view();
    }

    #[test]
    fn test_view_with_error() {
        let mut state = State::new();
        state.error = Some("Invalid password".into());
        let _view = state.view();
    }

    #[test]
    fn test_ui_no_vault_found() {
        let mut state = State::new();
        state.vault_exists = false;
        let mut ui = iced_test::simulator(state.view());
        assert!(ui.find("LibreCrate").is_ok());
        assert!(ui.find("No vault found. Create one to get started.").is_ok());
        assert!(ui.find("Create New Vault").is_ok());
    }

    #[test]
    fn test_ui_create_new_vault_produces_message() {
        let mut state = State::new();
        state.vault_exists = false;
        let mut ui = iced_test::simulator(state.view());
        ui.click("Create New Vault").unwrap();
        let msgs: Vec<Message> = ui.into_messages().collect();
        assert!(msgs.contains(&Message::CreateNewVault));
    }

    #[test]
    fn test_ui_error_displayed() {
        let mut state = State::new();
        state.error = Some("Invalid password".into());
        let mut ui = iced_test::simulator(state.view());
        assert!(ui.find("Invalid password").is_ok());
    }
}
