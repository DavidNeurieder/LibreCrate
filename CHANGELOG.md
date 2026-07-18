# Changelog

## 0.3.0 (2026-07-18)

### Features

- Initial release of the new version

## 0.2.0 (2026-07-08)

### Features

- **Full-text search**: FTS5-powered search in library with highlighted results, page-number awareness, and tap-to-navigate from search results to viewer
- **In-document search**: `searchInDocument()` API in vault-core for per-document FTS matching with snippet extraction
- **Modular architecture**: Extracted vault-core, vault-reader, reader-pdf, reader-epub, vault-cli modules from monolithic app
- **Backup progress**: Progress indicator shown during backup creation and restore
- **Import backup with different passkey**: Backup files encrypted with a different passkey can now be imported

### UI/UX

- **Redesigned library**: Search moves into TopAppBar; filter row condensed to Sort + Type dropdowns + Favorites chip
- **Default sort**: Changed to "Recently opened"; removed "Largest first" and "By type" options
- **Reading progress**: Document cards now show "Page X of Y" for PDFs and "% read" for EPUBs
- **Continue reading**: Merged into main list; shows last-opened timestamp
- **Edit removed**: Edit button removed from main screen DocumentCard (edit in viewer only)
- **Fullscreen removed**: Fullscreen mode removed from all viewers
- **Unified viewer headers**: Title in TopAppBar, type-specific buttons visible, all other actions in overflow menu
- **EPUB rename**: Added rename dialog to EPUB reader
- **Collections & Tags**: Entry points removed from Settings (functionality kept internally for future use)
- **Dark splash screen**: Splash screen now respects system dark theme
- **Disable password removed**: "Disable password" option removed from Settings for improved security

### Fixes

- **Thumbnail loading**: Fixed race condition by switching from `LaunchedEffect(Unit)` to `snapshotFlow`
- **PDF scroll position**: Replaced `scrollToItem` with `initialFirstVisibleItemIndex` in LazyColumn guarded by `pageCount > 0`
- **EPUB progress**: Reading position now saved as progression percentage (1–100)
- **Scroll restoration**: Fixed LazyListState scroll position loss when returning from a document
- **Backup crash**: Fixed crash during backup creation
- **Startup crash**: Fixed crash on app startup
- **Re-encryption**: Fixed document re-encryption flow
- **Previews**: Fixed document previews in backup
- **Password lifecycle**: Fixed password lifecycle handling during backup operations
- **Metadata leakage**: Fixed metadata exposure in logs
- **Password leakage**: Fixed password exposure in memory
- **Performance**: Various performance improvements

### Technical

- Bumped version to 0.2.0 (versionCode 2)
- Refactored monolithic app into 5 library modules + CLI (vault-core, vault-reader, reader-pdf, reader-epub, vault-cli)
- Moved cryptography and database logic into shared libraries
- Improved instrumented test coverage
- **120 unit tests**, **66 instrumented tests**
