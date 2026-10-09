#![no_main]
//! Fuzz thumbnail generation across every supported container/image format.
//! This is the other parser that consumes untrusted document bytes: image
//! headers, EPUB/CBZ ZIP metadata and cover lookups. All reads and decodes
//! are bounded (see `db::storage` constants).
//!
//! Run: `cargo +nightly fuzz run fuzz_thumbnail`
//!
//! NOTE: the PDF branch shells out to MuPDF render on a temp file per input,
//! which is the slowest branch; the image/EPUB/CBZ branches are fast.

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    for mime in [
        "image/png",
        "image/jpeg",
        "image/gif",
        "image/webp",
        "image/bmp",
        "application/epub+zip",
        "application/vnd.comicbook+zip",
        "application/pdf",
    ] {
        let _ = vault_native::db::storage::generate_thumbnail(data, mime);
    }
});