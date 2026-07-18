# LibreCrate — Build Plan

A document vault + private diary for F-Droid. Keep PDFs, ebooks, pkpass files, comic archives (CBZ/CBR),
document images, and personal notes safe, viewable, organized, and searchable — all encrypted at rest,
zero network access.

---

## Technology Stack

| Layer | Choice | Rationale |
|-------|--------|-----------|
| Language | Kotlin | Native Android, best F-Droid compatibility |
| UI | Jetpack Compose + Material 3 | Modern declarative UI, first-class F-Droid support |
| Architecture | Single-activity, unidirectional data flow (MVI-ish) | Matches Compose patterns, testable |
| Storage | SQLCipher (AES-256-CBC) + password-wrapped master key | Full at-rest encryption, protected by user password even when phone is unlocked |
| Password hashing | Argon2id (via libsodium or Bouncy Castle) | Memory-hard KDF, brute-force resistant |
| ORM | Room with FTS5 | Encrypted FTS5 full-text search out of the box |
| DI | Manual (factory pattern or Koin) | Avoid Hilt annotation processing complexity; F-Droid compatible |
| PDF | PdfBox-Android (Apache-2.0) | Render to bitmap, extract text, handle encrypted PDFs |
| EPUB | Readium Kotlin Toolkit (BSD-3-Clause) | Mature EPUB 2/3 support, OPDS, LCP |
| PKPass | jpasskit (Apache-2.0) | Parse .pkpass files, extract fields and images |
| Barcode | ML Kit Barcode Scanning on-device (Apache-2.0) | QR, Aztec, DataMatrix, PDF417, 1D codes |
| Image loading | Coil (Apache-2.0) | Compose-native image loader |
| CBZ/CBR | unrar-java (Apache-2.0) + ZipFile | Extract page images from comic archives |
| Markdown | commonmark-java (BSD-2-Clause) | Render note previews, parse markdown to styled text |
| Navigation | Compose Navigation | Official, type-safe |
| Permissions | No internet permission | F-Droid anti-feature avoidance, verifiable privacy |
| Build | Gradle with version catalogs | Standard Android build |

**Why not Flutter:** This is a pure Android app for F-Droid. Kotlin + Compose gives
better native PDF rendering integration, smaller APK size, and no Flutter engine overhead.

**Why not Hilt:** Annotation processing adds build complexity and APK size. Manual DI
or Koin is simpler for a solo project and avoids any non-FOSS dependency concerns.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      LibreCrate App                             │
├─────────────────────────────────────────────────────────────┤
│  ┌──────────────────┐    ┌──────────────────────────────┐   │
│  │  Unlock Screen   │    │   Main App (after unlock)    │   │
│  │  (password entry) │───►│  ┌──────────┐ ┌──────────┐  │   │
│  └──────────────────┘    │  │ Library  │ │ Viewer   │  │   │
│                          │  │ Screen   │ │ Screen   │  │   │
│                          │  └────┬─────┘ └────┬─────┘  │   │
│                          │       │            │         │   │
│                          │  ┌────▼────────────▼─────┐   │   │
│                          │  │  ViewModels / State   │   │   │
│                          │  └──────────┬────────────┘   │   │
├──────────────────────────┼─────────────┼────────────────┼───┤
│  Domain Layer            │             │                │   │
│  ┌───────────────────────▼─────────────▼────────────────┐│   │
│  │              Repository Layer                        ││   │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌────────┐  ││   │
│  │  │Document  │ │  Import  │ │  Search  │ │Export  │  ││   │
│  │  │Repo      │ │  Repo    │ │  Repo    │ │Repo    │  ││   │
│  │  └────┬─────┘ └────┬─────┘ └────┬─────┘ └───┬────┘  ││   │
│  └───────┼────────────┼────────────┼────────────┼───────┘│   │
├──────────┼────────────┼────────────┼────────────┼────────┤   │
│  Data    │            │            │            │        │   │
│  Layer   │            │            │            │        │   │
│  ┌───────▼────────────▼────────────▼────────────▼───────┐│   │
│  │              Encrypted Room Database                 ││   │
│  │  ┌──────────────────┐  ┌──────────────────────────┐  ││   │
│  │  │  documents table │  │  documents_fts (FTS5)    │  ││   │
│  │  │  tags table      │  │  tags to documents join  │  ││   │
│  │  │  collections     │  │                          │  ││   │
│  │  └──────────────────┘  └──────────────────────────┘  ││   │
│  └──────────────────────┬───────────────────────────────┘│   │
│                         │                                 │   │
│  ┌──────────────────────▼───────────────────────────────┐│   │
│  │  Encryption Layer                                    ││   │
│  │  ┌──────────────┐  ┌────────────┐  ┌──────────────┐ ││   │
│  │  │  Password    │  │  Argon2id  │  │  Master Key  │ ││   │
│  │  │  Entry       │──►│  KDF       │──►│  Wrapper     │ ││   │
│  │  └──────────────┘  └────────────┘  └──────┬───────┘ ││   │
│  │                                           │          ││   │
│  │  ┌────────────────────────────────────────▼────────┐ ││   │
│  │  │  SQLCipher (encrypted DB)   +   AES-256-GCM     │ ││   │
│  │  │  (per-file encrypted blobs in app-private dir)  │ ││   │
│  │  └─────────────────────────────────────────────────┘ ││   │
│  └──────────────────────────────────────────────────────┘│   │
└──────────────────────────────────────────────────────────┘───┘
```

### Document import flow

```
[Share intent / SAF picker / Scan]
        │
        ▼
[ImportService]
  ├── Decrypt if PKPass → jpasskit
  ├── Extract metadata (title, author, page count, etc.)
  ├── Generate thumbnail
  ├── If PDF/EPUB → extract text → index in FTS5
  ├── Copy encrypted blob to app-private storage
  └── Insert record into encrypted Room DB
        │
        ▼
[User sees document in Library]
```

### Document viewing flow

```
[User taps document in Library]
        │
        ▼
[ViewerScreen launches]
  ├── Check document type from DB record
  ├── For PDF → PdfBox-Android renders pages to bitmap
  │       └── If barcode detected → show barcode toolbar button
  │       └── Full-text search highlights within document
  ├── For EPUB → Readium reader opens
  ├── For PKPass → jpasskit parses, custom Compose renderer
  │       └── Barcode rendering for passes (store cards, etc.)
  ├── For CBZ/CBR → decompress archive, render page images with Coil
  │       └── Page-turn navigation, pinch-to-zoom
  ├── For Notes (text/markdown) → NoteEditor screen
  │       └── Edit markdown, preview rendered, save encrypts blob
  └── For Images → Coil display with zoom/pan
```

---

## Data Model

### `documents` table (encrypted via SQLCipher)

| Column | Type | Notes |
|--------|------|-------|
| id | TEXT (UUID) | Primary key |
| title | TEXT | Extracted from document or user-set |
| file_name | TEXT | Original filename |
| mime_type | TEXT | application/pdf, application/epub+zip, application/vnd.apple.pkpass, application/vnd.comicbook+zip (CBZ), application/x-cbr (CBR), text/markdown (notes), image/* |
| file_path | TEXT | Relative path inside app-private storage |
| file_size | INTEGER | Bytes |
| page_count | INTEGER | For PDFs |
| author | TEXT | Extracted from metadata |
| description | TEXT | User-added notes |
| thumbnail_path | TEXT | Generated thumbnail bitmap path |
| imported_at | INTEGER | Unix epoch millis |
| last_opened_at | INTEGER | Unix epoch millis |
| is_favorite | INTEGER | 0 or 1 |
| collection_id | TEXT | Foreign key to collections, nullable |
| encryption_iv | BLOB | Per-file encryption IV (for file-level encryption) |
| text_content | TEXT | Extracted full text for FTS indexing (PDFs, EPUBs) |
| barcode_format | TEXT | QR, AZTEC, PDF417, etc. (for documents with barcodes) |
| barcode_value | TEXT | Decoded barcode content |

### `documents_fts` FTS5 virtual table

```sql
CREATE VIRTUAL TABLE documents_fts USING fts5(
    title, author, description, text_content,
    content='documents',
    content_rowid='rowid',
    tokenize='porter unicode61'
);
```

### `tags` table

| Column | Type |
|--------|------|
| id | TEXT (UUID) |
| name | TEXT UNIQUE |
| color | INTEGER (ARGB hex) |

### `document_tags` join table

| Column | Type |
|--------|------|
| document_id | TEXT FK → documents |
| tag_id | TEXT FK → tags |

### `collections` table

| Column | Type |
|--------|------|
| id | TEXT (UUID) |
| name | TEXT |
| icon | TEXT (Material icon name) |
| sort_order | INTEGER |
| parent_id | TEXT FK → collections (nullable, for nesting) |

---

## Encryption Architecture

Password protection is **optional** — off by default, can be enabled in Settings at any time.
The key hierarchy supports both modes with no re-encryption needed when toggling.

### Key hierarchy

```
                    ┌──────────────────────────────┐
                    │  Wrapping Key                │
                    │  (auto-generated device key  │
                    │   OR user password-derived   │
                    │   key via Argon2id)          │
                    └──────────────┬───────────────┘
                                   │  AES-256-KW
                                   ▼
                    ┌──────────────────────────────┐
                    │  Wrapped Master Key blob     │
                    │  (stored in app-private dir) │
                    └──────────────┬───────────────┘
                                   │  Unwrap on launch
                                   ▼
                    Master Key (256-bit, in-memory)
                                   │
                         ┌─────────┼──────────────┐
                         ▼         ▼              ▼
                    SQLCipher   Per-file      FTS index
                    (DB key)    AES-256-GCM   (via Room+SQLCipher)
```

### Flow

**First launch (no password — transparent)**
1. Generate random 256-bit master key
2. Generate random 256-bit device wrapping key (stored in app-private dir, not Keystore)
3. Wrap master key: `AES-256-KW(device_key, master_key)`
4. Store wrapped blob in app-private storage
5. On every launch: read device key → unwrap master key → open SQLCipher
6. User sees library immediately — no unlock screen

**Password enable (via Settings → "Set password")**
1. User enters new password × 2
2. Generate random 16-byte salt
3. Derive user key: `Argon2id(password, salt, 3s delay, 2 threads)`
4. Read master key by unwrapping with device key
5. Re-wrap master key with user key: `AES-256-KW(user_key, master_key)`
6. Store new wrapped blob + salt; delete device key from storage
7. Master key held in-memory for current session

**Password change**
1. User enters old password → Argon2id → unwrap master key
2. User enters new password × 2 → new salt → new user key
3. Re-wrap same master key with new user key
4. Store new wrapped blob + new salt
5. No re-encryption needed — master key is unchanged

**Password disable (via Settings → "Remove password")**
1. User enters current password → verify
2. Generate new random device wrapping key
3. Re-wrap master key with device key
4. Store new wrapped blob; delete salt
5. Password is removed — app opens transparently again

**Launch with password set**
1. App starts → show unlock screen (no app content visible)
2. User enters password
3. Derive user key: `Argon2id(password, salt, ...)`
4. Attempt to unwrap master key
5. If success → open SQLCipher → show library
6. If failure → show "Wrong password" error
7. Master key held in `EncryptionManager` in-memory field
8. On app backgrounded → clear master key from memory → app locks

### Biometric unlock (optional enhancement)

Cache the user key in Android Keystore (protected by biometric prompt) so
the user can unlock with fingerprint/face instead of typing password each time.
Falls back to password if biometrics aren't available.

### Security properties

- **Password off**: Security matches standard file-based encryption — device
  screen lock protects the app data. Convenient for casual users.
- **Password on**: Even with the phone unlocked, an attacker cannot read app
  data without the LibreCrate password. The master key is only held in the app
  process memory while in the foreground.
- **Brute-force resistance**: Argon2id parameters target ~3s verification on
  a modern phone, making offline dictionary attacks expensive.
- **No key in Keystore**: Avoids Keystore invalidation issues entirely.
- **Zero network**: Password is never sent anywhere, no "reset password" flow.
  A forgotten password means permanent data loss — no recovery possible.

---

## Library Screen Organization

### Built-in collections (auto-populated)

| Collection | Contents |
|------------|----------|
| Recents | Documents opened in last 7 days |
| Favorites | User-starred documents |
| PDFs | All PDFs |
| Books | All EPUBs |
| Comics | All CBZ/CBR archives |
| Passes | All PKPass files |
| Images | All document images |
| Notes | All markdown notes (created in-app) |

### User organization

- **Tags**: Free-form tags (e.g. "tax-2026", "travel", "insurance")
- **Custom collections**: User-created groups (e.g. "Trip to Japan", "Car documents")
- **Nested collections**: Sub-collections within collections

### Creating notes

- **FAB** in library screen → "New Note" opens blank editor
- **Date auto-stamped** as title if empty (diary-style: "June 3, 2026")
- **Auto-saved** as user types (debounced, encrypted on each save)

### Sort options

- Name (A-Z, Z-A)
- Import date (newest/oldest)
- Last opened (newest/oldest)
- File size
- Type

### Search

- **Quick search**: Type-ahead search bar in top app bar
- **Full-text search**: Search across all indexed text content via FTS5
- **Filter chips**: By type (PDF/EPUB/CBZ/Notes/PKPass/Image), by tag, by collection, by date range
- **Search results**: Sorted by relevance (BM25) + recency boost
- **In-document search**: When viewing a PDF/EPUB, highlight matches within the document

### Settings

| Section | Items |
|---------|-------|
| **Security** | Enable password, change password, disable password, biometric unlock toggle |
| **Defaults** | Default sort order (name/date/size/type), default import collection |
| **Appearance** | Theme (system / light / dark) |
| **Backup** | Export encrypted backup, import backup (see Backup & Restore) |
| **Data** | Clear all data (factory reset), app storage usage |
| **About** | App version, GPL-3.0 license notice, source code link, library attributions |

All settings are persisted in `SharedPreferences` (not encrypted — nothing sensitive:
theme, sort order, unlock method preference).

---

## Document Processing Pipeline

### On import

```
SAF Uri / Share Intent
        │
        ▼
┌──────────────────────────────┐
│ Detect MIME type             │
│ - application/pdf            │
│ - application/epub+zip       │
│ - application/vnd.apple.pkpass│
│ - application/vnd.comicbook+zip│
│ - application/x-cbr          │
│ - image/*                    │
└──────────┬───────────────────┘
           ▼
┌──────────────────────────────┐
│ Extract metadata             │
│ - PDF: PdfBox metadata       │
│ - EPUB: Readium OPF parsing  │
│ - PKPass: jpasskit fields    │
│ - CBZ/CBR: page count from archive entries │
│ - Image: EXIF (basic)        │
└──────────┬───────────────────┘
           ▼
┌──────────────────────────────┐
│ Generate thumbnail           │
│ - PDF: Render first page     │
│ - EPUB: Extract cover image  │
│ - PKPass: Render pass front  │
│ - CBZ/CBR: Extract first page image │
│ - Image: Downscale bitmap    │
└──────────┬───────────────────┘
           ▼
┌──────────────────────────────┐
│ Extract text for FTS         │
│ - PDF: PdfBox text stripper  │
│ - EPUB: Readium text content │
│ - PKPass: JSON field values  │
│ - CBZ/CBR: No text (archive of images) │
│ - Image: No text (filename)  │
└──────────┬───────────────────┘
           ▼
┌──────────────────────────────┐
│ Barcode detection (PDFs)     │
│ - Render first page bitmap   │
│ - Run ML Kit barcode scan    │
│ - Store format + value       │
└──────────┬───────────────────┘
           ▼
┌──────────────────────────────┐
│ Encrypt + store              │
│ - Generate random IV         │
│ - AES-256-GCM encrypt blob   │
│ - Write to app-private dir   │
│ - Insert row in Room DB      │
└──────────────────────────────┘
```

### On note creation (in-app)

```
[User taps FAB → "New Note"]
        │
        ▼
[NoteEditor opens with empty editor]
  ├── User types markdown content
  ├── Auto-save timer (15s debounce)
  │   ├── Encrypt content → AES-256-GCM blob
  │   ├── Write to app-private dir
  │   ├── Upsert row in documents table (mime_type=text/markdown)
  │   └── Index content in FTS5
  └── User taps back → final save + return to library
```

---

## Dependencies (F-Droid safe)

| Dependency | License | Purpose |
|------------|---------|---------|
| Jetpack Compose BOM | Apache-2.0 | UI framework |
| Jetpack Room + SQLCipher | Apache-2.0 / BSD-3-Clause | Encrypted DB + FTS5 |
| AndroidX Security Crypto | Apache-2.0 | Keystore integration |
| PdfBox-Android | Apache-2.0 | PDF render + text extraction |
| Readium Kotlin Toolkit | BSD-3-Clause | EPUB parsing + reading |
| jpasskit | Apache-2.0 | PKPass parsing |
| Google ML Kit Barcode (on-device) | Apache-2.0 | Barcode detection |
| Coil Compose | Apache-2.0 | Image loading |
| unrar-java | Apache-2.0 | CBR archive extraction |
| commonmark-java | BSD-2-Clause | Markdown parsing for note preview |
| libsodium (or Bouncy Castle) | ISC / MIT | Argon2id KDF, AES key wrap |
| AndroidX Navigation Compose | Apache-2.0 | Screen navigation |
| Kotlin Coroutines | Apache-2.0 | Async operations |
| Kotlin Serialization | Apache-2.0 | JSON parsing (pkpass) |
| **Test dependencies** | | |
| JUnit 4 | EPL-2.0 | Test runner |
| MockK | Apache-2.0 | Kotlin mocking |
| kotlinx-coroutines-test | Apache-2.0 | Coroutine test dispatchers |
| Turbine | Apache-2.0 | Kotlin Flow testing |
| AndroidX Arch Core Testing | Apache-2.0 | ViewModel test utilities |
| Robolectric | MIT | Android framework unit testing |
| Compose UI Test | Apache-2.0 | Composable UI testing |
| AndroidX Test Runner/Ext | Apache-2.0 | Instrumentation test orchestration |

**Not used (avoiding for F-Droid):** Google Play Services, Firebase, Crashlytics, AdMob,
any analytics SDK, any network libraries.

---

## Backup & Restore

Backup creates a single encrypted `.librecrate-backup` file that can be migrated
to another device or stored offline. All data stays encrypted end-to-end.

### Export flow

```
Settings → "Export backup" → SAF directory picker
        │
        ▼
[BackupManager bundles]:
  ├── wrapped_master_key (the AES-256-KW wrapped blob)
  ├── salt (for Argon2id derivation)
  ├── sqlcipher_db (full Room DB: metadata + FTS index + tags + collections)
  └── /files/ (all encrypted document blobs, preserving directory structure)
        │
        ▼
[AES-256-GCM encrypt the entire bundle with a random backup IV]
        │
        ▼
[Write to .librecrate-backup file at chosen SAF location]
```

### Import flow

```
Settings → "Import backup" → SAF file picker (.librecrate-backup)
        │
        ▼
[BackupManager reads + decrypts bundle]
        │
        ▼
┌── Is password set?
│   ├── Yes → prompt user for current password → verify by unwrapping master key
│   └── No → use current device key to unwrap master key
│
├── If password doesn't match → reject import ("wrong password")
├── Replace wrapped_master_key + salt on disk
├── Replace SQLCipher DB on disk
├── Replace all files in /files/ directory
└── Re-launch to unlock screen (if passworded) or library

Note: Importing a backup replaces ALL current data. No merge.
```

### Security

- The backup file is encrypted with AES-256-GCM — a separate layer from the
  per-file encryption. An attacker with the backup file cannot read contents
  without the master key, which requires the password to unwrap.
- No password is embedded in the backup. If you forget your password, the
  backup is unrecoverable.
- Backup IV is random per export; the same data exported twice produces
  different ciphertexts.

---

## Testing

Testing strategy follows the same pragmatic approach as the ActivityTrace project:
focus on complex business logic, mock the data layer, use Compose UI tests for
critical screens, and rely on library correctness for well-tested dependencies.

### Framework stack

| Layer | Framework |
|-------|-----------|
| Runner | JUnit 4 |
| Mocking | MockK (relaxed for interfaces, strict for ViewModel deps) |
| Coroutines | `kotlinx-coroutines-test` (`runTest`, `TestDispatcher`) |
| Flow testing | Turbine |
| Android framework | Robolectric (for ViewModel + SharedPreferences tests) |
| Compose UI | `createComposeRule()` + semantic matchers |
| Instrumentation | AndroidX Test Runner, AndroidJUnit4 |

### What gets tested

| Module | Test file | What it covers |
|--------|-----------|----------------|
| EncryptionManager | `EncryptionManagerTest` | Master key gen + wrap/unwrap round-trip, device key mode, password enable/disable/change, wrong password rejection, Argon2id parameters |
| FileEncryptor | `FileEncryptorTest` | Encrypt then decrypt yields original bytes, wrong key fails, streaming large files |
| PdfProcessor | `PdfProcessorTest` | Metadata extraction from a sample PDF, thumbnail generation, encrypted PDF handling |
| EpubProcessor | `EpubProcessorTest` | Metadata + cover extraction from a sample EPUB |
| PkPassProcessor | `PkPassProcessorTest` | Field extraction from a sample .pkpass |
| ComicProcessor | `ComicProcessorTest` | Page count from CBZ + CBR samples, thumbnail from first page |
| ImageProcessor | `ImageProcessorTest` | EXIF extraction, thumbnail generation |
| SearchEngine | `SearchEngineTest` | FTS5 vs LIKE routing, wildcard detection, time range passthrough, empty/blank queries |
| FtsIndexer | `FtsIndexerTest` | Text extraction routing per document type, FTS5 insert |
| BackupManager | `BackupManagerTest` | Export then import round-trip yields identical DB + files, wrong password rejects |
| LibraryViewModel | `LibraryViewModelTest` | Initial state, sort/filter changes, collection assignment, toggle favorite |
| SearchViewModel | `SearchViewModelTest` | Query persistence, search dispatch, filter chip state, recent items fallback |
| SettingsViewModel | `SettingsViewModelTest` | Password toggle state, theme + sort prefs persistence |
| UnlockViewModel | `UnlockViewModelTest` | Correct password navigates, wrong password shows error, biometric fallback |
| LibraryScreen | `LibraryScreenTest` (instrumented) | Document cards render, sort menu opens, FAB exists, filter chips visible |
| SettingsScreen | `SettingsScreenTest` (instrumented) | All sections present, password toggle, backup/restore buttons fire callbacks |
| SearchScreen | `SearchScreenTest` (instrumented) | Search bar accepts input, results display, "no results" empty state, filter chips toggle |
| UnlockScreen | `UnlockScreenTest` (instrumented) | Password field exists, error shown on wrong password, setup screen on first launch |

### What is NOT tested

- Room DAO implementations (mocked in all tests — SQLCipher correctness is trusted)
- Platform rendering details (PdfBox page render, Readium EPUB layout, etc.)
- ML Kit barcode detection model behavior
- SAF file picker intents (Android framework — tested manually)
- Share intent receiver (tested manually)

### Test infrastructure

- **`build_and_test.py`** (same pattern as ActivityTrace): builds debug + release,
  runs lint, runs unit tests, boots emulator if needed, runs instrumented tests.
- Run with `./gradlew test` for fast unit test feedback during development.
- Processor tests use sample files stored in `src/test/resources/`.
- EncryptionManager tests use fast Argon2id parameters (1 iteration, no memory cost)
  to keep test execution fast.

---

```
LibreCrate/
├── app/
│   ├── src/main/
│   │   ├── java/com/librecrate/app/
│   │   │   ├── LibreCrateApplication.kt
│   │   │   ├── MainActivity.kt
│   │   │   │
│   │   │   ├── data/
│   │   │   │   ├── db/
│   │   │   │   │   ├── LibreCrateDatabase.kt
│   │   │   │   │   ├── DocumentDao.kt
│   │   │   │   │   ├── TagDao.kt
│   │   │   │   │   ├── CollectionDao.kt
│   │   │   │   │   └── Converters.kt
│   │   │   │   ├── encryption/
│   │   │   │   │   ├── EncryptionManager.kt
│   │   │   │   │   └── FileEncryptor.kt
│   │   │   │   ├── import/
│   │   │   │   │   ├── DocumentImporter.kt
│   │   │   │   │   ├── PdfProcessor.kt
│   │   │   │   │   ├── EpubProcessor.kt
│   │   │   │   │   ├── PkPassProcessor.kt
│   │   │   │   │   ├── ComicProcessor.kt
│   │   │   │   │   └── ImageProcessor.kt
│   │   │   │   ├── search/
│   │   │   │   │   ├── SearchEngine.kt
│   │   │   │   │   └── FtsIndexer.kt
│   │   │   │   ├── barcode/
│   │   │   │   │   └── BarcodeDetector.kt
│   │   │   │   └── model/
│   │   │   │       ├── Document.kt
│   │   │   │       ├── Tag.kt
│   │   │   │       ├── Collection.kt
│   │   │   │       └── DocumentType.kt
│   │   │   │
│   │   │   ├── domain/
│   │   │   │   ├── DocumentRepository.kt
│   │   │   │   ├── ImportRepository.kt
│   │   │   │   ├── SearchRepository.kt
│   │   │   │   ├── ExportRepository.kt
│   │   │   │   └── BackupManager.kt
│   │   │   │
│   │   │   └── ui/
│   │   │       ├── unlock/
│   │   │       │   ├── UnlockScreen.kt
│   │   │       │   ├── UnlockViewModel.kt
│   │   │       │   ├── PasswordSetupScreen.kt
│   │   │       │   └── BiometricPromptHelper.kt
│   │   │       ├── library/
│   │   │       │   ├── LibraryScreen.kt
│   │   │       │   ├── LibraryViewModel.kt
│   │   │       │   ├── DocumentCard.kt
│   │   │       │   └── CollectionGrid.kt
│   │   │       ├── viewer/
│   │   │       │   ├── ViewerScreen.kt
│   │   │       │   ├── PdfViewer.kt
│   │   │       │   ├── EpubReader.kt
│   │   │       │   ├── PkPassViewer.kt
│   │   │       │   ├── ComicViewer.kt
│   │   │       │   ├── NoteEditor.kt
│   │   │       │   └── ImageViewer.kt
│   │   │       ├── search/
│   │   │       │   ├── SearchScreen.kt
│   │   │       │   └── SearchViewModel.kt
│   │   │       ├── import/
│   │   │       │   ├── ImportScreen.kt
│   │   │       │   └── ImportViewModel.kt
│   │   │       ├── settings/
│   │   │       │   ├── SettingsScreen.kt
│   │   │       │   └── SettingsViewModel.kt
│   │   │       └── common/
│   │   │           ├── BarcodeImage.kt
│   │   │           ├── ThumbnailCache.kt
│   │   │           └── EmptyState.kt
│   │   │
│   │   ├── res/
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── ...
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
├── fastlane/
│   └── metadata/
│       └── android/
│           ├── en-US/
│           │   ├── full_description.txt
│           │   ├── short_description.txt
│           │   ├── title.txt
│           │   └── changelogs/
│               └── 1.txt
├── LICENSE (GPL-3.0)
└── README.md
```

### Test files

```
app/src/
├── test/java/com/librecrate/app/                    ← Unit tests (JVM + Robolectric)
│   ├── encryption/
│   │   ├── EncryptionManagerTest.kt
│   │   └── FileEncryptorTest.kt
│   ├── import/
│   │   ├── PdfProcessorTest.kt
│   │   ├── EpubProcessorTest.kt
│   │   ├── PkPassProcessorTest.kt
│   │   ├── ComicProcessorTest.kt
│   │   └── ImageProcessorTest.kt
│   ├── search/
│   │   ├── SearchEngineTest.kt
│   │   └── FtsIndexerTest.kt
│   ├── backup/
│   │   └── BackupManagerTest.kt
│   └── ui/
│       ├── LibraryViewModelTest.kt
│       ├── SearchViewModelTest.kt
│       ├── SettingsViewModelTest.kt
│       └── UnlockViewModelTest.kt
│
└── androidTest/java/com/librecrate/app/             ← Instrumented tests (device/emulator)
    └── ui/
        ├── LibraryScreenTest.kt
        ├── SettingsScreenTest.kt
        ├── SearchScreenTest.kt
        └── UnlockScreenTest.kt
```

---

## Build Plan — 9 Weeks (Solo)

### Week 1 — Project Scaffold + Encryption Foundation + Test Infra

- Set up Kotlin + Compose project with Gradle version catalogs
- Implement `EncryptionManager`:
  - Master key generation
  - Device key (auto, no password) wrapping + unwrapping
  - Argon2id password hashing + user key derivation (libsodium or Bouncy Castle)
  - AES-256-KW wrap/unwrap for both modes
  - Password enable / disable / change flows
  - In-memory master key holder with background→clear
- Implement `FileEncryptor` — per-file AES-256-GCM encrypt/decrypt
- Create Room database with `documents` table, FTS5 virtual table
- Basic `LibreCrateApplication.kt` and `MainActivity.kt` with Compose Navigation scaffold
- `UnlockScreen` + `PasswordSetupScreen` — only shown if password is set
- Install test dependencies (JUnit 4, MockK, coroutines-test, Turbine, Robolectric, Compose UI Test)
- Install all project dependencies, verify F-Droid compatibility

**Deliverable:** App launches (no password by default), password can be enabled in Settings.
All content encrypted in both modes. Test infrastructure ready.

### Week 2 — Document Import Pipeline + Processor Tests

- SAF picker integration (ACTION_OPEN_DOCUMENT for each MIME type)
- Share intent receiver (ACTION_SEND for files)
- `DocumentImporter` — copy to app-private storage, encrypt blob
- `PdfProcessor` — extract metadata, page count, generate thumbnail via PdfBox
- `EpubProcessor` — extract metadata, cover image via Readium
- `PkPassProcessor` — parse fields, extract images via jpasskit
- `ComicProcessor` — count pages, extract first-page thumbnail from CBZ/Zip and CBR/RAR
- `ImageProcessor` — generate thumbnail, basic EXIF
- Write tests: `PdfProcessorTest`, `EpubProcessorTest`, `PkPassProcessorTest`,
  `ComicProcessorTest`, `ImageProcessorTest`

**Deliverable:** Import PDF, EPUB, CBZ/CBR, PKPass, and image files → stored encrypted in DB.
All processors covered by unit tests.

### Week 3 — Library UI + Settings + ViewModel Tests

- `LibraryScreen` — grid/list toggle, sort options, filter by type
- `DocumentCard` — thumbnail, title, type icon, favorite button
- `CollectionGrid` — collection cards with document count
- Collection CRUD — create, rename, delete collections
- Document → collection assignment (drag or menu)
- Tags UI — create/assign/remove tags, color picker
- Favorites toggle + Recents tracking
- `SettingsScreen` + `SettingsViewModel`:
  - Security: enable/disable/change password, biometric unlock toggle
  - Defaults: default sort order, default import collection
  - Appearance: theme (system/light/dark)
  - About: version, license, source link
- Write tests: `LibraryViewModelTest`, `SettingsViewModelTest`,
  `LibraryScreenTest` (instrumented), `SettingsScreenTest` (instrumented)

**Deliverable:** Full library browsing, organization via collections and tags.
Settings screen fully functional. ViewModel + screen tests pass.

### Week 4 — PDF Viewer

- `PdfViewer` — PdfBox-Android page rendering to Compose `Image`
- Page navigation (swipe or paginated scroll)
- Page indicator + jump-to-page
- Pinch-to-zoom on rendered pages
- PDF metadata display (title, author, page count, file size)
- Barcode detection on first page render — show "View Barcode" button
- `BarcodeImage` composable — renders decoded barcode as crisp vector image
- Write tests: `PdfProcessorTest` (extended with PDF-specific edge cases)

**Deliverable:** PDFs open, render, scroll, zoom, show barcodes.

### Week 5 — EPUB Reader + Comic Viewer + Note Editor + PKPass Viewer + Image Viewer

- `EpubReader` — Readium integration with Compose
  - Chapter navigation
  - Font size adjustment
  - Theme toggle (light/sepia/dark)
  - Reading progress tracking
- `ComicViewer` — page-by-page viewer for CBZ/CBR archives
  - Decompress archive entries, render pages via Coil
  - Left/right page-turn navigation
  - Pinch-to-zoom on each page
  - Two-page spread option (landscape)
- `NoteEditor` — markdown composer for notes/diary
  - Split pane: edit markdown source / preview rendered view
  - Toolbar: bold, italic, heading, bullet list, checklist
  - Auto-save with debounce (encrypt on each save)
  - Date-stamped default title for diary entries
  - CommonMark parsing for preview rendering
  - Word/character count
- `PkPassViewer` — custom Compose renderer
  - Front/back card layout with fields
  - Barcode rendering for store/loyalty passes
  - Strip image display
- `ImageViewer` — Coil with zoomable `AsyncImage`
  - Pinch-to-zoom, pan
  - Basic info overlay (dimensions, file size)
- Write tests: `UnlockViewModelTest`, `UnlockScreenTest` (instrumented)

**Deliverable:** All six document types open and render properly; notes can be created, edited, and saved.

### Week 6 — Full-Text Search + Search Tests

- Text extraction for PDFs (PdfBox `PDFTextStripper`) and EPUBs (Readium text content)
- `FtsIndexer` — extract text on import, populate `documents_fts` table
- `SearchEngine` — FTS5 MATCH queries with BM25 ranking + recency boost
- `SearchScreen` — type-ahead search bar, filter chips (type, tag, collection, date)
- In-document search — find + highlight within PDF viewer and EPUB reader
- Search results as document cards with highlighted snippets
- Write tests: `SearchEngineTest`, `FtsIndexerTest`, `SearchViewModelTest`,
  `SearchScreenTest` (instrumented)

**Deliverable:** Full-text search across all documents, with in-document highlighting.
All search components covered by unit + UI tests.

### Week 7 — Backup/Restore + Encryption Tests

- `BackupManager` — export/import flow
  - Bundle wrapped master key + SQLCipher DB + all file blobs
  - AES-256-GCM encrypt the bundle
  - SAF directory picker for export, SAF file picker for import
  - Password verification on import
  - Full data replacement on restore
- Write tests: `EncryptionManagerTest`, `FileEncryptorTest`, `BackupManagerTest`

**Deliverable:** Full encrypted backup/restore round-trip. Encryption and backup
covered by unit tests.

### Week 8 — Polish + Edge Cases + Integration Tests

- Background import handling (WorkManager for large files)
- Progress indicator during import + text extraction
- Empty states for library, collections, search results
- Error handling — corrupt files, unsupported formats, decryption failures
- Note conflict resolution (auto-save vs. manual save edge cases)
- Document deletion (with confirmation)
- Batch operations (multi-select → move to collection, tag, delete)
- Dark theme support
- Landscape/tablet layout adjustments
- Final integration test pass on device

**Deliverable:** Production-quality UX, error handling, edge case coverage.

### Week 9 — F-Droid Release Prep

- Strip all debug logging from release builds
- Test APK on Android 12–15 (emulator + physical device)
- Verify zero network calls (StrictMode in debug builds)
- Add `fastlane/metadata/android/` for F-Droid auto-ingestion
- Write `README.md` with build instructions, screenshots, dependency list
- Write `build_and_test.py` (local CI: build → lint → unit tests → instrumented tests)
- Verify all dependencies are FOSS and correctly attributed
- Add `reproducible-builds` config if feasible
- Sign release APK with debug key for initial testing
- Publish on F-Droid (submit metadata to fdroiddata GitLab)
- Announce on r/fdroid, r/privacy, r/androidapps

**Deliverable:** v1.0.0 live on F-Droid.

---

## Hardest Technical Problems

| Problem | Solution |
|---------|----------|
| Encrypted FTS5 search | Room + SQLCipher supports FTS5 transparently on encrypted DBs — same query interface, no extra work |
| Password-based key protection | Argon2id KDF → AES-256-KW wrap master key; master key held in memory only, cleared on background |
| PDF rendering on Compose | PdfBox-Android renders pages to `Bitmap` → Coil's `AsyncImage` or manual `Image` composable |
| EPUB reader integration | Readium has a `Publication` model + locator; render each spine item as HTML in a WebView or parse to Compose text |
| Barcode detection without Play Services | ML Kit on-device model works without Play Services — download model at runtime, ~2MB |
| PKPass rendering | jpasskit parses the `.pkpass` zip; render pass fields in a card-like Compose layout, display barcode with ZXing or ML Kit |
| CBZ/CBR performance | Page images extracted on-demand and cached; LRU cache of decoded page bitmaps to avoid re-decompression |
| Markdown rendering | commonmark-java converts to HTML → render in Compose via AndroidView WebView or custom `AnnotatedString` builder |
| Large PDF performance | PdfBox renders pages on demand with caching; pre-render adjacent pages in background; thumbnails cached to disk |
| Per-file encryption overhead | Encrypt/decrypt on import/view boundaries with streaming cipher — barely noticeable at document scale |
| Encrypted backup/restore | Bundle wrapped key + DB + files into single AES-256-GCM encrypted archive; password must match to import |

---

## v1.1+ Ideas (Post-Launch)

- [ ] **Document scanning**: CameraX + ML Kit document detection → scan paper docs directly into LibreCrate
- [ ] **Auto-tagging**: Suggest tags based on document content (e.g. "tax", "contract", "receipt")
- [ ] **Document expiry tracking**: Extract dates from documents + optional notification (like Bin app)
- [ ] **Password-protected PDFs**: Decrypt on import using PdfBox's decryption support
- [ ] **Folder import**: Pick a SAF tree, batch-import all supported documents
- [ ] **Quick Search overlay**: System-level search shortcut (Quick Settings tile)
- [ ] **Document signing**: Basic canvas-based signature overlay for PDFs
- [ ] **OPDS catalog**: Browse public ebook catalogs from within LibreCrate
- [ ] **Print**: Android print integration for PDFs
- [ ] **CBZ/CBR reading progress**: Remember last page per archive + resume reading
- [ ] **Diary calendar view**: Browse notes by date on a calendar grid
- [ ] **Rich text editor**: Switch from markdown source to WYSIWYG toolbar
- [ ] **Note export**: Export individual notes or diary as plain text / markdown / PDF

---

## Why This Is Achievable Solo in 9 Weeks

1. **All libraries are well-documented Apache/BSD open source** — no proprietary SDKs to wrestle with
2. **Encryption reuses proven pattern** from Activity Trace plan — SQLCipher + Keystore is well-trodden ground
3. **PDF Wallet was built by one person** — LibreCrate is strictly additive (more formats, encryption, FTS search)
4. **No network code** — no server, no sync, no API integration, no auth
5. **Compose accelerates UI** — Library grid, viewer screens, and search are all standard Compose patterns
6. **F-Droid submission is mechanical** — fastlane metadata, license file, verify no non-FOSS deps
