#!/usr/bin/env bash
# Installs the toolchain pins from engine/impacto/source-lock.json that the build recipe needs and that distributions
# do not ship: the exact Android NDK revision and CMake. Used by CI and by developers on Linux/WSL.
#
#   IMPACTO_TOOLS_DIR   install root (default: $HOME/impacto-tools)
#
# Prints `export` lines for ANDROID_NDK_HOME and IMPACTO_CMAKE on success. Downloads are verified against the
# publisher's own checksums (Google's repository manifest for the NDK, Kitware's SHA-256 list for CMake).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TOOLS=${IMPACTO_TOOLS_DIR:-"$HOME/impacto-tools"}

read -r NDK_REV CMAKE_VER < <(python3 - "$ROOT/engine/impacto/source-lock.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding='utf-8'))['android']
print(d['ndk'], d['cmake'])
PY
)

mkdir -p "$TOOLS/dl"
cd "$TOOLS/dl"

# --- Android NDK: the revision-to-archive mapping and its SHA-1 come from Google's manifest, not from this script.
NDK_DIR="$TOOLS/ndk/$NDK_REV"
if ! grep -qE "^Pkg.Revision *= *$NDK_REV\$" "$NDK_DIR/source.properties" 2>/dev/null; then
    curl -fsSL --retry 3 -o repository2-3.xml https://dl.google.com/android/repository/repository2-3.xml
    read -r NDK_ZIP NDK_SHA1 < <(python3 - "$NDK_REV" <<'PY'
import re, sys
xml = open('repository2-3.xml', encoding='utf-8').read()
block = re.search(r'<remotePackage path="ndk;%s">(.*?)</remotePackage>' % re.escape(sys.argv[1]), xml, re.S)
if not block:
    raise SystemExit('NDK revision not in the repository manifest: ' + sys.argv[1])
for archive in re.findall(r'<archive>(.*?)</archive>', block.group(1), re.S):
    if '<host-os>linux</host-os>' in archive:
        print(re.search(r'<url>(.*?)</url>', archive).group(1), re.search(r'<checksum[^>]*>(\w+)</checksum>', archive).group(1))
        break
else:
    raise SystemExit('no Linux archive for the NDK')
PY
)
    # An archive placed in $TOOLS/dl beforehand (for example fetched over a faster route) is used if it matches.
    if ! { [[ -f "$NDK_ZIP" ]] && echo "$NDK_SHA1  $NDK_ZIP" | sha1sum -c - >/dev/null 2>&1; }; then
        curl -fL --retry 3 -o "$NDK_ZIP" "https://dl.google.com/android/repository/$NDK_ZIP"
    fi
    echo "$NDK_SHA1  $NDK_ZIP" | sha1sum -c -
    rm -rf "$TOOLS/ndk.tmp" "$NDK_DIR"
    mkdir -p "$TOOLS/ndk.tmp"
    unzip -q "$NDK_ZIP" -d "$TOOLS/ndk.tmp"
    mkdir -p "$TOOLS/ndk"
    mv "$TOOLS/ndk.tmp"/* "$NDK_DIR"
    rmdir "$TOOLS/ndk.tmp"
    rm -f "$NDK_ZIP"
fi
grep -qE "^Pkg.Revision *= *$NDK_REV\$" "$NDK_DIR/source.properties"

# --- CMake
CMAKE_NAME="cmake-$CMAKE_VER-linux-x86_64"
if [[ ! -x "$TOOLS/$CMAKE_NAME/bin/cmake" ]]; then
    BASE="https://github.com/Kitware/CMake/releases/download/v$CMAKE_VER"
    curl -fL --retry 3 -o "$CMAKE_NAME.tar.gz" "$BASE/$CMAKE_NAME.tar.gz"
    curl -fsSL --retry 3 -o "cmake-$CMAKE_VER-SHA-256.txt" "$BASE/cmake-$CMAKE_VER-SHA-256.txt"
    grep " $CMAKE_NAME.tar.gz\$" "cmake-$CMAKE_VER-SHA-256.txt" | sha256sum -c -
    tar -xzf "$CMAKE_NAME.tar.gz" -C "$TOOLS"
    rm -f "$CMAKE_NAME.tar.gz"
fi
"$TOOLS/$CMAKE_NAME/bin/cmake" --version | head -1 >&2

printf 'export ANDROID_NDK_HOME=%q\n' "$NDK_DIR"
printf 'export IMPACTO_CMAKE=%q\n' "$TOOLS/$CMAKE_NAME/bin/cmake"
