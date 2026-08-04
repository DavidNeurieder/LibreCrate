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
    Viewer(screens::viewer::State),
}

#[derive(Debug)]
pub enum Message {
    FirstRun(screens::first_run::Message),
    Unlock(screens::unlock::Message),
    Library(screens::library::Message),
    Settings(screens::settings::Message),
    Export(screens::export::Message),
    ExportDocs(screens::export_docs::Message),
    Viewer(screens::viewer::Message),
    Navigate(Navigation),
    FileDropped(PathBuf),
    WindowReady(Option<u32>),
}

pub struct App {
    pub screen: Screen,
    pub dnd: dnd::Dnd,
    pub dnd_pending: Arc<Mutex<VecDeque<PathBuf>>>,
    pub viewer_cache: VecDeque<screens::viewer::CachedViewer>,
}

const VIEWER_CACHE_MAX: usize = 2;
const VIEWER_SCROLL_STEP: f32 = 50.0;

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
                viewer_cache: VecDeque::new(),
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
                viewer_cache: VecDeque::new(),
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
        Message::Viewer(msg) => {
            if let Screen::Viewer(ref mut state) = app.screen {
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
        Navigation::OpenViewer(doc, vault) => {
            if let Some(cached) = app.viewer_cache.iter().position(|c| c.doc_id == doc.id) {
                let cached = app.viewer_cache.remove(cached).expect("just located");
                let (state, task) = screens::viewer::State::from_cached(doc, vault, cached);
                app.screen = Screen::Viewer(state);
                task
            } else {
                let (state, task) = screens::viewer::State::new(doc, vault);
                app.screen = Screen::Viewer(state);
                task
            }
        }
        Navigation::OpenViewerAt(doc, vault, page) => {
            if let Some(cached) = app.viewer_cache.iter().position(|c| c.doc_id == doc.id) {
                let cached = app.viewer_cache.remove(cached).expect("just located");
                let (state, task) = screens::viewer::State::from_cached_at(doc, vault, cached, Some(page));
                app.screen = Screen::Viewer(state);
                task
            } else {
                let (state, task) = screens::viewer::State::new_at(doc, vault, page);
                app.screen = Screen::Viewer(state);
                task
            }
        }
        Navigation::ViewerExit(vault) => {
            if let Screen::Viewer(state) = &mut app.screen {
                if let Some(cached) = state.leaving() {
                    app.viewer_cache.retain(|c| c.doc_id != cached.doc_id);
                    app.viewer_cache.push_back(cached);
                    while app.viewer_cache.len() > VIEWER_CACHE_MAX {
                        app.viewer_cache.pop_front();
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
        Screen::Viewer(state) => state.view().map(Message::Viewer),
    }
}

pub fn subscription(state: &App) -> Subscription<Message> {
    let mut subs: Vec<Subscription<Message>> = Vec::new();
    subs.push(iced::event::listen_with(drop_event_handler));

    if matches!(&state.screen, Screen::Viewer(_)) {
        subs.push(iced::event::listen_with(viewer_keyboard_handler));
    }

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

fn viewer_keyboard_handler(
    event: iced::Event,
    _status: iced::event::Status,
    _window: iced::window::Id,
) -> Option<Message> {
    let iced::Event::Keyboard(iced::keyboard::Event::KeyPressed {
        key,
        modified_key,
        physical_key,
        modifiers,
        ..
    }) = event
    else {
        return None;
    };

    use iced::keyboard::{key, Key};

    let viewer_msg = if modifiers.control() {
        let key_char = match key.as_ref() {
            Key::Character(c) => Some(c.as_ref()),
            _ => None,
        };
        let mod_char = match modified_key.as_ref() {
            Key::Character(c) => Some(c.as_ref()),
            _ => None,
        };
        let is_plus = matches!(
            physical_key,
            key::Physical::Code(key::Code::Equal) | key::Physical::Code(key::Code::NumpadAdd)
        ) || matches!(key_char, Some("+") | Some("="))
            || matches!(mod_char, Some("+") | Some("="));
        let is_minus = matches!(
            physical_key,
            key::Physical::Code(key::Code::Minus) | key::Physical::Code(key::Code::NumpadSubtract)
        ) || matches!(key_char, Some("-"))
            || matches!(mod_char, Some("-"));
        if is_plus {
            screens::viewer::Message::ZoomIn
        } else if is_minus {
            screens::viewer::Message::ZoomOut
        } else {
            return None;
        }
    } else {
        match key.as_ref() {
            iced::keyboard::Key::Named(key::Named::ArrowDown) => {
                screens::viewer::Message::ScrollBy(VIEWER_SCROLL_STEP)
            }
            iced::keyboard::Key::Named(key::Named::ArrowUp) => {
                screens::viewer::Message::ScrollBy(-VIEWER_SCROLL_STEP)
            }
            iced::keyboard::Key::Named(key::Named::PageDown) => {
                screens::viewer::Message::ScrollPage(1)
            }
            iced::keyboard::Key::Named(key::Named::PageUp) => {
                screens::viewer::Message::ScrollPage(-1)
            }
            _ => return None,
        }
    };

    Some(Message::Viewer(viewer_msg))
}

pub fn theme(_app: &App) -> Theme {
    Theme::Dark
}

#[cfg(test)]
mod tests {
    use super::*;

    fn wid() -> iced::window::Id {
        iced::window::Id::unique()
    }

    fn key_event(
        key: iced::keyboard::Key,
        code: iced::keyboard::key::Code,
        modifiers: iced::keyboard::Modifiers,
    ) -> iced::Event {
        key_event_with(key.clone(), key, code, modifiers)
    }

    fn key_event_with(
        key: iced::keyboard::Key,
        modified_key: iced::keyboard::Key,
        code: iced::keyboard::key::Code,
        modifiers: iced::keyboard::Modifiers,
    ) -> iced::Event {
        iced::Event::Keyboard(iced::keyboard::Event::KeyPressed {
            key,
            modified_key,
            physical_key: iced::keyboard::key::Physical::Code(code),
            location: iced::keyboard::Location::Standard,
            modifiers,
            text: None,
            repeat: false,
        })
    }

    fn named(
        named: iced::keyboard::key::Named,
    ) -> iced::Event {
        key_event(
            iced::keyboard::Key::Named(named),
            iced::keyboard::key::Code::KeyA,
            iced::keyboard::Modifiers::NONE,
        )
    }

    #[test]
    fn navigate_to_unlock_switches_to_unlock_screen() {
        let vault = crate::vault::tests::make_test_vault();
        let mut app = App {
            screen: Screen::Export(screens::export::State::new(vault)),
            dnd: dnd::Dnd::new(),
            dnd_pending: Arc::new(Mutex::new(VecDeque::new())),
            viewer_cache: VecDeque::new(),
        };
        let task = update(&mut app, Message::Navigate(Navigation::Unlock));
        assert!(matches!(&app.screen, Screen::Unlock(_)));
        assert_eq!(task.units(), 0);
    }

    #[test]
    fn zoom_keys_map_to_zoom_messages() {
        use iced::keyboard::{key, Modifiers};
        let ctrl = Modifiers::CTRL;

        let plus = key_event(key::Key::Character("=".into()), key::Code::Equal, ctrl);
        assert!(matches!(
            viewer_keyboard_handler(plus, iced::event::Status::Ignored, wid()),
            Some(Message::Viewer(screens::viewer::Message::ZoomIn))
        ));

        let shift_plus = key_event(
            key::Key::Character("+".into()),
            key::Code::Equal,
            ctrl | Modifiers::SHIFT,
        );
        assert!(matches!(
            viewer_keyboard_handler(shift_plus, iced::event::Status::Ignored, wid()),
            Some(Message::Viewer(screens::viewer::Message::ZoomIn))
        ));

        let numpad_plus = key_event(
            key::Key::Character("+".into()),
            key::Code::NumpadAdd,
            ctrl,
        );
        assert!(matches!(
            viewer_keyboard_handler(numpad_plus, iced::event::Status::Ignored, wid()),
            Some(Message::Viewer(screens::viewer::Message::ZoomIn))
        ));

        let minus = key_event(key::Key::Character("-".into()), key::Code::Minus, ctrl);
        assert!(matches!(
            viewer_keyboard_handler(minus, iced::event::Status::Ignored, wid()),
            Some(Message::Viewer(screens::viewer::Message::ZoomOut))
        ));

        let numpad_minus = key_event(
            key::Key::Character("-".into()),
            key::Code::NumpadSubtract,
            ctrl,
        );
        assert!(matches!(
            viewer_keyboard_handler(numpad_minus, iced::event::Status::Ignored, wid()),
            Some(Message::Viewer(screens::viewer::Message::ZoomOut))
        ));
    }

    #[test]
    fn zoom_keys_match_characters_regardless_of_physical_code() {
        use iced::keyboard::{key, Modifiers};
        let ctrl = Modifiers::CTRL;
        let arbitrary = key::Code::KeyA;

        let plus_char = key_event(key::Key::Character("+".into()), arbitrary, ctrl);
        assert!(matches!(
            viewer_keyboard_handler(plus_char, iced::event::Status::Ignored, wid()),
            Some(Message::Viewer(screens::viewer::Message::ZoomIn))
        ));

        let equal_char = key_event(key::Key::Character("=".into()), arbitrary, ctrl);
        assert!(matches!(
            viewer_keyboard_handler(equal_char, iced::event::Status::Ignored, wid()),
            Some(Message::Viewer(screens::viewer::Message::ZoomIn))
        ));

        let minus_char = key_event(key::Key::Character("-".into()), arbitrary, ctrl);
        assert!(matches!(
            viewer_keyboard_handler(minus_char, iced::event::Status::Ignored, wid()),
            Some(Message::Viewer(screens::viewer::Message::ZoomOut))
        ));

        let mod_key_plus = key_event_with(
            key::Key::Unidentified,
            key::Key::Character("+".into()),
            arbitrary,
            ctrl,
        );
        assert!(matches!(
            viewer_keyboard_handler(mod_key_plus, iced::event::Status::Ignored, wid()),
            Some(Message::Viewer(screens::viewer::Message::ZoomIn))
        ));

        let mod_key_minus = key_event_with(
            key::Key::Unidentified,
            key::Key::Character("-".into()),
            arbitrary,
            ctrl,
        );
        assert!(matches!(
            viewer_keyboard_handler(mod_key_minus, iced::event::Status::Ignored, wid()),
            Some(Message::Viewer(screens::viewer::Message::ZoomOut))
        ));
    }

    #[test]
    fn navigation_keys_map_to_scroll_messages() {
        use iced::keyboard::key;

        assert!(matches!(
            viewer_keyboard_handler(named(key::Named::ArrowDown), iced::event::Status::Ignored, wid()),
            Some(Message::Viewer(screens::viewer::Message::ScrollBy(d))) if d == VIEWER_SCROLL_STEP
        ));
        assert!(matches!(
            viewer_keyboard_handler(named(key::Named::ArrowUp), iced::event::Status::Ignored, wid()),
            Some(Message::Viewer(screens::viewer::Message::ScrollBy(d))) if d == -VIEWER_SCROLL_STEP
        ));
        assert!(matches!(
            viewer_keyboard_handler(named(key::Named::PageDown), iced::event::Status::Ignored, wid()),
            Some(Message::Viewer(screens::viewer::Message::ScrollPage(1)))
        ));
        assert!(matches!(
            viewer_keyboard_handler(named(key::Named::PageUp), iced::event::Status::Ignored, wid()),
            Some(Message::Viewer(screens::viewer::Message::ScrollPage(-1)))
        ));
    }

    #[test]
    fn unrelated_keys_are_ignored() {
        use iced::keyboard::{key, Modifiers};

        let ctrl_0 = key_event(
            key::Key::Character("0".into()),
            key::Code::Digit0,
            Modifiers::CTRL,
        );
        assert!(viewer_keyboard_handler(ctrl_0, iced::event::Status::Ignored, wid()).is_none());

        assert!(viewer_keyboard_handler(
            named(key::Named::Escape),
            iced::event::Status::Ignored,
            wid()
        )
        .is_none());

        let release = iced::Event::Keyboard(iced::keyboard::Event::KeyReleased {
            key: iced::keyboard::Key::Named(key::Named::ArrowDown),
            modified_key: iced::keyboard::Key::Named(key::Named::ArrowDown),
            physical_key: key::Physical::Code(key::Code::ArrowDown),
            location: iced::keyboard::Location::Standard,
            modifiers: iced::keyboard::Modifiers::NONE,
        });
        assert!(viewer_keyboard_handler(release, iced::event::Status::Ignored, wid()).is_none());
    }
}
