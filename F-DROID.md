# F-Droid Metadata

This file contains the complete F-Droid metadata for LibreCrate, ready for
submission to [fdroiddata](https://gitlab.com/fdroid/fdroiddata).

## App metadata: com.librecrate.app.yml

Submit this file to `fdroiddata/metadata/`:

```yaml
Categories:
  - Ebook Reader
  - Pass Wallet
  - Wallet
License: AGPL-3.0-or-later
AuthorName: David Neurieder
AuthorWebSite: https://davidneurieder.github.io/
SourceCode: https://github.com/DavidNeurieder/LibreCrate
IssueTracker: https://github.com/DavidNeurieder/LibreCrate/issues

AutoName: LibreCrate

Description: |-
  Encrypted document vault for Android. Stores, views, organizes, and
  searches PDFs, EPUBs, Apple Wallet passes, comic archives, images,
  and Markdown notes — all encrypted at rest with AES-256-GCM.

  Features include per-file encryption, optional password protection
  with Argon2id, PDF viewer with pinch-to-zoom, full-text search,
  collections and tags, encrypted backup export/import, and zero
  network access.

RepoType: git
Repo: https://github.com/DavidNeurieder/LibreCrate

Binaries: https://github.com/DavidNeurieder/LibreCrate/releases/download/v%v/LibreCrate_v%v.apk

AllowedAPKSigningKeys: 11f860ee7ac19b8d992a52bf114a491f9b8b598091b7a5e94ce775b50e6e69fa

Builds:
  - versionName: 0.5.2
    versionCode: 7
    commit: v0.5.2
    subdir: app
    sudo:
      - apt-get update
      - apt-get install -y build-essential clang libclang-dev perl rustup
    ndk: r28c
    prebuild:
      # Rust: Debian trixie ships rustup (apt); pin the exact Rust toolchain
      # (vault-native/rust-toolchain.toml pins channel 1.94.0 + targets).
      # MuPDF is compiled from source by Cargo via the mupdf crate (no Java/srclib needed).
      - rustup default 1.94.0
      - rustup target add aarch64-linux-android
      - test -x "$HOME/.cargo/bin/cargo"
    gradle:
      - yes

AutoUpdateMode: Version
UpdateCheckMode: Tags
UpdateCheckData: app/build.gradle.kts|versionCode\s=\s(\d+)|.|versionName\s=\s"(.*)"
CurrentVersion: 0.5.2
CurrentVersionCode: 7
```

## How it works

### Build flow

1. F-Droid clones the repo at the specified `commit` tag
2. `sudo` installs `rustup` from Debian (trixie); `prebuild` pins the Rust toolchain (1.94.0 + Android target) via rustup
3. Gradle's `assembleRelease` triggers:
   - Rust host build → UniFFI Kotlin bindings → Rust Android cross-compile → copy .so
   - APK compilation and minification
4. F-Droid downloads the signed APK from `Binaries:` URL
5. F-Droid copies the signature from your APK onto its own build
6. If they match byte-for-byte, your signed APK is published

### Key details

| Item | Value |
|------|-------|
| PDF rendering | Rust `mupdf` crate (compiles MuPDF from source via Cargo) |
| NDK version | r28c |
| Rust version | Pinned to 1.94.0 via `vault-native/rust-toolchain.toml`; installed by Debian's `rustup` (`apt-get install rustup`) |
| UniFFI version | 0.28 (library mode, no `.udl` file) |
| Android target | `aarch64-linux-android` |
| Min API | 26 |

### Updating

1. Bump versions in `app/build.gradle.kts`, all `Cargo.toml` files
2. Add a new `Builds:` entry with the new version/commit
3. Update `CurrentVersion` and `CurrentVersionCode`
4. Tag the release and push
5. F-Droid auto-detects the new tag via `UpdateCheckMode: Tags`

## Troubleshooting

### Build fails on Rust

Ensure `rustup` is installed (the recipe's `sudo` block runs
`apt-get install -y rustup` — available on Debian trixie, F-Droid's build
box; it is not in bookworm) and that `rust-toolchain.toml` is present at the
repo root. The `prebuild` `rustup default 1.94.0` step downloads the exact
channel and the Android target from rust-lang.org.

### `command 'cargo'` not found

The `buildHostRustLib` task fails with
`A problem occurred starting process 'command 'cargo''` when Gradle cannot
find `cargo`. Gradle resolves a bare command name against the Gradle
process's PATH, so neither `export PATH=...` in `prebuild` nor a task-level
`environment("PATH", ...)` override helps — `vault-native-android/build.gradle.kts`
resolves the absolute `cargo` path itself and injects it into the task's
`commandLine`. It probes (in order) the current PATH, `$CARGO_HOME/bin`,
`$HOME/.cargo/bin`, `${user.home}/.cargo/bin`, `/root/.cargo/bin`,
`/home/vagrant/.cargo/bin`, `/home/fdroid/.cargo/bin`, `/usr/local/cargo/bin`.
If none exist it fails with a `GradleException` listing the probed paths and
`HOME`/`user.home`/`CARGO_HOME`. Make sure the `prebuild` `rustup default`
step actually succeeded (the `test -x "$HOME/.cargo/bin/cargo"` line fails
fast if not). `rustup default` creates the `~/.cargo/bin` shims that Gradle
probes.

### Build fails on MuPDF

The `mupdf` crate compiles MuPDF from source via its `build.rs`. On Android,
this uses the NDK toolchain. Ensure `ndk:` version in the metadata matches
your local NDK installation, and that `BINDGEN_EXTRA_CLANG_ARGS` includes
the NDK sysroot (handled automatically by `vault-native-android/build.gradle.kts`).

### `aarch64-linux-android-ranlib: not found` while building OpenSSL

The Rust build script for `openssl-src` (pulled in by rusqlite's
`bundled-sqlcipher-vendored-openssl`) fails with
`make: *** [Makefile:2799: install_dev] Error 127` when the Android
`make install_dev` phase can't find ranlib. Root cause: the `cc` crate
resolves ranlib for Android targets by first checking the
`RANLIB_aarch64_linux_android`/`RANLIB` env vars, then probing `llvm-ranlib`
on PATH, and finally falling back to `aarch64-linux-android-ranlib` — which
does not exist in NDK r28 (it ships `llvm-ranlib`). Locally this works if
`llvm-ranlib` happens to be on PATH; on F-Droid's build box it isn't, so the
fallback name is used and make fails.

Fixed in `vault-native-android/build.gradle.kts`: `buildAndroidRustLib`
sets `RANLIB_aarch64_linux_android` to the NDK's absolute `llvm-ranlib`
path, mirroring the existing `AR_aarch64_linux_android`/
`CC_aarch64_linux_android` env vars. This takes highest precedence in the
`cc` crate, so the build no longer depends on what's on PATH.

### `checkupdate failed ... : Couldn't find any version information`

With AGP 7+, the source `AndroidManifest.xml` no longer contains
`package`, `versionCode`, or `versionName` (they live in
`app/build.gradle.kts`), so F-Droid's manifest parser reports
`package=None, version=None, vercode=None` and checkupdates fails. Fix: the
metadata must set `UpdateCheckData` to extract the versions from the Gradle
file:

```yaml
UpdateCheckData: app/build.gradle.kts|versionCode\s=\s(\d+)|.|versionName\s=\s"(.*)"
```

Note the second filename is `.` (reuse the first file). Without this field
`fdroid checkupdates` falls back to parsing manifests and fails with
"Couldn't find any version information".

### APKs don't match (reproducibility failure)

Common causes:
- Different NDK version (check `ndk:` field)
- Different Rust toolchain version
- `codegen-units` not set to 1 (check `vault-native/Cargo.toml`)
- `mupdf` crate features differ (check `default-features` in `vault-native/core/Cargo.toml`)
- Embedded build paths (F-Droid uses `/builds/fdroid/fdroiddata/build/...`)
- ZIP ordering differences (use `./gradlew assembleRelease`, not Android Studio)

Run `diffoscope` on both APKs to identify differences.
