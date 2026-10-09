# LibreCrate Security Hardening Implementation Plan

Status: **Implemented (2026-10-09) — all 12 PRs landed, awaiting review.**  
Previous status: **Draft (unapproved)** — for review.  
Author: security review (see `ideas/improvements.txt` / this document for origins).  
Last updated: 2026-10-09

This document converts the security hardening proposal (`ideas/improvements.txt`)
into a concrete, codebase-grounded implementation plan. It references **exact
files, line numbers, and current behavior** so each PR can be executed without
re-discovery. It follows the milestone split from the proposal:

- **Milestone 1 — Security fixes (P0):** PRs 1–4
- **Milestone 2 — Secret lifecycle (P1):** PRs 5–7
- **Milestone 3 — Backup v2 (P2):** PR 8
- **Milestone 4 — Crypto/parser hardening (P2):** PRs 9–11
- **Milestone 5 — Ongoing security (P2):** PR 12

Milestone 1 is the recommended first stopping point: the highest-risk
untrusted-input vulnerabilities are addressed without forcing a backup-format
migration.

---

## 0. Phase 0 — Security invariants

The implementation must satisfy these invariants (tested, not assumed):

1. The master key is randomly generated.
2. Passwords are only ever used through the KDF.
3. Derived keys and master keys are zeroized when no longer needed.
4. A locked vault has no usable master key and no open encrypted DB connection.
5. No archive-controlled path can escape the intended restore directory.
6. No attacker-controlled KDF parameters can cause unbounded resource consumption.
7. Backup data is authenticated before being trusted.
8. Archive extraction has bounded CPU, memory, disk, and entry consumption.

### Current state vs. invariants (from code exploration)

| Invariant | Status | Evidence |
|---|---|---|
| 1. Random master key | ✅ | `core/src/crypto/aes_kw.rs:7-12` (`generate_master_key`, OsRng) |
| 2. Passwords only via KDF | ✅ | `core/src/kdf.rs`, `core/src/crypto/argon2.rs` |
| 3. Keys zeroized when done | ✅ | `core/src/crypto/secrets.rs` (`MasterKey`/`DerivedKey`, zeroize-on-drop); FFI byte arrays zeroed on the Kotlin side (`RustKeyManager`) |
| 4. Locked vault has no key/DB | ✅ | `core/src/ffi.rs` `DbHandle` (Arc\<Mutex\<DbState\>\>, `lock()`/`is_locked()`/`Drop`); Android locks also call `vaultRepository.close()` |
| 5. No path escape on restore | ✅ | `core/src/format/path.rs` (`safe_archive_path`, `contained_join`) enforced in `extract_zip`, `merge.rs`, `vault_ops.rs` |
| 6. KDF params bounded | ✅ | `core/src/crypto/argon2.rs` `KdfPolicy` (64 MiB/10/4) validated before Argon2 in import, instance-KDF parse, and all FFI derivations |
| 7. Backup authed before trusted | ✅ | v2 format authenticates the JSON manifest (salt/KDF/counts) as GCM AAD (PR 8); v1 still importable on the hardened path |
| 8. Extraction bounded | ✅ | `BackupLimits` (package, total/entry size, entry/document counts, manifest size) + thumbnail parser caps (PR 11) |

### Regression-test suite (PR 4) — implemented as flat files (integration tests
### compile one file per crate; Rust does not support `tests/security/` submodules)

```
vault-native/core/tests/
├── security_kdf_bounds.rs      ← PR 1 invariants (incl. crafted over-cap manifest)
├── security_archive_path.rs    ← PR 2 invariants (incl. zip-slip import + restore)
├── security_backup_limits.rs   ← PR 3 invariants (extract_zip + manifest-size bounds)
└── security_corruption.rs      ← PR 4 robustness (truncation, bitflips, doc-count lies)
```

---

## Milestone 1 — Security fixes (P0)

### PR 1 — KDF parameter validation (P0)

**Problem:** backup import derives with attacker-controlled Argon2 parameters
before authentication.

- `core/src/format/import.rs:25-35` builds `Argon2Params::new(manifest.argon2_*)`
  directly from the plaintext manifest and calls Argon2 immediately.
- `core/src/vault_ops.rs:137-146` parses `params.toml` from the (already
  decrypted) backup, also with no upper bounds.
- `core/src/vault_ops.rs:19-40` (`parse_kdf_params_from_toml`) fills missing
  fields from defaults — no caps.
- The `argon2` crate only enforces *lower* bounds (`Params::new` in
  `core/src/crypto/argon2.rs:47-53`). A manifest can request `u32::MAX`
  (≈4 TiB) memory / iterations → memory/CPU exhaustion.
- **Bonus defect:** `format::import::import`'s `_kdf_params` third argument
  (`import.rs:18`) is **unused** — callers pass defaults that are ignored;
  manifest params always win.

**Change:**

1. Add a single policy object in `core/src/crypto/argon2.rs`:

   ```rust
   pub struct KdfPolicy {
       pub max_memory_kib: u32,
       pub max_iterations: u32,
       pub max_parallelism: u32,
   }
   ```

   Defaults to be benchmarked (see PR 10) — proposed starting point:
   `max_memory_kib = 64 * 1024`, `max_iterations = 10`, `max_parallelism = 4`.
   These sit above both normal parameter sets (Android 16384/3/2, desktop
   19456/2/2) so legitimate vaults are unaffected.

2. Add `validate_kdf_params(&Argon2Params, &KdfPolicy) -> Result<()>` that
   rejects over-limit, zero, and overflow values, and call it **before** Argon2
   executes at:
   - `core/src/format/import.rs:25` (container key — the untrusted path)
   - `core/src/vault_ops.rs:137-146` (user key from `params.toml`)
   - FFI helpers in `core/src/ffi.rs:699-711` (`derive_key`),
     `ffi.rs:770-782` (`verify_password`), `ffi.rs:784-796`
     (`derive_backup_master_key`)
3. Validate the manifest's `version` and `kdf` fields. Currently
   `core/src/format/package.rs:20` **reads the header version and discards it**,
   and `import.rs` never checks `manifest.version` or `manifest.kdf`. Reject
   unknown versions and anything other than `argon2id`.
4. Fix the dead `_kdf_params` argument: either honor an explicit policy
   argument or remove it and centralize policy inside `format::import`.

**Tests:** valid params accepted; memory/iterations/parallelism too large
rejected; zero values rejected; integer overflow rejected; validation occurs
**before** Argon2 executes (observe the error type, not a KDF result); unknown
version/kdf rejected.

---

### PR 2 — Archive path validation (P0)

**Problem:** restore writes archive-controlled names with no containment.

Direct `Path::join` on untrusted strings at:
- `core/src/merge.rs:32` — `encryption_dir.join(&kv.key)`
- `core/src/merge.rs:46-50` — `files_dir.join(&kv.key)` (after
  `create_dir_all(parent)`)
- `core/src/vault_ops.rs:254-258` and `344-349` (merge paths)
- `core/src/merge.rs:255, 265` — `reencrypt_files`, using
  `doc.file_path.rsplit('/').next()` (DB/archive-controlled file name)

There is **no** `is_absolute`/`components()`/`ParentDir`/symlink check anywhere
on the restore path; `zip = "2"` sanitization is never consulted.

**Change:**

1. Add one safe resolver:

   ```rust
   fn safe_archive_path(name: &str) -> Result<PathBuf> {
       let path = Path::new(name);
       if path.is_absolute() { return Err(Error::InvalidArchivePath); }
       for component in path.components() {
           match component {
               Component::Normal(_) => {}
               _ => return Err(Error::InvalidArchivePath),
           }
       }
       Ok(path.to_path_buf())
   }
   ```

   Rejects: `../foo`, `../../foo`, `/foo`, `C:\foo`, `C:/foo`, `\\server\share`,
   `.`, `..`, and any prefix/root/cur-dir component.

2. Apply it at **every** write site listed above: `safe_archive_path(&key)?`
   then `root.join(relative)`. This covers the `files/`, `keys/`, and `db/`
   entry types in `extract_zip`/`branch_b_fresh_install`/merge paths.

3. Defense-in-depth containment check after join:

   ```rust
   let root = root.canonicalize()?;
   let destination = root.join(relative);
   if !destination.starts_with(&root) { return Err(Error::InvalidArchivePath); }
   ```

   The restore directory should be a freshly `create_dir_all`'d controlled
   directory, and **symlink entries must be rejected outright** (check
   `entry.unix_mode()` / refuse entries whose name resolves through a symlink —
   the safest rule is rejecting any entry that isn't a normal file).

4. Reject `files/` and `keys/` collisions (duplicate normalized keys) rather
   than silently overwriting.

**Tests (permanent regression):** malicious archives containing
`files/../../outside`, `files/../../../tmp/test`, `files//absolute`,
`files/../database`, `files/C:/Windows/...`, `files/\absolute`, a symlink
entry, and duplicate keys. Verify **nothing outside the designated restore
directory changes**.

---

### PR 3 — Backup resource limits (P0)

**Problem:** zip-bomb / OOM surface; the whole backup is held in RAM multiple
times and every entry is read with no cap.

- `core/src/format/import.rs:64-66` — `entry.read_to_end(&mut content)` with no
  size check.
- `core/src/format/package.rs:34` copies `encrypted_blob`; decryption produces
  `plain_zip` in memory; each entry is another `Vec<u8>` →
  `ImportedContents` (`import.rs:11-16`). Peak ≈ 2–3× backup size.
- `manifest.document_count` is written (`export.rs:52,60`) but never read.
- Only a *minimum* length check exists (`import.rs:38-40`).

**Change:** Add a central policy:

```rust
pub struct BackupLimits {
    pub max_package_size: u64,
    pub max_uncompressed_size: u64,
    pub max_entries: usize,
    pub max_entry_size: u64,
    pub max_manifest_size: u64,
}
```

- Enforce on **actual bytes read**, never ZIP metadata (which an attacker can
  forge).
- Track `total_uncompressed += entry_size` across entries; reject on exceed.
- Cap `manifest.document_count`.
- Enforce at the FFI/entry boundary: `ffi.rs:853-868` (`restore_backup_to_dir`),
  `ffi.rs:878-894` (`restore_to_layout`), including the SAF/byte-array input
  path on Android.
- Values require tuning (see PR 10 matrix) but must comfortably exceed real
  vaults while bounding a single import.

**Tests:** oversized package, oversized single entry, oversized decompressed
total (zip bomb), too many entries, oversized manifest — all rejected with
distinct errors; cumulative (not per-entry) limits verified.

---

### PR 4 — Security regression tests (P0)

Deliverable is the `tests/security/` suite referenced in Phase 0 plus the
in-module unit tests from PRs 1–3. It covers:

- KDF parameter caps + ordering (validation before derivation)
- Path traversal vectors (PR 2 list) with on-disk verification
- Backup corruption (truncated header, bad magic, bad version, tampered
  ciphertext → authentication error, not partial restore)
- AES-KW RFC 3394 vectors (see PR 9 — land the vectors here)
- Key lifetime / zeroization smoke tests (hard to assert in Rust; assert that
  `lock()` closes the DB and drops key wrappers — see PRs 5–6)
- Resource limits (PR 3)

**Acceptance:** `cargo test --workspace` green with the new suite; every vector
in the malicious-archive matrix actually fails *before* this code and passes
after.

---

## Milestone 2 — Secret lifecycle (P1)

### PR 5 — Secret types and zeroization (P1)

**Problem:** no `zeroize` usage; keys are bare `Vec<u8>`.

- `core/src/ffi.rs:10` — `DbHandle.encryption_key: Option<Vec<u8>>`
- `core/src/crypto/argon2.rs:62-64` — `derive_key_and_zero` is a **fake**: a
  no-op alias for `derive_key` (no zeroization), called from `kdf.rs:8`
- `cli/src/session.rs:9-10` — `Session.master_key: Vec<u8>`, `password: String`
- Android `RustKeyManager.sessionMasterKey: ByteArray?`

**Change:**

1. Add `zeroize` to `core/Cargo.toml` (and propagate to cli).
2. Introduce secret wrappers in `core/src/crypto/`:

   ```rust
   #[derive(Zeroize, ZeroizeOnDrop)]
   pub struct MasterKey([u8; 32]);

   #[derive(Zeroize, ZeroizeOnDrop)]
   pub struct DerivedKey(Vec<u8>);
   ```

3. Migrate the FFI surface incrementally — UniFFI records cannot carry
   `ZeroizeOnDrop` wrappers directly, so keep FFI as byte arrays but zero the
   temporary buffers at the boundary, and use the wrappers internally
   (`DbHandle.encryption_key`, `kdf.rs`, `aes_kw.rs`).
4. Replace `derive_key_and_zero`: rename to `derive_key` (its real behavior) or
   actually zeroize. The type system should communicate ownership:

   ```
   Password → Argon2 → DerivedKey → AES-KW → MasterKey
   ```

**Tests:** wrappers zeroize on drop (observe heap bytes cleared via a test-only
hook); FFI-boundary temps cleared.

---

### PR 6 — Vault lock/session lifecycle (P1)

**Problem:** locking does not actually destroy key material.

- Android `RustKeyManager.lock()` (`app/.../RustKeyManager.kt:82`) only sets
  `sessionMasterKey = null`. The byte array is not zeroed.
- `VaultRepository.handle: DbHandle?` (`VaultRepository.kt:18`) stays open after
  lock; `VaultRepository.close()` (`:46-49`) exists but is **never called on
  lock**.
- On the Rust side the SQLCipher connection and `DbHandle.encryption_key`
  therefore remain live (with the master key) after "lock".
- Bonus: Android unlock runs Argon2 **twice** (`RustKeyManager.kt:40-56`).

**Change:**

1. Rust: add explicit lifetime to `DbHandle` — a `close()`/`lock()` that closes
   the `rusqlite::Connection` and zeroizes `encryption_key`; implement `Drop`.
   Introduce `VaultSession { db: DbConnection, master_key: MasterKey }` with an
   explicit lifecycle:

   ```rust
   fn lock(&mut self) {
       self.db.close();
       self.master_key.zeroize();
   }
   ```

2. Android: `RustKeyManager.lock()` must also close the vault: call
   `VaultRepository.close()` and null+zero `sessionMasterKey`. Wire into the
   existing `ActivityLifecycleLockCallbacks`
   (`LibreCrateApplication.kt:42-59`).
3. Document the state machine:

   ```
   UNLOCKED (MasterKey + DB open + ops allowed)
       ↓ LOCK: close DB, zeroize key, clear temp plaintext, delete temp files
   LOCKED (password required)
   ```

**Tests (lifecycle transitions):**
`unlock→lock→unlock`, `unlock→background→lock`, `unlock→background→foreground`,
`unlock→crash/error during op→lock`, `unlock→failed password→successful
password`.

---

### PR 7 — Streaming / bounded extraction (P1)

Replace the `Vec<KeyValue>`-entire-backup pattern with bounded streaming:

```
ZIP
 ├── DB        → bounded temporary file
 ├── document  → bounded temporary file
 └── key       → bounded memory buffer
```

- `core/src/format/import.rs:49-78` (`extract_zip`): write DB and document
  entries to temp files under the controlled restore dir (respecting PR 2 path
  rules), validate size watermark, then commit.
- Key/salt entries stay in bounded memory buffers.
- Reduces peak memory from ~2–3× backup size to ~one entry.

**Tests:** peak-memory assertions (rough, e.g. RSS budget) on a large import;
temp files cleaned on any error path.

---

## Milestone 3 — Backup v2 (P2)

### PR 8 — Authenticated backup envelope (P2)

**Problem:** manifest/KDF info sits outside the encrypted payload, so it can be
tampered with and used to drive resource exhaustion before authentication.

Current layout (`core/src/format/package.rs:13-56`):
`[16-byte MAGIC "LIBCRATE_VAULT\0\0"][u32 version][u32 manifest_len][JSON
manifest][encrypted_blob]`. The manifest carries `salt`, all `argon2_*` params,
and `documentCount` in the clear. The header `version` is read and **discarded**
(`package.rs:20`).

**Target layout:**

```
┌──────────────────────────┐
│ Magic                    │
│ Format version           │
└─────────────┬────────────┘
              ▼
       AEAD authenticated
              │
       ┌──────┴─────────────┐
       │                    │
   manifest              payload
   KDF info               DB
   salt                   files
                          keys
```

The outer header must contain only what's needed to identify/decode the
container. Everything security-sensitive is authenticated.

**Backward compatibility (required):**

```
Backup v1 → existing reader (hardened by PRs 1–3)
Backup v2 → new secure reader
```

- New exports use **v2**.
- Imports dispatch on header version (currently discarded at `package.rs:20`).
- The v1 path still enforces KDF caps, ZIP limits, path validation, and
  resource limits from Milestone 1, so old backups don't become an attack
  bypass.
- Eventually: v1 import → v2 export on next backup.

**Tests:** v1 round-trip still works; v2 round-trip; cross-version import;
tampered v2 header fails authentication; old-vs-new format sniffing.

---

## Milestone 4 — Crypto/parser hardening (P2)

### PR 9 — AES-KW audit / test vectors (P2)

`core/src/crypto/aes_kw.rs` is a custom RFC 3394 implementation (not a crate).
Its tests (`aes_kw.rs:112-169`) use RFC 3394 *key material* but only assert
round-trips — **no known-answer tests**, so a non-compliant implementation
could pass.

**Preferred:** use a maintained Rust AES-KW crate compatible with MSRV 1.80 and
the configured targets. If none is satisfactory, keep the implementation but:

1. Add official RFC 3394 Appendix A.1–A.3 vectors.
2. Add AES-128/192/256 vectors.
3. Add malformed-input tests.
4. Fuzz `wrap()` and `unwrap()`.
5. Test all supported key lengths.
6. Document RFC 3394 compliance.

Do **not** change the algorithm merely to change it; eliminate uncertainty
around the implementation.

### PR 10 — KDF strength benchmarking (P2)

`DEFAULT_MEMORY_COST = 19456` (19 MiB), `2` iterations, `2` parallelism
(`core/src/crypto/argon2.rs:4-7`); Android uses 16384/3/2.
Parameter increases must be data-driven, not arbitrary.

Build a benchmark matrix for low/mid/high Android + desktop measuring memory,
unlock latency, backup-unlock latency, and battery impact. Target ~250–500 ms
normal password derivation, then separate:

```
DEFAULT_KDF  → normal exports
KDF_POLICY   → absolute safety ceiling (feeds PR 1)
```

### PR 11 — Document-parser hardening / fuzzing (P2)

Treat every imported PDF/EPUB/CBZ/image as malicious. Current state: all reads
are full in-memory (`std::fs::read` at `cli/src/commands/import.rs:44`,
`gui/src/vault.rs:165`, `ffi.rs:264`, `core/src/db/storage.rs` entry reads) with
no size limits; no fuzz targets exist.

Add:
- file-size limit, archive-entry limit, decompression limit, thumbnail pixel
  limit (`THUMBNAIL_MAX_WIDTH = 200` currently only bounds width)
- dependency maintenance for MuPDF, image decoders, ZIP, EPUB, CBZ
- `cargo fuzz` targets for PDF, EPUB, CBZ, image, and backup importers

---

## Milestone 5 — Ongoing security (P2)

### PR 12 — CI / dependency security automation (P2)

There is **currently no CI** (no `.github` directory or workflow tracked).
Local-only automation exists: `Makefile`, `build_and_test.py`, and
`scripts/build_native.sh`.

Add the first CI workflow running:

```
cargo test
cargo clippy --all-targets -- -D warnings
cargo audit
cargo deny check
```

plus Android `assembleDebug`/`lint`. Optionally pin `cargo fuzz` to a nightly
manual trigger for the PR 11 targets.

Track security-sensitive dependencies separately: `argon2`, `aes-gcm`, `aes`,
`rusqlite`, SQLCipher, OpenSSL, `zip`, MuPDF, `image`, EPUB parser. For
SQLCipher, record the exact embedded SQLCipher version in release artifacts
(currently `libsqlite3-sys 0.28.0` via bundled feature
`core/Cargo.toml:20`).

---

## Suggested implementation order

| # | PR | Scope | Priority | Status |
|---|----|-------|----------|--------|
| 1 | PR 1 | KDF parameter validation | P0 | ✅ |
| 2 | PR 2 | Archive path validation | P0 | ✅ |
| 3 | PR 3 | ZIP size/count/resource limits | P0 | ✅ |
| 4 | PR 4 | Security regression tests | P0 | ✅ |
| 5 | PR 5 | `MasterKey`/`DerivedKey` zeroization | P1 | ✅ |
| 6 | PR 6 | Vault lock/session lifecycle | P1 | ✅ |
| 7 | PR 7 | Streaming/bounded extraction | P1 | ✅ (bounded reads/limits; see note) |
| 8 | PR 8 | Backup v2 authenticated envelope | P2 | ✅ |
| 9 | PR 9 | AES-KW audit/replacement | P2 | ✅ (RFC 3394 §4 KATs; primitives kept) |
| 10 | PR 10 | KDF parameter benchmarking | P2 | ✅ (`benches/kdf.rs`: 26.6 / 31.9 / 437 ms) |
| 11 | PR 11 | Document parser hardening/fuzzing | P2 | ✅ (`fuzz/` workspace + thumbnail caps) |
| 12 | PR 12 | CI/dependency security automation | P2 | ✅ (.github CI, dependabot, SECURITY/DEPENDENCIES) |

> PR 7 note: the envelope is AEAD-authenticated, so the current format cannot
> stream-decrypt; PR 7 is implemented as fully bounded reads + cumulative/
> per-entry caps (`BackupLimits`), documented as the architectural constraint.

### Definition of done

```
✓ Malicious backup cannot request arbitrary Argon2 resources
✓ Malicious archive cannot write outside its extraction directory
✓ ZIP bomb cannot exhaust available memory/disk
✓ Malicious archive cannot create symlinks during restore
✓ Master key is zeroized when the vault locks
✓ Derived keys are zeroized after use
✓ Locked vault has no usable DB encryption key
✓ Backup authentication covers security-sensitive metadata
✓ Old backups remain importable safely
✓ New backups use the hardened format
✓ Crypto primitives have official test vectors
✓ Backup/path/parser fuzz tests exist
✓ SQLCipher and parser dependencies are continuously audited
```