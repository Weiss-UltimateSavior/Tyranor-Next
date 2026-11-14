#!/usr/bin/env python3
"""Keep a separate caller repository intact under inherited Git overrides."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('verify_upstream', TOOLS / 'verify-upstream.py')
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class GitEnvironmentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='impacto-git-environment-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.environment = verifier.git_environment()
        self.caller = self.root / 'caller'
        self.source = self.root / 'source'
        self.create_repo(self.caller, 'caller.txt')
        self.create_repo(self.source, 'engine.cpp')
        self.lock = self.root / 'lock.json'
        self.lock.write_text(json.dumps({'impacto': {
            'url': verifier.UPSTREAM_URL,
            'revision': self.git(self.source, 'rev-parse', 'HEAD').strip(),
        }}), encoding='utf-8')
        metadata = self.caller / '.git'
        self.overrides = {
            'GIT_DIR': str(metadata), 'GIT_WORK_TREE': str(self.caller),
            'GIT_COMMON_DIR': str(metadata), 'GIT_INDEX_FILE': str(metadata / 'index'),
            'GIT_OBJECT_DIRECTORY': str(metadata / 'objects'),
            'GIT_ALTERNATE_OBJECT_DIRECTORIES': str(metadata / 'objects'),
            'GIT_CONFIG': str(metadata / 'config'),
            'GIT_CONFIG_COUNT': '1', 'GIT_CONFIG_KEY_0': 'core.bare', 'GIT_CONFIG_VALUE_0': 'true',
            'GIT_CONFIG_PARAMETERS': "'core.bare=true'",
        }
        self.before = self.snapshot()

    def git(self, directory, *args):
        return subprocess.check_output(['git', '-C', str(directory), *args],
                                       text=True, encoding='utf-8', stderr=subprocess.STDOUT,
                                       env=self.environment)

    def create_repo(self, directory, filename):
        directory.mkdir()
        self.git(directory, 'init', '--quiet')
        self.git(directory, 'config', 'user.name', 'Environment fixture')
        self.git(directory, 'config', 'user.email', 'fixture@example.invalid')
        self.git(directory, 'config', 'core.autocrlf', 'false')
        (directory / filename).write_text('original\n', encoding='utf-8')
        self.git(directory, 'add', filename)
        self.git(directory, '-c', 'commit.gpgsign=false', 'commit', '--quiet', '-m', 'fixture')

    def snapshot(self):
        return {p.relative_to(self.caller).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                for p in self.caller.rglob('*') if p.is_file()}

    def poisoned(self):
        return {**self.environment, **self.overrides}

    def assert_caller_unchanged(self):
        # Include config, index, refs, reflogs and objects, not just working files.
        self.assertEqual(self.snapshot(), self.before)

    def test_verifier_uses_requested_checkout_and_rejects_its_changes(self):
        command = [sys.executable, str(TOOLS / 'verify-upstream.py'),
                   '--source', str(self.source), '--lock', str(self.lock)]
        result = subprocess.run(command, env=self.poisoned(), capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)['revision'],
                         json.loads(self.lock.read_text())['impacto']['revision'])
        (self.source / 'engine.cpp').write_text('modified\n', encoding='utf-8')
        result = subprocess.run(command, env=self.poisoned(), capture_output=True, text=True)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('not clean', result.stderr)
        self.assert_caller_unchanged()

    def test_fixture_runner_cannot_configure_stage_or_commit_in_caller(self):
        result = subprocess.run([
            sys.executable, str(TOOLS / 'test-upstream-integrity.py'),
            'SourceIntegrityTest.test_clean_checkout_records_revision_and_tree',
            'SourceIntegrityTest.test_git_line_ending_conversion_is_not_a_patch',
            'SourceIntegrityTest.test_clean_submodule_records_locked_tree',
            'SourceIntegrityTest.test_report_cannot_modify_source',
        ], env=self.poisoned(), capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assert_caller_unchanged()

    def test_build_shell_clears_inherited_locations_before_git_writes(self):
        bash = shutil.which('bash')
        if os.name == 'nt':
            git_bash = Path(shutil.which('git')).parents[1] / 'bin/bash.exe'
            if git_bash.is_file():
                bash = str(git_bash)
        if not bash:
            self.skipTest('Bash is needed to test the Linux build helper')
        result = subprocess.run([bash, '-c',
                                 'set -euo pipefail; source "$1"; impacto_clear_git_environment; '
                                 'git -C "$2" config test.isolated true',
                                 'git-environment-test', str(TOOLS / 'build-tools.sh'), str(self.source)],
                                env=self.poisoned(), capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.git(self.source, 'config', '--get', 'test.isolated').strip(), 'true')
        self.assert_caller_unchanged()


if __name__ == '__main__':
    unittest.main(verbosity=2)
