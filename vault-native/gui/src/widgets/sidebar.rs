use iced::widget::{column, text, Column};
use iced::Element;

pub fn view<'a>(
    selected_collection: &'a Option<i64>,
    _collections: &'a [(i64, String)],
    _tags: &'a [(i64, String, String)],
) -> Column<'a, ()> {
    column![
        text("All Documents").size(14),
        text("Favorites").size(14),
        text("Collections").size(12),
        text("Tags").size(12),
    ]
    .spacing(4)
    .padding(10)
}
