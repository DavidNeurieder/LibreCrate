# F-Droid Metadata

This file contains the complete F-Droid metadata for LibreCrate, ready for
submission to [fdroiddata](https://gitlab.com/fdroid/fdroiddata).

## App metadata: com.librecrate.app.yml

Submit this file to `fdroiddata/metadata/`:

```yaml
Categories:
  - Security
  - Files and Files
License: AGPL-3.0-or-later
AuthorName: LibreCrate Contributors
WebSite: https://github.com/neurieder/LibreCrate
SourceCode: https://github.com/neurieder/LibreCrate
IssueTracker: https://github.com/neurieder/LibreCrate/issues

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
Repo: https://github.com/neurieder/LibreCrate.git

Binaries: https://github.com/neurieder/LibreCrate/releases/download/v%v/app-release.apk

# Replace with actual SHA-256 fingerprint after first signed release.
# Get with: keytool -exportcert -alias librecrate -keystore release.keystore \
#   -storepass <password> | sha256sum | tr -d ' ' | tr '[:upper:]' '[:lower:]'
AllowedAPKSigningKeys: REPLACE_WITH_SHA256_FINGERPRINT

Builds:
  - versionName: 0.4.0
    versionCode: 4
    commit: v0.4.0
    subdir: app
    sudo:
      - apt-get update
      - apt-get install -y make pkg-config curl openjdk-17-jdk-headless
      - update-java-alternatives -a
    ndk: r28c
    prebuild:
      # Rust: install Rustup (rust-toolchain.toml handles channel + targets)
      # MuPDF is compiled from source by Cargo via the mupdf crate (no Java/srclib needed)
      - curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
    gradle:
      - yes

AutoUpdateMode: Version
UpdateCheckMode: Tags
UpdateCheckData: app/build.gradle.kts|versionCode\s=\s(\d+)|.|versionName\s=\s"(.*)"
CurrentVersion: 0.4.0
CurrentVersionCode: 4
```

## How it works

### Build flow

1. F-Droid clones the repo at the specified `commit` tag
2. `prebuild` installs Rustup
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
| Rust version | Pinned via `vault-native/rust-toolchain.toml` (stable) |
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

Ensure `rust-toolchain.toml` is present at the repo root. Rustup reads it
automatically and installs the correct channel + Android target.

### Build fails on MuPDF

The `mupdf` crate compiles MuPDF from source via its `build.rs`. On Android,
this uses the NDK toolchain. Ensure `ndk:` version in the metadata matches
your local NDK installation, and that `BINDGEN_EXTRA_CLANG_ARGS` includes
the NDK sysroot (handled automatically by `vault-native-android/build.gradle.kts`).

### APKs don't match (reproducibility failure)

Common causes:
- Different NDK version (check `ndk:` field)
- Different Rust toolchain version
- `codegen-units` not set to 1 (check `vault-native/Cargo.toml`)
- `mupdf` crate features differ (check `default-features` in `vault-native/core/Cargo.toml`)
- Embedded build paths (F-Droid uses `/builds/fdroid/fdroiddata/build/...`)
- ZIP ordering differences (use `./gradlew assembleRelease`, not Android Studio)

Run `diffoscope` on both APKs to identify differences.
