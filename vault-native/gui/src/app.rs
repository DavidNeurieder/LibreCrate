use iced::{Element, Task, Theme};
use std::sync::Arc;

use crate::config::Config;
use crate::keychain::SecureStore;
use crate::opener::{DocumentOpener, SystemOpener};
use crate::screens::{self, Navigation};
use crate::vault::Vault;

pub enum Screen {
    FirstRun(screens::first_run::State),
    Unlock(screens::unlock::State),
    Library(screens::library::State),
    Settings(screens::settings::State),
    Export(screens::export::State),
    Collections(screens::collections::State),
}

#[derive(Debug)]
pub enum Message {
    FirstRun(screens::first_run::Message),
    Unlock(screens::unlock::Message),
    Library(screens::library::Message),
    Settings(screens::settings::Message),
    Export(screens::export::Message),
    Collections(screens::collections::Message),
    Navigate(Navigation),
}

pub struct App {
    pub screen: Screen,
    pub _keychain: SecureStore,
    pub config: Config,
    pub _opener: SystemOpener,
}

pub fn boot() -> (App, Task<Message>) {
    let config = Config::load();
    let keychain = SecureStore::new("com.librecrate.desktop");

    let vault_exists = config
        .vault_dir
        .as_ref()
        .map(|d| d.join("encryption").join("master_key").exists())
        .unwrap_or(false);

    if vault_exists {
        let unlock = screens::unlock::State::new();
        (
            App {
                screen: Screen::Unlock(unlock),
                _keychain: keychain,
                config,
                _opener: SystemOpener,
            },
            Task::none(),
        )
    } else {
        let first_run = screens::first_run::State::new();
        (
            App {
                screen: Screen::FirstRun(first_run),
                _keychain: keychain,
                config,
                _opener: SystemOpener,
            },
            Task::none(),
        )
    }
}

pub fn update(app: &mut App, message: Message) -> Task<Message> {
    match message {
        Message::FirstRun(msg) => {
            if let Screen::FirstRun(ref mut state) = app.screen {
                let task = state.update(msg);
                return task;
            }
            Task::none()
        }
        Message::Unlock(msg) => {
            if let Screen::Unlock(ref mut state) = app.screen {
                let task = state.update(msg);
                return task;
            }
            Task::none()
        }
        Message::Library(msg) => {
            if let Screen::Library(ref mut state) = app.screen {
                let task = state.update(msg);
                return task;
            }
            Task::none()
        }
        Message::Settings(msg) => {
            if let Screen::Settings(ref mut state) = app.screen {
                let task = state.update(msg);
                return task;
            }
            Task::none()
        }
        Message::Export(msg) => {
            if let Screen::Export(ref mut state) = app.screen {
                let task = state.update(msg);
                return task;
            }
            Task::none()
        }
        Message::Collections(msg) => {
            if let Screen::Collections(ref mut state) = app.screen {
                let task = state.update(msg);
                return task;
            }
            Task::none()
        }
        Message::Navigate(nav) => handle_navigation(app, nav),
    }
}

fn handle_navigation(app: &mut App, nav: Navigation) -> Task<Message> {
    match nav {
        Navigation::FirstRun => {
            let state = screens::first_run::State::new();
            app.screen = Screen::FirstRun(state);
            Task::none()
        }
        Navigation::Unlock => {
            let state = screens::unlock::State::new();
            app.screen = Screen::Unlock(state);
            Task::none()
        }
        Navigation::Library(vault) => {
            let (state, task) = screens::library::State::new(Arc::clone(&vault));
            app.screen = Screen::Library(state);
            task
        }
        Navigation::Settings => {
            let state = screens::settings::State::new();
            app.screen = Screen::Settings(state);
            Task::none()
        }
        Navigation::Export => {
            let state = screens::export::State::new();
            app.screen = Screen::Export(state);
            Task::none()
        }
        Navigation::Collections => {
            let state = screens::collections::State::new();
            app.screen = Screen::Collections(state);
            Task::none()
        }
        Navigation::Lock => {
            let state = screens::unlock::State::new();
            app.screen = Screen::Unlock(state);
            Task::none()
        }
        Navigation::OpenDocument(doc) => {
            let base_dir = app
                .config
                .vault_dir
                .clone()
                .unwrap_or_else(Config::vault_data_dir);
            if let Err(e) = app._opener.open(&doc, &base_dir) {
                tracing::error!("Failed to open document: {e}");
            }
            Task::none()
        }
    }
}

pub fn view(app: &App) -> Element<'_, Message> {
    match &app.screen {
        Screen::FirstRun(state) => state.view().map(Message::FirstRun),
        Screen::Unlock(state) => state.view().map(Message::Unlock),
        Screen::Library(state) => state.view().map(Message::Library),
        Screen::Settings(state) => state.view().map(Message::Settings),
        Screen::Export(state) => state.view().map(Message::Export),
        Screen::Collections(state) => state.view().map(Message::Collections),
    }
}

pub fn theme(_app: &App) -> Theme {
    Theme::Dark
}
