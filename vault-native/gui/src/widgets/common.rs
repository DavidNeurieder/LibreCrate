use iced::widget::{button, container, row, text, Row};
use iced::{Background, Border, Color, Length};

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

/// Styling for a toolbar / header row with bottom border.
pub fn toolbar_style() -> impl Fn(&iced::Theme) -> container::Style {
    |_| container::Style {
        border: Border {
            color: Color::from_rgb(0.25, 0.25, 0.28),
            width: 0.0,
            radius: 0.0.into(),
        },
        ..Default::default()
    }
}

/// Styling for a prominent action or primary card background.
pub fn elevated_style() -> impl Fn(&iced::Theme) -> container::Style {
    |_| container::Style {
        background: Some(Background::Color(Color::from_rgb(0.17, 0.17, 0.2))),
        border: Border {
            color: Color::from_rgb(0.3, 0.3, 0.35),
            width: 1.0,
            radius: 12.0.into(),
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