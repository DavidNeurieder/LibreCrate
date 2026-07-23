use std::path::Path;

fn fixture(name: &str) -> std::path::PathBuf {
    let dir = env!("CARGO_MANIFEST_DIR");
    Path::new(dir).join("tests").join("fixtures").join(name)
}

#[cfg(feature = "pdf")]
mod pdf_tests {
    use super::*;
    use vault_native::reader;

    #[test]
    fn test_open_single_page() {
        let reader = reader::open(&fixture("test_1page.pdf"), "application/pdf").unwrap();
        assert_eq!(reader.page_count().unwrap(), 1);
    }

    #[test]
    fn test_open_multi_page() {
        let reader = reader::open(&fixture("test_2page.pdf"), "application/pdf").unwrap();
        assert_eq!(reader.page_count().unwrap(), 2);
    }

    #[test]
    fn test_extract_text_page_0() {
        let reader = reader::open(&fixture("test_1page.pdf"), "application/pdf").unwrap();
        let text = reader.extract_text(0).unwrap();
        assert_eq!(text.trim(), "Test PDF content");
    }

    #[test]
    fn test_extract_all_text() {
        let reader = reader::open(&fixture("test_1page.pdf"), "application/pdf").unwrap();
        let text = reader.extract_all_text().unwrap();
        assert!(text.contains("Test PDF content"));
    }

    #[test]
    fn test_page_size_letter() {
        let reader = reader::open(&fixture("test_1page.pdf"), "application/pdf").unwrap();
        let (w, h) = reader.page_size(0).unwrap();
        assert!((w - 612.0).abs() < 0.1, "width={w} expected 612");
        assert!((h - 792.0).abs() < 0.1, "height={h} expected 792");
    }

    #[test]
    fn test_render_page_dimensions() {
        let reader = reader::open(&fixture("test_1page.pdf"), "application/pdf").unwrap();
        let rendered = reader.render_page(0, 1.0).unwrap();
        // At 150 DPI: 612pt * 150/72 = 1275px, 792pt * 150/72 = 1650px
        assert_eq!(rendered.width, 1275);
        assert_eq!(rendered.height, 1650);
        assert_eq!(rendered.data.len(), (1275 * 1650 * 4) as usize);
    }

    #[test]
    fn test_render_thumbnail() {
        let reader = reader::open(&fixture("test_1page.pdf"), "application/pdf").unwrap();
        let thumb = reader.render_thumbnail().unwrap();
        // render_thumbnail returns PNG bytes (not raw RGBA)
        assert!(thumb.len() > 100, "thumbnail too small: {} bytes", thumb.len());
        assert_eq!(&thumb[..8], &[137, 80, 78, 71, 13, 10, 26, 10], "not a PNG header");
    }

    #[test]
    fn test_render_page_not_uniform() {
        let reader = reader::open(&fixture("test_1page.pdf"), "application/pdf").unwrap();
        let rendered = reader.render_page(0, 0.5).unwrap();

        // On a system with Liberation Sans/Helvetica substitute, the page
        // should contain antialiased text on white background — pixel values
        // will vary.  Compute min/max across the R channel as a quick check.
        let mut min = 255u8;
        let mut max = 0u8;
        for chunk in rendered.data.chunks_exact(4) {
            let r = chunk[0];
            if r < min { min = r; }
            if r > max { max = r; }
        }
        assert!(
            min < 10,
            "min R channel = {min} — page appears all white (no rendered content)"
        );
        assert!(
            max > 240,
            "max R channel = {max} — page appears all black (render failure)"
        );

        // Count distinct R values — antialiasing should produce >2
        let mut seen = [false; 256];
        for chunk in rendered.data.chunks_exact(4) {
            seen[chunk[0] as usize] = true;
        }
        let distinct = seen.iter().filter(|&&b| b).count();
        assert!(
            distinct > 10,
            "only {distinct} distinct R values — likely solid black rectangles"
        );
    }

    #[test]
    fn test_render_two_page_first_page() {
        let reader = reader::open(&fixture("test_2page.pdf"), "application/pdf").unwrap();
        let r0 = reader.render_page(0, 0.5).unwrap();
        assert!(r0.width > 0 && r0.height > 0);
        assert!(!r0.data.is_empty());
    }

    #[test]
    fn test_render_two_page_second_page() {
        let reader = reader::open(&fixture("test_2page.pdf"), "application/pdf").unwrap();
        let r1 = reader.render_page(1, 0.5).unwrap();
        assert!(r1.width > 0 && r1.height > 0);
        assert!(!r1.data.is_empty());
    }

    // -----------------------------------------------------------------------
    // Error cases
    // -----------------------------------------------------------------------

    #[test]
    fn test_open_nonexistent() {
        let path = fixture("nonexistent.pdf");
        match reader::open(&path, "application/pdf") {
            Err(e) => assert!(format!("{e}").contains("failed to open"), "unexpected: {e}"),
            Ok(_) => panic!("expected error"),
        }
    }

    #[test]
    fn test_open_invalid_file() {
        let tmp = tempfile::NamedTempFile::new().unwrap();
        match reader::open(tmp.path(), "application/pdf") {
            Err(e) => {
                let msg = format!("{e}");
                assert!(
                    msg.contains("failed to open") || msg.contains("parse failed"),
                    "unexpected: {msg}"
                );
            }
            Ok(_) => panic!("expected error"),
        }
    }

    #[test]
    fn test_extract_text_out_of_range() {
        let reader = reader::open(&fixture("test_1page.pdf"), "application/pdf").unwrap();
        match reader.extract_text(99) {
            Err(e) => assert!(format!("{e}").contains("failed"), "unexpected: {e}"),
            Ok(_) => panic!("expected error"),
        }
    }

    #[test]
    fn test_render_out_of_range() {
        let reader = reader::open(&fixture("test_1page.pdf"), "application/pdf").unwrap();
        match reader.render_page(99, 1.0) {
            Err(e) => assert!(format!("{e}").contains("failed"), "unexpected: {e}"),
            Ok(_) => panic!("expected error"),
        }
    }

    #[test]
    fn test_open_wrong_mime_type() {
        // "application/zip" is not registered, so it should fail
        match reader::open(&fixture("test_1page.pdf"), "application/zip") {
            Err(e) => {
                let msg = format!("{e}");
                assert!(msg.contains("unsupported format"), "expected unsupported format, got: {msg}");
            }
            Ok(_) => panic!("expected error"),
        }
    }
}
