use iced::{
    widget::{button, column, container, row, text, text_input},
    Element, Task,
};
use std::sync::Arc;

use super::Navigation;
use crate::vault::Vault;

#[derive(Debug, Clone, PartialEq)]
pub enum Message {
    NewCollectionNameChanged(String),
    NewTagNameChanged(String),
    NewTagColorChanged(String),
    AddCollection,
    AddTag,
    DeleteCollection(i64),
    DeleteTag(i64),
    Back,
}

pub struct State {
    pub vault: Arc<Vault>,
    pub collections: Vec<(i64, String)>,
    pub tags: Vec<(i64, String, String)>,
    pub new_collection_name: String,
    pub new_tag_name: String,
    pub new_tag_color: String,
    pub error: Option<String>,
}

impl State {
    pub fn new(vault: Arc<Vault>) -> Self {
        Self {
            vault,
            collections: Vec::new(),
            tags: Vec::new(),
            new_collection_name: String::new(),
            new_tag_name: String::new(),
            new_tag_color: "#5b8def".to_string(),
            error: None,
        }
    }

    pub fn update(&mut self, message: Message) -> Task<crate::app::Message> {
        match message {
            Message::NewCollectionNameChanged(name) => {
                self.new_collection_name = name;
                Task::none()
            }
            Message::NewTagNameChanged(name) => {
                self.new_tag_name = name;
                Task::none()
            }
            Message::NewTagColorChanged(color) => {
                self.new_tag_color = color;
                Task::none()
            }
            Message::AddCollection => {
                let name = self.new_collection_name.trim().to_string();
                if !name.is_empty() {
                    let id = self.collections.len() as i64 + 1;
                    self.collections.push((id, name));
                    self.new_collection_name.clear();
                }
                Task::none()
            }
            Message::AddTag => {
                let name = self.new_tag_name.trim().to_string();
                if !name.is_empty() {
                    let id = self.tags.len() as i64 + 1;
                    self.tags.push((id, name, self.new_tag_color.clone()));
                    self.new_tag_name.clear();
                }
                Task::none()
            }
            Message::DeleteCollection(id) => {
                self.collections.retain(|(i, _)| *i != id);
                Task::none()
            }
            Message::DeleteTag(id) => {
                self.tags.retain(|(i, _, _)| *i != id);
                Task::none()
            }
            Message::Back => Task::done(crate::app::Message::Navigate(Navigation::Library(self.vault.clone()))),
        }
    }

    pub fn view(&self) -> Element<'_, Message> {
        let collection_list = self.collections.iter().fold(
            column![text("Collections").size(18)].spacing(4),
            |col, (id, name)| {
                col.push(
                    row![
                        text(name),
                        button("✕").on_press(Message::DeleteCollection(*id)),
                    ]
                    .spacing(10),
                )
            },
        );

        let tag_list = self.tags.iter().fold(
            column![text("Tags").size(18)].spacing(4),
            |col, (id, name, _color)| {
                col.push(
                    row![
                        text(name),
                        button("✕").on_press(Message::DeleteTag(*id)),
                    ]
                    .spacing(10),
                )
            },
        );

        let content = column![
            text("Collections & Tags").size(24),
            collection_list,
            text_input("New collection name", &self.new_collection_name)
                .on_input(Message::NewCollectionNameChanged),
            button("Add Collection").on_press(Message::AddCollection),
            tag_list,
            text_input("New tag name", &self.new_tag_name)
                .on_input(Message::NewTagNameChanged),
            text_input("Tag color (hex)", &self.new_tag_color)
                .on_input(Message::NewTagColorChanged),
            button("Add Tag").on_press(Message::AddTag),
            if let Some(ref err) = self.error {
                text(err).color(iced::Color::from_rgb(1.0, 0.3, 0.3))
            } else {
                text("")
            },
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
        assert!(state.collections.is_empty());
        assert!(state.tags.is_empty());
        assert!(state.new_collection_name.is_empty());
        assert_eq!(state.new_tag_color, "#5b8def");
    }

    #[test]
    fn test_new_collection_name_changed() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        let _ = state.update(Message::NewCollectionNameChanged("Books".into()));
        assert_eq!(state.new_collection_name, "Books");
    }

    #[test]
    fn test_add_collection() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.new_collection_name = "  Recipes  ".into();
        let _ = state.update(Message::AddCollection);
        assert_eq!(state.collections.len(), 1);
        assert_eq!(state.collections[0].1, "Recipes");
        assert!(state.new_collection_name.is_empty());
    }

    #[test]
    fn test_add_empty_collection_does_nothing() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        let _ = state.update(Message::AddCollection);
        assert!(state.collections.is_empty());
    }

    #[test]
    fn test_delete_collection() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.collections.push((1, "Books".into()));
        state.collections.push((2, "Papers".into()));
        let _ = state.update(Message::DeleteCollection(1));
        assert_eq!(state.collections.len(), 1);
        assert_eq!(state.collections[0].1, "Papers");
    }

    #[test]
    fn test_new_tag_name_changed() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        let _ = state.update(Message::NewTagNameChanged("urgent".into()));
        assert_eq!(state.new_tag_name, "urgent");
    }

    #[test]
    fn test_new_tag_color_changed() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        let _ = state.update(Message::NewTagColorChanged("#ff0000".into()));
        assert_eq!(state.new_tag_color, "#ff0000");
    }

    #[test]
    fn test_add_tag() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.new_tag_name = "  urgent  ".into();
        state.new_tag_color = "#ff0000".into();
        let _ = state.update(Message::AddTag);
        assert_eq!(state.tags.len(), 1);
        assert_eq!(state.tags[0].1, "urgent");
        assert_eq!(state.tags[0].2, "#ff0000");
        assert!(state.new_tag_name.is_empty());
    }

    #[test]
    fn test_delete_tag() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.tags.push((1, "urgent".into(), "#f00".into()));
        state.tags.push((2, "later".into(), "#00f".into()));
        let _ = state.update(Message::DeleteTag(1));
        assert_eq!(state.tags.len(), 1);
        assert_eq!(state.tags[0].1, "later");
    }

    #[test]
    fn test_view_default() {
        let vault = make_test_vault();
        let state = State::new(vault);
        let _view = state.view();
    }

    #[test]
    fn test_view_with_collections_and_tags() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.collections.push((1, "Books".into()));
        state.collections.push((2, "Papers".into()));
        state.tags.push((1, "urgent".into(), "#f00".into()));
        let _view = state.view();
    }

    #[test]
    fn test_view_with_error() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.error = Some("Database error".into());
        let _view = state.view();
    }

    #[test]
    fn test_ui_collections_renders() {
        let vault = make_test_vault();
        let state = State::new(vault);
        let mut ui = iced_test::simulator(state.view());
        assert!(ui.find("Collections & Tags").is_ok());
        assert!(ui.find("Add Collection").is_ok());
        assert!(ui.find("Add Tag").is_ok());
        assert!(ui.find("Back").is_ok());
    }

    #[test]
    fn test_ui_collections_and_tags_listed() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.collections.push((1, "Books".into()));
        state.collections.push((2, "Papers".into()));
        state.tags.push((1, "urgent".into(), "#f00".into()));

        let mut ui = iced_test::simulator(state.view());
        assert!(ui.find("Books").is_ok());
        assert!(ui.find("Papers").is_ok());
        assert!(ui.find("urgent").is_ok());
    }

    #[test]
    fn test_ui_error_displayed() {
        let vault = make_test_vault();
        let mut state = State::new(vault);
        state.error = Some("Database error".into());
        let mut ui = iced_test::simulator(state.view());
        assert!(ui.find("Database error").is_ok());
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
