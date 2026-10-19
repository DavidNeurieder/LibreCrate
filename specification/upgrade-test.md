# Android upgrade test (v0.5.6 → current)

## Goal
Validate an in-place upgrade preserves user data and the vault remains functional when upgrading from the last released version (v0.5.6) to the current build (0.6.0).

## Scope
- **APK-level upgrade** (real `adb install -r`) on an emulator/device.
- **Debug-signed builds** for both versions (same signing key → upgrade succeeds).
- **End-to-end**: use a deterministic seeded vault from v0.5.6.
- **Verification**: post-upgrade unlock succeeds, documents are intact, and backups work.

## Non-goals
- Release-signed APK verification
- Downgrade testing
- Desktop (CLI/GUI) flows

## Strategy
1. **Baseline**: build from tag `v0.5.6` (commit 69270fe).
2. **Seed snapshot**: commit a frozen tarball of a v0.5.6 app-private state (vault with known password + test documents) at `app/src/androidTest/resources/upgrade_v0_5_6_seed.tar.gz`.
3. **Orchestrator script**: `scripts/test_upgrade_e2e.sh` prepares old+new debug APKs, ensures emulator, seeds via `adb shell run-as`, upgrades with `adb install -r`, and verifies.
4. **Verification**: minimal UI checks (unlock + library state) or an instrumentation test run via `adb shell am instrument`.

## On-disk layout
From the codebase:
- Key material: `filesDir/encryption/` — `salt`, `wrapped_master_key`, `params.toml`, `device_wrapped_master_key` (via `FileKeyStore`)
- Database: `getDatabasePath("librecrate.db")` under `databases/`
- Documents: `filesDir/files/`
- SharedPreferences: `session` (`session.xml`) and others (e.g. reader preferences)

## Test data
Reuse `app/src/androidTest/resources/` fixtures (PDF/EPUB/CBZ/PNG). Seed snapshot uses password `upgrade-test` and includes at least 2–3 documents with known titles.

## Implementation
### Phase 2 — Seed snapshot
Create the seed once on a clean emulator (API 36 used). UI automation for the
first-run flow and the system document picker proved unreliable (layout shifts
when the keyboard opens, and grid/list rows would not register taps under
software rendering), so seeding is done programmatically instead:

1. Checkout v0.5.6 into a worktree, build/install the debug APK.
2. Copy `scripts/seed_v0_5_6/SeedVaultTest.kt` into that worktree's
   `app/src/androidTest/java/com/librecrate/app/data/` and build
   `:app:assembleDebugAndroidTest`.
3. `adb shell pm clear com.librecrate.app` (fresh), install the test APK, then
   run `SeedVaultTest`. It calls `initializeWithPassword("upgrade-test")`,
   opens the vault, and imports `test_1page.pdf`, `test.epub`, `test.cbz`.
4. Snapshot the app-private dir, streaming to the host (writing the archive to
   device storage produced a corrupt ~758 MB file; streaming yields ~81 KB):
   - `adb exec-out run-as com.librecrate.app sh -c 'cd /data/data/com.librecrate.app && tar czf - .' > app/src/androidTest/resources/upgrade_v0_5_6_seed.tar.gz`
5. Commit the tarball (immutable — never regenerate in place).

The committed snapshot contains `databases/librecrate.db`,
`files/encryption/{salt,wrapped_master_key,params.toml}`, three encrypted
documents under `files/files/`, and the androidx.work DB under `no_backup/`.

### Phase 3 — Orchestrator script (`scripts/test_upgrade_e2e.sh`)
Features:
- `set -euo pipefail`, cleanup trap (remove worktree, kill emulator if started)
- Args: `--avd <name>`, `--no-rebuild-old`, `--keep-emulator`
- Ensure emulator: reuse if running, else boot first AVD
- Build old: create temp worktree for v0.5.6, `./gradlew assembleDebug`
- Install old: uninstall first (v0.5.6's versionCode is lower, so an in-place install is rejected as a downgrade), then `adb install /tmp/librecrate-v0.5.6/app/build/outputs/apk/debug/app-debug.apk`
- Seed: clear, extract seed tarball into `/data/data/com.librecrate.app/` via `run-as`, then assert `databases/librecrate.db` exists
- Build new: from HEAD, `./gradlew :app:assembleDebug`
- Upgrade: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Verify: build `:app:assembleDebugAndroidTest`, install it, and run `UpgradeVerificationInstrumentedTest` via `adb shell am instrument`; fail the script unless the run reports `OK (`

### Phase 4 — Verification
`UpgradeVerificationInstrumentedTest` (runs after `adb install -r` over the seeded
v0.5.6 state) asserts:
- The password from the seed still unlocks
- The vault opens
- Every seeded document title is present
- The document count is unchanged

Additional manual checks, if wanted:
- App doesn't crash on launch
- Can open one document
- Backup export works
- Pre-upgrade backup imports into fresh install of HEAD

### Phase 5 — CI (optional)
Add `upgrade-e2e` job using `reactivecircus/android-emulator-runner@v2`. Run on workflow_dispatch or nightly (not on every PR) to avoid cost/time.

## Risks & mitigations
- UI automation brittle → prefer data-dir seeding
- Debug vs release signing → same keystore for debug builds
- Worktree cleanup → trap on EXIT
- Same package ID → `adb install -r` required
- Seed snapshot environment-dependent → generate on clean emulator API 36, treat as immutable

## Verification checklist
- [x] Seed tarball created and committed
- [x] Script builds old+new, seeds, upgrades, verifies
- [x] Documents preserved; unlock works; no crashes
- [ ] Backup created by v0.5.6 importable by HEAD
- [ ] Fresh install of HEAD can restore from backup

Last run: `scripts/test_upgrade_e2e.sh --no-rebuild-old` — v0.5.6 (versionCode 11)
upgraded in place to 0.6.0 (versionCode 12); `UpgradeVerificationInstrumentedTest`
reported `OK (3 tests)`. This exercises the schema migration path.
