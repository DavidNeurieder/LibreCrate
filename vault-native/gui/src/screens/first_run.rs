use iced::{
    widget::{button, column, container, row, text, text_input},
    Element, Task,
};
use std::sync::Arc;

use super::Navigation;
use crate::config::Config;
use crate::vault::Vault;

#[derive(Debug, Clone, PartialEq)]
pub enum Step {
    Welcome,
    ChooseDir,
    SetPassword,
    Creating,
}

#[derive(Debug, Clone)]
pub enum Message {
    VaultDirChanged(String),
    PasswordChanged(String),
    ConfirmChanged(String),
    Create,
    StepBack,
    CreationFailed(String),
}

pub struct State {
    pub step: Step,
    pub vault_dir: String,
    pub password: String,
    pub confirm: String,
    pub error: Option<String>,
}

impl State {
    pub fn new() -> Self {
        let default_dir = Config::vault_data_dir().join("vault").display().to_string();
        Self {
            step: Step::Welcome,
            vault_dir: default_dir,
            password: String::new(),
            confirm: String::new(),
            error: None,
        }
    }

    pub fn update(&mut self, message: Message) -> Task<crate::app::Message> {
        match message {
            Message::VaultDirChanged(dir) => {
                self.vault_dir = dir;
                self.error = None;
                Task::none()
            }
            Message::PasswordChanged(pw) => {
                self.password = pw;
                self.error = None;
                Task::none()
            }
            Message::ConfirmChanged(cf) => {
                self.confirm = cf;
                self.error = None;
                Task::none()
            }
            Message::Create => match self.step {
                Step::Welcome => {
                    self.step = Step::ChooseDir;
                    Task::none()
                }
                Step::ChooseDir => {
                    self.step = Step::SetPassword;
                    Task::none()
                }
                Step::SetPassword => {
                    if self.password.is_empty() {
                        self.error = Some("Password is required".into());
                        return Task::none();
                    }
                    if self.password != self.confirm {
                        self.error = Some("Passwords do not match".into());
                        return Task::none();
                    }
                    self.step = Step::Creating;
                    let dir = self.vault_dir.clone();
                    let password = self.password.clone();
                    Task::perform(
                        async move {
                            tokio::task::spawn_blocking(move || -> Result<Vault, String> {
                                let path = std::path::Path::new(&dir);
                                std::fs::create_dir_all(path).map_err(|e| e.to_string())?;
                                let vault = Vault::create(path, &password)
                                    .map_err(|e| e.to_string())?;
                                let mut config = Config::load();
                                config.vault_dir = Some(path.to_path_buf());
                                config.save().ok();
                                Ok(vault)
                            })
                            .await
                            .map_err(|e| e.to_string())?
                        },
                        |result| match result {
                            Ok(vault) => crate::app::Message::Navigate(
                                Navigation::Library(Arc::new(vault)),
                            ),
                            Err(e) => {
                                crate::app::Message::FirstRun(Message::CreationFailed(e))
                            }
                        },
                    )
                }
                Step::Creating => Task::none(),
            },
            Message::StepBack => {
                self.step = match self.step {
                    Step::SetPassword => Step::ChooseDir,
                    Step::ChooseDir => Step::Welcome,
                    _ => Step::Welcome,
                };
                Task::none()
            }
            Message::CreationFailed(e) => {
                self.step = Step::SetPassword;
                self.error = Some(e);
                Task::none()
            }
        }
    }

    pub fn view(&self) -> Element<'_, Message> {
        let content: Element<'_, Message> = match self.step {
            Step::Welcome => column![
                text("Welcome to LibreCrate").size(24),
                text("Create a new encrypted document vault to get started."),
                button("Get Started").on_press(Message::Create),
            ]
            .spacing(10)
            .into(),
            Step::ChooseDir => column![
                text("Choose Vault Location").size(20),
                text_input("Vault directory", &self.vault_dir)
                    .on_input(Message::VaultDirChanged),
                row![
                    button("Back").on_press(Message::StepBack),
                    button("Next").on_press(Message::Create),
                ]
                .spacing(10),
            ]
            .spacing(10)
            .into(),
            Step::SetPassword => column![
                text("Set Master Password").size(20),
                text_input("Password", &self.password)
                    .secure(true)
                    .on_input(Message::PasswordChanged),
                text_input("Confirm password", &self.confirm)
                    .secure(true)
                    .on_input(Message::ConfirmChanged),
                if let Some(ref err) = self.error {
                    text(err).color(iced::Color::from_rgb(1.0, 0.3, 0.3))
                } else {
                    text("")
                },
                row![
                    button("Back").on_press(Message::StepBack),
                    button("Create Vault").on_press(Message::Create),
                ]
                .spacing(10),
            ]
            .spacing(10)
            .into(),
            Step::Creating => column![
                text("Creating vault...").size(20),
                text("This may take a moment."),
            ]
            .spacing(10)
            .into(),
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
    fn test_initial_state() {
        let state = State::new();
        assert_eq!(state.step, Step::Welcome);
        assert!(state.password.is_empty());
        assert!(state.confirm.is_empty());
        assert!(state.error.is_none());
    }

    #[test]
    fn test_vault_dir_changed() {
        let mut state = State::new();
        let _ = state.update(Message::VaultDirChanged("/tmp/test".into()));
        assert_eq!(state.vault_dir, "/tmp/test");
        assert!(state.error.is_none());
    }

    #[test]
    fn test_password_changed() {
        let mut state = State::new();
        let _ = state.update(Message::PasswordChanged("hunter2".into()));
        assert_eq!(state.password, "hunter2");
    }

    #[test]
    fn test_confirm_changed() {
        let mut state = State::new();
        let _ = state.update(Message::ConfirmChanged("hunter2".into()));
        assert_eq!(state.confirm, "hunter2");
    }

    #[test]
    fn test_create_advances_from_welcome() {
        let mut state = State::new();
        let _ = state.update(Message::Create);
        assert_eq!(state.step, Step::ChooseDir);
    }

    #[test]
    fn test_create_advances_from_choose_dir() {
        let mut state = State::new();
        state.step = Step::ChooseDir;
        let _ = state.update(Message::Create);
        assert_eq!(state.step, Step::SetPassword);
    }

    #[test]
    fn test_create_empty_password_fails_at_set_password() {
        let mut state = State::new();
        state.step = Step::SetPassword;
        let _ = state.update(Message::Create);
        assert_eq!(state.error, Some("Password is required".into()));
        assert_eq!(state.step, Step::SetPassword);
    }

    #[test]
    fn test_create_mismatched_passwords_fails_at_set_password() {
        let mut state = State::new();
        state.step = Step::SetPassword;
        state.password = "abc".into();
        state.confirm = "def".into();
        let _ = state.update(Message::Create);
        assert_eq!(state.error, Some("Passwords do not match".into()));
        assert_eq!(state.step, Step::SetPassword);
    }

    #[test]
    fn test_step_back_from_set_password() {
        let mut state = State::new();
        state.step = Step::SetPassword;
        let _ = state.update(Message::StepBack);
        assert_eq!(state.step, Step::ChooseDir);
    }

    #[test]
    fn test_step_back_from_choose_dir() {
        let mut state = State::new();
        state.step = Step::ChooseDir;
        let _ = state.update(Message::StepBack);
        assert_eq!(state.step, Step::Welcome);
    }

    #[test]
    fn test_creation_failed_goes_back() {
        let mut state = State::new();
        state.step = Step::Creating;
        let _ = state.update(Message::CreationFailed("disk full".into()));
        assert_eq!(state.step, Step::SetPassword);
        assert_eq!(state.error, Some("disk full".into()));
    }

    #[test]
    fn test_view_welcome_step() {
        let state = State::new();
        let _view = state.view();
    }

    #[test]
    fn test_view_choose_dir_step() {
        let mut state = State::new();
        state.step = Step::ChooseDir;
        state.vault_dir = "/tmp/my-vault".into();
        let _view = state.view();
    }

    #[test]
    fn test_view_set_password_step() {
        let mut state = State::new();
        state.step = Step::SetPassword;
        state.password = "secret".into();
        state.confirm = "secret".into();
        let _view = state.view();
    }

    #[test]
    fn test_view_set_password_with_error() {
        let mut state = State::new();
        state.step = Step::SetPassword;
        state.error = Some("Passwords do not match".into());
        let _view = state.view();
    }

    #[test]
    fn test_view_creating_step() {
        let mut state = State::new();
        state.step = Step::Creating;
        let _view = state.view();
    }
}
