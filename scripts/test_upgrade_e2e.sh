#!/usr/bin/env bash
set -euo pipefail

# E2E upgrade test: install v0.5.6 debug APK, seed with the frozen snapshot,
# upgrade in place to the current HEAD debug APK, then verify the vault and its
# documents survive via UpgradeVerificationInstrumentedTest.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORKTREE_DIR="/tmp/librecrate-v0.5.6"
OLD_APK="$WORKTREE_DIR/app/build/outputs/apk/debug/app-debug.apk"
NEW_APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$REPO_ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
SEED_TAR="$REPO_ROOT/app/src/androidTest/resources/upgrade_v0_5_6_seed.tar.gz"
PACKAGE="com.librecrate.app"
TEST_PACKAGE="com.librecrate.app.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
VERIFY_CLASS="com.librecrate.app.data.UpgradeVerificationInstrumentedTest"
AVD=""
NO_REBUILD_OLD=0
KEEP_EMULATOR=0

ANDROID_SDK="${ANDROID_SDK:-$HOME/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
EMULATOR="${EMULATOR:-$ANDROID_SDK/emulator/emulator}"

cleanup() {
  set +e
  git worktree remove --force "$WORKTREE_DIR" 2>/dev/null || true
  set -e
}
trap cleanup EXIT

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]
  --avd <name>         Use specific AVD
  --no-rebuild-old     Skip building old APK (assume it exists)
  --keep-emulator     Don't stop emulator on exit
EOF
  exit 1
}

while [ $# -gt 0 ]; do
  case "$1" in
    --avd)
      AVD="$2"; shift 2 ;;
    --no-rebuild-old)
      NO_REBUILD_OLD=1; shift ;;
    --keep-emulator)
      KEEP_EMULATOR=1; shift ;;
    *)
      usage ;;
  esac
done

check_deps() {
  for cmd in git adb; do
    command -v "$cmd" >/dev/null 2>&1 || { echo "Missing: $cmd" >&2; exit 1; }
  done
  [ -x "$REPO_ROOT/gradlew" ] || { echo "Missing: $REPO_ROOT/gradlew" >&2; exit 1; }
}

ensure_emulator() {
  if $ADB devices 2>/dev/null | grep -q emulator; then
    echo "Emulator already running"
    $ADB wait-for-device
    return 0
  fi
  if [ -z "$AVD" ]; then
    mapfile -t avds < <($EMULATOR -list-avds 2>/dev/null | grep -v '^$')
    if [ ${#avds[@]} -eq 0 ]; then
      echo "No AVDs found; specify --avd" >&2
      exit 1
    fi
    AVD="${avds[0]}"
  fi
  echo "Booting emulator: $AVD"
  $EMULATOR -avd "$AVD" -no-snapshot -noaudio >/dev/null 2>&1 &
  $ADB wait-for-device
  for _ in $(seq 1 90); do
    if [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
      echo "Emulator booted"
      sleep 5
      return 0
    fi
    sleep 2
  done
  echo "Emulator failed to boot" >&2
  exit 1
}

build_old() {
  if [ "$NO_REBUILD_OLD" -eq 1 ] && [ -f "$OLD_APK" ]; then
    echo "Using existing old APK: $OLD_APK"
    return 0
  fi
  echo "Checking out v0.5.6 into worktree..."
  git worktree remove --force "$WORKTREE_DIR" 2>/dev/null || true
  git worktree add "$WORKTREE_DIR" v0.5.6
  echo "Building old debug APK (this takes a while)..."
  (cd "$WORKTREE_DIR" && ./gradlew :app:assembleDebug)
}

build_new() {
  echo "Building current HEAD debug APK..."
  (cd "$REPO_ROOT" && ./gradlew :app:assembleDebug)
}

install_old() {
  # v0.5.6 has a lower versionCode than a released build, so an in-place
  # install would be rejected as a downgrade. Remove first.
  echo "Installing v0.5.6 (removing any newer install first)..."
  $ADB uninstall "$PACKAGE" >/dev/null 2>&1 || true
  $ADB uninstall "$TEST_PACKAGE" >/dev/null 2>&1 || true
  $ADB install "$OLD_APK"
}

seed() {
  if [ ! -f "$SEED_TAR" ]; then
    echo "Seed tar not found: $SEED_TAR" >&2
    echo "Generate it first (see specification/upgrade-test.md Phase 2)" >&2
    exit 1
  fi
  echo "Seeding app data from frozen snapshot..."
  $ADB shell pm clear "$PACKAGE" >/dev/null
  $ADB push "$SEED_TAR" /data/local/tmp/seed.tar.gz >/dev/null
  $ADB shell run-as "$PACKAGE" tar xzf /data/local/tmp/seed.tar.gz
  $ADB shell rm -f /data/local/tmp/seed.tar.gz

  # Sanity: the snapshot must have landed in the app-private dir.
  if ! $ADB shell run-as "$PACKAGE" ls databases/librecrate.db >/dev/null 2>&1; then
    echo "Seeding failed: databases/librecrate.db missing after extraction" >&2
    exit 1
  fi
  echo "Seed extracted"
}

verify() {
  echo "Building and running upgrade verification test..."
  (cd "$REPO_ROOT" && ./gradlew :app:assembleDebugAndroidTest)
  $ADB install -r "$TEST_APK"

  local out
  out="$($ADB shell am instrument -w -e class "$VERIFY_CLASS" "$TEST_PACKAGE/$RUNNER" 2>&1)" || true
  echo "$out"

  if echo "$out" | grep -q "OK ("; then
    echo "Verification test passed"
  else
    echo "Upgrade verification FAILED" >&2
    exit 1
  fi
}

main() {
  check_deps
  ensure_emulator
  build_old
  install_old
  seed
  build_new
  $ADB install -r "$NEW_APK"
  verify
  echo "=== UPGRADE TEST PASSED ==="
}

main "$@"
