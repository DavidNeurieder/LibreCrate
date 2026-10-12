# LibreCrate Data-Integrity Regression Test Plan

Status: **In progress — PR 1 + PR 2 landed (`legacy-v1`/`legacy-v2` fixtures +
`compat_legacy.rs`). PRs 3–7 pending.**
Source proposal: `ideas/new_test_plan.txt`.
Author: test-infrastructure review.
Last updated: 2026-10-12

This document converts the proposal in `ideas/new_test_plan.txt` into a concrete,
codebase-grounded implementation plan. It references **exact files and current
behavior** so each PR can be executed without re-discovery, and it adapts the
proposal's generic layout to LibreCrate's actual repository conventions.

Guiding principle (from the proposal): **existing user data must stay readable,
and a failed or interrupted operation must never silently corrupt or destroy
it.** This plan adds tests and test infrastructure; it does not change encryption
formats or production behavior — except where a test exposes a real gap (see
§ 3, findings B and C).

---

## 1. Test strategy (priority order)

| Area | Priority | Goal |
|---|---|---|
| Legacy vault/backup compatibility | P0 | Vaults and backups from previous releases still open, decrypt, and read exactly. |
| Encryption & tamper detection | P0 | Wrong credentials, modified ciphertext, corrupt metadata, bad tags are rejected safely. |
| Backup & restore | P0 | A restored vault is logically identical to the source; old backups stay restorable. |
| DB migration & interruption | P1 | Schema upgrades preserve data; failures never leave a half-migrated vault. |
| Malformed imports & recovery | P1 | Truncated/hostile archives fail safely and leave the existing vault untouched. |

---

## 2. Current state inventory (Phase 1 deliverable)

### 2.1 Persisted formats

**On-disk vault layout** (shared by CLI/GUI/Android):

```
<vault>/
├── encryption/
│   ├── wrapped_master_key   # AES-KW wrapped master key
│   ├── salt                 # Argon2id salt (also legacy `master_key` name accepted)
│   └── params.toml          # memory_cost / iterations / parallelism / hash_length
├── databases/librecrate.db  # SQLCipher (compat=4, 4096 page, HMAC/PBKDF2 SHA-512)
└── files/<id>               # AES-GCM blobs: IV || ciphertext (plaintext if no key)
```

**Backup container** (`core/src/format/package.rs`, magic `LIBCRATE_VAULT\0\0`):

```
[16B magic][4B LE version][4B LE manifest_len][manifest JSON][IV||AES-GCM(blob)]
```

- **v1** (`FORMAT_VERSION_V1 = 1`): AES-GCM with **no AAD**. Import-only; still
  accepted by the hardened path (`import.rs`).
- **v2** (`FORMAT_VERSION_V2 = 2`, current write version): the manifest JSON is
  bound as **GCM AAD**; `document_count` is cross-checked against extracted
  files. Unknown versions are rejected before any crypto.
- Decrypted payload is a ZIP with three namespaces: `keys/*`, `db/librecrate.db`,
  `files/*`.

**DB schema versioning:** `PRAGMA user_version`. History documented in
`core/src/db/schema.rs`: `0` initial → `1` add `content_hash`.

### 2.2 Existing test suites

| Location | Kind | Notes |
|---|---|---|
| `vault-native/core/tests/e2e.rs` | core integration | DB lifecycle, FTS, export/import roundtrip, compat=4 PRAGMAs. |
| `vault-native/core/tests/phone_roundtrip.rs` | core integration | Cross-platform (phone↔desktop) backup compatibility + KDF params. |
| `vault-native/core/tests/security_{kdf_bounds,archive_path,backup_limits,corruption}.rs` | core integration | PR-4 security regression suite (flat files — Rust integration tests are one binary per file, no `tests/security/` submodules). |
| `core/src/**/#[cfg(test)]` | unit | Parsers, crypto KATs, schema, storage, import/export. |
| `vault-native/cli/tests/cli.rs` | CLI black-box | `init/import/list/search/backup/restore/open/delete` + REPL. |
| `app/src/test/**`, `app/src/androidTest/**` | Android | ViewModels/JVM units + instrumented (incl. `BackupManagerTest`, `EncryptionManagerInstrumentedTest`). |

### 2.3 Test helpers already available

- `tempfile` for isolated vaults/dirs.
- `format::export::create_vault_layout(dir, password) -> master_key`.
- `vault_ops::{export_vault_dir, export_vault_dirs, restore_backup_to_dir, restore_backup_to_dirs}`.
- `db::storage::import_document` / `export_document_file` (AES-GCM round trips).
- `db::schema::{create_encrypted_db, open_encrypted, get_schema_version, set_schema_version}`.
- `format::package::write_with_version(v, …)` (builds legacy v1 containers).

### 2.4 Atomicity map (must-record for interruption tests)

| Operation | Entry point | Behavior |
|---|---|---|
| Export/backup | `vault_ops::export_vault_dir` | Builds a full in-memory buffer, then writes once by caller. |
| Restore — full replace | `merge::branch_b_fresh_install` | **Writes key files, DB, then file blobs directly into target dirs** (no temp+rename); removes stale `-wal`/`-shm`. |
| Restore — merge (branch A) | `merge::branch_a_merge` | `BEGIN TRANSACTION … catch_unwind`, but per-statement `.ok()` swallows errors (see finding C). |
| CLI `restore` | `cli/commands/restore.rs` | Uses the **merge** path (not full replace). |
| DB migration | `schema::migrate_schema` | See finding B. |

### 2.5 CI today

`.github/workflows/ci.yml` has four jobs, all on PR/push-to-main and **no
scheduled workflow**: `rust-core` (fmt, clippy `-D warnings`, `cargo test -p
vault-native`, `-p librecrate`), `rust-cli-gui`, `audit` (cargo-audit, `deny:
true`), `android` (assembleDebug, unit tests, lint). Fuzzing lives in a separate
opt-in workspace `fuzz/` (6 targets) that is **not** built in CI.

### 2.6 Gaps vs. the proposal

1. **No fixture directory anywhere** (`tests/fixtures/`, `testdata/` do not
   exist). Every current test synthesizes its data at runtime, so nothing pins
   *historical* bytes.
2. **No versioned backup/DB fixtures** and no manifest-of-record.
3. **No interruption/recovery tests** and no fault hooks.
4. **No logical-snapshot assertion helper** (record/hash/diff) shared across suites.
5. **No scheduled (Tier 3) job**; fuzz corpus is not committed.
6. The proposal's suggested `docs/testing/data-integrity.md` **collides with
   `docs/`, which is the GitHub Pages website** (see `docs/index.html`,
   `docs/styles.css`, `docs/screenshots/`). Test documentation must not go there.

---

## 3. Findings that change the plan

**A. Fixture location.** Integration tests can only read fixtures relative to the
crate. Put them under `vault-native/core/tests/fixtures/data-integrity/` and load
small ones with `include_bytes!` (compile-time presence = CI fails if missing).
Documentation goes in `specification/data-integrity-tests.md` (this file) plus a
`README.md` inside the fixture dir — **not** `docs/`.

**B. `schema::migrate_schema` is defective (latent bug).** It self-recurses
unconditionally with no base case:

```rust
pub fn migrate_schema(conn: &Connection) -> rusqlite::Result<()> {
    let version = get_schema_version(conn)?;
    if version < 1 { /* ALTER + set to 1 */ }
    migrate_schema(conn)?;   // <-- infinite recursion
    Ok(())
}
```

It is currently **never called** (the FFI exposes `get_schema_version` /
`set_schema_version` only), so nothing exercises it. PR 5 must first decide its
fate (fix with a loop + base case, or delete + document that migrations are
handled elsewhere). Either way the decision is captured by a test.

**C. Branch-A merge swallows per-statement errors.** `branch_a_merge` wraps the
work in `BEGIN TRANSACTION` + `catch_unwind`, but several `execute(...)` calls
are `.ok()`. A mid-merge failure could therefore commit a partial merge. The
interruption tests (PR 5) will assert the intended all-or-nothing behavior and
will fail until this is tightened — treat that as a real defect, not a test bug.

**D. "Restore" has two meanings.** `cli restore` merges (branch A);
`restore_backup_to_dir` fully replaces (branch B). Tests must name which path
they exercise, and the "failed restore must not replace a working vault" test
targets branch B specifically.

---

## 4. Phase 2 — Versioned fixtures

```
vault-native/core/tests/fixtures/data-integrity/
├── README.md                # purpose, provenance, update policy
├── manifest.json            # per-fixture metadata + SHA-256
├── legacy-v1/
│   ├── vault.librecrate-backup     # v1 container (no AAD)
│   └── expected.json               # ids, titles, hashes of decrypted bytes
├── legacy-v2/
│   └── vault.librecrate-backup     # earliest v2 envelope we can pin
├── backups/
│   └── legacy-v1-backup.librecrate-backup
└── malformed/
    ├── truncated-v2.bin
    ├── bad-manifest-version.container
    ├── bad-tag.bin
    └── zip-slip.v1
```

**Rules (from the proposal, adapted):**

- At least one valid fixture per supported legacy format (`v1`, `v2`) and per
  path (backup container + on-disk vault).
- Synthetic documents and **test-only credentials** only; no personal vaults.
- **Bytes are committed and frozen.** A one-off generator emits them once; the
  generator is never run by ordinary tests. For v1 the "historical behavior" is
  the frozen legacy path (`write_with_version(V1, …)` + no-AAD AES-GCM), so the
  fixture records exactly those bytes; it must never be regenerated from the
  current writer to make a failing test pass.
- `manifest.json` records: libre­crate version, format version, purpose, expected
  result, and the fixture's SHA-256.
- A guard test (`fixtures_present_and_unmodified`) recomputes each SHA-256 and
  fails CI if a fixture is missing or altered.

**Generator:** `core/tests/make_fixtures.rs` — an `#[ignore]`d integration test
that writes the fixtures and the manifest. It is **never** run by an ordinary
`cargo test`; run it explicitly with
`cargo test -p vault-native --test make_fixtures -- --ignored`.

*Deviation from the original sketch:* the generator lives as an ignored
integration test rather than a separate `tools/` crate so it reuses the pinned
`vault-native` build (SQLCipher/OpenSSL/MuPDF) instead of compiling a second copy
of the dependency graph. Committed fixtures are the source of truth; the
generator exists only to document provenance.

---

## 5. Phases 3–4 — Tests, mapped to concrete files

Shared helper: `vault-native/core/tests/common/mod.rs` (a Rust integration-test
support module — not compiled as its own binary). Contains:

- `build_vault(root, password) -> master_key` and phone/desktop layout builders.
- `snapshot(conn, files_dir, key) -> VaultSnapshot` where `VaultSnapshot` holds
  sorted record ids + counts, canonicalized metadata/relationships, `user_version`,
  and SHA-256 of each decrypted document/attachment byte string.
- `assert_snapshot_eq(before, after)` using **logical** comparison (never
  byte-for-byte SQLite files).
- Secret hygiene: never print passwords/keys/plaintext in failure output.

### A. Legacy compatibility — `core/tests/compat_legacy.rs` (new)

Open each fixture with current code; read records, decrypt content; compare ids,
titles, attachments, counts, and SHA-256 against `expected.json`; assert opening
does **not** rewrite the persisted format (compare file mtime/bytes before/after,
and `user_version` unchanged). Supersedes the inline v1 test in
`format/import.rs::test_import_v1_backup_still_works` (keep that as a unit guard;
the fixture test is the durable one).

### B. Encryption & tamper — extend `core/tests/security_corruption.rs` (or new `security_tamper.rs`)

Wrong password/key; bit-flipped ciphertext and auth tag; truncated blob at every
cut; invalid base64 salt, nonce/IV, and manifest key metadata; missing/inconsistent
required DB fields. Pass = controlled `Error` (`AuthenticationFailed` /
`InvalidData` / `Format` / `InvalidKdfParameters`), no plaintext returned, and the
**source fixture bytes are unchanged after the attempt**.

### C. Backup & restore — `core/tests/backup_roundtrip.rs` (new)

Synthetic vault with Unicode text, binary attachments, empty and large files.
Export → restore into a fresh dir (branch B) and merge into an existing vault
(branch A); compare via `assert_snapshot_eq`. Include the legacy backup fixture.
Wrong password and corrupted archives must fail **without replacing a working
destination** (snapshot the destination before, assert equal after).

### D. Migration & interruption — `core/tests/migration.rs` (new)

Build a v0-schema DB (no `content_hash`), run the migration decision from finding
B, compare before/after snapshots, reopen and run `PRAGMA integrity_check` /
`foreign_key_check`. Interruption cases from the proposal's table, implemented
with **deterministic hooks, no sleeps**:

| Scenario | Expected |
|---|---|
| Migration fails before commit | Original DB valid |
| Migration fails after a staged write | Rollback / recoverable |
| Restore archive truncated | Fails, existing data intact |
| Disk-write fails (read-only dir / injected `io::Error`) | No success reported; deterministic recovery |
| Reopen after abort | Valid recoverable state |
| Temp extraction fails | No partial import exposed |

Prefer injecting failures through a test-only trait/`cfg(test)` hook or by
supplying a read-only target dir; avoid killing processes at random instants.

### E. Malformed imports — extend `security_archive_path.rs` / `security_backup_limits.rs`

Truncated ZIP, invalid/duplicate entry names, unexpected paths, oversized inputs;
assert `safe_archive_path` containment, that failures do not mutate the existing
vault, and that temp files (e.g. `.backup_merge_*` in `vault_ops.rs`) are cleaned
up after errors.

---

## 6. Phase 5 — CI integration

Reuse the existing jobs; add tiers rather than a new framework.

| Tier | Tests | Where |
|---|---|---|
| 1 | Legacy fixtures, encryption/tamper, basic round trips | existing `rust-core` job (`cargo test -p vault-native`) |
| 2 | All backup fixtures, migrations, malformed imports | same job (fast enough today; split if runtime grows) |
| 3 | Interruption/large-file scenarios, fuzz corpus replay | **new** `.github/workflows/nightly.yml` (`schedule:` + `workflow_dispatch`) |
| 4 | Android instrumented | existing `android` job; instrumented run stays local/AVD (arm64-translated) |

Gates: CI must fail if a required fixture is missing/altered (the guard test),
if a migration silently changes expected content, or if a tier-1/2 test is
skipped. Keep tests network-free. Document the local commands in the fixture
`README.md` and this file.

---

## 7. Phase 6 — Fuzzing

`fuzz/` already has `fuzz_package_read`, `fuzz_manifest_json`, `fuzz_extract_zip`,
`fuzz_archive_path`, `fuzz_thumbnail`, `fuzz_import`. PR 7 adds: committed seed
corpus (currently `/corpus` is gitignored — commit a curated `seeds/` dir),
assertions for unbounded allocation / escaping panics / path traversal, and a
nightly job that replays the corpus. Every discovered crash becomes a permanent
deterministic test in the Tier-1/2 suites.

---

## 8. PR breakdown

- [x] **PR 1 — Inventory & fixture format.** ✅ This document + fixture
  `README.md` + `manifest.json` schema + generator scaffold
  (`core/tests/make_fixtures.rs`). No production behavior change.
- [x] **PR 2 — Legacy fixtures + compat tests.** ✅ Committed `legacy-v1/` (v1,
  no AAD) and `legacy-v2/` (v2, manifest-as-AAD) bytes with `expected.json`;
  added `core/tests/compat_legacy.rs` (import + exact read-back) and the
  `fixtures_present_and_unmodified` guard test.
- [ ] **PR 3 — Encryption/tamper regressions.** Extend `security_corruption.rs`.
- [ ] **PR 4 — Backup/restore round trips.** Add `tests/common/mod.rs` snapshot
  helper + `backup_roundtrip.rs` + legacy backup fixture.
- [ ] **PR 5 — Migration & interruption.** Resolve finding B, tighten finding C,
  add `migration.rs` with deterministic fault injection.
- [ ] **PR 6 — Malformed imports & CI gates.** Extend the `security_*` suites;
  add the nightly workflow and the "skipped test" gate.
- [ ] **PR 7 — Fuzzing & release validation.** Seed corpus, corpus replay job,
  release-candidate checklist.

Recommended first step (matches the proposal): land **PR 1 + PR 2 together** as
one small change — document the formats and add one known-good v1 fixture with
exact expected data. That fixture is the baseline every future dependency bump
(rusqlite/SQLCipher, RustCrypto) is checked against.

---

## 9. Definition of done

- [ ] Every supported legacy vault and backup format has a committed fixture.
- [ ] Successful backup/restore tests verify exact logical data + attachment bytes.
- [ ] Corrupt inputs / wrong credentials fail without exposing plaintext.
- [ ] Failed migrations and restores leave a valid, recoverable state.
- [ ] Malformed archives cannot modify unrelated files or the existing vault.
- [ ] Tests run in CI with clear diagnostics and **no secrets in logs**.
- [ ] Local procedure and fixture-update policy documented.
- [ ] Full suite passes on the release candidate.

---

## 10. Open decisions (need confirmation before PR 2/5)

1. **Fixture binaries in git.** ✅ Yes — committed under
   `core/tests/fixtures/data-integrity/` (~75 KB each, synthetic; the repo already
   tracks binary PNGs under `docs/`).
2. **`migrate_schema` (finding B).** ✅ Resolved: **fix it** (bounded loop + base
   case) as part of PR 5.
3. **Branch-A error handling (finding C).** Open — tighten the swallowed `.ok()`
   calls as part of this work (small production change), or file it separately and
   only assert current behavior for now?
4. **CI tier split.** Open — add the `nightly.yml` Tier-3 job now, or defer until
   the fuzz corpus exists (PR 7)?
