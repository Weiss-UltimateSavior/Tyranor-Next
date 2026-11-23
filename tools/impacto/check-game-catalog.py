#!/usr/bin/env python3
"""Check the app's ImpactoGameCatalog against the pinned upstream checkout.

The catalog (app/.../core/game/scan/ImpactoGameCatalog.kt) lists, per upstream game profile, the root entry names
the engine opens. This reads each profile's vfs.lua and gamedefinitions.lua from a checkout and fails when a catalog
entry is not something upstream mounts, a title differs, or a catalog profile does not exist. It does not execute
Lua and does not prove any game runs.

Scope: the upstream profiles, declared as game("id", "title", "entry", ...). The Windows/Steam editions
(pcEdition("mo6tw-pc...", ...) for language variants, pcGame("sg-pc", ...) for the others) are defined by the host
repository's patch series, not by the pinned upstream commit, so the upstream comparison skips them and lists them as a
note. With --host-profiles they are compared against the "pc" detect blocks of the host's engine/impacto/profiles.json
(yuukana1/Tyranor-Next-MAGES): same profile ids, languages, text archives and required files. That is a comparison of two
copies of the same contract, not a game run.

Kotlin comments are ignored: a commented-out or mentioned call is not a catalog entry.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CATALOG = ROOT / 'app/src/main/java/com/tyranor/next/core/game/scan/ImpactoGameCatalog.kt'

# The tokens that can hide a comment marker or be hidden by one, in the order they are tried at each position: raw
# string, string, char, line comment, start of a block comment. "//" inside a literal is text; a quote inside a comment
# opens nothing.
KOTLIN_TOKEN = re.compile(
    r'"""[\s\S]*?"""'
    r'|"(?:\\.|[^"\\\r\n])*"'
    r"|'(?:\\.|[^'\\\r\n])'"
    r'|//[^\r\n]*'
    r'|/\*'
)
# Block comments nest in Kotlin: their end is found by counting these.
BLOCK_COMMENT_EDGE = re.compile(r'/\*|\*/')
# The patterns below are applied to source with its comments removed (strip_comments).
GAME_CALL = re.compile(r'game\(\s*"([^"]+)"\s*,\s*"([^"]*)"\s*,\s*((?:"[^"]+"\s*,?\s*)+)\)', re.S)
# Profiles that come from the host patch series (not in upstream): pcEdition("id", TEXT_X, "mesNN.cpk").
PC_EDITION_CALL = re.compile(r'pcEdition\(\s*"([^"]+)"')
# Every call site of the helper (not its declaration): all of them must be read completely.
PC_EDITION_ANY = re.compile(r'(?<!fun )pcEdition\(')
PC_EDITION_FULL = re.compile(r'pcEdition\(\s*"([^"]+)"\s*,\s*("[^"]+"|\w+)\s*,\s*"([^"]+)"\s*,?\s*\)')
# Host patch series profiles without language variants: pcGame("id", "title", MIN_VERSION, "entry", ...).
PC_GAME_ANY = re.compile(r'(?<!fun )pcGame\(')
PC_GAME_CALL = re.compile(r'(?<!fun )pcGame\(\s*"([^"]+)"')
# ... optionally followed by `excludedEntries = setOf("entry", ...)` (the host's detect.noneOf).
PC_GAME_FULL = re.compile(
    r'pcGame\(\s*"([^"]+)"\s*,\s*"[^"]*"\s*,\s*\w+\s*,((?:\s*"[^"]+"\s*,?)+)'
    r'(?:\s*excludedEntries\s*=\s*setOf\(((?:\s*"[^"]+"\s*,?)*)\s*\)\s*,?)?\s*\)',
    re.S,
)
# A string constant is exactly one string literal. "a" + ".b" or any other expression is not recorded: a member that
# refers to it is then unreadable and reported, never compared with a truncated value.
CONST_STRING = re.compile(r'const val (\w+) = "([^"]*)"[ \t]*(?:\r?\n|$)', re.M)
PC_COMMON = re.compile(r'PC_COMMON_DATA_ENTRIES\s*=\s*setOf\(([^)]*)\)[ \t]*\r?\n')
PC_BODY = re.compile(r'fun pcEdition\([^)]*\)\s*=\s*Game\((.*?)\n    \)', re.S)
PC_ROOT_ENTRIES = re.compile(r'requiredEntries\s*=\s*setOf\(([^)]*)\)(?=\s*,)')
MEMBER = re.compile(r'\s*(?:"([^"]*)"|(\w+))\s*(?:,|$)')
DEFINITION = re.compile(r'^\s{2}(?:\["([\w-]+)"\]|(\w+))\s*=\s*\{(.*?)^\s{2}\},?\s*$', re.S | re.M)
NAME = re.compile(r'^\s*Name\s*=\s*"([^"]*)"', re.M)
MOUNT = re.compile(r'RootGamedataDir\s*\.\.\s*"/([\w-]+)/?([^"]*)"')


def block_comment_end(source, start):
    """Index just after the `*/` that closes the block comment whose `/*` ends at `start`; None when it never closes."""
    depth = 1
    for edge in BLOCK_COMMENT_EDGE.finditer(source, start):
        depth += 1 if edge.group(0) == '/*' else -1
        if depth == 0:
            return edge.end()
    return None


def strip_comments(source):
    """Kotlin source without its comments; string and char literals stay as written.

    Line comments and block comments are removed, and block comments nest as they do in Kotlin. A comment marker inside
    a literal is text, and a quote inside a comment opens nothing. A comment leaves its line breaks behind (a block
    comment on one line leaves a space, so the tokens on both sides stay apart), so the patterns can keep relying on
    lines. A block comment that never closes is left as it is: the compiler would refuse it, and showing its text can
    only add reports. String templates are not interpreted: a `${...}` that itself holds quotes or comments is out of
    scope (the catalog has none).
    """
    out, position = [], 0
    while True:
        match = KOTLIN_TOKEN.search(source, position)
        if match is None:
            out.append(source[position:])
            return ''.join(out)
        out.append(source[position:match.start()])
        token, end = match.group(0), match.end()
        if token == '/*':
            closed = block_comment_end(source, end)
            if closed is None:
                out.append(token)
            else:
                out.append('\n' * source.count('\n', match.start(), closed) or ' ')
                end = closed
        elif not token.startswith('//'):
            out.append(token)
        position = end


def parse_catalog(text):
    games = {}
    for match in GAME_CALL.finditer(strip_comments(text)):
        entries = re.findall(r'"([^"]+)"', match.group(3))
        games[match.group(1)] = {'title': match.group(2), 'entries': entries}
    return games


def parse_definitions(text):
    out = {}
    for match in DEFINITION.finditer(text):
        key = match.group(1) or match.group(2)
        name = NAME.search(match.group(3))
        if name:
            out[key] = name.group(1)
    return out


def parse_pc_editions(text):
    """profile id -> {'language': 'ja' | 'zh-Hans' | ..., 'textArchive': 'data/mes00.cpk'} for every pcEdition(...) call.

    None when any call cannot be read completely (an expression or an unknown name where the language is expected, a
    first argument that is not a string literal, ...): report it instead of comparing only the calls that were read.
    Calls that only appear in comments are not calls.
    """
    text = strip_comments(text)
    constants = dict(CONST_STRING.findall(text))
    parsed = PC_EDITION_FULL.findall(text)
    if len(parsed) != len(PC_EDITION_ANY.findall(text)):
        return None
    editions = {}
    for profile, language, archive in parsed:
        if language.startswith('"'):
            value = language.strip('"')
        elif language in constants:
            value = constants[language]
        else:
            return None
        editions[profile] = {'language': value, 'textArchive': f'data/{archive}'}
    return editions


def parse_pc_games(text):
    """profile id -> set of required paths for every pcGame(...) call; None when any call cannot be read completely."""
    text = strip_comments(text)
    parsed = PC_GAME_FULL.findall(text)
    if len(parsed) != len(PC_GAME_ANY.findall(text)):
        return None
    return {profile: set(re.findall(r'"([^"]+)"', entries)) for profile, entries, _ in parsed}


def parse_pc_game_exclusions(text):
    """profile id -> set of excludedEntries paths for every pcGame(...) call (empty when it has none)."""
    return {
        profile: set(re.findall(r'"([^"]+)"', excluded))
        for profile, _, excluded in PC_GAME_FULL.findall(strip_comments(text))
    }


def resolve_members(arguments, constants):
    """Members of a Kotlin setOf(...) argument list as strings: string literals and string constants.

    None when any member is something else (an expression, an unknown name): the caller reports that instead of
    comparing an incomplete set.
    """
    arguments = arguments.strip()
    members, position = [], 0
    while position < len(arguments):
        match = MEMBER.match(arguments, position)
        if not match or match.end() == position:
            return None
        literal, name = match.groups()
        if literal is not None:
            members.append(literal)
        elif name in constants:
            members.append(constants[name])
        else:
            return None
        position = match.end()
    return members


def pc_requirements(text):
    """Files every pcEdition needs, as paths relative to the game folder (e.g. 'game.exe', 'data/script.cpk').

    None when the catalog's helper no longer has the shape this tool understands: report it, never guess.
    """
    text = strip_comments(text)
    constants = dict(CONST_STRING.findall(text))
    data_dir = constants.get('DATA_DIR')
    if data_dir is None:
        return None
    common = PC_COMMON.search(text)
    body = PC_BODY.search(text)
    root = PC_ROOT_ENTRIES.search(body.group(1)) if body else None
    if not common or not root:
        return None
    common_files = resolve_members(common.group(1), constants)
    root_entries = resolve_members(root.group(1), constants)
    if common_files is None or root_entries is None:
        return None
    # DATA_DIR names the folder that holds the data/ files, not a file of its own
    return {f'{data_dir}/{name}' for name in common_files} | {name for name in root_entries if name != data_dir}


def mounted_names(source, profile):
    vfs = source / 'profiles' / profile / 'vfs.lua'
    if not vfs.is_file():
        return None
    return {name for owner, name in MOUNT.findall(vfs.read_text(encoding='utf-8')) if owner == profile and name}


def check(catalog_path, source):
    problems, notes = [], []
    text = Path(catalog_path).read_text(encoding='utf-8')
    games = parse_catalog(text)
    if not games:
        return ['no game(...) entries found in the catalog'], notes
    stripped = strip_comments(text)
    editions = sorted(set(PC_EDITION_CALL.findall(stripped)) | set(PC_GAME_CALL.findall(stripped)))
    if editions:
        notes.append(
            f'{editions}: defined by the host patch series, not by upstream; skipped here '
            '(compare them with the host profiles.json using --host-profiles)',
        )
    definitions = parse_definitions((source / 'gamedefinitions.lua').read_text(encoding='utf-8'))
    for profile, info in sorted(games.items()):
        if profile not in definitions:
            problems.append(f'{profile}: not defined in gamedefinitions.lua')
            continue
        if definitions[profile] != info['title']:
            problems.append(f'{profile}: title {info["title"]!r} differs from upstream {definitions[profile]!r}')
        mounts = mounted_names(source, profile)
        if mounts is None:
            problems.append(f'{profile}: no profiles/{profile}/vfs.lua')
            continue
        if len(set(info['entries'])) != len(info['entries']):
            problems.append(f'{profile}: duplicate catalog entries')
        for entry in info['entries']:
            if entry not in mounts:
                problems.append(f'{profile}: {entry!r} is not mounted by upstream (case-sensitive)')
        skipped = sorted(mounts - set(info['entries']))
        if skipped:
            notes.append(f'{profile}: upstream also mounts {skipped} (not required for detection)')
    # The patch series' own profiles are in a patched checkout's gamedefinitions.lua too: not "missing" from the catalog.
    unlisted = sorted(set(definitions) - set(games) - set(editions))
    if unlisted:
        notes.append(f'defined upstream but not detectable here: {unlisted}')
    return problems, notes


def check_host_profiles(catalog_text, profiles_path):
    """Compare the catalog's pcEdition / pcGame entries with the "pc" detect blocks of the host's profiles.json."""
    data = json.loads(Path(profiles_path).read_text(encoding='utf-8'))
    host = {p['id']: p for p in data.get('profiles', []) if (p.get('detect') or {}).get('layout') == 'pc'}
    editions = parse_pc_editions(catalog_text)
    games = parse_pc_games(catalog_text)
    exclusions = parse_pc_game_exclusions(catalog_text)
    required = pc_requirements(catalog_text)
    if editions is None or required is None or games is None:
        return [
            'cannot read the Windows/Steam editions or their requirements '
            '(pcEdition / pcGame calls, DATA_DIR, PC_COMMON_DATA_ENTRIES, the pcEdition helper) from the catalog',
        ]
    if not host:
        return ['the host profiles.json has no profile with detect.layout "pc"']
    problems = []
    for profile in sorted(set(host) - set(editions) - set(games)):
        problems.append(f'{profile}: a "pc" profile of the host is missing from the catalog')
    for profile in sorted((set(editions) | set(games)) - set(host)):
        problems.append(f'{profile}: catalog pcEdition / pcGame is not a "pc" profile in the host profiles.json')
    for profile in sorted(set(host) & set(games)):
        detect = host[profile]['detect']
        if host[profile].get('language') is not None or detect.get('textArchive') is not None:
            problems.append(f'{profile}: the host has a language variant, the catalog a pcGame without one')
        if set(detect.get('allOf', [])) != games[profile]:
            problems.append(
                f'{profile}: required files {sorted(games[profile])} differ from the host allOf '
                f'{sorted(detect.get("allOf", []))}',
            )
        if set(detect.get('noneOf', [])) != exclusions.get(profile, set()):
            problems.append(
                f'{profile}: excluded files {sorted(exclusions.get(profile, set()))} differ from the host noneOf '
                f'{sorted(detect.get("noneOf", []))}',
            )
    for profile in sorted(set(host) & set(editions)):
        detect, edition = host[profile]['detect'], editions[profile]
        if host[profile].get('language') != edition['language']:
            problems.append(
                f'{profile}: language {edition["language"]!r} differs from the host {host[profile].get("language")!r}',
            )
        if detect.get('textArchive') != edition['textArchive']:
            problems.append(
                f'{profile}: text archive {edition["textArchive"]!r} differs from the host {detect.get("textArchive")!r}',
            )
        if set(detect.get('allOf', [])) != required:
            problems.append(
                f'{profile}: required files {sorted(required)} differ from the host allOf {sorted(detect.get("allOf", []))}',
            )
        if detect.get('noneOf'):
            problems.append(f'{profile}: the host noneOf {detect["noneOf"]} has no counterpart in pcEdition')
    return problems


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--source', type=Path, required=True, help='verified upstream checkout')
    ap.add_argument('--catalog', type=Path, default=DEFAULT_CATALOG)
    ap.add_argument(
        '--host-profiles', type=Path,
        help='engine/impacto/profiles.json of yuukana1/Tyranor-Next-MAGES: also compare the pcEdition entries with it',
    )
    args = ap.parse_args(argv)
    try:
        problems, notes = check(args.catalog, args.source.resolve())
        if args.host_profiles:
            problems += check_host_profiles(Path(args.catalog).read_text(encoding='utf-8'), args.host_profiles)
    except (OSError, ValueError) as exc:
        print(f'error: {exc}', file=sys.stderr)
        return 2
    for note in notes:
        print(f'note: {note}')
    for problem in problems:
        print(f'problem: {problem}')
    print('catalog matches the checked profiles' if not problems else f'{len(problems)} problem(s)')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
