#!/bin/bash
set -euo pipefail

NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/28.2.13676358}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"

export CC_x86_64_linux_android="$TOOLCHAIN/bin/x86_64-linux-android26-clang"
export CC_aarch64_linux_android="$TOOLCHAIN/bin/aarch64-linux-android26-clang"
export AR_x86_64_linux_android="$TOOLCHAIN/bin/llvm-ar"
export AR_aarch64_linux_android="$TOOLCHAIN/bin/llvm-ar"
export RANLIB_x86_64_linux_android="$TOOLCHAIN/bin/llvm-ranlib"
export RANLIB_aarch64_linux_android="$TOOLCHAIN/bin/llvm-ranlib"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VAULT_DIR="$SCRIPT_DIR/../vault-native"
JNILIBS="$SCRIPT_DIR/../app/src/main/jniLibs"

for target in aarch64-linux-android x86_64-linux-android; do
    echo "Building for $target..."
    cargo build --manifest-path "$VAULT_DIR/Cargo.toml" --target "$target" --release
done

cp "$VAULT_DIR/target/aarch64-linux-android/release/libvault_native.so" "$JNILIBS/arm64-v8a/"
cp "$VAULT_DIR/target/x86_64-linux-android/release/libvault_native.so" "$JNILIBS/x86_64/"

echo "Done. .so files updated in jniLibs."

if [ "${1:-}" = "--apk" ]; then
    echo "Rebuilding debug APK..."
    "$SCRIPT_DIR/../gradlew" -p "$SCRIPT_DIR/.." assembleDebug
fi
