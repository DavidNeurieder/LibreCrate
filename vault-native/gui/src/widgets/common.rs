use iced::widget::{button, container, row, text, text_input, Row};
use iced::{Background, Border, Color, Element, Length};

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

/// Navigation bar with optional back button and screen title.
pub fn navbar<'a, Message: 'a + Clone>(
    title: &'a str,
    on_back: Option<Message>,
) -> Row<'a, Message> {
    let back: iced::Element<'a, Message> = if let Some(msg) = on_back {
        button("Back").on_press(msg).into()
    } else {
        row![].into()
    };

    row![
        back,
        text(title).size(22),
    ]
    .spacing(12)
    .padding(iced::Padding::new(12.0).left(16.0).right(16.0))
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