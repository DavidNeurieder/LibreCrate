use iced::widget::{row, text_input, Row};
use iced::Length;

pub fn view<'a>(
    query: &'a str,
    on_input: impl Fn(String) -> () + 'a,
) -> Row<'a, ()> {
    row![
        text_input("Search documents...", query)
            .on_input(on_input)
            .width(Length::Fill),
    ]
    .spacing(10)
    .padding(10)
}
