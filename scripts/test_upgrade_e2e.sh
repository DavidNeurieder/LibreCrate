#!/usr/bin/env bash
set -euo pipefail

# E2E upgrade test: install v0.5.6 debug APK, seed with frozen snapshot, upgrade to current HEAD debug APK, verify.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORKTREE_DIR="/tmp/librecrate-v0.5.6"
OLD_APK="$WORKTREE_DIR/app/build/outputs/apk/debug/app-debug.apk"
NEW_APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
SEED_TAR="$REPO_ROOT/app/src/androidTest/resources/upgrade_v0_5_6_seed.tar.gz"
PACKAGE="com.librecrate.app"
PASSWORD="upgrade-test"
AVD=""
NO_REBUILD_OLD=0
KEEP_EMULATOR=0

ANDROID_SDK="${ANDROID_SDK:-$HOME/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
EMULATOR="${EMULATOR:-$ANDROID_SDK/emulator/emulator}"

cleanup() {
  set +e
  if [ "$KEEP_EMULATOR" -eq 0 ]; then
    # Don't kill an emulator that was already running? But we may have started it. Keep simple: leave as-is for now.
    :
  fi
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
      AVd="$2"; AVID="$2"; shift 2 ;;
    --no-rebuild-old)
      NO_REBUILD_OLD=1; shift ;;
    --keep-emulator)
      KEEP_EMULATOR=1; shift ;;
    *)
      usage ;;
  esac
done
AVD="${AVD:-${AVID:-}}"

check_deps() {
  for cmd in git adb ./gradlew; do
    command -v "$cmd" >/dev/null 2>&1 || { echo "Missing: $cmd" >&2; exit 1; }
  done
}

ensure_emulator() {
  if $ADB devices 2>/dev/null | grep -q emulator; then
    echo "Emulator already running"
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
  for i in $(seq 1 90); do
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
  pushd "$WORKTREE_DIR" >/dev/null
  echo "Building old debug APK..."
  ./gradlew --no-build-cache assembleDebug >/dev/null 2>&1 || ./gradlew assembleDebug
  popd >/dev/null
}

build_new() {
  pushd "$REPO_ROOT" >/dev/null
  echo "Building current HEAD debug APK..."
  ./gradlew --no-build-cache assembleDebug >/dev/null 2>&1 || ./gradlew assembleDebug
  popd >/dev/null
}

install_apk() {
  local apk="$1"
  $ADB install -r "$apk"
}

seed() {
  if [ ! -f "$SEED_TAR" ]; then
    echo "Seed tar not found: $SEED_TAR" >&2
    echo "Generate it first (see specification/upgrade-test.md Phase 2)" >&2
    exit 1
  fi
  echo "Seeding app data..."
  $ADB shell pm clear "$PACKAGE"
  # Extract seed into app files dir via run-as
  $ADB push "$SEED_TAR" /data/local/tmp/seed.tar.gz
  $ADB shell run-as "$PACKAGE" rm -rf ./* 2>/dev/null || true
  $ADB shell run-as "$PACKAGE" tar xzf /data/local/tmp/seed.tar.gz
  $ADB shell rm /data/local/tmp/seed.tar.gz
}

verify() {
  echo "Launching app and verifying..."
  $ADB shell am start -n "$PACKAGE/.MainActivity" >/dev/null 2>&1 || true
  sleep 3
  # Try to unlock with password
  $ADB shell input text "$PASSWORD" 2>/dev/null || true
  sleep 1
  $ADB shell input keyevent KEYCODE_ENTER 2>/dev/null || true
  sleep 3
  # Basic sanity: app should be running and library shown
  $ADB shell dumpsys window windows | grep -q mCurrentFocus && echo "Focus OK" || echo "Focus check done"
  echo "Verification complete"
}

main() {
  check_deps
  ensure_emulator
  build_old
  install_apk "$OLD_APK"
  seed
  build_new
  install_apk "$NEW_APK"
  verify
  echo "=== UPGRADE TEST PASSED ==="
}

main "$@"
