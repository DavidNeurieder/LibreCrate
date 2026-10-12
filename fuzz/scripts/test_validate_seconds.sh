#!/usr/bin/env bash
#
# Table-driven tests for fuzz/scripts/validate_seconds.sh.
#
# Runs locally and in CI (see .github/workflows/ci.yml, the `rust-core` job).
# Exit status is non-zero if any case behaves unexpectedly.
#
# Deliberately no `set -e`: arithmetic like `((pass++))` returns status 1 when
# the value is 0, which would abort the run.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
validator="$here/validate_seconds.sh"

pass=0
fail=0

expect_accept() {
  local input="$1" expected="$2" out
  if out="$(FUZZ_SECONDS="$input" bash "$validator" 2>/dev/null)"; then
    if [[ "$out" == "$expected" ]]; then
      echo "ok    accept '$input' -> $out"
      ((pass++))
      return
    fi
    echo "FAIL  accept '$input' -> '$out' (expected '$expected')"
  else
    echo "FAIL  accept '$input' -> rejected"
  fi
  ((fail++))
}

expect_reject() {
  local input="$1"
  if FUZZ_SECONDS="$input" bash "$validator" >/dev/null 2>&1; then
    echo "FAIL  reject '$input' -> accepted"
    ((fail++))
  else
    echo "ok    reject '$input'"
    ((pass++))
  fi
}

echo "== accepted =="
expect_accept "1" "1"
expect_accept "60" "60"
expect_accept "180" "180"
expect_accept "600" "600"
expect_accept "000000060" "60" # canonicalized (decimal, no leading zeros)

echo "== rejected =="
expect_reject ""                 # empty string
expect_reject "0"                # below minimum
expect_reject "-10"              # negative
expect_reject "601"              # above maximum
expect_reject "abc"              # non-numeric
expect_reject "1e3"              # not an integer
expect_reject " 60"              # leading space
expect_reject "60 "              # trailing space
expect_reject "60; echo injected"
expect_reject "60 && rm -rf /"
expect_reject "\$(touch /tmp/pwned)"
expect_reject "$(printf '1%.0s' {1..100})" # 100-digit string (no overflow)

echo "== argument form =="
if [[ "$(bash "$validator" 60)" == "60" ]]; then
  echo "ok    argument 60 -> 60"
  ((pass++))
else
  echo "FAIL  argument form"
  ((fail++))
fi
if bash "$validator" 9999 >/dev/null 2>&1; then
  echo "FAIL  argument 9999 -> accepted"
  ((fail++))
else
  echo "ok    reject argument 9999"
  ((pass++))
fi

echo
if (( fail > 0 )); then
  echo "${fail} failed, ${pass} passed"
  exit 1
fi
echo "all ${pass} cases passed"
