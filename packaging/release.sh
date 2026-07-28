#!/usr/bin/env bash
#
# Build release binaries and package them into a tarball.
#
# Usage:
#   packaging/release.sh [--arch <triple>]
#
# Defaults to the host architecture. Supports:
#   --arch x86_64-unknown-linux-gnu
#   --arch aarch64-unknown-linux-gnu  (requires cross toolchain)
#
# Output:
#   out/releases/librecrate-linux-<arch>.tar.gz
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
VERSION="$(grep '^version' "$REPO_ROOT/vault-native/gui/Cargo.toml" | head -1 | sed 's/.*"\(.*\)"/\1/')"
ARCH="x86_64-unknown-linux-gnu"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --arch) ARCH="$2"; shift 2 ;;
        *) echo "Unknown arg: $1"; exit 1 ;;
    esac
done

echo "=== Building release binaries for $ARCH (v$VERSION) ==="
cargo build --release --target "$ARCH" --manifest-path "$REPO_ROOT/vault-native/Cargo.toml" \
    -p librecrate-gui -p librecrate

BIN_DIR="$REPO_ROOT/vault-native/target/$ARCH/release"
STAGE_DIR="$REPO_ROOT/out/releases/librecrate-linux-$(echo "$ARCH" | sed 's/-unknown-linux-gnu//')"
TARBALL="$REPO_ROOT/out/releases/librecrate-linux-$(echo "$ARCH" | sed 's/-unknown-linux-gnu//').tar.gz"

rm -rf "$STAGE_DIR" "$TARBALL"
mkdir -p "$STAGE_DIR" "$REPO_ROOT/out/releases"

cp "$BIN_DIR/librecrate-gui" "$STAGE_DIR/"
cp "$BIN_DIR/librecrate" "$STAGE_DIR/"
cp "$REPO_ROOT/LICENSE" "$STAGE_DIR/"
cp "$REPO_ROOT/README.md" "$STAGE_DIR/"
cp "$SCRIPT_DIR/appimage/librecrate-gui.desktop" "$STAGE_DIR/"

cat > "$STAGE_DIR/INSTALL" << EOF
LibreCrate v$VERSION — $ARCH

Binaries:
  librecrate-gui  Desktop GUI (Iced)
  librecrate      CLI with interactive REPL

Install:
  cp librecrate-gui librecrate ~/.local/bin/
  cp librecrate-gui.desktop ~/.local/share/applications/

Dependencies:
  - GTK3 (libgtk-3-0)
  - OpenSSL/libssl (for SQLCipher)
EOF

echo "=== Creating tarball ==="
tar -czf "$TARBALL" -C "$REPO_ROOT/out/releases" "$(basename "$STAGE_DIR")"
echo "=== Done ==="
echo "Tarball: $TARBALL"
ls -lh "$TARBALL"
