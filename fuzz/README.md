# LibreCrate fuzz targets

Fuzz harnesses for the parsers that consume *untrusted* input:

| Target | Parses | Notes |
|--------|--------|-------|
| `fuzz_package_read` | vault container header + manifest bounds | cheap, high coverage |
| `fuzz_manifest_json` | manifest JSON | cheap |
| `fuzz_extract_zip` | decrypted ZIP payload (names + size limits) | cheap |
| `fuzz_archive_path` | archive entry names (zip-slip sanitizer) | cheap |
| `fuzz_thumbnail` | image / EPUB / CBZ / PDF thumbnail decoders | PDF branch is slow (MuPDF render) |
| `fuzz_import` | full import pipeline incl. Argon2 + AES-GCM | slow, use sparingly |

## Requirements

- Rust **nightly** toolchain (libFuzzer runtime needs `-Zsanitizer`)
- `cargo-fuzz` (`cargo install cargo-fuzz`)
- A C/C++ toolchain + clang for libFuzzer and MuPDF (vendored in `vault-native`)
- A one-time full build of `vault-native` and its vendored dependencies
  (SQLCipher + OpenSSL + MuPDF) — expect several minutes.

## Run

```sh
rustup toolchain install nightly
cargo install cargo-fuzz
cd fuzz
cargo +nightly fuzz run fuzz_package_read
cargo +nightly fuzz run fuzz_extract_zip -- -runs=100000

# From the committed seed corpus (recommended starting point):
mkdir -p corpus/fuzz_extract_zip
cp -n seeds/fuzz_extract_zip/. corpus/fuzz_extract_zip/
cargo +nightly fuzz run fuzz_extract_zip corpus/fuzz_extract_zip -max_total_time=120
```

## Seed corpus (`seeds/`)

`seeds/<target>/` holds small, curated inputs for each target: real containers
(the frozen data-integrity fixtures), valid ZIPs, valid/malformed manifest JSON,
sanitizer vectors, tiny images and a minimal PDF. They are committed, so they are
also replayed on **stable** Rust by
`vault-native/core/tests/fuzz_seed_corpus.rs` on every normal CI run — the corpus
is therefore exercised even though `fuzz/` itself only builds on nightly.

Regenerate them with:

```sh
bash fuzz/scripts/make_seeds.sh   # then commit fuzz/seeds/**
```

`corpus/` (what `cargo fuzz` writes to while running) and `artifacts/` (crashes)
stay gitignored; the nightly workflow copies `seeds/` into a fresh `corpus/`.

## Nightly CI

`.github/workflows/nightly.yml` runs every target for a bounded time seeded from
`seeds/` and uploads any crash reproducer. The per-target duration is the
`workflow_dispatch` input `seconds` (default **180**): it is passed to the shell
only through an environment variable and validated by
`fuzz/scripts/validate_seconds.sh` to an integer in **1–600** before it reaches
`cargo fuzz`, so it cannot inject shell commands or request an excessive run.
Scheduled runs use the same 180 s default. The validator's accept/reject rules
are covered by `fuzz/scripts/test_validate_seconds.sh`, which runs in the
`rust-core` CI job.

Scheduled workflows only run from the default branch, so the job is inert until
this lands on `main`.

## Notes

- This directory is **not** a member of the `vault-native/` workspace and is
  not compiled by `cargo build`/`cargo test` in CI, keeping stable builds
  free of the sanitizer harness.
- A crashable invariant found by these targets should reproduce via the
  parallel regression suite in `vault-native/core/tests/security_*.rs`.
- **Promoting a crash:** copy the reproducer from `artifacts/<target>/...` into
  `seeds/<target>/` (it is then replayed deterministically on stable), and add a
  focused assertion in the matching `security_*.rs` test when it represents a
  real invariant.
