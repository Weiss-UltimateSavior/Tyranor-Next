#!/usr/bin/env python3
"""Regression tests for check-game-catalog.py using a tiny synthetic upstream checkout."""
from __future__ import annotations

import importlib.util
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

    def test_the_real_catalog_file_is_parseable(self):
        games = tool.parse_catalog(tool.DEFAULT_CATALOG.read_text(encoding='utf-8'))
        self.assertIn('mo6tw', games)
        self.assertGreaterEqual(len(games), 7)


if __name__ == '__main__':
    unittest.main()
