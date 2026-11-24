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

    def test_commented_out_entries_are_not_catalog_entries(self):
        catalog = CATALOG.replace(
            '    )\n}',
            '        // game("gamma", "Gamma", "X.CPK"),\n'
            '        /* pcEdition("alpha-pc", "ja", "mes00.cpk"), */\n'
            '    )\n}',
        )
        self.assertIn('// game("gamma"', catalog)
        patched = DEFINITIONS.replace('  viewer = {', '  ["alpha-pc"] = {\n    Name = "Alpha (PC)",\n  },\n  viewer = {')
        (self.source / 'gamedefinitions.lua').write_text(patched, encoding='utf-8')
        problems, notes = self.run_check(catalog)
        # The commented game is not looked up upstream, and the commented edition does not stand in for the upstream
        # profile "alpha-pc": it is still defined there but not detectable here.
        self.assertEqual(problems, [])
        self.assertEqual(set(tool.parse_catalog(catalog)), {'alpha'})
        self.assertFalse(any('host patch series' in n for n in notes), notes)
        listed = [n for n in notes if n.startswith('defined upstream but not detectable here')]
        self.assertEqual(len(listed), 1)
        self.assertIn('alpha-pc', listed[0])

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
# SIRENA-Core/Tyranor-Next-MAGES; set HOST_PROFILES_JSON to a checkout's file to compare against the real thing as well.
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
        {'id': 'sg-pc', 'language': None,
         'detect': {'layout': 'pc', 'allOf': ['Game.exe', 'USRDIR/script.mpk', 'USRDIR/system.mpk'],
                    'noneOf': ['USRDIR/mpk_bg.lst']}},
        {'id': 'sg0-pc', 'language': None,
         'detect': {'layout': 'pc',
                    'allOf': ['Game.exe', 'USRDIR/script.mpk', 'USRDIR/system.mpk', 'USRDIR/mpk_bg.lst']}},
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

    def test_pc_games_without_variants_are_read_and_compared(self):
        self.assertEqual(
            tool.parse_pc_games(self.catalog_text),
            {
                'sg-pc': {'Game.exe', 'USRDIR/script.mpk', 'USRDIR/system.mpk'},
                'sg0-pc': {'Game.exe', 'USRDIR/script.mpk', 'USRDIR/system.mpk', 'USRDIR/mpk_bg.lst'},
            },
        )
        fewer = self.mutated(lambda p: p['sg-pc']['detect'].update(allOf=['Game.exe', 'USRDIR/script.mpk']))
        self.assertTrue(any('sg-pc' in x and 'required files' in x for x in self.compare(fewer)))
        variant = self.mutated(lambda p: p['sg-pc'].update(language='ja'))
        self.assertTrue(any('sg-pc' in x and 'language variant' in x for x in self.compare(variant)))
        # the call site (8 spaces in builtInGames), not the declaration of the helper
        without = self.catalog_text.replace('        pcGame(\n', '        pcGameRemoved(\n', 1)
        self.assertNotEqual(without, self.catalog_text)
        self.assertTrue(any(
            'sg-pc' in x and 'missing from the catalog' in x for x in self.compare(HOST_PROFILES, without)
        ))

    def test_excluded_files_are_read_and_compared_with_the_host_none_of(self):
        self.assertEqual(
            tool.parse_pc_game_exclusions(self.catalog_text), {'sg-pc': {'USRDIR/mpk_bg.lst'}, 'sg0-pc': set()},
        )
        missing = self.mutated(lambda p: p['sg-pc']['detect'].pop('noneOf'))
        self.assertTrue(any('sg-pc' in x and 'excluded files' in x for x in self.compare(missing)))
        other = self.mutated(lambda p: p['sg-pc']['detect'].update(noneOf=['USRDIR/other.lst']))
        self.assertTrue(any('sg-pc' in x and 'excluded files' in x for x in self.compare(other)))
        edition = self.mutated(lambda p: p['mo6tw-pc']['detect'].update(noneOf=['data/x.cpk']))
        self.assertTrue(any('mo6tw-pc' in x and 'noneOf' in x for x in self.compare(edition)))

    def test_a_pc_game_call_that_cannot_be_read_is_reported(self):
        text = self.catalog_text.replace('"sg-pc", "STEINS;GATE (PC, Steam)"', 'SG_ID, "STEINS;GATE (PC, Steam)"', 1)
        self.assertNotEqual(text, self.catalog_text)
        self.assertIsNone(tool.parse_pc_games(text))
        self.assertTrue(any('cannot read' in x for x in self.compare(HOST_PROFILES, text)))

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

    def test_a_constant_member_of_the_common_files_is_resolved_and_compared(self):
        text = self.catalog_text.replace(
            'private val PC_COMMON_DATA_ENTRIES = setOf("script.cpk", "mes00.cpk")',
            'private const val EXTRA_REQUIRED = "extra.cpk"\n'
            '    private val PC_COMMON_DATA_ENTRIES = setOf("script.cpk", "mes00.cpk", EXTRA_REQUIRED)',
        )
        self.assertNotEqual(text, self.catalog_text)
        self.assertEqual(
            tool.pc_requirements(text), {'game.exe', 'data/script.cpk', 'data/mes00.cpk', 'data/extra.cpk'},
        )
        # The host allOf did not change: the extra required file must be reported, not ignored.
        problems = self.compare(HOST_PROFILES, text)
        self.assertTrue(any('required files' in x for x in problems), problems)
        # And it passes once the host lists the file too.
        both = self.mutated(lambda p: [
            p[i]['detect']['allOf'].append('data/extra.cpk') for i in ('mo6tw-pc', 'mo6tw-pc-zhs', 'mo6tw-pc-zht')
        ])
        self.assertEqual(self.compare(both, text), [])

    def test_a_member_the_tool_cannot_resolve_is_reported_instead_of_ignored(self):
        unknown = self.catalog_text.replace(
            'setOf("script.cpk", "mes00.cpk")', 'setOf("script.cpk", "mes00.cpk", NOT_DEFINED)',
        )
        self.assertNotEqual(unknown, self.catalog_text)
        self.assertIsNone(tool.pc_requirements(unknown))
        self.assertIn('cannot read', self.compare(HOST_PROFILES, unknown)[0])
        # Something added after the closing parenthesis is not part of the parsed set either.
        appended = self.catalog_text.replace(
            'setOf("script.cpk", "mes00.cpk")', 'setOf("script.cpk", "mes00.cpk") + "extra.cpk"',
        )
        self.assertNotEqual(appended, self.catalog_text)
        self.assertIsNone(tool.pc_requirements(appended))
        # A root-level member that is neither a literal nor a known constant.
        root = self.catalog_text.replace(
            'requiredEntries = setOf("game.exe", DATA_DIR),', 'requiredEntries = setOf("game.exe", DATA_DIR, MISSING),',
        )
        self.assertNotEqual(root, self.catalog_text)
        self.assertIsNone(tool.pc_requirements(root))

    def test_members_are_literals_or_string_constants(self):
        constants = {'A': 'a.cpk'}
        self.assertEqual(tool.resolve_members('"x.cpk", A', constants), ['x.cpk', 'a.cpk'])
        self.assertEqual(tool.resolve_members('"only.cpk"', constants), ['only.cpk'])
        self.assertEqual(tool.resolve_members('"a,b.cpk", "c.cpk",', constants), ['a,b.cpk', 'c.cpk'])
        self.assertEqual(tool.resolve_members('', constants), [])
        for bad in ('B', '"x.cpk", foo()', 'A + "x"', '"x.cpk" "y.cpk"'):
            self.assertIsNone(tool.resolve_members(bad, constants), bad)

    def test_a_string_constant_must_be_one_complete_literal(self):
        text = self.catalog_text.replace(
            'private val PC_COMMON_DATA_ENTRIES = setOf("script.cpk", "mes00.cpk")',
            'private const val EXTRA_REQUIRED = "script.cpk" + ".extra"\n'
            '    private val PC_COMMON_DATA_ENTRIES = setOf("script.cpk", "mes00.cpk", EXTRA_REQUIRED)',
        )
        self.assertNotEqual(text, self.catalog_text)
        # The concatenation is not recorded as its first half ("script.cpk"); the member is reported as unreadable.
        self.assertNotIn('EXTRA_REQUIRED', dict(tool.CONST_STRING.findall(text)))
        self.assertIsNone(tool.pc_requirements(text))
        self.assertIn('cannot read', self.compare(HOST_PROFILES, text)[0])
        # A trailing comment after one complete literal is fine.
        commented = text.replace('"script.cpk" + ".extra"', '"extra.cpk" // one literal')
        self.assertEqual(
            tool.pc_requirements(commented), {'game.exe', 'data/script.cpk', 'data/mes00.cpk', 'data/extra.cpk'},
        )

    def test_data_dir_must_be_a_plain_literal_too(self):
        text = self.catalog_text.replace('const val DATA_DIR = "data"', 'const val DATA_DIR = "da" + "ta"')
        self.assertNotEqual(text, self.catalog_text)
        self.assertIsNone(tool.pc_requirements(text))
        self.assertIn('cannot read', self.compare(HOST_PROFILES, text)[0])

    def test_every_pc_edition_call_is_read_completely_or_reported(self):
        zht = 'pcEdition("mo6tw-pc-zht", TEXT_ZH_HANT, "mes01.cpk"),'
        self.assertIn(zht, self.catalog_text)
        # A literal language and a trailing comma are read like the constant form.
        for variant in (
            'pcEdition("mo6tw-pc-zht", "zh-Hant", "mes01.cpk"),',
            'pcEdition("mo6tw-pc-zht", TEXT_ZH_HANT, "mes01.cpk",),',
        ):
            self.assertEqual(self.compare(HOST_PROFILES, self.catalog_text.replace(zht, variant)), [], variant)
        # A new edition the host does not list is reported, not skipped.
        extra = self.catalog_text.replace(zht, zht + '\n        pcEdition("extra-pc", "ja", "mes00.cpk"),')
        self.assertTrue(any('extra-pc' in x for x in self.compare(HOST_PROFILES, extra)))
        # Calls that cannot be read completely make the whole comparison unreadable.
        for broken in (
            'pcEdition("extra-pc", languageOf(), "mes00.cpk"),',
            'pcEdition("extra-pc", TEXT_UNKNOWN, "mes00.cpk"),',
            'pcEdition(SOME_ID, TEXT_JA, "mes00.cpk"),',
        ):
            text = self.catalog_text.replace(zht, zht + '\n        ' + broken)
            problems = self.compare(HOST_PROFILES, text)
            self.assertEqual(len(problems), 1, broken)
            self.assertIn('cannot read', problems[0], broken)

    def test_a_pc_edition_call_inside_a_comment_is_not_a_call(self):
        zht = 'pcEdition("mo6tw-pc-zht", TEXT_ZH_HANT, "mes01.cpk"),'
        note = 'pcEdition("extra-pc", "ja", "mes00.cpk")'
        self.assertIn(zht, self.catalog_text)
        # A note after a real call, a commented-out line and block comments: none of them is an edition. A prose
        # mention such as "pcEdition(...)" is no call either (it is not even a complete one).
        for commented in (
            zht + ' // ' + note,
            zht + '\n        // ' + note + ',',
            zht + ' /* ' + note + ' */',
            zht + '\n        /*\n         * ' + note + ',\n         */',
            zht + ' // keep in step with pcEdition(...) in the host profiles',
            '/** one pcEdition(...) per text archive */\n        ' + zht,
        ):
            text = self.catalog_text.replace(zht, commented)
            self.assertNotEqual(text, self.catalog_text)
            self.assertEqual(self.compare(HOST_PROFILES, text), [], commented)
        # Nor does a comment make the catalog list a profile it does not have.
        profiles = json.loads(json.dumps(HOST_PROFILES))
        profiles['profiles'].append({
            'id': 'extra-pc', 'language': 'ja',
            'detect': {
                'layout': 'pc', 'allOf': ['game.exe', 'data/script.cpk', 'data/mes00.cpk'],
                'textArchive': 'data/mes00.cpk',
            },
        })
        problems = self.compare(profiles, self.catalog_text.replace(zht, zht + ' // ' + note))
        self.assertTrue(any('extra-pc' in x and 'missing from the catalog' in x for x in problems), problems)

    def test_block_comments_nest_like_in_kotlin(self):
        zht = 'pcEdition("mo6tw-pc-zht", TEXT_ZH_HANT, "mes01.cpk"),'
        real = 'pcEdition("extra-pc", "ja", "mes00.cpk"),'
        # The call after the outer comment closes is real code, even though a "//" and a "*/" sit inside the comment:
        # the host does not list it, so it must be reported.
        text = self.catalog_text.replace(zht, zht + '\n        /* outer /* inner */ // */ ' + real)
        problems = self.compare(HOST_PROFILES, text)
        self.assertTrue(any('extra-pc' in x and 'not a "pc" profile' in x for x in problems), problems)
        self.assertFalse(any('cannot read' in x for x in problems), problems)
        # A commented-out call inside a nested comment is no call.
        text = self.catalog_text.replace(zht, zht + '\n        /* old /* x */ ' + real + ' */')
        self.assertEqual(self.compare(HOST_PROFILES, text), [])

    def test_a_call_that_cannot_be_read_is_still_reported_next_to_a_comment(self):
        zht = 'pcEdition("mo6tw-pc-zht", TEXT_ZH_HANT, "mes01.cpk"),'
        broken = 'pcEdition("extra-pc", languageOf(), "mes00.cpk"), // pcEdition("fine-pc", "ja", "mes00.cpk")'
        problems = self.compare(HOST_PROFILES, self.catalog_text.replace(zht, zht + '\n        ' + broken))
        self.assertEqual(len(problems), 1, problems)
        self.assertIn('cannot read', problems[0])

    def test_a_comment_marker_inside_a_string_literal_is_text(self):
        zht = 'pcEdition("mo6tw-pc-zht", TEXT_ZH_HANT, "mes01.cpk"),'
        slashed = self.catalog_text.replace(zht, zht + '\n        pcEdition("odd//pc", TEXT_JA, "mes00.cpk"),')
        problems = self.compare(HOST_PROFILES, slashed)
        # The call is read like any other (and compared), not cut off at the "//".
        self.assertTrue(any('odd//pc' in x and 'not a "pc" profile' in x for x in problems), problems)
        self.assertFalse(any('cannot read' in x for x in problems), problems)

    def test_comments_inside_the_requirement_sets_are_ignored(self):
        text = self.catalog_text.replace(
            'private val PC_COMMON_DATA_ENTRIES = setOf("script.cpk", "mes00.cpk")',
            'private val PC_COMMON_DATA_ENTRIES = setOf(\n'
            '        "script.cpk", // the scripts (host: data/script.cpk)\n'
            '        /* the Japanese text */ "mes00.cpk",\n'
            '    )',
        )
        self.assertNotEqual(text, self.catalog_text)
        self.assertEqual(tool.pc_requirements(text), {'game.exe', 'data/script.cpk', 'data/mes00.cpk'})
        self.assertEqual(self.compare(HOST_PROFILES, text), [])

    def test_a_helper_the_tool_cannot_read_is_reported_instead_of_passing(self):
        broken = self.catalog_text.replace('PC_COMMON_DATA_ENTRIES = setOf(', 'PC_COMMON_ENTRIES = setOf(')
        self.assertNotEqual(broken, self.catalog_text)
        problems = self.compare(HOST_PROFILES, broken)
        self.assertEqual(len(problems), 1)
        self.assertIn('cannot read', problems[0])

    @unittest.skipUnless(os.environ.get('HOST_PROFILES_JSON'), 'set HOST_PROFILES_JSON to the host repository file')
    def test_the_real_host_file_matches_the_catalog(self):
        self.assertEqual(tool.check_host_profiles(self.catalog_text, Path(os.environ['HOST_PROFILES_JSON'])), [])


class StripCommentsTests(unittest.TestCase):
    def test_line_and_block_comments_are_removed(self):
        strip = tool.strip_comments
        self.assertEqual(strip('a // x\nb'), 'a \nb')
        self.assertEqual(strip('a /* x */ b').split(), ['a', 'b'])
        # A comment still separates the tokens on both sides of it.
        self.assertEqual(strip('a/* x */b').split(), ['a', 'b'])
        # Line structure survives a block comment that spans lines.
        self.assertEqual(strip('a /* x\n y\n */ b').count('\n'), 2)
        self.assertEqual(strip('/** doc with pcEdition("x", "ja", "a.cpk") */\nval a = 1').split(), ['val', 'a', '=', '1'])

    def test_block_comments_nest(self):
        strip = tool.strip_comments
        self.assertEqual(strip('a /* x /* y */ z */ b').split(), ['a', 'b'])
        # The "*/" after a nested "//" still closes the outer comment; the code behind it stays.
        shown = strip('/* outer /* inner */ // */ pcEdition("extra-pc", "ja", "mes00.cpk"),')
        self.assertEqual(shown.split(), ['pcEdition("extra-pc",', '"ja",', '"mes00.cpk"),'])
        # Line structure survives a nested comment that spans lines.
        self.assertEqual(strip('a /* x\n/* y\n*/\n*/ b').count('\n'), 3)

    def test_a_block_comment_that_never_closes_stays_visible(self):
        strip = tool.strip_comments
        self.assertEqual(strip('a /* b'), 'a /* b')
        # Closing only the inner comment leaves the outer one open: nothing is hidden.
        self.assertIn('pcEdition(', strip('/* outer /* inner */ pcEdition("x", "ja", "m.cpk"),'))

    def test_comment_markers_inside_literals_are_text(self):
        strip = tool.strip_comments
        self.assertEqual(strip('val u = "http://x" // c'), 'val u = "http://x" ')
        self.assertEqual(strip('val u = "a/*b*/c"'), 'val u = "a/*b*/c"')
        # An escaped quote does not end the string.
        self.assertEqual(strip('"a\\"//b" // c'), '"a\\"//b" ')
        self.assertEqual(strip('val q = \'"\' // it\'s'), 'val q = \'"\' ')
        # A quote in a char literal does not open a string that would swallow the next real one.
        self.assertEqual(strip('val q = \'"\' + "//x"'), 'val q = \'"\' + "//x"')
        self.assertEqual(strip('val r = """a // b\n/* c */"""'), 'val r = """a // b\n/* c */"""')

    def test_quotes_inside_comments_open_nothing(self):
        strip = tool.strip_comments
        self.assertEqual(strip('// say "hi\nval a = "b//c"'), '\nval a = "b//c"')
        self.assertEqual(strip('/* " */ val a = "x//y" // z').split(), ['val', 'a', '=', '"x//y"'])

    def test_text_without_comments_is_unchanged(self):
        source = 'private val X = setOf("a.cpk", "b.cpk")\nfun f() = Game("it\'s", 1)\n'
        self.assertEqual(tool.strip_comments(source), source)
        real = tool.DEFAULT_CATALOG.read_text(encoding='utf-8')
        self.assertEqual(tool.strip_comments(tool.strip_comments(real)), tool.strip_comments(real))
        self.assertIn('pcEdition("mo6tw-pc", TEXT_JA, "mes00.cpk")', tool.strip_comments(real))


if __name__ == '__main__':
    unittest.main()
