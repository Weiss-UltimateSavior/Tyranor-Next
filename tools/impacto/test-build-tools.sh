#!/usr/bin/env bash
# Focused hermetic tests. No CMake, Ninja, network or Android build is invoked.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
source "$ROOT/tools/impacto/build-tools.sh"
TEMP=$(mktemp -d)
trap 'rm -rf "$TEMP"' EXIT
mkdir -p "$TEMP/sdk" "$TEMP/standalone" "$TEMP/path" "$TEMP/override" "$TEMP/empty"
for program in sdk/cmake sdk/ninja standalone/cmake path/ninja override/ninja; do
    printf '#!/bin/sh\nexit 0\n' > "$TEMP/$program"
    chmod +x "$TEMP/$program"
done
expect() {
    local label=$1 cmake=$2 ninja=$3 search=$4 expected=$5
    local actual
    actual=$(export IMPACTO_CMAKE="$cmake" IMPACTO_NINJA="$ninja" PATH="$search"; impacto_resolve_build_tools; printf '%s' "$NINJA")
    [[ "$actual" == "$expected" ]] || { printf 'FAIL %s: %s\n' "$label" "$actual" >&2; exit 1; }
}
reject() {
    local label=$1 cmake=$2 ninja=$3 search=$4
    if (export IMPACTO_CMAKE="$cmake" IMPACTO_NINJA="$ninja" PATH="$search"; impacto_resolve_build_tools) > "$TEMP/error" 2>&1; then
        printf 'FAIL %s unexpectedly resolved\n' "$label" >&2; exit 1
    fi
    grep -q 'executable not found' "$TEMP/error"
}
expect 'explicit override wins' "$TEMP/sdk/cmake" "$TEMP/override/ninja" "$TEMP/empty" "$TEMP/override/ninja"
expect 'SDK adjacent Ninja' "$TEMP/sdk/cmake" '' "$TEMP/path" "$TEMP/sdk/ninja"
expect 'standalone CMake with PATH Ninja' "$TEMP/standalone/cmake" '' "$TEMP/path" "$TEMP/path/ninja"
expect 'CMake command resolved from PATH' cmake '' "$TEMP/sdk:$TEMP/path" "$TEMP/sdk/ninja"
expect 'Ninja override command resolved from PATH' "$TEMP/sdk/cmake" ninja "$TEMP/path" "$TEMP/path/ninja"
reject 'explicit missing Ninja is not silently replaced' "$TEMP/sdk/cmake" "$TEMP/missing" "$TEMP/path"
reject 'missing Ninja explains setup' "$TEMP/standalone/cmake" '' "$TEMP/empty"
reject 'missing CMake' "$TEMP/missing-cmake" '' "$TEMP/path"
printf 'PASS: 8 Impacto CMake/Ninja resolution cases\n'
