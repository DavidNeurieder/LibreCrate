#!/usr/bin/env bash
#
# Validate a fuzz duration in seconds and print its canonical decimal value.
#
#   fuzz/scripts/validate_seconds.sh 180          # value as an argument
#   FUZZ_SECONDS=180 fuzz/scripts/validate_seconds.sh   # value from the env
#
# On success: exits 0 and prints the validated integer (no leading zeros) on
# stdout, so callers can use it without ever embedding untrusted input in a
# command string.
#
# On failure: exits 1 and prints a message on stderr. Empty strings, non-numeric
# values, over-long digit strings and out-of-range numbers are all rejected.
#
# The digit-length cap is checked *before* any arithmetic, so an extremely long
# numeric string can never overflow bash's integer math (its wrap-around could
# otherwise turn a huge value into one that passes the range check).
set -euo pipefail

readonly MIN_SECONDS=1
readonly MAX_SECONDS=600
# 9 digits is <= 999999999, which fits a signed 32-bit int, so `10#$value`
# cannot overflow regardless of the host bash's integer width.
readonly MAX_DIGITS=9

value="${1:-${FUZZ_SECONDS:-}}"

if [[ ! "$value" =~ ^[0-9]+$ ]]; then
  echo "seconds must be a positive integer, got: '${value}'" >&2
  exit 1
fi

if (( ${#value} > MAX_DIGITS )); then
  echo "seconds must be at most ${MAX_SECONDS}, got a ${#value}-digit number" >&2
  exit 1
fi

# `10#` forces decimal so e.g. "010" is not read as octal.
seconds=$((10#$value))

if (( seconds < MIN_SECONDS || seconds > MAX_SECONDS )); then
  echo "seconds must be between ${MIN_SECONDS} and ${MAX_SECONDS}, got: ${seconds}" >&2
  exit 1
fi

printf '%s\n' "$seconds"
