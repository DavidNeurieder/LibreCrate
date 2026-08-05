# LibreCrate — offline document vault

[![Get it on GitHub](https://img.shields.io/badge/Get_it_on_GitHub-181717?style=for-the-badge&logo=github)](https://github.com/DavidNeurieder/LibreCrate/releases)

**Version 0.5.4** · 

Encrypted document vault for Android, Linux, macOS, and Windows — stores, views, organizes, and searches PDFs, EPUBs, PKPass files, comic archives (CBZ), images, and personal notes. All documents are encrypted at rest with optional password protection and zero network access.

## Platforms

| Platform | UI | Status |
|----------|-----|--------|
| Android | Jetpack Compose | Stable |
| Desktop (Linux/macOS/Windows) | Iced (Rust) | Stable |
| CLI (Linux/macOS/Windows) | Terminal with interactive REPL | Stable |
| Android Kotlin → Rust bridge | UniFFI | Stable |

## Features

- **Six document types**: PDF, EPUB, PKPass (Apple Wallet passes), CBZ comics, Images, and Markdown notes
- **Encryption at rest**: AES-256-GCM per-file encryption; master key wrapped via Argon2id + AES-256 Key Wrap (RFC 3394)
- **Optional password**: Even with the device unlocked, content can't be read without the password
- **No network**: Zero internet permission — your documents never leave the device
- **Library view**: Grid/list, type filter, favorites, sort options, and reading-progress indicators
- **Reading position**: Remembers last page for PDFs and comics, last location for EPUBs; shows "Page X of Y" / "% read" on cards
- **Full-text search**: FTS5 search across title, author, description, and extracted document text, with highlighted snippets
- **Import**: Share intents (single or multiple) and SAF file picker (bulk import)
- **Backup**: Single encrypted `.librecrate-backup` file via SAF, verified by your password
- **Cross-platform backup**: Backups created on Android can be restored on desktop and vice versa
- **F-Droid only**: No Google Play Services, Firebase, Crashlytics, or AdMob

### Desktop GUI

- **Iced-based native UI** with vault creation, unlock, document library, and settings
- **Multi-file import**: Drag-and-drop or file picker for bulk document import
- **Collections & Tags**: Organize documents into collections and tag them
- **Backup/Restore**: Export and import encrypted backups from the GUI
- **Password change**: Change vault password from the settings screen
- **Settings**: Theme selection, vault info, and security options

### CLI

- **Interactive REPL**: Launch `librecrate` with no arguments for a persistent shell session — enter vault and password once, then run commands
- **One-shot mode**: `librecrate <command> <vault_dir> -p <password>` for scripting and automation
- **Commands**: `init`, `import`, `list`, `open`, `delete`, `search`, `backup`, `restore`
- **Readline support**: Command history (up/down arrows), line editing, ctrl-c/ctrl-d
- **Tab completion**: Command names auto-complete

### Android Viewers

- **PDF** (MuPDF): paginated scroll, pinch-to-zoom/pan, fit modes (width/page/actual), night mode, last-page memory
- **EPUB** (Readium 2): reflowable reader, table of contents, reader settings (font family/size, line height, margins), reading progress, rename/favorite/delete
- **Comics** (CBZ): thumbnail grid + full-page reader, zoom/pan, last-page memory
- **Apple Wallet pass** (PKPass): pass fields, themed colors, logo/strip images, barcode display (ZXing)
- **Images**: full-screen Coil viewer with zoom/pan and an info overlay
- **Notes**: Markdown editor with live preview, formatting toolbar, word/character count, and debounced autosave

## Built with

| Library | Purpose |
|---------|---------|
| [Jetpack Compose](https://developer.android.com/jetpack/compose) | Android UI framework |
| [Iced](https://iced.rs/) | Desktop GUI framework (Rust) |
| [Clap](https://docs.rs/clap) | CLI argument parsing |
| [Rustyline](https://docs.rs/rustyline) | Interactive REPL (line editing, history) |
| [Coil](https://coil-kt.github.io/coil/) | Image loading (Android) |
| [MuPDF](https://mupdf.com/) | PDF rendering |
| [Readium](https://readium.org/) | EPUB reader toolkit |
| [ZXing](https://github.com/zxing/zxing) | Barcode display |
| [CommonMark](https://github.com/commonmark/commonmark-java) | Markdown parsing |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) | Comic archive (CBZ) extraction |
| [Argon2id](https://en.wikipedia.org/wiki/Argon2) + [AES-256-GCM](https://en.wikipedia.org/wiki/Galois/Counter_Mode) (Rust) | Encryption at rest |
| [SQLCipher](https://www.zetetic.net/sqlcipher/) (Rust via `rusqlite`) | Encrypted database |
| [UniFFI](https://mozilla.github.io/uniffi-rs/) | Kotlin–Rust bridge |

## Security

| Layer | Mechanism |
|-------|-----------|
| Key derivation | Argon2id (19 MiB memory, 2 iterations, 2 parallelism) |
| Key wrapping | AES-256 Key Wrap (RFC 3394) |
| File encryption | AES-256-GCM (12-byte IV, 128-bit tag) |
| Password mode | Master key wrapped with password-derived key; device key deleted |
| Device-key mode | Master key wrapped with per-device AES key (no password) |
| Lock | Clears in-memory master key when app is backgrounded; requires password re-entry |
| Backup | Encrypted Zip bundle with wrapped master key + DB + files |

## Project Structure

```
librecrate/
├── vault-native/
│   ├── core/          vault-native: shared Rust library (crypto, DB, merge, backup)
│   ├── cli/           librecrate CLI (interactive REPL + one-shot commands)
│   └── gui/           librecrate-gui (Iced desktop application)
├── app/               Android application
├── gradle/            Gradle build scripts
└── fastlane/          F-Droid metadata and screenshots
```

## Building

### Android

```sh
git clone https://github.com/DavidNeurieder/librecrate
cd librecrate
./gradlew assembleDebug
```

APK at `app/build/outputs/apk/debug/app-debug.apk`.

Requires Android SDK 36 (`compileSdk`). Set `ANDROID_HOME` or create `local.properties`:

```
sdk.dir=/path/to/Android/Sdk
```

The Rust native library (`vault-native`) is auto-built by Gradle via UniFFI — no manual steps needed. Install the Rust toolchain if missing:

```sh
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
```

### Desktop CLI and GUI

```sh
cd vault-native

# Build CLI
cargo build --release --package librecrate
# → target/release/librecrate

# Build GUI
cargo build --release --package librecrate-gui
# → target/release/librecrate-gui
```

## Testing

### Android

```sh
# Unit tests (JUnit 4 + MockK + Robolectric + Turbine)
./gradlew testDebugUnitTest

# Instrumented tests (AndroidJUnit4 + Compose Test)
./gradlew connectedDebugAndroidTest
```

- 120+ unit tests
- 47+ instrumented tests

### Desktop (CLI + GUI + Core)

```sh
cd vault-native
cargo test --workspace
```

- 226+ tests (CLI integration, GUI unit, core unit, e2e)

## CLI Usage

```sh
# Launch interactive REPL
librecrate
# Vault: ~/my-vault
# Password: ********
# librecrate> list
# librecrate> import ~/file.pdf
# librecrate> search algorithm
# librecrate> backup -o ~/backup.librecrate-backup
# librecrate> quit

# One-shot mode (for scripts/automation)
librecrate init ~/my-vault -p "mypassword" --from ~/Documents
librecrate import ~/my-vault -p "mypassword" ~/file.pdf ~/file.epub
librecrate list ~/my-vault -p "mypassword"
librecrate search ~/my-vault -p "mypassword" "search term"
librecrate backup ~/my-vault -p "mypassword" -o ~/backup.librecrate-backup
librecrate restore ~/my-vault -p "mypassword" ~/backup.librecrate-backup
```

## Known Limitations (current build)

- **No idle auto-lock** — the vault locks only when the app is closed.
- **No in-document search** — search covers the whole library, not find-within a PDF/EPUB.
- **Barcodes are display-only** — passes show barcodes; there is no camera scanning.
- **FTS after merge restore** — full-text search may not work immediately after restoring a backup via merge.

## Screenshots

<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="180" alt="Screenshot 1"> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="180" alt="Screenshot 2"> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="180" alt="Screenshot 3"> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="180" alt="Screenshot 4"> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" width="180" alt="Screenshot 5"> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/6.png" width="180" alt="Screenshot 6"> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/7.png" width="180" alt="Screenshot 7"> 

## AllowedAPKSigningKeys to verify Releases:

SHA-256: 11f860ee7ac19b8d992a52bf114a491f9b8b598091b7a5e94ce775b50e6e69fa

## License

AGPL-3.0-only. See [LICENSE](LICENSE) for details.
