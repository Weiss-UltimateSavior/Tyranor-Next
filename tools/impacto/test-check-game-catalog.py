#!/usr/bin/env python3
"""Regression tests for check-game-catalog.py using a tiny synthetic upstream checkout."""
from __future__ import annotations

import importlib.util
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('check_game_catalog', HERE / 'check-game-catalog.py')
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)

DEFINITIONS = '''root.GameDefinitions = {
  alpha = {
    Name = "Alpha Game",
    GameProfile = root.BasePaths.RootProfilesDir .. "/alpha/game.lua",
  },
  ["beta-switch"] = {
    Hidden = true,
    Name = "Beta (Switch)",
  },
  viewer = {
    Name = "Viewer",
  },
};
'''
VFS = '''root.Vfs = {
    Mounts={
        ["bg"]={root.BasePaths.RootGamedataDir .. "/alpha/BG.CPK"},
        ["script"]={root.BasePaths.RootGamedataDir .. "/alpha/SCRIPT.cls"},
        ["movie"]={root.BasePaths.RootGamedataDir .. "/alpha/MOVIE.CPK"},
    }
};'''
CATALOG = '''object ImpactoGameCatalog {
    val games: List<Game> = listOf(
        game(
            "alpha", "Alpha Game",
            "BG.CPK", "SCRIPT.cls",
        ),
    )
}
'''


class CheckGameCatalogTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.source = Path(self.tmp.name) / 'source'
        (self.source / 'profiles/alpha').mkdir(parents=True)
        (self.source / 'gamedefinitions.lua').write_text(DEFINITIONS, encoding='utf-8')
        (self.source / 'profiles/alpha/vfs.lua').write_text(VFS, encoding='utf-8')
        self.catalog = Path(self.tmp.name) / 'catalog.kt'

    def run_check(self, catalog_text):
        self.catalog.write_text(catalog_text, encoding='utf-8')
        return tool.check(self.catalog, self.source)

    def test_matching_catalog_passes_and_notes_the_unrequired_movie(self):
        problems, notes = self.run_check(CATALOG)
        self.assertEqual(problems, [])
        self.assertTrue(any("MOVIE.CPK" in n for n in notes))
        self.assertTrue(any('viewer' in n for n in notes))

    def test_wrong_case_is_a_problem(self):
        problems, _ = self.run_check(CATALOG.replace('"BG.CPK"', '"bg.cpk"'))
        self.assertTrue(any("'bg.cpk' is not mounted" in p for p in problems))

    def test_unknown_profile_and_title_mismatch(self):
        problems, _ = self.run_check(CATALOG.replace('"alpha", "Alpha Game"', '"gamma", "Gamma"'))
        self.assertTrue(any('gamma: not defined' in p for p in problems))
        problems, _ = self.run_check(CATALOG.replace('"Alpha Game"', '"Alpha Gamee"'))
        self.assertTrue(any('title' in p for p in problems))

    def test_entry_upstream_does_not_mount_is_a_problem(self):
        problems, _ = self.run_check(CATALOG.replace('"SCRIPT.cls"', '"SCRIPT.cls", "EXTRA.CPK"'))
        self.assertTrue(any('EXTRA.CPK' in p for p in problems))

    def test_empty_catalog_fails_instead_of_passing_vacuously(self):
        problems, _ = self.run_check('object ImpactoGameCatalog {}')
        self.assertEqual(len(problems), 1)

    def test_windows_editions_are_skipped_upstream_and_only_noted(self):
        catalog = CATALOG.replace(
            '    )\n}',
            '        pcEdition("alpha-pc", "ja", "mes00.cpk"),\n        pcEdition("alpha-pc-zhs", "zh-Hans", "mes02.cpk"),\n    )\n}',
        )
        self.assertIn('pcEdition("alpha-pc-zhs"', catalog)
        problems, notes = self.run_check(catalog)
        # Not defined upstream, but that is expected for the host patch series: no problem, one note.
        self.assertEqual(problems, [])
        self.assertTrue(any('alpha-pc' in n and 'host patch series' in n and '--host-profiles' in n for n in notes))
        # And they are not parsed as upstream games (they would fail the upstream checks above).
        self.assertEqual(set(tool.parse_catalog(catalog)), {'alpha'})

    def test_patch_series_profiles_in_a_patched_checkout_are_not_reported_as_missing(self):
        catalog = CATALOG.replace('    )\n}', '        pcEdition("alpha-pc", "ja", "mes00.cpk"),\n    )\n}')
        patched = DEFINITIONS.replace('  viewer = {', '  ["alpha-pc"] = {\n    Name = "Alpha (PC)",\n  },\n  viewer = {')
        (self.source / 'gamedefinitions.lua').write_text(patched, encoding='utf-8')
        problems, notes = self.run_check(catalog)
        self.assertEqual(problems, [])
        listed = [n for n in notes if n.startswith('defined upstream but not detectable here')]
        self.assertEqual(len(listed), 1)
        self.assertIn('viewer', listed[0])
        self.assertNotIn('alpha-pc', listed[0])

    def test_the_real_catalog_file_is_parseable(self):
        games = tool.parse_catalog(tool.DEFAULT_CATALOG.read_text(encoding='utf-8'))
        self.assertIn('mo6tw', games)
        self.assertGreaterEqual(len(games), 7)
        # The Windows/Steam editions are declared with pcEdition(...) and must stay out of the upstream comparison.
        self.assertEqual([p for p in games if p.startswith('mo6tw-pc')], [])
        text = tool.DEFAULT_CATALOG.read_text(encoding='utf-8')
        self.assertEqual(
            sorted(set(tool.PC_EDITION_CALL.findall(text))), ['mo6tw-pc', 'mo6tw-pc-zhs', 'mo6tw-pc-zht'],
        )


# The two copies of the Windows/Steam contract. HOST_PROFILES mirrors the "pc" blocks of engine/impacto/profiles.json in
# yuukana1/Tyranor-Next-MAGES; set HOST_PROFILES_JSON to a checkout's file to compare against the real thing as well.
HOST_PROFILES = {
    'version': 1,
    'profiles': [
        {'id': 'mo6tw', 'language': None, 'detect': {'layout': 'ps3', 'allOf': ['SCRIPT.cls']}},
        {'id': 'mo6tw-pc', 'language': 'ja',
         'detect': {'layout': 'pc', 'allOf': ['game.exe', 'data/script.cpk', 'data/mes00.cpk'],
                    'textArchive': 'data/mes00.cpk'}},
        {'id': 'mo6tw-pc-zhs', 'language': 'zh-Hans',
         'detect': {'layout': 'pc', 'allOf': ['game.exe', 'data/script.cpk', 'data/mes00.cpk'],
                    'textArchive': 'data/mes02.cpk'}},
        {'id': 'mo6tw-pc-zht', 'language': 'zh-Hant',
         'detect': {'layout': 'pc', 'allOf': ['game.exe', 'data/script.cpk', 'data/mes00.cpk'],
                    'textArchive': 'data/mes01.cpk'}},
        {'id': 'darling', 'language': None, 'detect': None},
    ],
}


class HostProfilesTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.catalog_text = tool.DEFAULT_CATALOG.read_text(encoding='utf-8')

    def compare(self, profiles, catalog_text=None):
        path = Path(self.tmp.name) / 'profiles.json'
        path.write_text(json.dumps(profiles), encoding='utf-8')
        return tool.check_host_profiles(catalog_text if catalog_text is not None else self.catalog_text, path)

    def mutated(self, edit):
        profiles = json.loads(json.dumps(HOST_PROFILES))
        edit({p['id']: p for p in profiles['profiles']})
        return profiles

    def test_the_real_catalog_matches_the_host_contract(self):
        self.assertEqual(self.compare(HOST_PROFILES), [])

    def test_the_catalog_requirements_are_read_from_the_helper(self):
        self.assertEqual(
            tool.pc_requirements(self.catalog_text), {'game.exe', 'data/script.cpk', 'data/mes00.cpk'},
        )
        self.assertEqual(
            tool.parse_pc_editions(self.catalog_text),
            {
                'mo6tw-pc': {'language': 'ja', 'textArchive': 'data/mes00.cpk'},
                'mo6tw-pc-zhs': {'language': 'zh-Hans', 'textArchive': 'data/mes02.cpk'},
                'mo6tw-pc-zht': {'language': 'zh-Hant', 'textArchive': 'data/mes01.cpk'},
            },
        )

    def test_a_different_text_archive_is_a_problem(self):
        problems = self.compare(self.mutated(lambda p: p['mo6tw-pc-zhs']['detect'].update(textArchive='data/mes01.cpk')))
        self.assertTrue(any('mo6tw-pc-zhs' in x and 'text archive' in x for x in problems), problems)

    def test_a_different_language_is_a_problem(self):
        problems = self.compare(self.mutated(lambda p: p['mo6tw-pc-zht'].update(language='zh-Hans')))
        self.assertTrue(any('mo6tw-pc-zht' in x and 'language' in x for x in problems), problems)

    def test_different_required_files_are_a_problem(self):
        problems = self.compare(
            self.mutated(lambda p: p['mo6tw-pc']['detect'].update(allOf=['game.exe', 'data/script.cpk'])),
        )
        self.assertTrue(any('mo6tw-pc' in x and 'required files' in x for x in problems), problems)

    def test_a_profile_on_one_side_only_is_a_problem(self):
        added = self.mutated(lambda p: p['darling'].update(
            detect={'layout': 'pc', 'allOf': ['game.exe'], 'textArchive': 'data/mes00.cpk'}, language='ja',
        ))
        self.assertTrue(any('darling' in x and 'missing from the catalog' in x for x in self.compare(added)))
        only_two = self.catalog_text.replace('pcEdition("mo6tw-pc-zht", TEXT_ZH_HANT, "mes01.cpk"),', '')
        self.assertNotEqual(only_two, self.catalog_text)
        self.assertTrue(any(
            'mo6tw-pc-zht' in x and 'missing from the catalog' in x for x in self.compare(HOST_PROFILES, only_two)
        ))
        dropped = self.mutated(lambda p: p['mo6tw-pc-zht'].update(detect=None))
        self.assertTrue(any('mo6tw-pc-zht' in x and 'not a "pc" profile' in x for x in self.compare(dropped)))

    def test_a_helper_the_tool_cannot_read_is_reported_instead_of_passing(self):
        broken = self.catalog_text.replace('PC_COMMON_DATA_ENTRIES = setOf(', 'PC_COMMON_ENTRIES = setOf(')
        self.assertNotEqual(broken, self.catalog_text)
        problems = self.compare(HOST_PROFILES, broken)
        self.assertEqual(len(problems), 1)
        self.assertIn('cannot read', problems[0])

    @unittest.skipUnless(os.environ.get('HOST_PROFILES_JSON'), 'set HOST_PROFILES_JSON to the host repository file')
    def test_the_real_host_file_matches_the_catalog(self):
        self.assertEqual(tool.check_host_profiles(self.catalog_text, Path(os.environ['HOST_PROFILES_JSON'])), [])


if __name__ == '__main__':
    unittest.main()
