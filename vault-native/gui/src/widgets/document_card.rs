use iced::widget::{button, column, container, image, row, text, Column};
use iced::Length;

use crate::screens::library;
use vault_native::db::queries::DocumentRow;

pub fn view<'a>(
    doc: &'a DocumentRow,
    thumbnail: Option<&'a [u8]>,
) -> Column<'a, library::Message> {
    let thumb_element: iced::Element<'a, library::Message> = if let Some(bytes) = thumbnail {
        let handle = image::Handle::from_bytes(bytes.to_vec());
        image::Image::new(handle)
            .width(Length::Fill)
            .height(100)
            .into()
    } else {
        container(text("📄").size(32))
            .center_x(Length::Fill)
            .width(Length::Fill)
            .height(80)
            .into()
    };

    column![
        thumb_element,
        text(&doc.title).size(14).width(140),
        text(format!("{} · {}", doc.file_size, doc.mime_type))
            .size(11)
            .color(iced::Color::from_rgb(0.6, 0.64, 0.7)),
        row![
            button(if doc.is_favorite { "★" } else { "☆" })
                .on_press(library::Message::ToggleFavorite(doc.id.clone())),
            button("Open").on_press(library::Message::OpenDocument(doc.id.clone())),
        ]
        .spacing(4),
    ]
    .spacing(4)
    .padding(12)
}