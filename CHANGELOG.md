# Changelog

## 0.2.0 (2026-07-03)

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
- **Scroll restoration**: Fixed LazyListState scroll position loss when returning from a document

### Fixes

- **Thumbnail loading**: Fixed race condition by switching from `LaunchedEffect(Unit)` to `snapshotFlow`
- **PDF scroll position**: Replaced `scrollToItem` with `initialFirstVisibleItemIndex` in LazyColumn guarded by `pageCount > 0`
- **EPUB progress**: Reading position now saved as progression percentage (1–100)

### Technical

- Bumped version to 0.2.0 (versionCode 2)

### Features

- **Document types**: PDF, EPUB, PKPass, CBZ/CBR, Images, Markdown notes
- **Encryption at rest**: AES-256-GCM per-file, master key wrapped with Argon2id + AES-256-KW
- **Password protection**: Optional password mode locks content even when phone is unlocked
- **Zero network**: No internet permission — documents never leave the device
- **PDF viewer**: MuPDF-based rendering with pinch-to-zoom, page indicator, scroll-to-last-page
- **EPUB reader**: Readium2-based reader with reflowable layout and locator persistence
- **Comic viewer**: CBZ/CBR support with grid thumbnail view and full-page reader
- **PKPass viewer**: Rendering for Apple Wallet passes
- **Image viewer**: Full-screen image display
- **Note editor**: Markdown notes with save/load
- **Library**: Grid/list views, favorites, recents, search
- **Collections & Tags**: Custom organization with drag-reorder
- **Import**: Share intents, SAF file picker, bulk import
- **Backup / Restore**: Single encrypted `.docwallet-backup` file via SAF
- **Encrypted database**: Room + SQLCipher for metadata
- **Session lock**: Auto-lock when app goes to background (ProcessLifecycleOwner)
- **Reading position**: Remembers last page/location per document across sessions

### Technical

- **Android SDK**: compileSdk 36, minSdk 26, targetSdk 36
- **UI**: Jetpack Compose (Material 3), navigation via Compose Navigation
- **PDF**: MuPDF (artifex/mupdf) for rendering
- **EPUB**: Readium2 (readium/kotlin-toolkit) with navigator and locator APIs
- **Encryption**: JCA providers (AES/GCM, Argon2id via Bouncy Castle)
- **Database**: Room with SQLCipher via sqlcipher/android-database-sqlcipher
- **Tests**: JUnit 4, MockK, Robolectric, Turbine (unit); Compose Test (instrumented)
- **Build**: Gradle 8.x with Kotlin DSL, version catalog, KSP
- **119 unit tests**, **46 instrumented tests**
