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
Kotlin/JNA. The UniFFI Kotlin bindings are **pre-generated and committed**
(`vault-native-android/src/main/java/uniffi/vault_native/vault_native.kt`), so
F-Droid only needs to compile the Rust `.so` files.

The `.so` files are **not committed to git**. Local development requires running
`scripts/build_native.sh` before `./gradlew assembleDebug`. F-Droid builds them
from source in the recipe below.

### Recipe additions

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
      # Rust: install toolchain and build .so before Gradle
      - curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
      - source $HOME/.cargo/env && rustup target add aarch64-linux-android
      - |
        export TOOLCHAIN="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64"
        export CC_aarch64_linux_android="$TOOLCHAIN/bin/aarch64-linux-android26-clang"
        export CC_x86_64_linux_android="$TOOLCHAIN/bin/x86_64-linux-android26-clang"
        export AR_aarch64_linux_android="$TOOLCHAIN/bin/llvm-ar"
        export AR_x86_64_linux_android="$TOOLCHAIN/bin/llvm-ar"
        export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN/bin/aarch64-linux-android26-clang"
        export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$TOOLCHAIN/bin/x86_64-linux-android26-clang"
        cargo build --manifest-path vault-native/Cargo.toml \
          --target aarch64-linux-android --release &&
        cp vault-native/target/aarch64-linux-android/release/libvault_native.so \
          vault-native-android/src/main/jniLibs/arm64-v8a/
      - |
        cargo build --manifest-path vault-native/Cargo.toml \
          --target x86_64-linux-android --release &&
        cp vault-native/target/x86_64-linux-android/release/libvault_native.so \
          vault-native-android/src/main/jniLibs/x86_64/
    scandelete:
      - libs/mupdf-android-fitz/libmupdf/thirdparty
```

### Minimal approach (recommended)

Since the Kotlin bindings are pre-committed, only the `.so` build is needed.
The `build` step above replaces the normal Gradle build for the native lib.

Alternatively, if you prefer to keep the Gradle build as a single step, ensure
the `.so` files are present *before* Gradle runs:

```yaml
prebuild:
  # ... MuPDF prebuild steps ...
  # Rust build before Gradle
  - curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
  - source $HOME/.cargo/env && rustup target add aarch64-linux-android
  - # ... CC/CARGO_TARGET exports, cargo build, cp as above ...
```

In this approach the Gradle step is just `gradle: - yes` and the Rust `.so`
files are already in `jniLibs/` before Gradle resolves.

### Key details

| Item | Value |
|------|-------|
| Rust edition | 2021 |
| UniFFI version | 0.28 (library mode, no `.udl` file) |
| Targets | `aarch64-linux-android`, `x86_64-linux-android` |
| NDK toolchain | Set via `CARGO_TARGET_*_LINKER`, `CC_*`, `AR_*` env vars pointing to `$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/` |
| C Compiler | `aarch64-linux-android26-clang` / `x86_64-linux-android26-clang` |
| Linker | `aarch64-linux-android26-clang` / `x86_64-linux-android26-clang` (via `CARGO_TARGET_*_LINKER`) |
| JNA | `net.java.dev.jna:jna:5.14.0@aar` (from Maven Central, no issue) |
| Min API | 26 |

### Updating Rust

1. Update the Rust source in `vault-native/core/`.
2. Run `scripts/build_native.sh` locally to regenerate Kotlin bindings + `.so`.
3. Commit the updated Kotlin bindings in `vault-native-android/.../vault_native.kt`.
4. The F-Droid recipe only rebuilds the `.so` — bindings come from git.

## Updating MuPDF

1. Bump the version in `gradle/libs.versions.toml`.
2. Test locally with `assembleDebug` (uses Maven AAR).
3. Before tagging a release, update the srclib tag in the recipe.
4. Verify the new mupdf-android-fitz tag's `build.gradle` NDK version hasn't
   changed.
