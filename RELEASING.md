# Releasing LibreCrate

This document covers how to cut a new release of LibreCrate across all targets.

## Pre-release checklist

1. All tests pass (`./gradlew connectedDebugAndroidTest`, `cargo test --workspace`)
2. Version numbers are bumped in:
   - `vault-native/core/Cargo.toml`
   - `vault-native/cli/Cargo.toml`
   - `vault-native/gui/Cargo.toml`
   - `app/build.gradle.kts` (`versionCode` + `versionName`)
3. `CHANGELOG.md` is updated with the new version
4. `F-DROID.md` recipe is updated with the new version/commit
5. `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` exists

## Step 1: Tag the release

```bash
git tag -a v0.5.4 -m "Release v0.5.4"
git push origin v0.5.4
```

## Step 2: Build and sign Android APK

### First-time setup: create keystore

```bash
keytool -genkeypair -v \
  -keystore release.keystore \
  -alias librecrate \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass <your-store-password> \
  -keypass <your-key-password> \
  -dname "CN=Your Name, OU=Your Org, O=Your Org, L=City, ST=State, C=US"
```

Store `release.keystore` securely. It is gitignored. **Do not lose it** —
Android requires the same signing key for all updates.

### Get signing fingerprint for F-Droid

After the first signed release, get the SHA-256 fingerprint:

```bash
apksigner verify --print-certs app-release.apk | grep SHA-256
```

Put the lowercase hex fingerprint into `F-DROID.md` as
`AllowedAPKSigningKeys`.

### Build and sign

```bash
# 1. Build unsigned release APK
./gradlew assembleRelease

# 2. Sign manually with apksigner
apksigner sign \
  --ks release.keystore \
  --alias librecrate \
  --out app-release.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk

# 3. Verify signature
apksigner verify --print-certs app-release.apk
```

The signed APK is at `app-release.apk` in the repo root.

## Step 3: Build desktop binaries

### Option A: Tarball (recommended)

```bash
packaging/release.sh --arch x86_64-unknown-linux-gnu
```

Output: `out/releases/librecrate-linux-x86_64.tar.gz`

### Option B: AppImage

```bash
packaging/appimage/build-appimage.sh
```

Output: `out/appimage/LibreCrate-GUI-x86_64.AppImage`

### Option C: Cross-compile for aarch64

```bash
packaging/release.sh --arch aarch64-unknown-linux-gnu
```

## Step 4: Create GitHub Release

1. Go to https://github.com/neurieder/LibreCrate/releases/new
2. Select the tag `v0.5.4`
3. Title: `LibreCrate v0.5.4`
4. Attach artifacts:
   - `app-release.apk` (signed Android APK)
   - `librecrate-linux-x86_64.tar.gz`
   - `LibreCrate-GUI-x86_64.AppImage` (if built)
5. Copy the changelog from `CHANGELOG.md` into release notes
6. Publish the release

## Step 5: F-Droid

F-Droid auto-detects new tags via `UpdateCheckMode: Tags` in the metadata.
When you push a new tag:

1. F-Droid detects the new version
2. Builds from source using the recipe in `F-DROID.md`
3. Downloads your signed APK from the GitHub Release
4. Verifies the builds match byte-for-byte
5. If they match, publishes your signed APK
6. If they don't match, publishes F-Droid's own build

No manual action needed unless the build fails. Check
https://gitlab.com/fdroid/fdroiddata/-/merge_requests for new MRs.

## Version numbering

- Android uses `versionCode` (integer, incremented per release) and `versionName`
  (semantic version string)
- Rust crates use semantic versioning (`0.4.0`)
- All targets share the same version string — bump them in sync
