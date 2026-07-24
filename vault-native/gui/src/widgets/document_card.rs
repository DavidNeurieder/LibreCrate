use iced::widget::{button, column, container, image, mouse_area, row, text};
use iced::{Border, Color, Element, Length, Padding};

use crate::screens::library;
use crate::widgets::common;
use vault_native::db::queries::DocumentRow;

/// Returns a short label + background colour for the document type badge.
fn mime_badge(mime_type: &str) -> (&'static str, Color) {
    match mime_type {
        "application/pdf" => ("PDF", Color::from_rgb(0.8, 0.2, 0.2)),
        "application/epub+zip" => ("EPUB", Color::from_rgb(0.2, 0.5, 0.8)),
        "application/vnd.comicbook+zip" | "application/x-cbr" => {
            ("CBZ", Color::from_rgb(0.7, 0.4, 0.1))
        }
        "application/vnd.apple.pkpass" => ("PKPass", Color::from_rgb(0.3, 0.7, 0.3)),
        "text/markdown" | "text/plain" => ("Note", Color::from_rgb(0.5, 0.5, 0.5)),
        mt if mt.starts_with("image/") => ("Image", Color::from_rgb(0.3, 0.6, 0.6)),
        _ => ("File", Color::from_rgb(0.4, 0.4, 0.4)),
    }
}

pub fn view<'a>(
    doc: &'a DocumentRow,
    thumbnail: Option<&'a [u8]>,
) -> Element<'a, library::Message> {
    let thumb_element: iced::Element<'a, library::Message> = if let Some(bytes) = thumbnail {
        let handle = image::Handle::from_bytes(bytes.to_vec());
        image::Image::new(handle)
            .width(Length::Fill)
            .height(110)
            .into()
    } else {
        let (label, color) = mime_badge(&doc.mime_type);
        container(
            text(label).size(18).color(Color::from_rgb(0.8, 0.8, 0.8)),
        )
        .center_x(Length::Fill)
        .center_y(Length::Fill)
        .width(Length::Fill)
        .height(110)
        .style(move |_| container::Style {
            background: Some(iced::Background::Color(Color {
                r: color.r * 0.35,
                g: color.g * 0.35,
                b: color.b * 0.35,
                a: 1.0,
            })),
            ..Default::default()
        })
        .into()
    };

    let file_size = if doc.file_size > 1_000_000 {
        format!("{:.1} MB", doc.file_size as f64 / 1_000_000.0)
    } else if doc.file_size > 1_000 {
        format!("{:.0} KB", doc.file_size as f64 / 1_000.0)
    } else {
        format!("{} B", doc.file_size)
    };

    let (badge_label, badge_color) = mime_badge(&doc.mime_type);

    let card_content = column![
        thumb_element,
        column![
            text(&doc.title).size(13).width(Length::Fill),
            row![
                container(
                    text(badge_label).size(9).color(Color::from_rgb(0.9, 0.9, 0.9)),
                )
                .padding(Padding::new(2.0).left(6).right(6))
                .style(move |_| container::Style {
                    background: Some(iced::Background::Color(badge_color)),
                    border: Border {
                        radius: 4.0.into(),
                        ..Default::default()
                    },
                    ..Default::default()
                }),
                text(file_size).size(10).color(Color::from_rgb(0.5, 0.5, 0.5)),
            ]
            .spacing(6)
            .align_y(iced::Alignment::Center),
            button(if doc.is_favorite { "★" } else { "☆" })
                .on_press(library::Message::ToggleFavorite(doc.id.clone())),
        ]
        .spacing(2)
        .padding(8),
    ]
    .spacing(0)
    .width(180);

    mouse_area(
        container(card_content).style(common::card_style()),
    )
    .on_press(library::Message::OpenDocument(doc.id.clone()))
    .into()
}