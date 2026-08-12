#include "internal.h"

/* Chapter-based document navigation. Reflowable documents (e.g. EPUB) are laid
 * out chapter by chapter, so these functions let callers count and load pages
 * without forcing the whole document to be laid out up front. */

int mupdf_count_chapters(fz_context *ctx, fz_document *doc, mupdf_error_t **errptr)
{
    TRY_CATCH(int, 0, fz_count_chapters(ctx, doc));
}

int mupdf_count_chapter_pages(fz_context *ctx, fz_document *doc, int chapter, mupdf_error_t **errptr)
{
    TRY_CATCH(int, 0, fz_count_chapter_pages(ctx, doc, chapter));
}

fz_page *mupdf_load_chapter_page(fz_context *ctx, fz_document *doc, int chapter, int page, mupdf_error_t **errptr)
{
    TRY_CATCH(fz_page*, NULL, fz_load_chapter_page(ctx, doc, chapter, page));
}

fz_location mupdf_location_from_page_number(fz_context *ctx, fz_document *doc, int number, mupdf_error_t **errptr)
{
    fz_location loc = { 0, 0 };
    TRY_CATCH(fz_location, loc, fz_location_from_page_number(ctx, doc, number));
}

int mupdf_page_number_from_location(fz_context *ctx, fz_document *doc, int chapter, int page, mupdf_error_t **errptr)
{
    fz_location loc;
    loc.chapter = chapter;
    loc.page = page;
    TRY_CATCH(int, 0, fz_page_number_from_location(ctx, doc, loc));
}
