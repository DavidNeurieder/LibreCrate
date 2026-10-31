# Changelog

## 0.6.0 (2026-10-17)

### Security

- **Key-derivation parameters are validated before use**: Argon2id parameters read from a vault or backup (`params.toml`) are range-checked before key derivation — memory ≤ 64 MiB, iterations ≤ 10, parallelism ≤ 4, none zero. Hostile or corrupted values are rejected with `InvalidKdfParameters` instead of being handed to Argon2, so a tampered `params.toml` can no longer cause unbounded memory or CPU consumption. Normal desktop (19456/2/2) and Android (16384/3/2) parameters are unaffected.
- **Archive entries can no longer escape the extraction directory**: every path inside an imported ZIP/CBZ/EPUB/backup is validated through a single `format::path` entry point before any write. Absolute paths, `..` traversal, drive prefixes and symlink escapes are rejected with `InvalidArchivePath`; extraction is streamed and bounded per entry and in total.
- **Backup resource limits**: a backup's declared document count is cross-checked against its contents, and oversized or inconsistent archives fail with `ResourceLimit` instead of exhausting memory.
- **Secrets are zeroized on drop**: master keys and derived key material now live in zeroizing wrappers and are cleared when the session locks. The desktop GUI and Android drop the session key and close the database on lock, and key bytes handed across UniFFI are zeroed on the Kotlin side.
- **AES key-wrap is pinned to the RFC 3394 known-answer vectors** and rejects a wrong KEK.

### Backup format v2

- **New backups are written as format v2**, in which the manifest (including the document count) is authenticated as AES-GCM additional authenticated data: the manifest can no longer be modified without the payload failing to decrypt. Version-1 backups remain fully importable.

### Backup & thumbnails

- **Backup import now creates missing thumbnails**: after a backup is merged or restored, documents without a `<id>.thumb` blob get one generated from the restored file and encrypted with the local key. Existing thumbnails are never overwritten, and generation is best-effort so unsupported or corrupt documents cannot fail the import.
- **Thumbnails stored in a backup are preserved**: merging now copies the backup's `.thumb` blobs and re-encrypts them with the local key instead of discarding them, keeping covers that only the source platform could produce. Anything still missing is generated afterwards.
- **Fixed PDF thumbnails never being generated**: the core rendered PDFs successfully but then handed RGBA pixels to the JPEG encoder, which rejects them, so every PDF thumbnail was silently dropped. Pixels are now converted to RGB before encoding.
- **Fixed PDF thumbnails on Android**: PDF rendering wrote its scratch file to the process temp directory, which resolves to the non-writable `/data/local/tmp` on Android. Import, thumbnail backfill and regeneration now use the vault's own `files/` directory as scratch space.
- **PKPass thumbnails and more EPUB covers**: the core now extracts PKPass thumbnails/icons/logos, and resolves EPUB covers declared through EPUB 2 `<meta name="cover">`, percent-encoded hrefs, legacy item IDs, or a first-image fallback — matching the Android importer.

### Data-integrity regression suite

- **Frozen legacy fixtures**: the v1 and v2 containers produced by 0.5.6 are committed as immutable fixtures with a SHA-256 manifest and expected contents, and are replayed on every build so an old backup can never silently stop importing.
- **Encryption & tamper tests**: corrupted headers, truncated ciphertext, flipped bits, wrong passwords and tampered metadata all fail closed without panicking or leaking plaintext.
- **Backup/restore round trips**: a shared logical-snapshot helper (documents, collections, tags, favorites, extracted text) verifies that export → restore and cross-password merges preserve every field.
- **Migration & interruption**: schema migration is now safe and idempotent, and the backup merge is all-or-nothing. Fixed `migrate_schema` and made `reencrypt_files` fallible so a failed merge can no longer drop documents; a merge always re-encrypts with the local key and never clobbers existing documents.
- **Malformed-import tests**: hand-crafted zip bombs, duplicate entry names, bad manifests and truncated central directories are rejected cleanly; the ZIP writer now refuses duplicate entry names outright.
- **Fuzzing**: committed seed corpora for every fuzz target, a corpus-replay test in CI, and a nightly fuzzing workflow.
- **No silently-skipped tests**: CI fails if a data-integrity regression test is skipped.

### Dependencies

- rusqlite 0.31 → 0.40 / libsqlite3-sys 0.28 → 0.38 (**SQLCipher 4.14.0, SQLite 3.51.3**), plus the RustCrypto group, `zip` 9 and other minor crates. Dependabot is now restricted to security updates, backed by `cargo-audit` running with `deny: true` and GitHub security alerts.

### Android

- **Fixed the library showing "No documents yet" instead of asking for the password after the vault auto-locked**: the vault locks when the app is backgrounded, but the main activity is reused on return, so the library kept rendering against a closed database. The app now returns to the unlock screen (clearing the screens above the navigation root) whenever the session is locked.
- **Fixed imports being lost when the document picker locked the vault**: opening the system picker backgrounds the app and locks the vault, so the picked file was imported into a closed vault and dropped. Picker selections now wait in the pending-import queue and are imported once the vault is open again.
- **Compose UI tests pass on Android API 37**: bumped the AndroidX test train (`androidx.test` 1.7.0, Espresso 3.7.0), which no longer uses the `InputManager.getInstance()` API removed on newer releases.
- **Dropped the unused `mockk-android` test dependency**, removing a 16 KB page-size warning from the test APK.

## 0.5.6 (2026-09-16)

### Search

- **Fixed crash when searching documents containing multi-byte characters** (`byte index is not a char boundary`): search result snippets (`truncate_around_match`) now slice on character boundaries (`floor_char_boundary`/`ceil_char_boundary`) instead of raw byte offsets, so text containing non-ASCII characters (e.g. `’`) no longer panics in the vault-native core. Added regression tests for multibyte snippets and page matches.

### Android

- **Fixed crash on devices with 16 KB memory pages** (`program alignment (8192) cannot be smaller than system page size (16384)`): upgraded JNA from 5.14.0 to 5.17.0 in the Android build, whose `libjnidispatch.so` ships with a matching 16 KB load alignment alongside the already-aligned `libvault_native.so`.
- **Fixed "Confirm password" field not focusable by tap on the vault setup screen**: the setup screen is now scrollable and IME-aware, and the "Next" key explicitly moves focus from the Password field to the Confirm field. Previously the confirmation field shifted behind the on-screen keyboard, so direct taps were intercepted by the IME while keyboard navigation still worked.

## 0.5.5 (2026-08-19)

### Code quality

- **Fixed all clippy/compiler warnings across the workspace**: eliminated ~30 warnings including redundant closures, needless borrows, unnecessary casts, unused variables, and mutable-variable-does-not-need-to-be-mutable warnings. Added `#[allow]` annotations for intentionally-high-argument-count FFI functions and large enum variants.
- **Derived `Default` for `DocumentRow`**: replaced 26-line manual `impl Default` with `#[derive(Default)]`.
- **Modernized code style**: `.is_multiple_of()`, `.next_back()`, `std::io::Error::other()`, `if let Some` over `is_some()` + `unwrap()`.

### License

- **License changed to AGPL-3.0-only** across all source files and metadata.

## 0.5.4 (2026-08-05)

### F-Droid build fix

- **Fixed F-Droid build failure** (`pkg-config`/`fontconfig` not found): MuPDF's `system-fonts` feature — which pulls `font-kit` and needs a system `fontconfig` on Linux hosts — is now an opt-in feature enabled only by the desktop GUI. F-Droid's Android build compiles the core crate on a Linux host (to generate UniFFI bindings) and no longer requires `pkg-config` or `libfontconfig`. Desktop font rendering is unchanged; Android never used system fonts (the `font-kit` dependency is excluded for Android anyway).

### Backup compatibility

- **Phone and desktop now share one backup/import implementation**: the Android app and the desktop GUI both use the single Rust `export_vault_dirs` / `restore_backup_to_dirs` code path, so backups move between platforms with no format differences.
- **Fixed phone → desktop backup import** (`crypto error: key unwrap failed`): phone backups now include a `params.toml` recording the exact Argon2id parameters used to wrap the master key. Restoring overwrites any stale desktop `params.toml`, so the GUI always derives the correct key.
- **Phone no longer hardcodes KDF parameters**: `RustKeyManager` writes and reads `params.toml` (its own 16384/3/2 settings), while remaining compatible with desktop vaults (19456/2/2).
- **Round-trip tests**: phone↔GUI backup compatibility is now covered in Rust (`phone_roundtrip`), through the real GUI code path (`vault.rs`), and with Kotlin unit tests for the `params.toml` builder/parser.

### Backup import now merges

- **Import Backup merges instead of replacing** (desktop GUI and Android): existing documents, collections, and tags are preserved, and the backup's content is added on top. When a document already exists with different content, the local one is kept with a conflict flag and the backup's version is added as a copy.
- **The local passkey stays unchanged**: merging keeps the current vault open under its own passkey; only the backup's own passkey is needed to decrypt it, so backups created with a different passkey can be merged and are re-encrypted with the local key.
- **Merged documents are full-text searchable**: text content is carried over from the backup.
- **Fixed backup import failing on Android** (`Permission denied`): the temporary database is now written inside the app's own storage instead of the system temp directory.
- **Fresh installs still restore wholesale**: when there is no local vault (fresh install/reinstall), the backup is restored exactly as before and the app opens the unlock screen.
- **Merge tests**: cross-passkey merge covered in Rust (`test_merge_backup_to_vault_cross_password`, `test_merge_backup_into_existing_library_cross_password`) and with Android instrumented tests; the document-type library test now tolerates thumbnails.

## 0.5.2 (2026-08-01)

### Privacy

- **App permissions trimmed**: removed `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, and `FOREGROUND_SERVICE` from the APK. All four were inherited from library manifests (WorkManager/ExoPlayer) and never used — the APK now declares only AndroidX's inert `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.
- **Removed the unused WorkManager dependency**: `work-runtime` was declared in `app/build.gradle.kts` but never referenced in code.

### Store listing

- Rewrote the Play Store description in plain, non-technical language and added multi-device backup/restore.

## 0.5.1 (2026-08-01)

### Reproducible builds

- **Byte-identical builds between local and F-Droid**: `libvault_native.so` now builds identically on any machine. Verified section-by-section against F-Droid's build — the only remaining APK difference is the git revision embedded in `META-INF/version-control-info.textproto`.
- **No embedded build paths**: `--remap-path-prefix` in the Android Rust build strips all local source and Cargo registry paths from the binary.
- **Deterministic MuPDF layout**: fixed non-deterministic C source ordering in the bundled `mupdf-sys` wrapper (previously compiled in filesystem `readdir` order). The wrapper sources are now sorted, making the native library layout machine-independent.

### F-Droid

- **OpenSSL ranlib fix**: `RANLIB_aarch64_linux_android` set to the NDK's `llvm-ranlib` (fixes `make install_dev Error 127`).
- **Robust cargo discovery**: the Gradle build now probes more cargo locations before failing.
- **Rust pinned to 1.94.0** via `vault-native/rust-toolchain.toml`.

## 0.5.0 (2026-08-01)

### F-Droid compliance

- **Dropped CBR (RAR) comic support**: Removed the non-free `junrar` dependency (UnRAR license, rejected by the F-Droid license scanner). CBZ comics are unaffected — they use the JDK's built-in zip support.
- **Removed GitHub Packages publishing**: Deleted the `maven-publish` plugin and `publishing {}` block from `vault-native-android/build.gradle.kts`, removing the `maven.pkg.github.com` repo rejected by the F-Droid scanner.
- **F-Droid build dependencies fixed**: `F-DROID.md` recipe now installs `build-essential clang libclang-dev perl pkg-config curl` (needed for bindgen, the MuPDF C build, and vendored OpenSSL); dropped the unnecessary OpenJDK 17 lines.

### Changes

- Comics: CBZ only (`application/vnd.comicbook+zip`); CBR MIME type and code paths removed from the importer, library, and viewer.
- README and AppStream metadata updated to reflect CBZ-only comic support.

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
