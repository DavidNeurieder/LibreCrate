# Changelog

## 0.4.0 (2026-07-26)

### Features

- **CLI interactive REPL**: Launch `librecrate` with no arguments for a persistent shell — enter vault and password once, then run commands without re-typing credentials. Supports readline (history, line editing, ctrl-c/ctrl-d).
- **CLI one-shot mode**: All commands available as one-shot invocations (`librecrate <cmd> <vault_dir> -p <password>`) for scripting and automation.
- **CLI commands**: `init`, `import`, `list`, `open`, `delete`, `search`, `backup`, `restore` — full document lifecycle from the terminal.
- **Desktop GUI (Iced)**: Native desktop application with vault creation, unlock, document library, multi-file import, collections, tags, backup/restore, and password change.
- **Shared vault operations module**: `vault_ops` in core provides `VaultSnapshot`, `export_vault_dir`, `merge_vault_dir`, and `restore_backup_to_dir` — shared business logic for CLI, GUI, and Android.
- **Cross-platform backup compatibility**: Backups created on Android can be restored on desktop (CLI/GUI) and vice versa.
- **Multi-file import (GUI)**: Import multiple documents at once via file picker.
- **Collections & Tags (GUI)**: Organize documents into collections and assign tags from the desktop GUI.
- **Backup password verification**: CLI `backup` command verifies the password (derives master key) before encrypting, preventing silent corruption.

### Architecture

- **vault_ops module** (`core/src/vault_ops.rs`): Shared business logic for backup export, merge, and full restore — used by both CLI and GUI.
- **Session struct** (`cli/src/session.rs`): Caches vault dir, password, and pre-derived master key for the REPL lifetime, avoiding ~1s Argon2id re-derivation per command.
- **CLI refactored to 8 commands**: Minimal interface matching GUI patterns (vault directory + password). Deleted 10 old files (document.rs, vault.rs, create.rs, backup_export.rs, merge.rs, export.rs, inspect.rs, crypto.rs, bench.rs, password.rs).
- **Key file naming normalized**: `master_key` → `wrapped_master_key` everywhere with fallback reads for backwards compatibility.
- **DB filename normalized**: `vault.db` → `librecrate.db` across CLI, GUI, and vault_ops.

### Tests

- **226+ tests passing**: 31 CLI integration tests (including 5 REPL tests), 139 GUI unit tests, 51 core unit tests, 5 e2e tests.
- **CLI integration tests**: Subprocess-based tests covering init, import, list, search, backup/restore, open, delete, errors, and full workflow.
- **REPL tests**: Piped-stdin tests verifying list, help, import+list, search, and wrong-password handling in interactive mode.

## 0.3.0 (2026-07-22)

### Features

- **PDF viewer rewritten**: Dynamic render scale for pixel-perfect page width; `BoxWithConstraints` replaces `displayMetrics.widthPixels` for accurate layout (handles insets, multi-window, scaffold padding)
- **Pinch-to-zoom**: Two-finger pinch zoom in PDF viewer (1x–5x), vertical scroll at zoomed-in levels
- **Rust native library auto-build**: Gradle now builds Rust `vault-native` library automatically — generates UniFFI Kotlin bindings, compiles for Android, and packages the `.so` into the APK. No pre-committed build artifacts needed.

### F-Droid

- **F-Droid build recipe documented**: `F-DROID.md` covers Rust toolchain, NDK config, and full recipe
- **Rust version pinned**: `vault-native/rust-toolchain.toml` pins Rust 1.78.0 for reproducible builds
- **NDK path hardcoding removed**: `.cargo/config.toml` deleted; NDK discovered automatically via `ANDROID_NDK_HOME` or `android.ndkDirectory`

### Fixes

- **Backup import crash**: Fixed crash when importing backup into existing database — `reopenDatabase()` no longer closes the old database unnecessarily
- **Backup import database corruption**: Fixed "file is not a database" error after import by deferring database recreation and hardening WAL/SHM cleanup
- **Startup crash after failed import**: `initializeDatabase()` now force-opens the database inside a try/catch block, catching open errors early instead of crashing on the first DAO call
- **SettingsScreenTest reliability**: Fixed duplicate `SectionHeader("Security")` ambiguity
- **EpubReaderInstrumentedTest robustness**: Wrapped `onActivity` in try/catch to handle flaky composition
- **PIN lock default**: Changed to disabled (`false`) — no longer enabled by default on fresh install

### Technical

- `renderPageBitmap()` accepts `targetWidthPx: Int?` instead of fixed `scale: Float`; `PdfViewer` passes `maxWidth.toPx()` from `BoxWithConstraints`
- Gesture handler simplified: 2-finger zoom, 1-finger scroll only (no horizontal pan, no double-tap)
- `MAX_CACHED_PAGES` reduced from 20 to 4
- `*.so` files removed from git; generated bindings removed from git (both now built by Gradle)
- F-Droid lint issue (`NewApi` in UniFFI `Cleaner`) suppressed
- `scripts/build_native.sh` simplified and fixed — requires `ANDROID_NDK_HOME`
- Added full-branch logging to `BackupManager.restoreContents()` (Branch A/B/C selection)
- Added `backupUninstallReinstallImportCloseReopen` instrumented test covering full backup → wipe → restore → close → reopen cycle
- Bumped to versionCode 3

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
