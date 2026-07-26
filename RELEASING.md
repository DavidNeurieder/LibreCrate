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
git tag -a v0.5.0 -m "Release v0.5.0"
git push origin v0.5.0
```

## Step 2: Build Android APK

```bash
# Debug APK (for testing)
make build

# Release APK (unsigned — F-Droid builds from source)
make build-release
```

The APK is at `app/build/outputs/apk/release/app-release-unsigned.apk`.

F-Droid builds the APK from source using the recipe in `F-DROID.md`. No
signing key is needed in the repo.

## Step 3: Build desktop binaries

### Option A: Tarball (recommended for quick release)

```bash
packaging/release.sh --arch x86_64-unknown-linux-gnu
```

Output: `out/releases/librecrate-linux-x86_64.tar.gz`

### Option B: AppImage (portable, no deps)

```bash
packaging/appimage/build-appimage.sh
```

Output: `out/appimage/LibreCrate-GUI-x86_64.AppImage`

### Option C: Cross-compile for aarch64

Requires a cross-compilation toolchain (e.g., `cross`):

```bash
packaging/release.sh --arch aarch64-unknown-linux-gnu
```

## Step 4: Create GitHub Release

1. Go to https://github.com/neurieder/LibreCrate/releases/new
2. Select the tag `v0.5.0`
3. Title: `LibreCrate v0.5.0`
4. Attach artifacts:
   - `librecrate-linux-x86_64.tar.gz`
   - `LibreCrate-GUI-x86_64.AppImage` (if built)
   - `app-release-unsigned.apk` (if desired)
5. Copy the changelog section from `CHANGELOG.md` into the release notes

## Step 5: Update F-Droid

When the new tag is pushed, F-Droid's metadata repo can be updated:

1. Update `CurrentVersion` and `CurrentVersionCode` in the F-Droid metadata
2. Update the `commit` field to match the new tag
3. Submit a merge request to `fdroiddata`

## Version numbering

- Android uses `versionCode` (integer, incremented per release) and `versionName`
  (semantic version string)
- Rust crates use semantic versioning (`0.4.0`)
- All targets share the same version string — bump them in sync
