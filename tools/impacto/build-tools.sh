#!/usr/bin/env bash
# Source this file; tool resolution has no filesystem or network side effects.
impacto_clear_git_environment() {
    local variables name
    variables=$(git rev-parse --local-env-vars) || return 1
    while IFS= read -r name; do
        [[ -z "$name" ]] || unset "$name"
    done <<< "$variables"
    unset GIT_INTERNAL_SUPER_PREFIX GIT_QUARANTINE_PATH
    # Count/parameters were removed above; remove their now-inactive payload too.
    for name in "${!GIT_CONFIG_KEY_@}" "${!GIT_CONFIG_VALUE_@}"; do
        [[ -z "$name" ]] || unset "$name"
    done
}

impacto_resolve_program() {
    local requested=$1 label=$2 resolved directory name
    if ! resolved=$(command -v -- "$requested") || [[ ! -f "$resolved" || ! -x "$resolved" ]]; then
        printf 'Impacto build: %s executable not found: %s\n' "$label" "$requested" >&2
        return 1
    fi
    directory=${resolved%/*}
    name=${resolved##*/}
    [[ "$directory" != "$resolved" ]] || directory=.
    directory=$(cd -- "$directory" && pwd) || return 1
    printf '%s/%s\n' "$directory" "$name"
}

impacto_resolve_build_tools() {
    CMAKE=$(impacto_resolve_program "${IMPACTO_CMAKE:-cmake}" CMake) || return 1
    if [[ -n "${IMPACTO_NINJA:-}" ]]; then
        NINJA=$(impacto_resolve_program "$IMPACTO_NINJA" Ninja) || return 1
    elif [[ -f "${CMAKE%/*}/ninja" && -x "${CMAKE%/*}/ninja" ]]; then
        NINJA="${CMAKE%/*}/ninja"
    else
        NINJA=$(impacto_resolve_program ninja 'Ninja (set IMPACTO_NINJA or add ninja to PATH)') || return 1
    fi
}
