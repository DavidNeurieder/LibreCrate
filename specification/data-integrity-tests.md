# LibreCrate Data-Integrity Regression Test Plan

Status: **Complete — PRs 1–7 landed (legacy fixtures, encryption/tamper,
backup/restore round trips, migration + all-or-nothing merge, malformed-import
regressions + CI gate, fuzz seed corpus + nightly job + release checklist).**
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
§ 3, findings B, C, and E).

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

It is **never called** by the app (the FFI exposes `get_schema_version` /
`set_schema_version` only), so nothing exercised it.

✅ **Resolved (PR 5):** rewritten as a bounded loop (`apply_migrations`) with a
base case and an idempotent `column_exists` guard, wrapped in a single
`BEGIN IMMEDIATE … COMMIT` with `ROLLBACK` on error. `CURRENT_SCHEMA_VERSION`
(= 1) is now exported. Unit tests cover add-column + preserve-row + idempotency;
`migration.rs` covers a real v0 vault and a failing migration.

Note: new vaults are still created at `user_version = 0` (stamping them would
change `getSchemaVersion()` semantics on Android and the frozen fixtures), but
the idempotent `column_exists` guard makes migrating such vaults a harmless
stamp to 1.

**C. Branch-A merge swallows per-statement errors.** `branch_a_merge` wraps the
work in `BEGIN TRANSACTION` + `catch_unwind`, but several `execute(...)` calls
are `.ok()`. A mid-merge failure could therefore commit a partial merge.

✅ **Resolved (PR 5):** `branch_a_merge` now runs under `BEGIN IMMEDIATE` and
propagates every fallible statement (no swallowed `.ok()`); the closure returns
`Result<MergeStats>` and a failure `ROLLBACK`s and returns the error. Covered by
`merge::tests::test_branch_a_merge_rolls_back_on_error` (an injected mid-merge
failure leaves no partial document).

**D. "Restore" has two meanings.** `cli restore` merges (branch A);
`restore_backup_to_dir` fully replaces (branch B). Tests must name which path
they exercise, and the "failed restore must not replace a working vault" test
targets branch B specifically.

**E. CLI merge (`merge_vault_dir`) does not re-encrypt attachments.** It always
calls `branch_a_merge` with `backup_key=None, local_key=None`, then copies the
backup's file blobs verbatim into the target `files/` dir. For an **encrypted**
vault those blobs stay encrypted with the *backup's* master key while the local
DB references them with the backup's IV, so the merged attachments are not
readable with the local key. The correct encrypted cross-key path is
`merge_backup_to_vault` (used by FFI/GUI), which passes both keys and re-encrypts.
PR 4's branch-A test therefore exercises `merge_backup_to_vault`; PR 5 should
either route `merge_vault_dir`/`cli restore` through the re-encrypting path (when
the local master key is available) or explicitly document/guard the limitation.

✅ **Resolved (PR 5):** `merge_vault_dir` now calls `branch_a_merge` with
`Some(backup_key), Some(mk_a)` and re-encrypts; the separate verbatim copy was
removed. `reencrypt_files` re-encrypts IV‖ciphertext blobs and copies
non-decryptable (plaintext) blobs verbatim, so both encrypted and legacy
plaintext vaults merge correctly, and blobs for pre-existing documents are left
untouched.

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
support module — not compiled as its own binary; `#![allow(dead_code)]` because
suites use different subsets). Contains:

- `create_vault(root, password) -> master_key` / `create_empty_vault` and
  `add_document(...)`, plus `fixture_documents()` (Unicode / binary / empty /
  large payload set).
- `open_vault(root, password) -> (Connection, master_key)` — unlocks exactly like
  the CLI/GUI (`encryption/{salt,wrapped_master_key,params.toml}` + SQLCipher).
- `snapshot_vault(root, password) -> VaultSnapshot` where `VaultSnapshot` holds
  `schema_version` (`PRAGMA user_version`) plus `DocSnapshot`s sorted by id:
  id / title / mime_type / file_name / file_size and the **SHA-256 of each
  decrypted attachment** (via `export_document_file`).
- `assert_snapshot_eq(before, after)` using **logical** comparison (never
  byte-for-byte SQLite files).
- Secret hygiene: snapshots hash decrypted bytes; they never print
  passwords/keys/plaintext in failure output.

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

✅ Landed in `security_corruption.rs` (17 tests): IV / auth-tag / ciphertext-body
tamper, sub-IV blob, non-base64 salt, unsupported KDF, manifest/container version
mismatch, zeroed KDF params, unknown container version, and corrupt/partial
SQLCipher DB (failed open must not rewrite the file). Destination-level
"failed restore leaves the existing vault unchanged" is asserted in PR 4.

### C. Backup & restore — `core/tests/backup_roundtrip.rs` (new)

Synthetic vault with Unicode text, binary attachments, empty and large files.
Export → restore into a fresh dir (branch B) and merge into an existing vault
(branch A); compare via `assert_snapshot_eq`. Include the legacy backup fixture.
Wrong password and corrupted archives must fail **without replacing a working
destination** (snapshot the destination before, assert equal after).

✅ Landed in `backup_roundtrip.rs` (7 tests): full branch-B round trip over the
Unicode/binary/empty/large set; branch-A merge via `merge_backup_to_vault` from a
**differently-keyed** vault (asserts attachments are re-encrypted to the local
key and existing docs untouched); failed restore (wrong password / truncated /
tampered tag) leaves the destination unchanged; both frozen `legacy-v1`/`v2`
fixtures restore into a fresh vault with contents matching `expected.json`; and
two merge-failure tests (a truncated backup, and an injected mid-merge insert
failure) that assert the vault is unchanged **and** no `.backup_merge_*` scratch
file is left behind.

Note: `merge_vault_dir` (the CLI path) passes `None` keys and only re-encrypts
when keys are supplied, so it is correct only for plaintext vaults; the encrypted
cross-key path is `merge_backup_to_vault` (FFI/GUI), which is what this suite
exercises. The plaintext-only constraint of `merge_vault_dir` is recorded as a
finding for PR 5.

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

✅ Landed in `migration.rs` (3 tests): v0→current preserves every document +
attachment (`ALTER TABLE … DROP COLUMN content_hash` + `user_version = 0` then
migrate, verified with `integrity_check` / `foreign_key_check`); a fresh DB is
stamped to the current version and re-migrating is harmless; and a migration that
cannot complete (missing target table) returns a controlled error without bumping
the version, leaving the DB openable. Together with the PR-4
`failed_restore_does_not_replace_destination` test this covers "restore archive
truncated → fails, existing data intact".

### E. Malformed imports — extend `security_archive_path.rs` / `security_backup_limits.rs`

Truncated ZIP, invalid/duplicate entry names, unexpected paths, oversized inputs;
assert `safe_archive_path` containment, that failures do not mutate the existing
vault, and that temp files (e.g. `.backup_merge_*` in `vault_ops.rs`) are cleaned
up after errors.

✅ Landed (PR 6): `security_backup_limits.rs` (+4: truncated ZIP, stray entries
ignored, writer rejects duplicate names, traversal rejected at the extract seam);
`security_archive_path.rs` (+2: truncated container refused before crypto, stray
entries ignored with the document-count cross-check); `backup_roundtrip.rs` (+2:
truncated backup and injected mid-merge failure both leave the vault unchanged and
leave no `.backup_merge_*` scratch file).

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

✅ Implemented (PR 6): the fixture guard (`fixtures_present_and_unmodified`) runs
in `rust-core`; a new `Ensure no regression test is silently skipped` step fails
unless the *only* `#[ignore]`d test is the fixture generator.

✅ Implemented (PR 7): the Tier-3 job is `.github/workflows/nightly.yml`
(daily `schedule:` + `workflow_dispatch`), which runs each libFuzzer target for a
bounded time seeded from the committed `fuzz/seeds/` corpus. Because scheduled
workflows run from the default branch, the job is inert until this lands on
`main`.

---

## 7. Phase 6 — Fuzzing

`fuzz/` already has `fuzz_package_read`, `fuzz_manifest_json`, `fuzz_extract_zip`,
`fuzz_archive_path`, `fuzz_thumbnail`, `fuzz_import`. PR 7 adds: committed seed
corpus (currently `/corpus` is gitignored — commit a curated `seeds/` dir),
assertions for unbounded allocation / escaping panics / path traversal, and a
nightly job that replays the corpus. Every discovered crash becomes a permanent
deterministic test in the Tier-1/2 suites.

✅ Landed (PR 7):

- **Committed seed corpus** under `fuzz/seeds/<target>/` (31 curated inputs:
  the frozen v1/v2 containers, valid/malformed manifest JSON, valid/zip-slip/
  truncated ZIPs, sanitizer vectors, tiny images and a minimal PDF). Regenerated
  by `fuzz/scripts/make_seeds.sh`; `corpus/` and `artifacts/` stay gitignored.
- **Stable corpus replay** — `core/tests/fuzz_seed_corpus.rs` (7 tests) feeds the
  same seeds through the same entry points on the pinned toolchain, so the corpus
  runs in normal `rust-core` CI even though `fuzz/` itself needs nightly.
- **Stronger oracles** — `fuzz_archive_path` now asserts an accepted name only
  decomposes into `Normal` components; `fuzz_extract_zip` asserts every extracted
  key is a safe relative archive path.
- **Nightly job** — `.github/workflows/nightly.yml` runs every target for a
  bounded time seeded from `seeds/` and uploads crash reproducers; the promotion
  path (artifact → `seeds/` → stable regression) is documented in `fuzz/README.md`.
- **Release-candidate checklist** — the data-integrity gates are folded into the
  pre-release checklist in `RELEASING.md`.

---

## 8. PR breakdown

- [x] **PR 1 — Inventory & fixture format.** ✅ This document + fixture
  `README.md` + `manifest.json` schema + generator scaffold
  (`core/tests/make_fixtures.rs`). No production behavior change.
- [x] **PR 2 — Legacy fixtures + compat tests.** ✅ Committed `legacy-v1/` (v1,
  no AAD) and `legacy-v2/` (v2, manifest-as-AAD) bytes with `expected.json`;
  added `core/tests/compat_legacy.rs` (import + exact read-back) and the
  `fixtures_present_and_unmodified` guard test.
- [x] **PR 3 — Encryption/tamper regressions.** ✅ Extended
  `security_corruption.rs` to 17 tests (IV/tag/body tamper, sub-IV blob, invalid
  salt, bad KDF, version mismatch, zeroed KDF params, unknown version, corrupt /
  partial DB).
- [x] **PR 4 — Backup/restore round trips.** ✅ Added `tests/common/mod.rs`
  (logical snapshot + unlock helpers) and `backup_roundtrip.rs` (round trip,
  cross-key branch-A merge, failed-restore-keeps-destination, legacy v1/v2
  fixture restores).
- [x] **PR 5 — Migration & interruption.** ✅ Fixed `migrate_schema` (finding B),
  made `branch_a_merge` all-or-nothing and routed `merge_vault_dir` through the
  re-encrypting path (finding E); added `migration.rs` + unit tests.
- [x] **PR 6 — Malformed imports & CI gates.** ✅ Extended the `security_*` suites
  (truncated ZIP, stray/duplicate names, traversal at the extract seam, truncated
  container), added merge-failure/scratch-cleanup tests, and the
  "no skipped test" CI gate. The nightly workflow is deferred to PR 7.
- [x] **PR 7 — Fuzzing & release validation.** ✅ Committed seed corpus
  (`fuzz/seeds/`), a stable corpus-replay suite (`fuzz_seed_corpus.rs`), stronger
  fuzz oracles, the Tier-3 `nightly.yml` job, and the release-candidate checklist.

Recommended first step (matches the proposal): land **PR 1 + PR 2 together** as
one small change — document the formats and add one known-good v1 fixture with
exact expected data. That fixture is the baseline every future dependency bump
(rusqlite/SQLCipher, RustCrypto) is checked against.

---

## 9. Definition of done

- [x] Every supported legacy vault and backup format has a committed fixture.
- [x] Successful backup/restore tests verify exact logical data + attachment bytes.
- [x] Corrupt inputs / wrong credentials fail without exposing plaintext.
- [x] Failed migrations and restores leave a valid, recoverable state.
- [x] Malformed archives cannot modify unrelated files or the existing vault.
- [x] Tests run in CI with clear diagnostics and **no secrets in logs**.
- [x] Local procedure and fixture-update policy documented.
- [ ] Full suite passes on the release candidate — run the pre-release checklist
  in `RELEASING.md` (data-integrity gates) before tagging.

---

## 10. Open decisions (need confirmation before PR 2/5)

1. **Fixture binaries in git.** ✅ Yes — committed under
   `core/tests/fixtures/data-integrity/` (~75 KB each, synthetic; the repo already
   tracks binary PNGs under `docs/`).
2. **`migrate_schema` (finding B).** ✅ Resolved: **fix it** (bounded loop + base
   case) as part of PR 5.
3. **Branch-A error handling (finding C).** ✅ Resolved — tightened the swallowed
   `.ok()` calls and routed `merge_vault_dir` through the re-encrypting path in
   PR 5.
4. **CI tier split.** ✅ Resolved — defer the `nightly.yml` Tier-3 job to PR 7 so
   it lands with the fuzz corpus it replays.
