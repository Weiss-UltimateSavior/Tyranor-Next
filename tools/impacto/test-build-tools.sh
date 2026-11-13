#!/usr/bin/env bash
# Focused hermetic tests. No CMake, Ninja, network or Android build is invoked.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
source "$ROOT/tools/impacto/build-tools.sh"
TEST_TEMP=$(mktemp -d)
trap 'rm -rf "$TEST_TEMP"' EXIT
mkdir -p "$TEST_TEMP/sdk" "$TEST_TEMP/standalone" "$TEST_TEMP/path" "$TEST_TEMP/override" "$TEST_TEMP/empty"
for program in sdk/cmake sdk/ninja standalone/cmake path/ninja override/ninja; do
    printf '#!/bin/sh\nexit 0\n' > "$TEST_TEMP/$program"
    chmod +x "$TEST_TEMP/$program"
done
expect() {
    local label=$1 cmake=$2 ninja=$3 search=$4 expected=$5
    local actual
    actual=$(export IMPACTO_CMAKE="$cmake" IMPACTO_NINJA="$ninja" PATH="$search"; impacto_resolve_build_tools; printf '%s' "$NINJA")
    [[ "$actual" == "$expected" ]] || { printf 'FAIL %s: %s\n' "$label" "$actual" >&2; exit 1; }
}
reject() {
    local label=$1 cmake=$2 ninja=$3 search=$4
    if (export IMPACTO_CMAKE="$cmake" IMPACTO_NINJA="$ninja" PATH="$search"; impacto_resolve_build_tools) > "$TEST_TEMP/error" 2>&1; then
        printf 'FAIL %s unexpectedly resolved\n' "$label" >&2; exit 1
    fi
    grep -q 'executable not found' "$TEST_TEMP/error"
}
expect 'explicit override wins' "$TEST_TEMP/sdk/cmake" "$TEST_TEMP/override/ninja" "$TEST_TEMP/empty" "$TEST_TEMP/override/ninja"
expect 'SDK adjacent Ninja' "$TEST_TEMP/sdk/cmake" '' "$TEST_TEMP/path" "$TEST_TEMP/sdk/ninja"
expect 'standalone CMake with PATH Ninja' "$TEST_TEMP/standalone/cmake" '' "$TEST_TEMP/path" "$TEST_TEMP/path/ninja"
expect 'CMake command resolved from PATH' cmake '' "$TEST_TEMP/sdk:$TEST_TEMP/path" "$TEST_TEMP/sdk/ninja"
expect 'Ninja override command resolved from PATH' "$TEST_TEMP/sdk/cmake" ninja "$TEST_TEMP/path" "$TEST_TEMP/path/ninja"
reject 'explicit missing Ninja is not silently replaced' "$TEST_TEMP/sdk/cmake" "$TEST_TEMP/missing" "$TEST_TEMP/path"
reject 'missing Ninja explains setup' "$TEST_TEMP/standalone/cmake" '' "$TEST_TEMP/empty"
reject 'missing CMake' "$TEST_TEMP/missing-cmake" '' "$TEST_TEMP/path"
printf 'PASS: 8 Impacto CMake/Ninja resolution cases\n'
