#!/usr/bin/env bash
# Rebuild the private Impacto runtime without changing the shared SDL runtimes.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
BUILD=${IMPACTO_BUILD_ROOT:-"$ROOT/.impacto-build"}
META="$ROOT/engine/impacto"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK 28.0.13004108}"
source "$ROOT/tools/impacto/build-tools.sh"
impacto_resolve_build_tools
export PATH="${CMAKE%/*}:$PATH"
export VCPKG_ROOT="$BUILD/vcpkg"
export VCPKG_MAX_CONCURRENCY=${VCPKG_MAX_CONCURRENCY:-4}
export VCPKG_DISABLE_METRICS=1
mkdir -p "$BUILD" "$BUILD/binary-cache"
export VCPKG_DEFAULT_BINARY_CACHE="$BUILD/binary-cache"
export VCPKG_BINARY_SOURCES="clear;files,$BUILD/binary-cache,readwrite"

checkout() {
    local name=$1 url=$2 rev=$3 dir="$BUILD/$1"
    if [[ ! -d "$dir/.git" ]]; then
        git init "$dir"
        git -C "$dir" remote add origin "$url"
    fi
    if ! git -C "$dir" cat-file -e "$rev^{commit}" 2>/dev/null; then
        git -C "$dir" fetch --depth 1 origin "$rev"
    fi
    [[ $(git -C "$dir" rev-parse HEAD 2>/dev/null || true) == "$rev" ]] || git -C "$dir" checkout --detach "$rev"
}
readarray -t LOCK < <(python3 - "$META/source-lock.json" <<'PY'
import json,sys
d=json.load(open(sys.argv[1]))
for k in ('impacto','vcpkg'):
    print(d[k]['url']); print(d[k]['revision'])
PY
)
checkout source "${LOCK[0]}" "${LOCK[1]}"
checkout vcpkg "${LOCK[2]}" "${LOCK[3]}"
# Version overrides reference historical port trees, not just the baseline tree.
if [[ $(git -C "$VCPKG_ROOT" rev-parse --is-shallow-repository) == true ]]; then
    git -C "$VCPKG_ROOT" fetch --unshallow origin "${LOCK[3]}"
fi
if [[ ! -x "$VCPKG_ROOT/vcpkg" ]]; then "$VCPKG_ROOT/bootstrap-vcpkg.sh" -disableMetrics; fi
PATCH="$META/patches/impacto-tyranor-launch.patch"
if git -C "$BUILD/source" apply --check "$PATCH" 2>/dev/null; then
    git -C "$BUILD/source" apply "$PATCH"
else
    git -C "$BUILD/source" apply --reverse --check "$PATCH"
fi
python3 "$ROOT/tools/impacto/verify-java.py" --source "$BUILD/source"
mkdir -p "$BUILD/ports"
cp -R "$VCPKG_ROOT/ports/sdl3" "$BUILD/ports/"
cp "$META/patches/sdl-private-namespace.patch" "$BUILD/ports/sdl3/"
python3 - "$BUILD/ports/sdl3/portfile.cmake" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1]); s=p.read_text()
s=s.replace('        fix-freebsd.patch', '        fix-freebsd.patch\n        sdl-private-namespace.patch')
p.write_text(s)
PY
export VULKAN_SDK="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr"
"$CMAKE" -S "$BUILD/source" -B "$BUILD/build" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DCMAKE_TOOLCHAIN_FILE="$VCPKG_ROOT/scripts/buildsystems/vcpkg.cmake" \
    -DVCPKG_CHAINLOAD_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DVCPKG_TARGET_TRIPLET=arm64-android-impacto \
    -DVCPKG_OVERLAY_TRIPLETS="$META/triplets" \
    -DVCPKG_OVERLAY_PORTS="$BUILD/ports;$BUILD/source/portfiles" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DCMAKE_CXX_FLAGS=-DIMPACTO_TYRANOR_HOST=1 \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384,-z,common-page-size=16384" \
    -DCMAKE_EXPORT_COMPILE_COMMANDS=ON -DIMPACTO_WARNINGS=ON
"$CMAKE" --build "$BUILD/build" --parallel "${VCPKG_MAX_CONCURRENCY}"
python3 "$ROOT/tools/impacto/package-runtime.py" --source "$BUILD/source" --build "$BUILD/build" --vcpkg "$VCPKG_ROOT" --ndk "$ANDROID_NDK_HOME"
