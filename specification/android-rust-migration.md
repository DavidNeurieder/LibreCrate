# Plan: Migrate Android app onto the Rust core (`vault-native`)

**Status:** Proposed
**Date:** 2026-07-19
**Prerequisite context:** `vault-native` Rust core already backs the working CLI. The Android app currently uses the Kotlin `vault-core` module (BouncyCastle + zetetic SQLCipher + Room). This plan replaces `vault-core` with the Rust core via UniFFI/JNI.

**Locked decisions (from user):**
1. **SQLCipher format:** standardize on **`cipher_compatibility = 4`** (Rust side wins). Android app is reconfigured to match; existing user `.db` files get a one-time migration on upgrade.
2. **DB layer:** **drop Room entirely** — the Rust core owns ALL document/collection/tag/FTS access. Single schema source.
3. **Sequencing:** **big-bang cutover** — rewrite the app's data/backup/crypto layers to the Rust core in one pass, then delete `vault-core`.

---

## 0. Current state (from exploration)

**Critical blocker — format mismatch.** The two sides write mutually unreadable `.db` files:

| | Rust core (`db/schema.rs`) | Android app (`SqlCipherOpener.kt:13`, `LibreCrateDatabase.kt:103`) |
|---|---|---|
| `cipher_compatibility` | `4` | zetetic default `1` |
| KDF | `sha512`, `kdf_iter 256000` | SHA1, 64000 (zetetic default) |
| page size | 4096 | 4096 (matches) |
| key encoding | **raw 32-byte** (`x'<hex>'`) | **passphrase** `ByteArray` |

`SqlCipherOpener` uses `SQLiteDatabase.openOrCreateDatabase(path, password, null)` (zetetic default = compat 1). `LibreCrateDatabase` uses `SupportFactory(passphrase, null, false)` (same default). Both must change.

**App surface that touches `vault-core`** (relative to `app/src/main/java/com/librecrate/app`):
- `domain/BackupManager.kt` — VaultExporter/Importer, BackupRestoreService, SqlHandle* adapters
- `domain/DatabaseMerger.kt` — VaultDatabaseMerger
- `data/model/Mappings.kt` — Vault* ↔ Room model mappers
- `data/encryption/EncryptionManager.kt` (+ `AndroidKeyStoreCryptographer`, `FileKeyStore`) — **stays Kotlin** (Keystore/device-key boundary)
- `data/db/LibreCrateDatabase.kt` — Room DB + FTS DDL (to be deleted)
- `data/storage/AndroidFileStorage.kt` — Storage impl
- `data/import/DocumentImporter.kt` — FileEncryptor + reader processors
- `ui/export/ExportViewModel.kt`, `ui/library/LibraryViewModel.kt`, `ui/viewer/*`, `ui/unlock/*`, `ui/settings/SettingsViewModel.kt`

**Two parallel DB stacks today:** Room (live UI) + raw vault-core `SqlHandle` (backup/merge). Migration unifies on the Rust core.

**Rust core gaps for Android** (from assessment): no UniFFI/cdylib surface; missing collection CRUD, tag CRUD + document_tags linking, rich document updates (author/description/collection_id/reading_position/current_page/barcode/thumbnail), pagination/filtering, FTS `snippet()`/`highlight()`, in-document matches, `restore_to_layout` helper, WAL checkpoint, schema migration tracking. `MergeStats.documents_conflicted` is never incremented (conflict detection unimplemented).

---

## 1. Phase 0 — SQLCipher alignment + conformance test (PREREQUISITE, blocks everything)

**Goal:** prove a `.db` written by one side opens in the other, before any app rewrite.

### 1.1 Android SQLCipher reconfiguration
- In `SqlCipherOpener.kt`, stop passing the passphrase to `openOrCreateDatabase`; instead open then issue PRAGMAs (or use a `SQLiteDatabaseHook`):
  ```kotlin
  SQLiteDatabase.loadLibs(context)
  val db = SQLiteDatabase.openOrCreateDatabase(path, "", null) // empty pass; key set via PRAGMA
  db.rawExecSQL("PRAGMA cipher_compatibility = 4")
  db.rawExecSQL("PRAGMA cipher_kdf_algorithm = sha512")
  db.rawExecSQL("PRAGMA cipher_hmac_algorithm = sha512")
  db.rawExecSQL("PRAGMA kdf_iter = 256000")
  db.rawExecSQL("PRAGMA cipher_page_size = 4096")
  db.rawExecSQL("PRAGMA key = \"x'${masterKey.toHex()}'\"")   // raw 32-byte key, hex
  ```
  Mirror exactly `db/schema.rs::open_encrypted` PRAGMAs (must stay byte-identical via a shared constants doc).
- Same PRAGMA block must be applied in `LibreCrateDatabase.kt` (Room `SupportFactory` path). With Room, set the key via `SupportFactory` using the **raw key hex** and apply the same `cipher_*` PRAGMAs in a `RoomDatabase.Callback`/`SupportSQLiteOpenHelper` hook. Confirm zetetic 4.5.4 supports `cipher_compatibility=4` + `cipher_kdf_algorithm`/`cipher_hmac_algorithm` pragmas (it does).
- **Key encoding handoff:** the master key is a 32-byte `ByteArray` produced by `EncryptionManager.getMasterKeyForSession()` (Argon2id-derived + AES-KW-unwrapped, already in Kotlin). Pass that raw 32 bytes (hex `x'...'`) to SQLCipher — do NOT re-derive inside SQLCipher. This matches the Rust core, which also takes a raw 32-byte key. (Both sides derive the master key identically from password+salt; only the SQLCipher key encoding must be raw-hex on both.)

### 1.2 Conformance test (the gate)
- Add `vault-native/core/tests/conformance.rs` (or extend `e2e.rs`):
  1. Build a `.db` with the Rust core (`create_encrypted_db` + `add_document_full`).
  2. Save bytes to a temp file with the **zetetic-compatible** PRAGMAs (i.e. the Phase-0-aligned config).
  3. Attempt to open with the **same** PRAGMAs and assert rows readable. (Pure-Rust proof that the config is self-consistent.)
- Add an Android instrumented test (`app/src/androidTest`): take a `.db` bytes produced by the CLI/Rust core, copy into app storage, open with the reconfigured `SqlCipherOpener` using the raw master key, assert `documents` readable. And the reverse: app creates a `.db`, Rust core opens it. **Both directions must pass before Phase 1 starts.**
- Keep `db/schema.rs` PRAGMAs as the single source of truth; document them in `specification/encryption-backup.md`.

### 1.3 One-time user DB migration (design now, implement in Phase 3)
- On app upgrade, detect legacy `compat=1` `.db` (try open with compat=1 raw key; if it opens, it's legacy).
- Migration strategy: open legacy DB (compat=1), `export` its contents via the Rust core, re-`import`/rebuild into a fresh `compat=4` DB. Simpler and safer than in-place PRAGMA re-key. Implement in Phase 3.

---

## 2. Phase 1 — UniFFI binding surface on the Rust core

**Goal:** a `cdylib` + Kotlin bindings that mirror `vault-core`'s public API, taking a **raw master-key `ByteArray`** (not password) for DB open.

### 2.1 Build config
- `vault-native/core/Cargo.toml`: add `uniffi = "0.28"` (or current), set `[lib] crate-type = ["lib", "cdylib"]`. Add `uniffi` to deps. Keep `rlib` for the CLI.
- Add `cargo-ndk` for Android cross-build. Add a `build.rs` invoking `uniffi-bindgen` to emit `vault_core.kt` + JNI symbols.
- Workspace: no change (core + cli already members).

### 2.2 FFI-friendly API (new module `vault-native/core/src/ffi.rs` or UDL)
Expose, all taking `master_key: Vec<u8>` (raw 32 bytes) where a DB is opened:
- **crypto:** `derive_key(password, salt, params) -> Vec<u8>`, `generate_salt() -> Vec<u8>`, `wrap_key(kek, pt) -> Vec<u8>`, `unwrap_key(kek, wrapped) -> Vec<u8>`, `aes_gcm_encrypt(data, key) -> (iv, ct)`, `aes_gcm_decrypt(iv, ct, key) -> data`.
- **format:** `export_vault(files, db, password, keys, params) -> Vec<u8>`, `import_vault(bytes, password) -> ImportedContentsFFI`, `create_vault_layout(dir, password) -> Vec<u8>`.
- **db:** `open_db(path, master_key) -> DbHandleFFI`, `list_documents(handle) -> Vec<DocumentFFI>`, `get_document(handle, id)`, `add_document(handle, doc)`, `update_document(handle, id, title, favorite, ...)`, `delete_document(handle, id)`, `list_collections`, `add_collection`, `update_collection`, `delete_collection`, `list_tags`, `add_tag`, `update_tag`, `delete_tag`, `link_document_tag`, `search(handle, query, limit, offset) -> Vec<SearchResultFFI>` (with snippet/highlight), `search_in_document(handle, id, query) -> Vec<SnippetFFI>`.
- **merge:** `merge_branch_a(backup_db_path, backup_master_key, current_handle, files, backup_key, local_key, files_dir) -> MergeStatsFFI`, `restore_fresh(contents, db_data, enc_dir, db_dir, files_dir)`.
- **FFI record types:** `DocumentFFI`, `CollectionFFI`, `TagFFI`, `SearchResultFFI` (id, title, snippet, rank), `SnippetFFI` (text, page), `MergeStatsFFI`, `ImportedContentsFFI`, `VaultManifestFFI`.
- **Error:** flatten `core::error::Error` into a UniFFI enum (e.g. `VaultError { message: String }`) so it crosses the boundary.
- Platform callbacks stay in Kotlin: the binding does **not** include `KeyStore`/`KeyStoreCryptographer`/`KeyManager`/`RestoreEnvironment`/`Storage` — those remain Kotlin and feed raw bytes/keys into the Rust calls. (Rust `Storage`/`files` access is done by passing `files_dir: String` paths; the core reads/writes files directly.)

### 2.3 Codegen + NDK build
- `uniffi-bindgen generate` → `app/src/main/java/com/librecrate/app/vault/VaultCore.kt` + JNI `.so`.
- `cargo ndk -t arm64-v8a -t armeabi-v7a build --release` → place `.so` in `app/src/main/jniLibs/{abi}/`.
- ProGuard rule: `-keep class com.librecrate.app.vault.** { *; }` and keep JNI package.

---

## 3. Phase 2 — Close Rust core gaps for Android

Implement in `vault-native/core` (no Android deps), with `cargo test` + conformance coverage:

1. **Collection CRUD:** `add_collection`, `update_collection`, `delete_collection`, `get_collection` in `db/queries.rs`.
2. **Tag CRUD + linking:** `add_tag`, `update_tag`, `delete_tag`, `link_document_tag`, `unlink_document_tag`, `get_tags_for_document`, `get_documents_for_tag`.
3. **Rich document update:** extend `update_document` (or add `update_document_full`) to set `author`, `description`, `collection_id`, `reading_position`, `current_page`, `page_count`, `barcode_format`, `barcode_value`, `thumbnail_path`, `is_conflict`, `conflict_with`. Add `set_reading_position`, `set_current_page`.
4. **Pagination/filtering:** `list_documents(handle, limit, offset, collection_id?, favorite_only?, tag_id?)`.
5. **FTS snippet/highlight:** add `search_with_snippet` using FTS5 `snippet()`/`highlight()` (marker chars), return `SearchResultFFI { id, title, snippet }`. Add `search_in_document(handle, id, query) -> Vec<SnippetFFI>` via `highlight()` + page extraction (port `VaultSearchEngine.parseHighlight/extractSnippet/extractPageNumber`).
6. **Conflict detection:** implement in `merge::branch_a_merge` — when a doc exists in both and content differs (file_size/mime_type/modified_at), set `is_conflict` on the original and insert a copy with `conflict_with` (port `VaultDatabaseMerger.merge` logic). Increment `documents_conflicted`.
7. **restore helper:** `restore_to_layout(contents, db_data, enc_dir, db_dir, files_dir)` writing keys+db+files (supersedes ad-hoc code in CLI merge). Handle WAL checkpoint (`PRAGMA wal_checkpoint(TRUNCATE)`) and delete `-wal`/`-shm`.
8. **Schema version/migration:** add a `user_version` pragma tracker so future schema changes migrate; document current version in `specification/`.
9. **Thumbnail:** add `store_thumbnail(id, bytes)` / `load_thumbnail(id)` in `db/storage.rs` (files/<id>.thumb) since the app needs thumbnails.
10. **Tests:** unit tests for each new fn; extend `e2e.rs` with collection/tag/link/search-snippet/conflict scenarios.

> Note: file blobs stay **unencrypted at `files/<id>`** per `specification/unencrypted-file-blobs.md`. The merge re-encryption path (`backup_key`+`local_key`) remains available but is not used by the app's default flow.

---

## 4. Phase 3 — Android rewire (big-bang)

**Goal:** delete `vault-core`, Room, BouncyCastle; app talks only to the Rust core via UniFFI.

### 4.1 Delete / replace
- Delete `app/src/main/java/.../data/db/LibreCrateDatabase.kt` and all `@Entity`/`@Dao` files in `data/model`, `data/db`. Remove Room from `app/build.gradle.kts`.
- Delete `vault-core/` module; remove `implementation(project(":vault-core"))` from `app/build.gradle.kts`. Remove BouncyCastle + kotlinx-serialization-json from app deps (now only used transitively by vault-core, which is gone). Keep `net.zetetic:android-database-sqlcipher` ONLY if still needed by the raw `SqlCipherOpener` hook for legacy migration (Phase 1.3); otherwise drop it too (Rust core uses bundled SQLCipher in the `.so`).
- Delete `vault/database/SqlCipherOpener.kt`, `SqlHandleAndroid.kt`, `SqlHandleSupportAndroid.kt` (Raw SQLCipher adapters no longer needed).

### 4.2 New data layer
- Add `data/vault/VaultRepository.kt` — the single app entry point to the Rust core. Wraps the UniFFI `DbHandleFFI` (opened with `EncryptionManager.getMasterKeyForSession()`), exposes Kotlin-suspend/`Flow` APIs: `documents(limit, offset, filter)`, `document(id)`, `addDocument(...)`, `updateDocument(...)`, `deleteDocument(id)`, `collections()`, `tags()`, `search(query)`, `searchInDocument(id, query)`. Converts `DocumentFFI` ↔ existing UI model (`VaultDocument` or a new `Document` UI model).
- Replace `data/model/Mappings.kt` to map `DocumentFFI`/`CollectionFFI`/`TagFFI` ↔ UI models (the old Room `Document`/`Tag`/`Collection` entities are gone).
- `data/import/DocumentImporter.kt`: replace `FileEncryptor` usage with the Rust core's `add_document` (blob + FTS). Keep `vault-reader` processors (PDF/EPUB) — they're separate modules, untouched.
- `data/storage/AndroidFileStorage.kt`: replace with direct `files/` path access via the Rust core `Storage` (or keep as a thin wrapper passing `files_dir`).

### 4.3 Crypto / backup / merge rewire
- `domain/BackupManager.kt`: replace `VaultExporter/Importer` + `BackupRestoreService` with Rust-core `export_vault`/`import_vault`/`restore_fresh`/`merge_branch_a`. The `RestoreEnvironment` callbacks collapse: `getSessionMasterKey()` → pass raw master key to Rust; `setupDeviceKey()` → call `EncryptionManager.setupDeviceKeyForDailyUnlock()`.
- `domain/DatabaseMerger.kt`: call Rust `merge_branch_a`.
- `ui/export/ExportViewModel.kt`, `ui/viewer/*`, `ui/library/LibraryViewModel.kt`: replace `FileEncryptor.decryptBytes` with Rust `aes_gcm_decrypt` (or load plaintext blob via `load_file` since files are unencrypted). Replace `VaultSearchEngine.sanitizeFtsQuery` with Rust `search`/`search_in_document`.
- `ui/settings/SettingsViewModel.kt`: password set/change/verify now go through `EncryptionManager` (Kotlin, unchanged) + re-key the DB via Rust `rekey` (open with old raw key, re-open with new raw key — or implement a `change_master_key` in the core that re-writes the wrapped key + re-opens). 

### 4.4 Keep in Kotlin (platform boundary — DO NOT port)
- `data/encryption/EncryptionManager.kt`, `AndroidKeyStoreCryptographer.kt`, `FileKeyStore.kt` — Android Keystore, biometric/device-key unlock, master-key derivation/unwrap. These produce the raw 32-byte master key handed to Rust. `setupDeviceKeyForDailyUnlock` / `resolveDeviceKeyForBackup` stay here.

### 4.5 Legacy DB migration (from Phase 1.3)
- On first launch after upgrade: if a `compat=1` `librecrate.db` exists, open it (zetetic compat=1, raw key), export via Rust core, rebuild a `compat=4` DB in place, delete the old. Guard with a one-time flag in `SharedPreferences`.

### 4.6 Build / ProGuard
- Add UniFFI JNI `.so` to `jniLibs`. Add `-keep` rules. Test a **release** build on a device before shipping.

---

## 5. Phase 4 — Conformance + cleanup

- Cross-surface conformance: Rust CLI `create`/`export` ↔ Android open/restore, and Android export ↔ Rust CLI `export`/`inspect`. Add to CI.
- Run existing Android instrumented tests against the Rust core.
- Confirm `vault-core` fully deleted and its deps removed from the app.
- Update `specification/rust-core-plan.md` status to "Implemented for CLI + Android".

---

## 6. Key risks & mitigations

| Risk | Mitigation |
|---|---|
| SQLCipher interop still broken after Phase 0 | The Phase 0 conformance test (both directions) is a hard gate; do not start Phase 1 until green. |
| zetetic 4.5.4 PRAGMA support | Verified: supports `cipher_compatibility=4` + `cipher_kdf_algorithm`/`cipher_hmac_algorithm`. Pin zetetic version. |
| UniFFI JNI + ProGuard stripping | `-keep` generated bindings; test release build on device in Phase 3.6 before shipping. |
| Big-bang regressions | Keep `vault-core` in git history; feature-flag the data layer only if needed mid-flight. Instrumented tests in Phase 4. |
| Raw-key encoding mismatch | Both sides must use `x'<hex32>'` PRAGMA key, never the passphrase param. Documented + tested. |
| FTS5 snippet/highlight dialect | FTS5 aux functions must be enabled in the bundled-sqlcipher build (already are per Phase-1 CLI search). Verify on Android `.so`. |
| Bundle size | Rust `.so` (~1–2 MB arm64) replaces BouncyCastle dex + vault-core jar (~neutral). Keep zetetic only during legacy migration, then drop. |
| `document_tags` / conflicts untested | New Rust unit tests in Phase 2.9. |

---

## 7. File/area checklist

**Rust core (`vault-native/core`):** `Cargo.toml` (uniffi, cdylib), `build.rs` (uniffi-bindgen), `src/ffi.rs` (bindings), `src/db/queries.rs` (CRUD/pagination/linking), `src/db/fts.rs` (snippet/highlight), `src/db/storage.rs` (thumbnail), `src/merge.rs` (conflict detection), `src/format/export.rs`+`import.rs` (restore_to_layout), `src/error.rs` (FFI error), `tests/conformance.rs`, `tests/e2e.rs` (extended).

**Android (`app`):** `SqlCipherOpener.kt` (PRAGMAs), `LibreCrateDatabase.kt` (deleted), `data/db/*` + `data/model/*` (deleted), `data/vault/VaultRepository.kt` (new), `data/model/Mappings.kt` (rewritten), `domain/BackupManager.kt` + `DatabaseMerger.kt` (rewired), `data/import/DocumentImporter.kt` (rewired), `data/encryption/*` (kept), `ui/*ViewModel.kt` (rewired to VaultRepository), `build.gradle.kts` (deps), `proguard-rules.pro` (keep).

**Deleted:** `vault-core/` module, `vault/database/*` adapters.

**Build infra:** `cargo-ndk` Android build, `.so` → `jniLibs`, UniFFI Kotlin bindgen in CI.
