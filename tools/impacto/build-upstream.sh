#!/usr/bin/env bash
# Build an unmodified upstream candidate. Never install it into the TYN APK.
set -euo pipefail
export GIT_NO_REPLACE_OBJECTS=1
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
META="$ROOT/engine/impacto"
if [[ $(uname -s) != Linux ]]; then
    echo 'This recipe requires Linux and a Linux Android NDK.' >&2
    exit 2
fi
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK 28.0.13004108}"
source "$ROOT/tools/impacto/build-tools.sh"
impacto_resolve_build_tools
readarray -t LOCK < <(python3 - "$META/source-lock.json" "$ANDROID_NDK_HOME/source.properties" <<'PY'
import json, re, sys
from pathlib import Path
d = json.loads(Path(sys.argv[1]).read_text())
ndk = Path(sys.argv[2]).read_text()
if not re.search(r'^Pkg\.Revision\s*=\s*' + re.escape(d['android']['ndk']) + r'\s*$', ndk, re.M):
    raise SystemExit('Android NDK revision differs from source-lock.json')
for name, url in [('impacto', 'https://github.com/CommitteeOfZero/impacto.git'),
                  ('vcpkg', 'https://github.com/microsoft/vcpkg.git')]:
    if d[name]['url'] != url or not re.fullmatch('[0-9a-f]{40}', d[name]['revision']):
        raise SystemExit('Invalid upstream source lock')
    print(url)
    print(d[name]['revision'])
PY
)
[[ ${#LOCK[@]} == 4 ]] || { echo 'Unable to read source lock/toolchain identity' >&2; exit 2; }

# Keep this route separate from the old patched engine and SDL binary caches.
BUILD=${IMPACTO_UPSTREAM_BUILD_ROOT:-"$ROOT/.impacto-upstream-build/${LOCK[1]}"}
mkdir -p "$BUILD"
BUILD=$(cd "$BUILD" && pwd)
SOURCE="$BUILD/source"
export VCPKG_ROOT="$BUILD/vcpkg"
export VCPKG_MAX_CONCURRENCY=${VCPKG_MAX_CONCURRENCY:-4}
export VCPKG_DISABLE_METRICS=1
export VCPKG_DEFAULT_BINARY_CACHE="$BUILD/binary-cache"
export VCPKG_BINARY_SOURCES="clear;files,$BUILD/binary-cache,readwrite"
export VCPKG_DOWNLOADS="$BUILD/downloads"
export PATH="${CMAKE%/*}:$PATH"
if [[ ! -f "$BUILD/.upstream-only-v1" ]] &&
   [[ -e "$BUILD/build" || -e "$BUILD/binary-cache" || -e "$BUILD/ports" ]]; then
    echo 'Refusing pre-existing build/cache inputs without an upstream-only recipe marker.' >&2
    echo 'Choose a fresh IMPACTO_UPSTREAM_BUILD_ROOT.' >&2
    exit 2
fi

mkdir -p "$BUILD/candidate"
printf '{"version":1,"status":"build-in-progress","releaseReady":false}\n' > "$BUILD/candidate/candidate.json"

checkout() {
    local dir=$1 url=$2 rev=$3
    if [[ ! -e "$dir" ]]; then
        git init "$dir"
        git -C "$dir" config core.autocrlf false
        git -C "$dir" remote add origin "$url"
        git -C "$dir" fetch --depth 1 origin "$rev"
        git -C "$dir" checkout --detach "$rev"
    fi
    [[ $(git -C "$dir" rev-parse --show-toplevel) == "$dir" ]] || {
        echo "Not an independent checkout: $dir" >&2; exit 2;
    }
    [[ $(git -C "$dir" remote get-url origin) == "$url" ]] || {
        echo "Unexpected checkout origin: $dir" >&2; exit 2;
    }
    [[ $(git -C "$dir" rev-parse HEAD) == "$rev" ]] || {
        echo "Revision mismatch: choose a fresh IMPACTO_UPSTREAM_BUILD_ROOT ($dir)" >&2; exit 2;
    }
    [[ -z $(git -C "$dir" status --porcelain --untracked-files=no) ]] || {
        echo "Modified checkout: $dir" >&2; exit 2;
    }
}
checkout "$SOURCE" "${LOCK[0]}" "${LOCK[1]}"
python3 "$ROOT/tools/impacto/verify-upstream.py" --source "$SOURCE" --report "$BUILD/source-before.json"

# Failed configure/build attempts must also leave a source-state report.
verify_on_exit() {
    local result=$?
    trap - EXIT
    if ! python3 "$ROOT/tools/impacto/verify-upstream.py" --source "$SOURCE" --report "$BUILD/source-after.json"; then
        result=1
    fi
    if [[ $result != 0 ]]; then
        printf '{"version":1,"status":"build-failed","releaseReady":false}\n' > "$BUILD/candidate/candidate.json"
    fi
    exit "$result"
}
trap verify_on_exit EXIT
checkout "$VCPKG_ROOT" "${LOCK[2]}" "${LOCK[3]}"
mkdir -p "$VCPKG_DEFAULT_BINARY_CACHE" "$VCPKG_DOWNLOADS"
printf 'upstream-only-v1\n' > "$BUILD/.upstream-only-v1"
if [[ $(git -C "$VCPKG_ROOT" rev-parse --is-shallow-repository) == true ]]; then
    git -C "$VCPKG_ROOT" fetch --unshallow origin "${LOCK[3]}"
fi
if [[ ! -x "$VCPKG_ROOT/vcpkg" ]]; then
    "$VCPKG_ROOT/bootstrap-vcpkg.sh" -disableMetrics
fi
export VULKAN_SDK="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr"
"$CMAKE" -S "$SOURCE" -B "$BUILD/build" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DCMAKE_TOOLCHAIN_FILE="$VCPKG_ROOT/scripts/buildsystems/vcpkg.cmake" \
    -DVCPKG_CHAINLOAD_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DVCPKG_TARGET_TRIPLET=arm64-android-impacto \
    -DVCPKG_OVERLAY_TRIPLETS="$META/triplets" \
    -DVCPKG_OVERLAY_PORTS="$SOURCE/portfiles" \
    -DCMAKE_PROJECT_impacto_INCLUDE="$META/host-build/pin-dependencies.cmake" \
    -DTYRANOR_IMPACTO_SOURCE_LOCK="$META/source-lock.json" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_CXX_FLAGS= -DCMAKE_C_FLAGS= \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,--build-id=sha1,-z,max-page-size=16384,-z,common-page-size=16384" \
    -DCMAKE_EXPORT_COMPILE_COMMANDS=ON -DIMPACTO_GL_DEBUG=OFF -DIMPACTO_WARNINGS=ON
"$CMAKE" --build "$BUILD/build" --parallel "$VCPKG_MAX_CONCURRENCY"
python3 "$ROOT/tools/impacto/verify-upstream.py" --source "$SOURCE" --report "$BUILD/source-after.json"
# Upstream Android install paths point inside its source checkout. Do not install.
# The old package-runtime.py requires a patched SDL namespace; do not use it here.
python3 "$ROOT/tools/impacto/stage-upstream.py" --build-root "$BUILD" --ndk "$ANDROID_NDK_HOME"
