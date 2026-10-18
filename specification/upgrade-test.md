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
Create the seed once on a clean emulator (API 36 recommended):
1. Checkout v0.5.6, build/install debug APK.
2. `adb shell pm clear com.librecrate.app` (fresh).
3. Set up vault with password `upgrade-test`.
4. Import small test documents from `androidTest/resources/`.
5. (Optional) export a backup to `/sdcard/Download/upgrade_v0_5_6.librecrate-backup`.
6. Pull app data:
   - `adb shell run-as com.librecrate.app tar czf /data/local/tmp/seed.tar.gz .`
   - `adb pull /data/local/tmp/seed.tar.gz app/src/androidTest/resources/upgrade_v0_5_6_seed.tar.gz`
7. Commit the tarball (immutable).

### Phase 3 — Orchestrator script (`scripts/test_upgrade_e2e.sh`)
Features:
- `set -euo pipefail`, cleanup trap (remove worktree, kill emulator if started)
- Args: `--avd <name>`, `--no-rebuild-old`, `--keep-emulator`
- Ensure emulator: reuse if running, else boot first AVD
- Build old: create temp worktree for v0.5.6, `./gradlew assembleDebug`
- Install old: `adb install -r /tmp/librecrate-v0.5.6/app/build/outputs/apk/debug/app-debug.apk`
- Seed: clear, extract seed tarball into `/data/data/com.librecrate.app/` via `run-as`
- Build new: from HEAD, `./gradlew assembleDebug`
- Upgrade: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Verify: launch app, unlock with `upgrade-test`, check documents exist

### Phase 4 — Verification
Minimal checks (can be done via adb input + dumpsys or `am start` + UI inspection):
- App doesn't crash on launch
- Unlock succeeds
- Library lists expected documents
- Can open one document
- Backup export works
- Pre-upgrade backup imports into fresh install of HEAD

Optional: add `UpgradeVerificationInstrumentedTest` to run via `adb shell am instrument` after upgrade.

### Phase 5 — CI (optional)
Add `upgrade-e2e` job using `reactivecircus/android-emulator-runner@v2`. Run on workflow_dispatch or nightly (not on every PR) to avoid cost/time.

## Risks & mitigations
- UI automation brittle → prefer data-dir seeding
- Debug vs release signing → same keystore for debug builds
- Worktree cleanup → trap on EXIT
- Same package ID → `adb install -r` required
- Seed snapshot environment-dependent → generate on clean emulator API 36, treat as immutable

## Verification checklist
- [ ] Seed tarball created and committed
- [ ] Script builds old+new, seeds, upgrades, verifies
- [ ] Documents preserved; unlock works; no crashes
- [ ] Backup created by v0.5.6 importable by HEAD
- [ ] Fresh install of HEAD can restore from backup
