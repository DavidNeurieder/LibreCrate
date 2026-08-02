use iced::{Element, Subscription, Task, Theme};
use std::collections::VecDeque;
use std::hash::{Hash, Hasher};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use crate::config::Config;
use crate::dnd;
use crate::screens::{self, Navigation};

struct DndPending(Arc<Mutex<VecDeque<PathBuf>>>);

impl Clone for DndPending {
    fn clone(&self) -> Self {
        Self(self.0.clone())
    }
}

impl Hash for DndPending {
    fn hash<H: Hasher>(&self, state: &mut H) {
        Arc::as_ptr(&self.0).hash(state);
    }
}

pub enum Screen {
    FirstRun(screens::first_run::State),
    Unlock(screens::unlock::State),
    Library(screens::library::State),
    Settings(screens::settings::State),
    Export(screens::export::State),
    ExportDocs(screens::export_docs::State),
    Pdf(screens::pdf::State),
}

#[derive(Debug)]
pub enum Message {
    FirstRun(screens::first_run::Message),
    Unlock(screens::unlock::Message),
    Library(screens::library::Message),
    Settings(screens::settings::Message),
    Export(screens::export::Message),
    ExportDocs(screens::export_docs::Message),
    Pdf(screens::pdf::Message),
    Navigate(Navigation),
    FileDropped(PathBuf),
    WindowReady(Option<u32>),
}

pub struct App {
    pub screen: Screen,
    pub dnd: dnd::Dnd,
    pub dnd_pending: Arc<Mutex<VecDeque<PathBuf>>>,
    pub pdf_cache: VecDeque<screens::pdf::CachedPdf>,
}

const PDF_CACHE_MAX: usize = 2;

pub fn boot() -> (App, Task<Message>) {
    let config = Config::load();

    let dnd = dnd::Dnd::new();
    let dnd_pending = dnd.pending();

    let vault_exists = config
        .vault_dir
        .as_ref()
        .map(|d| {
            let enc = d.join("encryption");
            enc.join("wrapped_master_key").exists() || enc.join("master_key").exists()
        })
        .unwrap_or(false);

    // Request the X11 window XID after the window is created
    let xid_task = iced::window::latest().then(|maybe_id| {
        match maybe_id {
            Some(id) => iced::window::run(id, |window| {
                match window.window_handle().ok() {
                    Some(handle) => match handle.as_raw() {
                        iced::window::raw_window_handle::RawWindowHandle::Xlib(h) => {
                            Some(h.window as u32)
                        }
                        iced::window::raw_window_handle::RawWindowHandle::Xcb(h) => {
                            Some(h.window.get())
                        }
                        _ => None,
                    },
                    None => None,
                }
            }),
            None => Task::done(None),
        }
    });

    if vault_exists {
        let unlock = screens::unlock::State::new();
        (
            App {
                screen: Screen::Unlock(unlock),
                dnd,
                dnd_pending,
                pdf_cache: VecDeque::new(),
            },
            xid_task.map(Message::WindowReady),
        )
    } else {
        let first_run = screens::first_run::State::new();
        (
            App {
                screen: Screen::FirstRun(first_run),
                dnd,
                dnd_pending,
                pdf_cache: VecDeque::new(),
            },
            xid_task.map(Message::WindowReady),
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
        Message::ExportDocs(msg) => {
            if let Screen::ExportDocs(ref mut state) = app.screen {
                let task = state.update(msg);
                return task;
            }
            Task::none()
        }
        Message::Pdf(msg) => {
            if let Screen::Pdf(ref mut state) = app.screen {
                let task = state.update(msg);
                return task;
            }
            Task::none()
        }
        Message::Navigate(nav) => handle_navigation(app, nav),
        Message::WindowReady(Some(xid)) => {
            app.dnd.start(xid);
            Task::none()
        }
        Message::WindowReady(None) => {
            tracing::warn!("Could not extract X11 window ID for DnD");
            Task::none()
        }
        Message::FileDropped(path) => {
            if let Screen::Library(ref mut state) = app.screen {
                let vault = state.vault.clone();
                return Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || -> Result<usize, String> {
                            if path.is_dir() {
                                let mut count = 0;
                                for entry in std::fs::read_dir(&path).map_err(|e| e.to_string())? {
                                    let entry = entry.map_err(|e| e.to_string())?;
                                    let child = entry.path();
                                    if child.is_file() {
                                        vault.import_file(&child).map_err(|e| e.to_string())?;
                                        count += 1;
                                    }
                                }
                                Ok(count)
                            } else {
                                vault.import_file(&path).map(|_| 1).map_err(|e| e.to_string())
                            }
                        })
                        .await
                        .map_err(|e| e.to_string())?
                    },
                    |result| Message::Library(screens::library::Message::DropResult(result)),
                );
            }
            Task::none()
        }
    }
}

fn handle_navigation(app: &mut App, nav: Navigation) -> Task<Message> {
    match nav {
        Navigation::FirstRun => {
            let state = screens::first_run::State::new();
            app.screen = Screen::FirstRun(state);
            Task::none()
        }
        Navigation::Library(vault) => {
            let (state, task) = screens::library::State::new(Arc::clone(&vault));
            app.screen = Screen::Library(state);
            task
        }
        Navigation::Settings(vault) => {
            let state = screens::settings::State::new(vault);
            app.screen = Screen::Settings(state);
            Task::none()
        }
        Navigation::Export(vault) => {
            let state = screens::export::State::new(vault);
            app.screen = Screen::Export(state);
            Task::none()
        }
        Navigation::ExportDocs(vault) => {
            let (state, task) = screens::export_docs::State::new(vault);
            app.screen = Screen::ExportDocs(state);
            task
        }
        Navigation::OpenDocument(doc, vault) => {
            if let Err(e) = vault.open_document(&doc) {
                tracing::error!("Failed to open document: {e}");
            }
            Task::none()
        }
        Navigation::OpenPdf(doc, vault) => {
            if let Some(cached) = app.pdf_cache.iter().position(|c| c.doc_id == doc.id) {
                let cached = app.pdf_cache.remove(cached).expect("just located");
                let (state, task) = screens::pdf::State::from_cached(doc, vault, cached);
                app.screen = Screen::Pdf(state);
                task
            } else {
                let (state, task) = screens::pdf::State::new(doc, vault);
                app.screen = Screen::Pdf(state);
                task
            }
        }
        Navigation::PdfExit(vault) => {
            if let Screen::Pdf(state) = &mut app.screen {
                if let Some(cached) = state.leaving() {
                    app.pdf_cache.retain(|c| c.doc_id != cached.doc_id);
                    app.pdf_cache.push_back(cached);
                    while app.pdf_cache.len() > PDF_CACHE_MAX {
                        app.pdf_cache.pop_front();
                    }
                }
            }
            let (state, task) = screens::library::State::new(vault);
            app.screen = Screen::Library(state);
            task
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
        Screen::ExportDocs(state) => state.view().map(Message::ExportDocs),
        Screen::Pdf(state) => state.view().map(Message::Pdf),
    }
}

pub fn subscription(state: &App) -> Subscription<Message> {
    let mut subs: Vec<Subscription<Message>> = Vec::new();
    subs.push(iced::event::listen_with(drop_event_handler));

    #[cfg(target_os = "linux")]
    {
        use iced::futures::stream::unfold;

        let pending = DndPending(state.dnd_pending.clone());
        let dnd_sub = Subscription::run_with(pending, |p| {
            let queue = p.0.clone();
            unfold(queue, |queue| async move {
                loop {
                    tokio::time::sleep(std::time::Duration::from_millis(50)).await;
                    let path = queue.lock().unwrap().pop_front();
                    if let Some(path) = path {
                        return Some((Message::FileDropped(path), queue));
                    }
                }
            })
        });
        subs.push(dnd_sub);
    }

    Subscription::batch(subs)
}

fn drop_event_handler(
    event: iced::Event,
    _status: iced::event::Status,
    _window: iced::window::Id,
) -> Option<Message> {
    match event {
        iced::Event::Window(iced::window::Event::FileDropped(path)) => {
            Some(Message::FileDropped(path))
        }
        _ => None,
    }
}

pub fn theme(_app: &App) -> Theme {
    Theme::Dark
}
