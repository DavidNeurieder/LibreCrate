# F-Droid Build Recipe

Local dev builds use the prebuilt MuPDF AAR from `maven.ghostscript.com`.
F-Droid does not allow this repository, so MuPDF must be built from source.

## How the official MuPDF viewer does it

The [official MuPDF viewer on F-Droid](https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/com.artifex.mupdf.viewer.app.yml)
uses `submodules: true`. Their repo already has `jni/libmupdf` as a git submodule
pointing to `mupdf.git`. The recipe:

```yaml
submodules: true
prebuild:
  - sed -i -e '/maven.ghostscript.com/d' ../build.gradle
  - sed -i -e "s/'-j4'/'-j`nproc`'/" ../jni/build.gradle
  - echo 'ABI_FILTERS=armeabi-v7a,arm64-v8a,x86' >> ../gradle.properties
build:
  - make -C ../jni/libmupdf generate
scandelete:
  - jni/libmupdf/thirdparty
ndk: r28c
```

The `build` step runs `make generate` to produce JNI stubs, then Gradle
compiles the native `.so` via ndkBuild (`Android.mk`).

## Required recipe for LibreCrate

Since LibreCrate uses `mupdf-android-fitz` (a library wrapping the same MuPDF C
source) rather than having a `jni/libmupdf` submodule in-repo, the recipe
needs to handle two source trees.

The predefined `MuPDF` srclib
([srclibs/MuPDF.yml](https://gitlab.com/fdroid/fdroiddata/-/blob/master/srclibs/MuPDF.yml))
points to the main MuPDF C library at `git.ghostscript.com/mupdf.git`. It
initializes submodules (which pulls in thirdparty dependencies).

For the Android JNI bindings, a custom srclib or inline approach is needed.
This is the same challenge Librera faces, and they solve it with an srclib +
build script pattern.

### Recommended approach

Submit a new srclib definition alongside the app metadata:

**`srclibs/MupdfAndroidFitz.yml`:**
```yaml
RepoType: git
Repo: https://github.com/ArtifexSoftware/mupdf-android-fitz.git
Prepare: |
  git submodule update --init --recursive
```

**App metadata:**
```yaml
Builds:
  - versionName: 0.3.0
    versionCode: 3
    commit: v0.3.0
    sudo:
      - apt-get update
      - apt-get install -y make pkg-config
    gradle:
      - yes
    srclibs:
      - MupdfAndroidFitz@1.27.1
    prebuild:
      # 1. Remove Ghostscript Maven repo
      - sed -i -e '/maven.ghostscript.com/d' settings.gradle.kts
      # 2. Copy mupdf-android-fitz source into the build tree
      - cp -r $$MupdfAndroidFitz$$ libs/mupdf-android-fitz
      # 3. Generate JNI stubs
      - make -C libs/mupdf-android-fitz/libmupdf generate
      # 4. Add as a Gradle subproject
      - echo 'include(":libs:mupdf-android-fitz")' >> settings.gradle.kts
      # 5. Replace Maven dependency with project dependency
      - sed -i 's|implementation(libs.mupdf.fitz)|implementation(project(":libs:mupdf-android-fitz"))|' app/build.gradle.kts
    scandelete:
      - libs/mupdf-android-fitz/libmupdf/thirdparty
    build:
      # Build must run after Gradle resolves, so place native gen in build step
      - make -C libs/mupdf-android-fitz/libmupdf generate
    ndk: r28c
```

### Key details

| Item | Value |
|------|-------|
| Tag to use | `1.27.1` (match `gradle/libs.versions.toml`) |
| NDK version | `r28c` (set in mupdf-android-fitz's `build.gradle`) |
| System deps | `make`, `gcc`, `pkg-config` |
| scandelete | `libs/mupdf-android-fitz/libmupdf/thirdparty` (avoid scanning 3rd-party C libs) |

The scandelete path removes the large thirdparty directory (harfbuzz, curl,
jbig2dec, openjpeg, leptonica, tesseract) to avoid F-Droid license scanner
noise.

## Rust native library (`vault-native`)

LibreCrate uses a Rust library (`vault-native/core/`) exposed via UniFFI to
Kotlin/JNA. Neither the `.so` files nor the Kotlin bindings are committed to
git. **Gradle handles everything automatically:**

1. Builds Rust for the host platform (`x86_64-unknown-linux-gnu`)
2. Generates Kotlin UniFFI bindings from the host `.so`
3. Builds Rust for Android (`aarch64-linux-android`)
4. Copies the `.so` to `jniLibs/` before APK packaging

Prerequisites: Rustup, `cargo`, and Android NDK (set `ANDROID_NDK_HOME`).

Rust version is pinned via `vault-native/rust-toolchain.toml` (currently
`stable`). Rustup reads this file automatically and installs the correct
channel + Android target on first `cargo build`. No `rustup target add`
needed in the recipe.

### Recipe

```yaml
Builds:
  - versionName: 0.4.0
    versionCode: 4
    commit: v0.4.0
    sudo:
      - apt-get update
      - apt-get install -y make pkg-config curl
    ndk: r28c
    srclibs:
      - MupdfAndroidFitz@1.27.1
    prebuild:
      # MuPDF: remove Ghostscript Maven repo, copy source, set up subproject
      - sed -i -e '/maven.ghostscript.com/d' settings.gradle.kts
      - cp -r $$MupdfAndroidFitz$$ libs/mupdf-android-fitz
      - make -C libs/mupdf-android-fitz/libmupdf generate
      - echo 'include(":libs:mupdf-android-fitz")' >> settings.gradle.kts
      - sed -i 's|implementation(libs.mupdf.fitz)|implementation(project(":libs:mupdf-android-fitz"))|' app/build.gradle.kts
      # Rust: install Rustup (rust-toolchain.toml handles version + targets)
      - curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
    gradle:
      - yes
    scandelete:
      - libs/mupdf-android-fitz/libmupdf/thirdparty
```

Gradle's `assembleRelease` task automatically runs the Rust build pipeline
(bindings generation → Android `.so` → copy to jniLibs) before compiling the
APK. Rustup reads `vault-native/rust-toolchain.toml` during `cargo build` and
installs the pinned Rust version + `aarch64-linux-android` target on the fly.

### Key details

| Item | Value |
|------|-------|
| Rust version | Pinned via `vault-native/rust-toolchain.toml` (currently `stable`) |
| UniFFI version | 0.28 (library mode, no `.udl` file) |
| Host target | `x86_64-unknown-linux-gnu` (default in Rustup, no action needed) |
| Android target | `aarch64-linux-android` (auto-installed by `rust-toolchain.toml`) |
| JNA | `net.java.dev.jna:jna:5.14.0@aar` (from Maven Central, no issue) |
| Min API | 26 |

### Updating Rust

1. Update the Rust source in `vault-native/core/`.
2. Run `./gradlew assembleDebug` — Gradle rebuilds everything automatically.
3. No pre-committed artifacts to update.

## Updating MuPDF

1. Bump the version in `gradle/libs.versions.toml`.
2. Test locally with `assembleDebug` (uses Maven AAR).
3. Before tagging a release, update the srclib tag in the recipe.
4. Verify the new mupdf-android-fitz tag's `build.gradle` NDK version hasn't
   changed.
