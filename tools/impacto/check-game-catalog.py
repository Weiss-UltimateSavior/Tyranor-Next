#!/usr/bin/env python3
"""Check the app's ImpactoGameCatalog against the pinned upstream checkout.

The catalog (app/.../core/game/scan/ImpactoGameCatalog.kt) lists, per upstream game profile, the root entry names
the engine opens. This reads each profile's vfs.lua and gamedefinitions.lua from a checkout and fails when a catalog
entry is not something upstream mounts, a title differs, or a catalog profile does not exist. It does not execute
Lua and does not prove any game runs.

Scope: the upstream profiles, declared as game("id", "title", "entry", ...). The Windows/Steam editions
(pcEdition("mo6tw-pc...", ...)) are defined by the host repository's patch series, not by the pinned upstream commit,
so the upstream comparison skips them and lists them as a note. With --host-profiles they are compared against the
"pc" detect blocks of the host's engine/impacto/profiles.json (yuukana1/Tyranor-Next-MAGES): same profile ids, languages,
text archives and required files. That is a comparison of two copies of the same contract, not a game run.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CATALOG = ROOT / 'app/src/main/java/com/tyranor/next/core/game/scan/ImpactoGameCatalog.kt'

GAME_CALL = re.compile(r'game\(\s*"([^"]+)"\s*,\s*"([^"]*)"\s*,\s*((?:"[^"]+"\s*,?\s*)+)\)', re.S)
# Profiles that come from the host patch series (not in upstream): pcEdition("id", TEXT_X, "mesNN.cpk").
PC_EDITION_CALL = re.compile(r'pcEdition\(\s*"([^"]+)"')
PC_EDITION_FULL = re.compile(r'pcEdition\(\s*"([^"]+)"\s*,\s*(\w+)\s*,\s*"([^"]+)"\s*\)')
CONST_STRING = re.compile(r'const val (\w+) = "([^"]*)"')
PC_COMMON = re.compile(r'PC_COMMON_DATA_ENTRIES\s*=\s*setOf\(([^)]*)\)')
PC_BODY = re.compile(r'fun pcEdition\([^)]*\)\s*=\s*Game\((.*?)\n    \)', re.S)
PC_ROOT_ENTRIES = re.compile(r'requiredEntries\s*=\s*setOf\(([^)]*)\)')
DEFINITION = re.compile(r'^\s{2}(?:\["([\w-]+)"\]|(\w+))\s*=\s*\{(.*?)^\s{2}\},?\s*$', re.S | re.M)
NAME = re.compile(r'^\s*Name\s*=\s*"([^"]*)"', re.M)
MOUNT = re.compile(r'RootGamedataDir\s*\.\.\s*"/([\w-]+)/?([^"]*)"')


def parse_catalog(text):
    games = {}
    for match in GAME_CALL.finditer(text):
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
    """profile id -> {'language': 'ja' | 'zh-Hans' | ..., 'textArchive': 'data/mes00.cpk'} for every pcEdition(...)."""
    constants = dict(CONST_STRING.findall(text))
    return {
        profile: {'language': constants.get(language, language), 'textArchive': f'data/{archive}'}
        for profile, language, archive in PC_EDITION_FULL.findall(text)
    }


def pc_requirements(text):
    """Files every pcEdition needs, as paths relative to the game folder (e.g. 'game.exe', 'data/script.cpk').

    None when the catalog's helper no longer has the shape this tool understands: report it, never guess.
    """
    constants = dict(CONST_STRING.findall(text))
    data_dir = constants.get('DATA_DIR', 'data')
    common = PC_COMMON.search(text)
    body = PC_BODY.search(text)
    root = PC_ROOT_ENTRIES.search(body.group(1)) if body else None
    if not common or not root:
        return None
    required = {f'{data_dir}/{name}' for name in re.findall(r'"([^"]+)"', common.group(1))}
    for token in (part.strip() for part in root.group(1).split(',') if part.strip()):
        if token.startswith('"'):
            required.add(token.strip('"'))
        elif constants.get(token) != data_dir:  # DATA_DIR names the folder that holds the data/ files, not a file
            return None
    return required


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
    editions = sorted(set(PC_EDITION_CALL.findall(text)))
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
    """Compare the catalog's pcEdition entries with the "pc" detect blocks of the host's profiles.json."""
    data = json.loads(Path(profiles_path).read_text(encoding='utf-8'))
    host = {p['id']: p for p in data.get('profiles', []) if (p.get('detect') or {}).get('layout') == 'pc'}
    editions = parse_pc_editions(catalog_text)
    required = pc_requirements(catalog_text)
    if required is None:
        return ['cannot read the Windows/Steam requirements (PC_COMMON_DATA_ENTRIES / the pcEdition helper) from the catalog']
    if not host:
        return ['the host profiles.json has no profile with detect.layout "pc"']
    problems = []
    for profile in sorted(set(host) - set(editions)):
        problems.append(f'{profile}: a "pc" profile of the host is missing from the catalog')
    for profile in sorted(set(editions) - set(host)):
        problems.append(f'{profile}: catalog pcEdition is not a "pc" profile in the host profiles.json')
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
