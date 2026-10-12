# Dependencies

Inventory of third-party components, per [`SECURITY.md`](SECURITY.md). Locked
versions come from `vault-native/Cargo.lock` and the Gradle version catalog
(`gradle/libs.versions.toml`). Update cadence is weekly via Dependabot
(`.github/dependabot.yml`); the Rust graph is scanned by `cargo-audit` in CI.

## Rust core — `vault-native/core`

| Crate | Version | Purpose |
| ----- | ------- | ------- |
| `argon2` | 0.6.0 | Argon2id key derivation (password → vault/backup key) |
| `aes` | 0.9.3 | AES block primitive for AES Key Wrap (RFC 3394) |
| `aes-gcm` | 0.11.1 | AES-256-GCM envelope encryption (v1/v2 backup format, file blobs) |
| `zip` | 9.0.2 | Backup payload container (deflate) |
| `rusqlite` | 0.40.2 | SQLCipher-encrypted SQLite database (see bundled natives below) |
| `serde` / `serde_json` | 1 / 1.0.154 | Manifest (JSON) serialization / parsing |
| `base64` | 0.23.1 | Manifest salt/base64 encoding |
| `hex` | 0.4.3 | Hashing / diagnostics |
| `sha2` | 0.11.0 | Content hashing for deduplication |
| `rand` | 0.10.3 | Salt / IV generation (RNG sources per use site) |
| `zeroize` | 1.9.1 | Key material scrubbing (`MasterKey`, `DerivedKey`) |
| `uniffi` | 0.28.3 | Kotlin/Swift bindings for the native core |
| `image` | 0.25.10 | Thumbnail decode/resize (JPEG/PNG/GIF/WebP/BMP) |
| `mupdf` | 0.8.0 | PDF/EPUB/CBZ rendering for thumbnails (vendored) |
| `thiserror` | 2.0.21 | Error types |
| `tempfile` | 3.27.0 | Temp directories (PDF render staging) |
| `toml` | 1.1.8 | `params.toml` (instance KDF parameters) |

The RustCrypto crates (`argon2`, `aes`, `aes-gcm`, `sha2`) were bumped as a
coordinated group onto the `hybrid-array` / `cipher` 0.5 generation, and `rand`
moved to 0.10 in the same pass. Correctness is pinned by in-tree Known-Answer
Tests (RFC 3394 §4 AES-KW vectors, Argon2 vectors) plus the security suites.

`uniffi` (0.28) and the Android version catalog are intentionally held back:
both need separate migrations (generated Kotlin bindings; Kotlin 2.x). They are
tracked here until those land, rather than being bumped piecemeal.

## Bundled native libraries

| Component | Version | Notes |
| --------- | ------- | ----- |
| **SQLCipher** | **4.14.0** (SQLite 3.51.3 amalgamation) | Bundled via `libsqlite3-sys` 0.38.2 with the `bundled-sqlcipher-vendored-openssl` feature; OpenSSL is vendored into the build. |
| OpenSSL | vendored | Cryptographic backend for SQLCipher (from the libsqlite3-sys build). |
| MuPDF | 0.8.0 | Document rendering (EPUB/CBZ/PDF); sources vendored in `vault-native/vendor`. |

## Android app

| Component | Version | Purpose |
| --------- | ------- | ------- |
| JNA (`net.java.dev.jna`) | 5.17.0 | Loading the native core `.so` (UniFFI) |
| Argon2KT | 1.6.0 | Client-side Argon2 (new-account flows) |
| `net.zetetic:android-database-sqlcipher` | 4.5.4 | App-side SQLCipher database on Android |
| Kotlin / AGP / Jetpack Compose | per `gradle/libs.versions.toml` | Application stack |

## `cargo-audit` status (as of this commit)

`cargo audit` reports **0 vulnerabilities** and the following **informational
warnings** in transitive dependencies (tracked; none are exploited reachable
paths today):

| Advisory | Crate (version) | Severity·Type | Note |
| -------- | --------------- | ------------- | ---- |
| RUSTSEC-2025-0141 | `bincode` 1.3.3 | unmaintained | Transitive via PDF pipeline deps |
| RUSTSEC-2024-0436 | `paste` 1.0.15 | unmaintained | Transitive macro dep |
| RUSTSEC-2026-0206 | `rustybuzz` 0.20.1 | unmaintained | Transitive text shaping |
| RUSTSEC-2026-0192 | `ttf-parser` 0.25.1 | unmaintained | Font parsing (shaping) |
| RUSTSEC-2026-0253 | `lru` 0.16.4 | unsound | Transitive cache; panic-safety issue |

These are addressed as upstream replacements land; Dependabot keeps the pin
set current and `cargo-audit` will convert a *vulnerability* (not a warning)
into a red build.

## Toolchain

- Rust is pinned to **1.94.0** via `vault-native/rust-toolchain.toml`
  (targets include `aarch64-linux-android` for the Android ABI).
- Android NDK pinned to **28.2.13676358** (`ndkVersion` in
  `vault-native-android/build.gradle.kts`).
- Fuzzing (nightly-only) uses the isolated `fuzz/` workspace — see
  [`fuzz/README.md`](fuzz/README.md); it is not built by stable CI.