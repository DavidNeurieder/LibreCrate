use iced::widget::{button, column, container, row, rule, text, text_input, Button, Column};
use iced::{Background, Border, Color, Element, Length};

/// Style for a subtle, flat button on the dark theme (1px border, rounded, hover/pressed states).
pub fn subtle_button_style(_theme: &iced::Theme, status: button::Status) -> button::Style {
    let base = button::Style {
        background: Some(Background::Color(Color::from_rgb(0.14, 0.14, 0.16))),
        text_color: Color::from_rgb(0.92, 0.92, 0.95),
        border: Border {
            color: Color::from_rgb(0.28, 0.28, 0.32),
            width: 1.0,
            radius: 5.0.into(),
        },
        ..Default::default()
    };
    match status {
        button::Status::Hovered => button::Style {
            background: Some(Background::Color(Color::from_rgb(0.19, 0.19, 0.22))),
            border: Border {
                color: Color::from_rgb(0.36, 0.36, 0.42),
                ..base.border
            },
            ..base
        },
        button::Status::Pressed => button::Style {
            background: Some(Background::Color(Color::from_rgb(0.10, 0.10, 0.12))),
            ..base
        },
        _ => base,
    }
}

/// A subtle, flat button with a given label.
pub fn subtle_button<'a, Message: Clone + 'a>(
    label: &'a str,
) -> Button<'a, Message, iced::Theme, iced::Renderer> {
    button(text(label))
        .padding(iced::Padding::new(6.0).horizontal(10.0))
        .style(subtle_button_style)
}

/// Styling for a card container with border, rounded corners, and background.
pub fn card_style() -> impl Fn(&iced::Theme) -> container::Style {
    |_| container::Style {
        background: Some(Background::Color(Color::from_rgb(0.14, 0.14, 0.16))),
        border: Border {
            color: Color::from_rgb(0.25, 0.25, 0.28),
            width: 1.0,
            radius: 8.0.into(),
        },
        ..Default::default()
    }
}

/// Navigation bar with optional back button, screen title, and subtitle.
pub fn navbar<'a, Message: 'a + Clone>(
    title: &'a str,
    subtitle: Option<&'a str>,
    on_back: Option<Message>,
) -> Element<'a, Message> {
    let back: iced::Element<'a, Message> = if let Some(msg) = on_back {
        button(
            row![
                text("←").size(16),
                text("Back").size(13),
            ]
            .spacing(4)
            .align_y(iced::Alignment::Center),
        )
        .on_press(msg)
        .padding(iced::Padding::new(6.0).horizontal(10.0))
        .style(subtle_button_style)
        .into()
    } else {
        row![].into()
    };

    let mut title_col = Column::new().spacing(2).push(
        text(title)
            .size(19)
            .font(iced::Font {
                weight: iced::font::Weight::Bold,
                ..iced::Font::DEFAULT
            })
            .wrapping(iced::widget::text::Wrapping::None),
    );
    if let Some(sub) = subtitle {
        title_col = title_col
            .push(text(sub).size(12).color(Color::from_rgb(0.6, 0.6, 0.65)));
    }

    column![
        container(
            row![back, title_col]
                .spacing(12)
                .align_y(iced::Alignment::Center)
                .padding(iced::Padding::new(12.0).left(16.0).right(16.0))
                .width(Length::Fill),
        )
        .width(Length::Fill)
        .style(|_| container::Style {
            background: Some(Background::Color(Color::from_rgb(0.12, 0.12, 0.14))),
            ..Default::default()
        }),
        rule::horizontal(1.0).style(|_| rule::Style {
            color: Color::from_rgb(0.24, 0.24, 0.28),
            radius: 0.0.into(),
            fill_mode: rule::FillMode::Full,
            snap: false,
        }),
    ]
    .width(Length::Fill)
    .spacing(0)
    .into()
}

/// A password input field with a show/hide toggle button.
pub fn secure_field<'a, Message: Clone + 'a>(
    placeholder: &'a str,
    value: &'a str,
    show: bool,
    on_input: impl Fn(String) -> Message + 'a,
    on_toggle: Message,
    on_submit: Option<Message>,
) -> Element<'a, Message> {
    let label = if show { "Hide" } else { "Show" };
    row![
        text_input(placeholder, value)
            .secure(!show)
            .on_input(on_input)
            .on_submit_maybe(on_submit)
            .width(Length::Fill),
        button(text(label).size(12))
            .on_press(on_toggle)
            .padding(iced::Padding::new(6.0).horizontal(10.0))
            .style(|_theme: &iced::Theme, _status| {
                button::Style {
                    background: None,
                    text_color: Color::from_rgb(0.4, 0.6, 1.0),
                    border: Border {
                        color: Color::from_rgb(0.3, 0.45, 0.8),
                        width: 1.0,
                        radius: 4.0.into(),
                    },
                    ..Default::default()
                }
            }),
    ]
    .spacing(4)
    .align_y(iced::Alignment::Center)
    .into()
}