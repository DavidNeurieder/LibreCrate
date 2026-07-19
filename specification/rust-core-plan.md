# Plan: Shared Rust Core (B2) for LibreCrate

**Status:** Proposed  
**Scope:** Android app + CLI + Linux GUI + Windows GUI, all with full functionality  
**Core language:** Rust  
**DB layer:** single SQLCipher V4 stack owned by the core

---

## 1. Goal & scope

Replace the current per-surface crypto/DB duplication with **one Rust core** (`vault-native`) that owns:

- Key derivation (Argon2id)
- Vault container format (MAGIC + manifest JSON + AES-GCM encrypted ZIP)
- AES-256-GCM file encryption/decryption
- AES-KW (RFC 3394) master-key wrap/unwrap
- The SQLCipher DB layer: schema, document/collection/tag queries, FTS5 search
- Branch A/B/C restore and merge with file re-encryption

Consumed by four surfaces, all with **identical, full functionality**:

1. **Android app** — via JNI (UniFFI); Compose UI + device-key unlock stay Kotlin
2. **CLI** — native binary, no JVM
3. **Linux GUI**
4. **Windows GUI**

Outcome: one schema, one SQLCipher V4 config, one conformance test matrix; single-binary CLI and desktops with no JVM requirement.

---

## 2. Decision rationale

### Why B2 (unified core) instead of B1 (core + separate DB stacks)

| Concern | B1 (keep Room) | B2 (unified core) |
|---------|-----------------|---------------------|
| Schema definitions to maintain | 2 (Room entities + C/Rust DB layer) | 1 (Rust core) |
| SQLCipher engines/configs | 2 (zetetic + core's sqlcipher) | 1 (core's sqlcipher) |
| DB query implementations | 2 (Room DAOs + core queries) | 1 (core) |
| Cost to add a new surface | High (reimplement DB + schema parity) | Low (just bind the core) |
| Drift risk | High (schema/engine diverge) | Low (single source) |
| Upfront app refactor | Low (keep Room) | High (replace Room paths) |
| Ongoing maintenance | Higher, grows with surfaces | Lower, flat |

With **4 surfaces all doing full DB work**, B1's duplicated DB stack is a persistent multiplying maintenance/testing tax. **B2 wins clearly.**

### Why Rust over C

- **UniFFI** — auto-generates JNI + Kotlin bindings for Android (hand-written JNI for C would be substantial boilerplate for a core with many exported functions)
- **Memory safety** — the core handles keys, ciphertext, KDF output; Rust eliminates entire bug classes
- **`cargo test` + crates** — conformance/unit tests are easy to write and run in CI
- **Tauri option** — Rust desktop GUI calls the core with zero FFI boundary (same process, same language)

C's edge (smallest binary, no toolchain friction) doesn't outweigh these for this project's surface count and security sensitivity.

---

## 3. Architecture

```
vault-native/  (Rust crate, no Android deps)
  crypto/   argon2, aes-gcm, aes-kw
  format/   vault container (MAGIC, manifest JSON, AES-GCM blob, ZIP)
  kdf/      KDF params, userKey↔masterKey wrap/unwrap
  merge/    Branch A/B/C, conflict detection, file re-encryption
  db/       schema (tables+FTS5+triggers), queries, FTS5  [rusqlite + sqlcipher]
        │
        ├─► Android  : UniFFI → JNI → Kotlin bindings; Compose UI + device-key unlock stay Kotlin
        ├─► CLI      : Rust main + clap → native binary (no JVM)
        ├─► Linux GUI: Tauri or Qt binding the core
        └─► Windows GUI: Tauri or Qt binding the core
```

---

## 4. Module breakdown — port from `vault-core` (Kotlin)

| `vault-core` (Kotlin) | Rust module | Notes |
|---|---|---|
| `Argon2HasherImpl` (BouncyCastle) | `crypto::argon2` | `argon2`/`rust-argon2` crate; params: memoryCost=19456, iterations=2, parallelism=2, hashLength=32, Argon2id v0x13 |
| `FileEncryptor` (AES-GCM) | `crypto::aes` | `aes-gcm` crate; IV 12B, tag 16B, layout IV‖CT‖tag |
| `KeyWrap` (AES-KW RFC 3394) | `crypto::aes_kw` | `aes` crate + RFC 3394 implementation |
| `VaultExporter` / `VaultImporter` | `format::export` / `format::import` | `zip` crate + `serde`/`serde_json` for manifest |
| `VaultPackage` / `VaultManifest` | `format::package` / `format::manifest` | MAGIC(16), version(4), manifest-len(4); `@Serializable` → `#[derive(Serialize,Deserialize)]` |
| `BackupRestoreService` (Branch A/B/C) | `merge` | same orchestration; `getSessionMasterKey`/`setupDeviceKey` are platform callbacks |
| `VaultDatabaseMerger` | `merge::db_merge` + `db` | row-level merge + file re-encryption with platform key |
| `DatabaseSchema` (tables+FTS5+triggers) | `db::schema` | `rusqlite` + FTS5; replicate `documents_fts` + 3 triggers |
| `KdfParams`, `KeyDerivation` | `kdf` | identical params, two-salt separation (manifest salt ≠ keys salt) |

**Reference/oracle:** `vault-core` + `specification/encryption-backup.md`.

---

## 5. Toolchain & build

- **Core:** `cargo`, crate `vault-native` with a C-ABI surface (`uniffi` / plain `#[no_mangle]`)
- **Android:** `cargo-ndk` → `.so` for `arm64-v8a` + `armeabi-v7a` (matches app's `abiFilters`); UniFFI generates JNI + Kotlin bindings
- **CLI:** `cargo build --release` host binary; `clap` for args
- **Desktops:** Tauri (recommended for Rust) or Qt with `cbindgen`-generated headers
- **CI:** build core for all targets; `cargo test`; cross-compile Android `.so`; package releases

---

## 6. Phased implementation

### Phase 0 — Core scaffold + non-DB crypto
- Set up `vault-native` crate with `cargo-ndk`, `uniffi`, and C-ABI surface
- Port `crypto/` (Argon2id, AES-GCM, AES-KW), `format/` (vault container), `kdf/`, `merge/` (without DB)
- Build CLI binary with `vault` commands (import/export/merge/inspect/extract/roundtrip) + `crypto` commands
- **Deliverable:** single-binary CLI with no JVM; Kotlin `vault-core` retained as oracle

### Phase 1 — DB layer
- Port `DatabaseSchema` + document/collection/tag/FTS5 queries to `rusqlite` + sqlcipher
- Add `document` and `search` CLI commands
- **Deliverable:** CLI has full functionality (library browse, document ops, FTS5)

### Phase 2 — Android rewire
- UniFFI bindings integrated into the Android app
- Replace Room usage in backup/restore + library document paths with Rust-core calls
- Keep Compose UI + device-key unlock glue in Kotlin
- Remove BouncyCastle, kotlinx-serialization-json, `vault-core.jar` from app deps
- Add Rust `.so` (~1–2 MB arm64; net app size approximately neutral)
- ProGuard: `-keep` UniFFI JNI bindings
- **Deliverable:** Android app uses the same Rust core as CLI/desktops

### Phase 3 — Desktop GUIs
- Tauri apps (Linux + Windows) binding the Rust core directly (no FFI boundary)
- Implement the same feature surfaces as the Android app
- Package as native installers (AppImage/deb, MSI)

### Phase 4 — Conformance + cleanup
- All cross-surface conformance tests green
- Optionally delete Kotlin `vault-core` (or retain as test-only oracle)

---

## 7. Per-surface notes

### Android
- Device-key unlock (`setupDeviceKeyForDailyUnlock`) stays Kotlin — platform boundary, not crypto
- UniFFI JNI symbols must be kept via ProGuard rules
- Existing test suite (unit + instrumented) runs against the Rust core backed by a SQLCipher DB in tests

### CLI
- Replace current Kotlin + Clikt front-end with Rust + `clap`
- Same command surface as today: `vault`, `crypto`, `document`, `search`, `bench`

### Linux / Windows GUI
- Tauri (Rust shell) renders the UI; all core logic is native
- FTS5 and document browsing work because the core owns the DB

---

## 8. SQLCipher V4 pinning (critical)

The on-disk `.db` must open identically across zetetic SQLCipher (app during transition) and `rusqlite`+`sqlcipher` (core). Pin in the Rust core:

- SQLCipher **V4** defaults; `withRawUnsaltedKey(masterKey)` semantics
- PBKDF2-HMAC-SHA512 for the SQLCipher KDF
- Page size, HMAC algorithm, and legacy config matching the spec
- Same KDF iteration count in both stacks

Conformance tests: assert an app-created `.db` opens correctly in the Rust core and vice versa.

---

## 9. Testing & conformance strategy

- **Unit:** `cargo test` per primitive against known vectors (Argon2 RFC test vector, AES-GCM, AES-KW)
- **Core conformance:** generate vault/DB with the Rust core → assert byte-identical container + successful restore + decrypted file content matches `specification/encryption-backup.md` test vectors
- **Cross-surface:** create DB/vault with the Rust core → open/restore with each surface (Android emulator, CLI, Linux GUI, Windows GUI) and the reverse direction
- **Oracle:** existing 44 CLI tests + 16 `vault-core` tests + Android app tests remain green during transition. They are the reference until the Rust core supersedes them.

---

## 10. Dependency & size impact

| Surface | Before | After | Delta |
|---------|--------|-------|-------|
| **Android APK** | BouncyCastle (~1–2 MB shrunk dex) + kotlinx-serialization (256 KB) + vault-core (108 KB) | Rust `.so` (~1–2 MB arm64) | **≈ neutral** |
| **CLI** | 38 MB fat jar + JRE required | ~1–5 MB native binary, no JVM | **−37 MB + no JRE dependency** |
| **Desktops** | n/a (new) | native binary, no JVM | new, but no big dependency |

`libsqlcipher.so` (3.6 MB arm64) stays in the app either way — it's the C SQLCipher engine, used by both zetetic and rusqlite.

---

## 11. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Upfront app refactor (replace Room) | Phase: ship CLI first (oracle still Kotlin), then rewire app, then desktops |
| SQLCipher interop (zetetic vs rusqlite) | Pin V4 config in core; conformance tests across surfaces |
| UniFFI JNI + ProGuard stripping | `-keep` generated bindings; test release build on device before shipping |
| cargo-ndk cross-compile | Pin NDK version in CI; build both arm64-v8a + armeabi-v7a |
| FTS5 missing in sqlcipher build | Enable FTS5 extension via sqlcipher feature; test search early in Phase 1 |
| Memory safety of Rust FFI boundary | Keep `unsafe` minimal and documented; validate inputs in the C-ABI wrappers |
| Tauri Windows packaging | Test MSI/EXE bundling in CI during Phase 3 |
| Rust ecosystem churn | Pin crate versions; update deliberately, not automatically |

---

## 12. Out of scope (for this plan)

- B1 (keep Room as a separate DB stack) — rejected because full functionality on 4 surfaces makes the duplicated DB stack a permanent maintenance liability, not a temporary simplification
- Full Rust/C app UI — Compose UI stays Kotlin for Android; desktops use Tauri/Qt
- GraalVM native image (Option A) — incompatible with the "one core for all surfaces" goal

---

## 13. Open decisions

- **Desktop GUI toolkit:** defaulting to **Tauri** (Rust shell, zero FFI with the core, single `cargo` build). Acceptable alternatives: **Qt** (C++, `cbindgen` headers), **GTK** (C). If you prefer a different toolkit, Phase 3 is adjusted accordingly.
