# LibreCrate

**Version 0.3.0**

Encrypted document vault for Android — stores, views, organizes, and searches PDFs, EPUBs, PKPass files, comic archives (CBZ/CBR), images, and personal notes. All documents are encrypted at rest with optional password protection and zero network access.

## Features

- **Six document types**: PDF, EPUB, PKPass (Apple Wallet passes), CBZ/CBR comics, Images, and Markdown notes
- **Encryption at rest**: AES-256-GCM per-file encryption; master key wrapped via Argon2id + AES-256 Key Wrap (RFC 3394)
- **Optional password**: Even with the phone unlocked, content can't be read without the password
- **No network**: Zero internet permission — your documents never leave the device
- **Library view**: Grid/list, type filter, favorites, sort options, and reading-progress indicators
- **Reading position**: Remembers last page for PDFs and comics, last location for EPUBs; shows "Page X of Y" / "% read" on cards
- **Full-text search**: FTS5 search across title, author, description, and extracted document text, with highlighted snippets
- **Import**: Share intents (single or multiple) and SAF file picker (bulk import)
- **Backup**: Single encrypted `.librecrate-backup` file via SAF, verified by your password
- **F-Droid only**: No Google Play Services, Firebase, Crashlytics, or AdMob

### Viewers

- **PDF** (MuPDF): paginated scroll, pinch-to-zoom/pan, fit modes (width/page/actual), night mode, last-page memory
- **EPUB** (Readium 2): reflowable reader, table of contents, reader settings (font family/size, line height, margins), reading progress, rename/favorite/delete
- **Comics** (CBZ/CBR): thumbnail grid + full-page reader, zoom/pan, last-page memory
- **Apple Wallet pass** (PKPass): pass fields, themed colors, logo/strip images, barcode display (ZXing)
- **Images**: full-screen Coil viewer with zoom/pan and an info overlay
- **Notes**: Markdown editor with live preview, formatting toolbar, word/character count, and debounced autosave

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

## Known Limitations (current build)

- **No biometric unlock** — unlock is by password only.
- **No idle auto-lock** — the vault locks only when the app is backgrounded.
- **Collections & Tags** exist internally but are not reachable from the current UI and cannot be assigned to documents.
- **No in-document search** — search covers the whole library, not find-within a PDF/EPUB.
- **Barcodes are display-only** — passes show barcodes; there is no camera scanning.

## Screenshots

<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="180" alt="Screenshot 1"> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="180" alt="Screenshot 2"> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="180" alt="Screenshot 3"> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="180" alt="Screenshot 4"> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" width="180" alt="Screenshot 5"> 

## Building

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

## Testing

```sh
# Unit tests (JUnit 4 + MockK + Robolectric + Turbine)
./gradlew testDebugUnitTest

# Instrumented tests (AndroidJUnit4 + Compose Test)
./gradlew connectedDebugAndroidTest
```

- 119+ unit tests
- 46+ instrumented tests

## AllowedAPKSigningKeys to verify Releases:

SHA-256: 11f860ee7ac19b8d992a52bf114a491f9b8b598091b7a5e94ce775b50e6e69fa

## License

GPL-3.0-only. See [LICENSE](LICENSE) for details.
