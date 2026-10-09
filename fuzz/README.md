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
```

## Notes

- This directory is **not** a member of the `vault-native/` workspace and is
  not compiled by `cargo build`/`cargo test` in CI, keeping stable builds
  free of the sanitizer harness.
- Seeds for the structured formats (vault headers, valid ZIP files) can be
  dropped into `corpus/<target>/` to speed up coverage from `cargo +nightly
  fuzz build` outputs, e.g. a `vault-native --example` that writes a valid
  export.
- A crashable invariant found by these targets should reproduce via the
  parallel regression suite in `vault-native/core/tests/security_*.rs`.