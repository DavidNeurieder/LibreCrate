# Changelog

## 0.1.0 (2026-06-05)

Initial release.

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
