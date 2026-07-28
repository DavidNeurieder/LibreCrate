#!/usr/bin/env bash
#
# Build an AppImage for LibreCrate GUI and CLI.
#
# Usage:
#   packaging/appimage/build-appimage.sh
#
# Requirements:
#   - Rust toolchain (stable)
#   - GTK3 development headers (libgtk-3-dev / gtk3-devel)
#   - patchelf (for rewriting rpath)
#
# Output:
#   out/appimage/LibreCrate-GUI-x86_64.AppImage
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
GUI_CRATE="$REPO_ROOT/vault-native/gui"
OUT_DIR="$REPO_ROOT/out/appimage"
APPDIR="$OUT_DIR/LibreCrate.AppDir"
ARCH="${ARCH:-x86_64}"

echo "=== Building LibreCrate GUI + CLI (release) ==="
cargo build --release --manifest-path "$REPO_ROOT/vault-native/Cargo.toml" -p librecrate-gui -p librecrate

BINARY_GUI="$REPO_ROOT/vault-native/target/release/librecrate-gui"
BINARY_CLI="$REPO_ROOT/vault-native/target/release/librecrate"

if [[ ! -f "$BINARY_GUI" ]]; then
    echo "ERROR: GUI binary not found at $BINARY_GUI"
    exit 1
fi

echo "=== Creating AppDir ==="
rm -rf "$OUT_DIR"
mkdir -p "$APPDIR/usr/bin"
mkdir -p "$APPDIR/usr/share/applications"
mkdir -p "$APPDIR/usr/share/icons/hicolor/256x256/apps"
mkdir -p "$APPDIR/usr/share/metainfo"

cp "$BINARY_GUI" "$APPDIR/usr/bin/librecrate-gui"
cp "$BINARY_CLI" "$APPDIR/usr/bin/librecrate"
chmod +x "$APPDIR/usr/bin/librecrate-gui"
chmod +x "$APPDIR/usr/bin/librecrate"

cp "$SCRIPT_DIR/librecrate-gui.desktop" "$APPDIR/usr/share/applications/librecrate-gui.desktop"
cp "$REPO_ROOT/docs/icon.png" "$APPDIR/usr/share/icons/hicolor/256x256/apps/librecrate-gui.png"
cp "$REPO_ROOT/packaging/librecrate.metainfo.xml" "$APPDIR/usr/share/metainfo/librecrate-gui.metainfo.xml"

cat > "$APPDIR/AppRun" << 'APPRUN'
#!/bin/bash
HERE="$(dirname "$(readlink -f "$0")")"
export PATH="$HERE/usr/bin:$PATH"
exec "$HERE/usr/bin/librecrate-gui" "$@"
APPRUN
chmod +x "$APPDIR/AppRun"

echo "=== Downloading linuxdeploy + appimagetool ==="
TOOLS_DIR="$OUT_DIR/tools"
mkdir -p "$TOOLS_DIR"

LINUXDEPLOY_URL="https://github.com/linuxdeploy/linuxdeploy/releases/download/continuous/linuxdeploy-${ARCH}.AppImage"
APPIMAGETOOL_URL="https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-${ARCH}.AppImage"

if [[ ! -f "$TOOLS_DIR/linuxdeploy" ]]; then
    curl -L -o "$TOOLS_DIR/linuxdeploy" "$LINUXDEPLOY_URL"
    chmod +x "$TOOLS_DIR/linuxdeploy"
fi
if [[ ! -f "$TOOLS_DIR/appimagetool" ]]; then
    curl -L -o "$TOOLS_DIR/appimagetool" "$APPIMAGETOOL_URL"
    chmod +x "$TOOLS_DIR/appimagetool"
fi

echo "=== Bundling dependencies ==="
"$TOOLS_DIR/linuxdeploy" \
    --appdir "$APPDIR" \
    --desktop-file "$APPDIR/usr/share/applications/librecrate-gui.desktop" \
    --icon-file "$APPDIR/usr/share/icons/hicolor/256x256/apps/librecrate-gui.png" \
    --deploy-deps-only "$APPDIR/usr/bin/librecrate-gui" \
    --output appimage

APPIMAGE_NAME="LibreCrate-GUI-${ARCH}.AppImage"
mv "$OUT_DIR/LibreCrate.GNU.${ARCH}.AppImage" "$OUT_DIR/$APPIMAGE_NAME" 2>/dev/null || true

echo "=== Done ==="
echo "AppImage: $OUT_DIR/$APPIMAGE_NAME"
ls -lh "$OUT_DIR"/LibreCrate*.AppImage 2>/dev/null || echo "(check $OUT_DIR)"
