#!/bin/bash
# Builds the XP3 archive Rust core for arm64-v8a and drops the .so file
# into engine/src/main/jniLibs (committed, like the other prebuilt engine libs).
#
# Prereqs: Rust + Android target + NDK + cargo-ndk:
#   rustup target add aarch64-linux-android
#   cargo install cargo-ndk
# NDK resolves via ANDROID_NDK_HOME, else the newest under $ANDROID_HOME/ndk
# (must match engine/build.gradle ndkVersion 28.0.13004108 or newer).
#
# Only arm64-v8a is built: the app ships arm64-v8a native libs exclusively.
set -euo pipefail

RUST_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="$RUST_DIR/../src/main/jniLibs/arm64-v8a"
TARGET="aarch64-linux-android"

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk" ]; then
        # Prefer the exact ndkVersion pinned in engine/build.gradle; fall back
        # to the newest only when the pinned one is not installed (a bare
        # `sort -r | head -1` would silently switch toolchains on beta/29).
        PINNED="28.0.13004108"
        if [ -d "$ANDROID_HOME/ndk/$PINNED" ]; then
            export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$PINNED"
        else
            CANDIDATE=$(ls -d "$ANDROID_HOME/ndk/"*/ 2>/dev/null | sort -r | head -1 || true)
            if [ -z "$CANDIDATE" ]; then
                echo "[!] no NDK found under \$ANDROID_HOME/ndk"
                exit 1
            fi
            export ANDROID_NDK_HOME="$CANDIDATE"
        fi
        echo "NDK: $ANDROID_NDK_HOME"
    else
        echo "[!] ANDROID_NDK_HOME not set and no NDK under \$ANDROID_HOME/ndk"
        exit 1
    fi
fi

echo "[1/2] cargo test (host) — Rust suite must stay green"
cargo test --workspace --manifest-path "$RUST_DIR/Cargo.toml"

echo "[2/2] cargo-ndk build ($TARGET)"
# NOTE: cargo-ndk resolves the manifest from the current directory —
# it must run inside RUST_DIR, --manifest-path alone is not enough.
cd "$RUST_DIR"
cargo ndk --target "$TARGET" --platform 26 build --release \
    -p archive_xp3-core
cd - > /dev/null

mkdir -p "$OUT_DIR"
cp -f "$RUST_DIR/target/$TARGET/release/libarchive_xp3_core.so" "$OUT_DIR/"
ls -la "$OUT_DIR/libarchive_xp3_core.so"
echo "done."
