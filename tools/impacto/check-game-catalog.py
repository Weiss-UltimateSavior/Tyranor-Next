#!/usr/bin/env python3
"""Check the app's ImpactoGameCatalog against the pinned upstream checkout.

The catalog (app/.../core/game/scan/ImpactoGameCatalog.kt) lists, per upstream game profile, the root entry names
the engine opens. This reads each profile's vfs.lua and gamedefinitions.lua from a checkout and fails when a catalog
entry is not something upstream mounts, a title differs, or a catalog profile does not exist. It does not execute
Lua and does not prove any game runs.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CATALOG = ROOT / 'app/src/main/java/com/tyranor/next/core/game/scan/ImpactoGameCatalog.kt'

GAME_CALL = re.compile(r'game\(\s*"([^"]+)"\s*,\s*"([^"]*)"\s*,\s*((?:"[^"]+"\s*,?\s*)+)\)', re.S)
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


def mounted_names(source, profile):
    vfs = source / 'profiles' / profile / 'vfs.lua'
    if not vfs.is_file():
        return None
    return {name for owner, name in MOUNT.findall(vfs.read_text(encoding='utf-8')) if owner == profile and name}


def check(catalog_path, source):
    problems, notes = [], []
    games = parse_catalog(Path(catalog_path).read_text(encoding='utf-8'))
    if not games:
        return ['no game(...) entries found in the catalog'], notes
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
    unlisted = sorted(set(definitions) - set(games))
    if unlisted:
        notes.append(f'defined upstream but not detectable here: {unlisted}')
    return problems, notes


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--source', type=Path, required=True, help='verified upstream checkout')
    ap.add_argument('--catalog', type=Path, default=DEFAULT_CATALOG)
    args = ap.parse_args(argv)
    try:
        problems, notes = check(args.catalog, args.source.resolve())
    except OSError as exc:
        print(f'error: {exc}', file=sys.stderr)
        return 2
    for note in notes:
        print(f'note: {note}')
    for problem in problems:
        print(f'problem: {problem}')
    print('catalog matches upstream profiles' if not problems else f'{len(problems)} problem(s)')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
