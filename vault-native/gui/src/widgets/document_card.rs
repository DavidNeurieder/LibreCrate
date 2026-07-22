use iced::widget::{column, container, text, Column};
use iced::Length;

use vault_native::db::queries::DocumentRow;

pub fn view<'a>(doc: &'a DocumentRow) -> Column<'a, ()> {
    column![
        container(text(&doc.title).size(14))
            .width(Length::Fill)
            .height(80),
        text(&doc.mime_type).size(11),
    ]
    .spacing(4)
    .padding(12)
}
