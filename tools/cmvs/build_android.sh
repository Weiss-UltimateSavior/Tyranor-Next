#!/usr/bin/env bash
# Reproducible CMVS Android runtime build.  The fork checkout is only a sync
# source: this script copies it to a temporary directory, applies the local
# build-time patch, and never writes into the fork.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="${CMVS_SOURCE:-/Volumes/HUAWEI-M2LITE/app/cmvs-rs}"
NDK="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/28.0.13004108}"
OUT="${CMVS_OUT:-$ROOT/engine/src/main/jniLibs/arm64-v8a}"
WORK="${CMVS_WORK:-$(mktemp -d "${TMPDIR:-/tmp}/cmvs-build.XXXXXX")}"
PATCH="$ROOT/tools/cmvs/patches/0001-absolute-launch-paths.patch"

if [[ ! -f "$PATCH" ]]; then
  echo "missing patch: $PATCH" >&2
  exit 1
fi
if [[ ! -d "$NDK" ]]; then
  echo "missing NDK: $NDK" >&2
  exit 1
fi

rsync -a --exclude target --exclude .git "$SRC/" "$WORK/src/"
(cd "$WORK/src" && patch -p1 < "$PATCH")
export ANDROID_NDK_HOME="$NDK"
export CARGO_TARGET_DIR="$WORK/target"
(cd "$WORK/src" && cargo ndk -t arm64-v8a --platform 26 -o "$WORK/jni" build --locked --release -p cmvs-android)
STRIP="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip"
if [[ -x "$STRIP" ]]; then
  "$STRIP" --strip-unneeded "$WORK/jni/arm64-v8a/libcmvs_android.so"
fi
mkdir -p "$OUT"
cp "$WORK/jni/arm64-v8a/libcmvs_android.so" "$OUT/libcmvs_android.so"
echo "built $OUT/libcmvs_android.so"
echo "work dir: $WORK"
