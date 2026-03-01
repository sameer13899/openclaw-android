#!/usr/bin/env bash
# install-glibc-env.sh - Install glibc environment (glibc-runner + Node.js) on Termux
# This is the core new script for the glibc architecture.
#
# What it does:
#   1. Install pacman + proot packages
#   2. Initialize pacman and install glibc-runner
#   3. Download Node.js linux-arm64 LTS
#   4. Create grun-style wrapper scripts (ld.so direct execution)
#   5. Verify everything works
#
# patchelf is NOT used — Android seccomp causes SIGSEGV on patchelf'd binaries.
# All glibc binaries are executed via: exec ld.so binary "$@"
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

OPENCLAW_DIR="$HOME/.openclaw-android"
NODE_DIR="$OPENCLAW_DIR/node"
GLIBC_LDSO="$PREFIX/glibc/lib/ld-linux-aarch64.so.1"
PACMAN_CONF="$PREFIX/etc/pacman.conf"

# Node.js LTS version to install
NODE_VERSION="22.14.0"
NODE_TARBALL="node-v${NODE_VERSION}-linux-arm64.tar.xz"
NODE_URL="https://nodejs.org/dist/v${NODE_VERSION}/${NODE_TARBALL}"

echo "=== Installing glibc Environment ==="
echo ""

# ── Pre-checks ───────────────────────────────

if [ -z "${PREFIX:-}" ]; then
    echo -e "${RED}[FAIL]${NC} Not running in Termux (\$PREFIX not set)"
    exit 1
fi

ARCH=$(uname -m)
if [ "$ARCH" != "aarch64" ]; then
    echo -e "${RED}[FAIL]${NC} glibc environment requires aarch64 (got: $ARCH)"
    exit 1
fi

# Check if already installed
if [ -f "$OPENCLAW_DIR/.glibc-arch" ] && [ -x "$NODE_DIR/bin/node" ]; then
    # Verify it actually works
    if "$NODE_DIR/bin/node" --version &>/dev/null; then
        NODE_VER=$("$NODE_DIR/bin/node" --version 2>/dev/null)
        echo -e "${GREEN}[SKIP]${NC} glibc environment already installed (Node.js $NODE_VER)"
        exit 0
    else
        echo -e "${YELLOW}[INFO]${NC} glibc environment exists but Node.js broken — reinstalling"
    fi
fi

# ── Step 1: Install pacman and proot ─────────

echo "Installing pacman and proot..."
if ! pkg install -y pacman proot; then
    echo -e "${RED}[FAIL]${NC} Failed to install pacman and proot"
    exit 1
fi
echo -e "${GREEN}[OK]${NC}   pacman and proot installed"

# ── Step 2: Initialize pacman ────────────────

echo ""
echo "Initializing pacman..."
echo "  (This may take a few minutes for GPG key generation)"

# SigLevel workaround: Some devices have a GPGME crypto engine bug
# that prevents signature verification. Temporarily set SigLevel = Never.
SIGLEVEL_PATCHED=false
if [ -f "$PACMAN_CONF" ]; then
    # Check if SigLevel is already Never
    if ! grep -q "^SigLevel = Never" "$PACMAN_CONF"; then
        # Backup and patch
        cp "$PACMAN_CONF" "${PACMAN_CONF}.bak"
        sed -i 's/^SigLevel\s*=.*/SigLevel = Never/' "$PACMAN_CONF"
        SIGLEVEL_PATCHED=true
        echo -e "${YELLOW}[INFO]${NC} Applied SigLevel = Never workaround (GPGME bug)"
    fi
fi

# Initialize pacman keyring (may hang on low-entropy devices)
pacman-key --init 2>/dev/null || true
pacman-key --populate 2>/dev/null || true

# ── Step 3: Install glibc-runner ─────────────

echo ""
echo "Installing glibc-runner..."

# --assume-installed: these packages are provided by Termux's apt but pacman
# doesn't know about them, causing dependency resolution failures
if pacman -Sy glibc-runner --noconfirm --assume-installed bash,patchelf,resolv-conf 2>&1; then
    echo -e "${GREEN}[OK]${NC}   glibc-runner installed"
else
    echo -e "${RED}[FAIL]${NC} Failed to install glibc-runner"
    # Restore SigLevel
    if [ "$SIGLEVEL_PATCHED" = true ] && [ -f "${PACMAN_CONF}.bak" ]; then
        mv "${PACMAN_CONF}.bak" "$PACMAN_CONF"
    fi
    exit 1
fi

# Restore SigLevel after successful install
if [ "$SIGLEVEL_PATCHED" = true ] && [ -f "${PACMAN_CONF}.bak" ]; then
    mv "${PACMAN_CONF}.bak" "$PACMAN_CONF"
    echo -e "${GREEN}[OK]${NC}   Restored pacman SigLevel"
fi

# Verify glibc dynamic linker exists
if [ ! -x "$GLIBC_LDSO" ]; then
    echo -e "${RED}[FAIL]${NC} glibc dynamic linker not found at $GLIBC_LDSO"
    exit 1
fi
echo -e "${GREEN}[OK]${NC}   glibc dynamic linker available"

# Verify grun works
if command -v grun &>/dev/null; then
    echo -e "${GREEN}[OK]${NC}   grun command available"
else
    echo -e "${YELLOW}[WARN]${NC} grun command not found (will use ld.so directly)"
fi

# ── Step 4: Download Node.js linux-arm64 ─────

echo ""
echo "Downloading Node.js v${NODE_VERSION} (linux-arm64)..."

mkdir -p "$NODE_DIR"

TMP_DIR=$(mktemp -d "$PREFIX/tmp/node-install.XXXXXX") || {
    echo -e "${RED}[FAIL]${NC} Failed to create temp directory"
    exit 1
}
trap 'rm -rf "$TMP_DIR"' EXIT

if ! curl -fL --max-time 300 "$NODE_URL" -o "$TMP_DIR/$NODE_TARBALL"; then
    echo -e "${RED}[FAIL]${NC} Failed to download Node.js v${NODE_VERSION}"
    exit 1
fi
echo -e "${GREEN}[OK]${NC}   Downloaded $NODE_TARBALL"

# Extract
echo "Extracting..."
if ! tar -xJf "$TMP_DIR/$NODE_TARBALL" -C "$NODE_DIR" --strip-components=1; then
    echo -e "${RED}[FAIL]${NC} Failed to extract Node.js"
    exit 1
fi
echo -e "${GREEN}[OK]${NC}   Extracted to $NODE_DIR"

# ── Step 5: Create wrapper scripts ───────────

echo ""
echo "Creating wrapper scripts (grun-style, no patchelf)..."

# Move original node binary to node.real
if [ -f "$NODE_DIR/bin/node" ] && [ ! -L "$NODE_DIR/bin/node" ]; then
    mv "$NODE_DIR/bin/node" "$NODE_DIR/bin/node.real"
fi

# Create node wrapper script
# This uses grun-style execution: ld.so directly loads the binary
# LD_PRELOAD must be unset to prevent Bionic libtermux-exec.so from
# being loaded into the glibc process (causes version mismatch crash)
# glibc-compat.js is auto-loaded to fix Android kernel quirks (os.cpus() returns 0,
# os.networkInterfaces() throws EACCES) that affect native module builds and runtime.
cat > "$NODE_DIR/bin/node" << 'WRAPPER'
#!/data/data/com.termux/files/usr/bin/bash
unset LD_PRELOAD
_OA_COMPAT="$HOME/.openclaw-android/patches/glibc-compat.js"
if [ -f "$_OA_COMPAT" ]; then
    case "${NODE_OPTIONS:-}" in
        *"$_OA_COMPAT"*) ;;
        *) export NODE_OPTIONS="${NODE_OPTIONS:+$NODE_OPTIONS }-r $_OA_COMPAT" ;;
    esac
fi
exec "$PREFIX/glibc/lib/ld-linux-aarch64.so.1" "$(dirname "$0")/node.real" "$@"
WRAPPER
chmod +x "$NODE_DIR/bin/node"
echo -e "${GREEN}[OK]${NC}   node wrapper created"

# npm is a JS script that uses the node from its own directory,
# so it automatically inherits the wrapper. No additional wrapping needed.
# Same for npx.

# ── Step 6: Configure npm ────────────────────

echo ""
echo "Configuring npm..."

# Set script-shell to ensure npm lifecycle scripts use the correct shell
# On Android 9+, /bin/sh exists. On 7-8 it doesn't.
# Using $PREFIX/bin/sh is always safe.
export PATH="$NODE_DIR/bin:$PATH"
"$NODE_DIR/bin/npm" config set script-shell "$PREFIX/bin/sh" 2>/dev/null || true
echo -e "${GREEN}[OK]${NC}   npm script-shell set to $PREFIX/bin/sh"

# ── Step 7: Verify ───────────────────────────

echo ""
echo "Verifying glibc Node.js..."

NODE_VER=$("$NODE_DIR/bin/node" --version 2>/dev/null) || {
    echo -e "${RED}[FAIL]${NC} Node.js verification failed — wrapper script may be broken"
    exit 1
}
echo -e "${GREEN}[OK]${NC}   Node.js $NODE_VER (glibc, grun wrapper)"

NPM_VER=$("$NODE_DIR/bin/npm" --version 2>/dev/null) || {
    echo -e "${YELLOW}[WARN]${NC} npm verification failed"
}
if [ -n "${NPM_VER:-}" ]; then
    echo -e "${GREEN}[OK]${NC}   npm $NPM_VER"
fi

# Quick platform check
PLATFORM=$("$NODE_DIR/bin/node" -e "console.log(process.platform)" 2>/dev/null) || true
if [ "$PLATFORM" = "linux" ]; then
    echo -e "${GREEN}[OK]${NC}   platform: linux (correct)"
else
    echo -e "${YELLOW}[WARN]${NC} platform: ${PLATFORM:-unknown} (expected: linux)"
fi

# ── Step 8: Create marker file ───────────────

touch "$OPENCLAW_DIR/.glibc-arch"
echo -e "${GREEN}[OK]${NC}   glibc architecture marker created"

echo ""
echo -e "${GREEN}glibc environment installed successfully.${NC}"
echo "  Node.js: $NODE_VER ($NODE_DIR/bin/node)"
echo "  ld.so:   $GLIBC_LDSO"
