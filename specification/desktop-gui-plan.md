# LibreCrate Desktop GUI — Specification

## 1. Overview

LibreCrate will gain a native desktop GUI for Linux and Windows, built with [Iced](https://iced.rs/) (Rust, Elm Architecture, GPU-rendered). The desktop app starts as a **vault manager** (Model A) that opens documents with the system's default viewer, with a clean upgrade path to **built-in viewers** (Model B) later.

### Goals

- Native desktop experience on Linux and Windows
- Reuse the existing `vault-native` Rust core directly (no FFI/IPC boundary)
- Small footprint (~5 MB bundle)
- Secure credential storage via OS keyring
- Same vault format as Android (cross-platform compatibility)
- Designed for Model A → Model B upgrade without refactoring vault logic

### Non-Goals (v1)

- Built-in PDF/EPUB/comic/image viewers (deferred to Model B)
- macOS support (can be added later — Iced supports it)
- Mobile support (Android uses its own Compose UI)

---

## 2. Architecture

### 2.1 Framework Choice: Iced

| Criterion | Iced | Tauri | egui | Dioxus | GTK |
|---|---|---|---|---|---|
| Language | Pure Rust | Rust + JS/HTML | Pure Rust | Rust + WebView | Rust bindings |
| Rendering | GPU (wgpu) | System WebView | CPU + optional GPU | System WebView | GTK native |
| Bundle size | ~5 MB | ~12 MB | ~3 MB | ~12 MB | System GTK |
| Production ready | Yes (COSMIC desktop) | Yes | Yes | Yes | Yes |
| Linux native look | Yes | Depends on WebView | Custom | Depends on WebView | GNOME only |
| Windows native look | Yes | Depends on WebView2 | Custom | Depends on WebView2 | Awkward |
| Learning curve | Medium | Low | Low | Low | Medium-high |

**Why Iced over Tauri:** LibreCrate's core is Rust. Tauri adds a JavaScript/HTML WebView layer that introduces IPC serialization overhead, a JavaScript runtime dependency, and WebView version inconsistencies across Linux distros. Iced renders natively via GPU, keeps everything in Rust, and produces a smaller binary.

**Why Iced over egui:** egui is immediate-mode and looks like a debug tool. For a library app users browse daily, appearance matters. Iced uses retained-mode Elm Architecture with GPU rendering, producing a more polished UI.

### 2.2 Project Structure

```
vault-native/
├── Cargo.toml              # workspace: ["core", "cli", "gui"]
├── core/                   # existing — untouched
├── cli/                    # existing — untouched
└── gui/                    # NEW
    ├── Cargo.toml
    ├── src/
    │   ├── main.rs
    │   ├── app.rs
    │   ├── vault.rs
    │   ├── opener.rs
    │   ├── keychain.rs
    │   ├── config.rs
    │   ├── screens/
    │   │   ├── mod.rs
    │   │   ├── first_run.rs
    │   │   ├── unlock.rs
    │   │   ├── library.rs
    │   │   ├── settings.rs
    │   │   ├── export.rs
    │   │   └── collections.rs
    │   └── widgets/
    │       ├── mod.rs
    │       ├── document_card.rs
    │       ├── sidebar.rs
    │       └── search_bar.rs
    └── resources/
        ├── icon.png
        └── styles/
            ├── light.toml
            └── dark.toml
```

### 2.3 Workspace Configuration

`vault-native/Cargo.toml`:
```toml
[workspace]
resolver = "2"
members = ["core", "cli", "gui"]
```

### 2.4 Dependencies

`vault-native/gui/Cargo.toml`:
```toml
[package]
name = "librecrate-gui"
version = "0.1.0"
edition = "2021"
rust-version = "1.80"
description = "LibreCrate — encrypted document vault for Linux and Windows"

[dependencies]
vault-native = { path = "../core" }
iced = { version = "0.14", features = ["svg", "image", "auto-detect-theme"] }
tokio = { version = "1", features = ["rt", "fs", "io-util"] }
keyring = "4"
dirs = "5"
serde = { version = "1", features = ["derive"] }
toml = "0.8"
thiserror = "2"
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter"] }
open = "5"
anyhow = "1"

[profile.release]
lto = true
codegen-units = 1
strip = true
opt-level = 3
```

---

## 3. Screen Design

### 3.1 Screen Flow

```
Startup
  │
  ├─ No vault found ──> FirstRun ──> Unlock
  │                                      │
  ├─ Vault found ──────────────────> Unlock
  │                                      │
  │                                      ▼
  │                                   Library ←──> Settings
  │                                      │
  │                                      ├──> Export
  │                                      ├──> Collections
  │                                      └──> Open Document ──> System Viewer
  │
  └─ Vault locked (backgrounded) ──> Unlock
```

### 3.2 Screen Details

#### FirstRun

New vault setup wizard. Shown when no vault exists at the configured path.

**State:**
```rust
pub struct FirstRun {
    password: String,
    confirm_password: String,
    vault_dir: PathBuf,
    error: Option<String>,
    step: Step, // Welcome, ChooseDir, SetPassword, Creating
}
```

**Messages:** `PasswordChanged(String)`, `ConfirmChanged(String)`, `ChooseDirectory`, `Create`, `StepBack`

**Actions:** `→ Unlock` (on success), `→ None`

#### Unlock

Password entry screen. Shown on startup (vault exists) and after backgrounding.

**State:**
```rust
pub struct Unlock {
    password: String,
    error: Option<String>,
    loading: bool,
}
```

**Messages:** `PasswordChanged(String)`, `Submit`

**Actions:** `→ Library` (on success, passes `DbHandle` + master key), `→ None`

#### Library

Main library view with grid, search, and collections sidebar.

**State:**
```rust
pub struct Library {
    documents: Vec<DocumentRow>,
    collections: Vec<CollectionRow>,
    tags: Vec<TagRow>,
    search_query: String,
    search_results: Option<Vec<FtsResult>>,
    selected_collection: Option<i64>,
    selected_tag: Option<i64>,
    sort: SortOrder,
    vault_dir: PathBuf,
    db: DbHandle,
    master_key: Vec<u8>,
}
```

**Messages:**
- `Search(String)`, `ClearSearch`
- `FilterByCollection(Option<i64>)`, `FilterByTag(Option<i64>)`
- `SortChanged(SortOrder)`
- `ToggleFavorite(i64)`
- `OpenDocument(i64)`
- `ImportFiles(Vec<PathBuf>)`
- `NavigateToSettings`, `NavigateToExport`, `NavigateToCollections`
- `DocumentsLoaded(Vec<DocumentRow>)`, `SearchResultsLoaded(Vec<FtsResult>)`

**Actions:**
- `→ Viewer(document)` (Model B) or `SystemOpen(document)` (Model A)
- `→ Settings`, `→ Export`, `→ Collections`
- `→ Unlock` (if vault lock detected)
- `→ None`

#### Settings

Password change and preferences.

**State:**
```rust
pub struct Settings {
    current_password: String,
    new_password: String,
    confirm_password: String,
    error: Option<String>,
    success: Option<String>,
    theme: Theme,
}
```

**Messages:** `CurrentPasswordChanged(String)`, `NewPasswordChanged(String)`, `ConfirmPasswordChanged(String)`, `ChangePassword`, `SetTheme(Theme)`

**Actions:** `→ Library` (back), `→ None`

#### Export

Backup export and import.

**State:**
```rust
pub struct Export {
    progress: Option<String>,
    error: Option<String>,
    export_path: Option<PathBuf>,
    import_path: Option<PathBuf>,
}
```

**Messages:** `ExportBackup`, `ImportBackup`, `SelectExportPath(PathBuf)`, `SelectImportPath(PathBuf)`, `ExportComplete`, `ImportComplete`

**Actions:** `→ Library` (back), `→ Unlock` (after import restore), `→ None`

#### Collections

Collection and tag management.

**State:**
```rust
pub struct Collections {
    collections: Vec<CollectionRow>,
    tags: Vec<TagRow>,
    new_collection_name: String,
    new_tag_name: String,
    new_tag_color: String,
    error: Option<String>,
}
```

**Messages:** `AddCollection(String)`, `RenameCollection(i64, String)`, `DeleteCollection(i64)`, `AddTag(String, String)`, `DeleteTag(i64)`

**Actions:** `→ Library` (back), `→ None`

---

## 4. Document Opener Abstraction

### 4.1 Trait Definition

```rust
// opener.rs
use anyhow::Result;
use std::path::Path;
use vault_native::types::DocumentRow;

pub trait DocumentOpener: Send + Sync {
    fn open(&self, document: &DocumentRow, base_dir: &Path) -> Result<()>;
}
```

### 4.2 Model A: System Viewer

```rust
pub struct SystemOpener;

impl DocumentOpener for SystemOpener {
    fn open(&self, doc: &DocumentRow, base_dir: &Path) -> Result<()> {
        let path = base_dir.join(&doc.file_path);
        if !path.exists() {
            return Err(anyhow::anyhow!("File not found: {}", path.display()));
        }
        open::that(&path).map_err(|e| anyhow::anyhow!("Failed to open: {}", e))?;
        Ok(())
    }
}
```

### 4.3 Model B: In-App Viewer (Future)

When Model B is implemented, the `Screen` enum gains a `Viewer` variant and `Library::Action::OpenDocument` transitions to it instead of calling `SystemOpener`. The vault management code is untouched.

```rust
// Future: screens/viewer.rs
pub struct Viewer {
    document: DocumentRow,
    viewer_type: ViewerType,
}

enum ViewerType {
    Pdf { current_page: u32 },
    Epub { location: ReaderLocation },
    Comic { current_page: u32 },
    Image,
    PkPass,
    Note { content: String },
}
```

### 4.4 Upgrade Path

| Component | Model A | Model B | Changes Required |
|---|---|---|---|
| Vault management | ✓ | ✓ | None |
| Library grid | ✓ | ✓ | None |
| Search | ✓ | ✓ | None |
| Collections/tags | ✓ | ✓ | None |
| Import/export | ✓ | ✓ | None |
| `DocumentOpener` trait | ✓ | ✓ | Same interface |
| `SystemOpener` | ✓ | Removed | Swap implementation |
| `InAppOpener` | — | ✓ | New implementation |
| `Screen::Viewer` | — | ✓ | New screen variant |
| MuPDF bindings | — | ✓ | New dependency |
| EPUB parser | — | ✓ | Port from Kotlin |
| Comic renderer | — | ✓ | zip + image crate |

---

## 5. Vault Integration

### 5.1 Vault Operations Module

```rust
// vault.rs
use vault_native::db::DbHandle;
use vault_native::types::*;
use std::path::Path;

pub struct Vault {
    pub db: DbHandle,
    pub master_key: Vec<u8>,
    pub base_dir: PathBuf,
}

impl Vault {
    /// Open an existing vault at the given directory.
    pub fn open(dir: &Path, password: &str) -> Result<Self> { ... }

    /// Create a new vault at the given directory.
    pub fn create(dir: &Path, password: &str) -> Result<Self> { ... }

    /// List all documents, optionally filtered.
    pub fn list_documents(&self, filter: DocumentFilter) -> Result<Vec<DocumentRow>> { ... }

    /// Full-text search across documents.
    pub fn search(&self, query: &str) -> Result<Vec<FtsResult>> { ... }

    /// Import a file into the vault.
    pub fn import_file(&self, path: &Path, title: &str) -> Result<DocumentRow> { ... }

    /// Export a document's file data.
    pub fn export_document(&self, id: i64) -> Result<Option<Vec<u8>>> { ... }

    /// Create a backup of the entire vault.
    pub fn export_backup(&self, password: &str, output: &Path) -> Result<()> { ... }

    /// Restore from a backup.
    pub fn import_backup(backup: &Path, password: &str, target: &Path) -> Result<()> { ... }

    /// List collections.
    pub fn list_collections(&self) -> Result<Vec<CollectionRow>> { ... }

    /// List tags.
    pub fn list_tags(&self) -> Result<Vec<TagRow>> { ... }

    /// Change the vault password.
    pub fn change_password(&self, old_password: &str, new_password: &str) -> Result<()> { ... }
}
```

### 5.2 Key Derivation Flow

```
User enters password
  │
  ├─ Check keychain for cached master key
  │    ├─ Found: unwrap with password → verify → use
  │    └─ Not found: derive from password via Argon2id → unwrap master key
  │
  ├─ Open SQLCipher DB with master key
  │
  └─ Cache wrapped master key in keychain (if keyring available)
```

---

## 6. Credential Storage

### 6.1 Keyring Integration

| OS | Backend | Service Name |
|---|---|---|
| Linux | Secret Service D-Bus (GNOME Keyring / KDE Wallet) | `com.librecrate.desktop` |
| Windows | Windows Credential Manager (DPAPI) | `com.librecrate.desktop` |

### 6.2 SecureStore Wrapper

```rust
// keychain.rs
pub struct SecureStore {
    service: String,
    available: bool,
}

impl SecureStore {
    pub fn new(service: &str) -> Self { ... }
    pub fn is_available(&self) -> bool { ... }
    pub fn set(&self, key: &str, value: &str) -> Result<()> { ... }
    pub fn get(&self, key: &str) -> Result<Option<String>> { ... }
    pub fn delete(&self, key: &str) -> Result<()> { ... }
}
```

**Stored credentials:**
- `vault_master_key`: Wrapped master key hex string (allows passwordless unlock on same device)
- `vault_salt`: Argon2id salt (hex)

**Fallback:** If keyring is unavailable (no D-Bus, headless), the app warns the user and falls back to password-only mode (no device key caching).

---

## 7. Configuration

### 7.1 Config File Location

| OS | Path |
|---|---|
| Linux | `~/.config/librecrate/config.toml` |
| Windows | `%APPDATA%\LibreCrate\config.toml` |

### 7.2 Config Schema

```toml
vault_dir = "/home/user/.local/share/librecrate"
theme = "dark"        # "light" | "dark" | "system"
window_width = 1200
window_height = 800
window_x = 100
window_y = 100
sort_by = "imported_at"   # "title" | "imported_at" | "last_opened_at" | "file_size"
sort_desc = true
```

### 7.3 Vault Directory Layout

| OS | Path |
|---|---|
| Linux | `~/.local/share/librecrate/` |
| Windows | `%APPDATA%\LibreCrate\` |

```
librecrate/
├── databases/           # SQLCipher DB (vault.db)
├── encryption/          # Wrapped master key, salt, Argon2 params
├── files/               # Document blobs (by UUID)
└── thumbnails/          # Cached document thumbnails
```

---

## 8. UI Design

### 8.1 Layout

```
┌──────────────────────────────────────────────────────────────┐
│ ◆ LibreCrate        🔍 Search library...         ⚙ ⬇ ⋮    │
├────────────┬─────────────────────────────────────────────────┤
│            │                                                 │
│ 📁 All     │  ┌─────────┐ ┌─────────┐ ┌─────────┐          │
│ ★ Favorites│  │ 📄      │ │ 📕      │ │ 🖼      │          │
│            │  │ contract│ │ novel   │ │ photo   │          │
│ Collections│  │ .pdf    │ │ .epub   │ │ .jpg    │          │
│ ├─ Work    │  │ 2.4 MB  │ │ 890 KB  │ │ 1.2 MB  │          │
│ ├─ Personal│  └─────────┘ └─────────┘ └─────────┘          │
│ └─ Archive │                                                 │
│            │  ┌─────────┐ ┌─────────┐ ┌─────────┐          │
│ Tags       │  │ 📄      │ │ 📰      │ │ 📝      │          │
│ ● red      │  │ report  │ │ comic   │ │ notes   │          │
│ ● blue     │  │ .pdf    │ │ .cbz    │ │ .md     │          │
│            │  │ 1.1 MB  │ │ 45 MB   │ │ 12 KB   │          │
│            │  └─────────┘ └─────────┘ └─────────┘          │
├────────────┴─────────────────────────────────────────────────┤
│ 12 documents · 3 collections · 156 MB                       │
└──────────────────────────────────────────────────────────────┘
```

**Header bar:** App logo + name on the left, search input center, action buttons (import, settings, menu) on the right.

**Left sidebar:** Fixed-width (200px), collapsible. Lists "All Documents", "Favorites", then collections (expandable), then tags (with color dots). Shows document count per collection.

**Main content:** Document grid with responsive column count based on window width.

**Status bar:** Document count, collection count, total vault size.

### 8.2 Card Design

```
┌───────────────┐
│ ┌───────────┐ │
│ │           │ │
│ │ thumbnail │ │  ← document preview image or type icon
│ │           │ │
│ └───────────┘ │
│ document.pdf  │  ← title (truncated with ellipsis)
│ 2.4 MB · PDF  │  ← size + type badge
│          ★    │  ← favorite star (clickable)
└───────────────┘
```

- **Hover:** subtle border glow (`#5b8def` at 30% opacity)
- **Right-click:** context menu (Open, Rename, Delete, Favorite, Info)
- **Double-click:** open with system viewer
- **Empty state:** centered icon + "Drop files here or press Ctrl+I to import"

### 8.3 Color Palette

| Element | Color |
|---|---|
| Background | `#0f1115` |
| Sidebar | `#161922` |
| Cards | `#1c2130` |
| Text | `#e6e9ef` |
| Muted text | `#9aa3b2` |
| Accent (links, active) | `#5b8def` |
| Secondary accent (tags, favorites) | `#4ad6a8` |
| Borders | `#2a3142` |
| Card hover border | `#5b8def` (30% opacity) |

### 8.4 Responsive Behavior

| Window width | Layout |
|---|---|
| < 800px | Sidebar collapsed (hamburger menu), single-column grid |
| 800–1200px | Sidebar visible, 2-column grid |
| > 1200px | Sidebar visible, 3+ column grid |

### 8.5 Interactions

- **Search** — debounced (300ms), live filtering, highlights matching text in results
- **Import** — drag-and-drop onto the window, or Ctrl+I file picker
- **Sort** — dropdown in header (by name, date imported, date opened, size)
- **Theme toggle** — follows system by default, manual override in settings

---

## 9. Widgets

### 9.1 DocumentCard

Grid card showing document thumbnail, title, type icon, reading progress, and favorite star. Used in the library grid view.

### 9.2 Sidebar

Left sidebar listing collections and tags. Collapsible. Shows document count per collection.

### 9.3 SearchBar

Text input with debounced search. Shows result count. Clears on escape.

---

## 10. Theming

Iced supports custom themes via the `Theme` type. LibreCrate will ship with:

- **Dark theme** (default) — matches the Android app's dark UI
- **Light theme** — for users who prefer light mode
- **System** — follows OS light/dark setting (via `auto-detect-theme` feature)

Theme colors defined in `resources/styles/dark.toml` and `resources/styles/light.toml`:

```toml
# Dark theme colors
background = "#0f1115"
surface = "#161922"
card = "#1c2130"
text = "#e6e9ef"
muted = "#9aa3b2"
accent = "#5b8def"
accent_2 = "#4ad6a8"
border = "#2a3142"
```

---

## 11. Keyboard Shortcuts

| Shortcut | Action |
|---|---|
| `Ctrl+F` | Focus search bar |
| `Ctrl+I` | Import files |
| `Ctrl+E` | Export backup |
| `Ctrl+,` | Settings |
| `Ctrl+Q` | Quit |
| `Escape` | Close search / go back |
| `Enter` | Open selected document |
| `Delete` | Delete selected document (with confirmation) |
| `F5` | Refresh library |

---

## 12. Packaging

### 11.1 Linux

| Format | Tool | Notes |
|---|---|---|
| `.deb` | `cargo-deb` | For Debian/Ubuntu |
| `.AppImage` | `cargo-appimage` | Portable, works everywhere |
| `.rpm` | `cargo-rpm` | For Fedora/RHEL |

### 11.2 Windows

| Format | Tool | Notes |
|---|---|---|
| `.msi` | `cargo-wix` | Standard Windows installer |
| `.exe` (NSIS) | `cargo-nsis` | Alternative installer |

### 11.3 Build Commands

```sh
# Linux .deb
cargo deb --no-build --target x86_64-unknown-linux-gnu

# Linux AppImage
cargo appimage --release

# Windows MSI
cargo wix --release
```

---

## 13. Implementation Phases

| Phase | Scope | Est. Time |
|---|---|---|
| **1: Vault shell** | FirstRun, Unlock, Library grid, Settings, keychain integration | 2 weeks |
| **2: Library features** | Search, collections sidebar, tags, sort, favorites, import | 1 week |
| **3: Export/import** | Backup export, backup import, restore | 3 days |
| **4: System viewer** | Open documents with xdg-open / ShellExecuteW | 2 days |
| **5: Polish & packaging** | Keyboard shortcuts, dark/light theme, deb/AppImage/MSI packaging | 1 week |

**Total: ~5 weeks**

---

## 14. Testing Strategy

| Level | Tool | Coverage |
|---|---|---|
| Unit tests | Built-in `#[test]` | Vault operations, keychain, config parsing |
| Integration tests | `#[test]` + tempdir | Full vault open/import/export/backup cycles |
| UI tests | Manual + screenshot comparison | Screen layout, theme rendering |
| Cross-platform CI | GitHub Actions | Linux (Ubuntu) + Windows matrix build |

---

## 15. Future Work (Model B)

When built-in viewers are added:

1. **PDF viewer** — MuPDF Rust bindings (`mupdf` crate) or FFI to `libmupdf`. Render pages to texture, display in Iced canvas.
2. **EPUB reader** — Port `EpubParser` from Kotlin to pure Rust (xml + zip crates). Render HTML to texture via a lightweight HTML renderer.
3. **Comic viewer** — `zip` crate for CBZ, `rar` crate for CBR. Decode images with `image` crate. Display in Iced canvas.
4. **Image viewer** — `image` crate. Display in Iced with zoom/pan.
5. **Notes editor** — Iced text editor widget with markdown preview.
6. **PKPass viewer** — Parse `.pkpass` zip, render pass fields.
