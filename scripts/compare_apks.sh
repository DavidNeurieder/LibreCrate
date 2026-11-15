#!/usr/bin/env bash
# Compare two APKs by extracted content instead of the zip container itself.
#
# Signatures are expected to differ between a local build and an F-Droid build,
# so v1 signature files (META-INF/*.SF, *.RSA, etc.) are ignored. Everything
# else must be byte-identical.
#
# Usage: scripts/compare_apks.sh <apk-a> <apk-b>
set -euo pipefail

usage() {
  printf 'Usage: %s <apk-a> <apk-b>\n' "$(basename "$0")"
}

if [ "$#" -ne 2 ]; then
  usage
  exit 2
fi

apk_a=$1
apk_b=$2

for apk in "$apk_a" "$apk_b"; do
  [ -f "$apk" ] || { printf 'error: %s does not exist\n' "$apk" >&2; exit 2; }
done

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

strip_signatures() {
  local dir=$1
  [ -d "$dir/META-INF" ] || return 0
  find "$dir/META-INF" -maxdepth 1 -type f \
    \( -name '*.RSA' -o -name '*.DSA' -o -name '*.EC' -o -name '*.SF' -o -name 'MANIFEST.MF' \) \
    -delete
}

for label in a b; do
  if [ "$label" = a ]; then apk=$apk_a; else apk=$apk_b; fi
  mkdir -p "$work/$label"
  unzip -q "$apk" -d "$work/$label"
  strip_signatures "$work/$label"
  (cd "$work/$label" && find . -type f -print0 | sort -z | xargs -0 -r sha256sum) > "$work/$label.sha256"
done

printf 'APK A: %s\n' "$apk_a"
printf 'APK B: %s\n' "$apk_b"

if diff -q "$work/a.sha256" "$work/b.sha256" >/dev/null; then
  printf 'OK: extracted contents (excluding v1 signatures) are identical.\n'
  exit 0
fi

printf 'MISMATCH: extracted contents differ.\n\n' >&2
diff -u "$work/a.sha256" "$work/b.sha256" | sed -n '1,200p' >&2 || true
printf '\nHint: run diffoscope on the APKs for a detailed report.\n' >&2
exit 1
