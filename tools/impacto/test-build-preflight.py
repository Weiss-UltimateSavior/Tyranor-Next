#!/usr/bin/env python3
"""Exercise failed build attempts without fetching or compiling dependencies."""
import importlib.util
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('verify_upstream', TOOLS / 'verify-upstream.py')
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class BuildPreflightTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='impacto-preflight-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.build = self.root / 'build root'
        candidate = self.build / 'candidate'
        candidate.mkdir(parents=True)
        self.report = candidate / 'candidate.json'
        self.previous = {'version': 1, 'status': 'candidate-not-integrated', 'sha256': 'old'}
        self.report.write_text(json.dumps(self.previous), encoding='utf-8')
        self.ndk = self.root / 'ndk'
        self.ndk.mkdir()
        (self.ndk / 'source.properties').write_text('Pkg.Revision = 28.0.13004108\n', encoding='utf-8')
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.program('uname', 'echo Linux')
        self.program('python3', f'exec {shlex.quote(Path(sys.executable).as_posix())} "$@"')
        self.program('cmake', 'echo "CMake must not run during these rejected attempts" >&2; exit 90')
        self.program('ninja', 'exit 91')
        self.bash = shutil.which('bash')
        if os.name == 'nt':
            git_bash = Path(shutil.which('git')).parents[1] / 'bin/bash.exe'
            if git_bash.is_file():
                self.bash = str(git_bash)
        if not self.bash:
            self.skipTest('Bash is required')
        # Git Bash prepends its own tools when it starts. Prepend the fixture
        # shims inside the shell, using its native spelling of the temp path.
        self.command = [self.bash, '-c',
                        'PATH="$(cd "$1" && pwd):$PATH"; export PATH; exec bash "$2"',
                        'preflight-test', self.bin.as_posix(), (TOOLS / 'build-upstream.sh').as_posix()]
        self.environment = {
            **verifier.git_environment(), 'PATH': str(self.bin) + os.pathsep + os.environ['PATH'],
            'IMPACTO_UPSTREAM_BUILD_ROOT': self.build.as_posix(),
            'ANDROID_NDK_HOME': self.ndk.as_posix(),
            'IMPACTO_CMAKE': (self.bin / 'cmake').as_posix(),
            'IMPACTO_NINJA': (self.bin / 'ninja').as_posix(),
        }

    def program(self, name, body):
        script = self.bin / name
        script.write_text('#!/usr/bin/env bash\n' + body + '\n', encoding='utf-8', newline='\n')
        script.chmod(0o755)

    def rejected(self, message):
        result = subprocess.run(self.command,
                                env=self.environment, capture_output=True, text=True, timeout=20)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(message, result.stderr)
        self.assertEqual(json.loads(self.report.read_text())['status'], 'build-failed')
        self.assertFalse((self.build / '.build.lock').exists())
        self.assertFalse((self.build / 'source').exists(), 'Rejected preflight must not fetch source')

    def test_missing_cmake_invalidates_previous_candidate(self):
        self.environment['IMPACTO_CMAKE'] = (self.root / 'missing-cmake').as_posix()
        self.rejected('CMake executable not found')

    def test_missing_ninja_invalidates_previous_candidate(self):
        self.environment['IMPACTO_NINJA'] = (self.root / 'missing-ninja').as_posix()
        self.rejected('Ninja executable not found')

    def test_wrong_ndk_invalidates_previous_candidate(self):
        (self.ndk / 'source.properties').write_text('Pkg.Revision = 27.0.0\n', encoding='utf-8')
        self.rejected('Android NDK revision differs')

    def test_unset_ndk_invalidates_previous_candidate(self):
        del self.environment['ANDROID_NDK_HOME']
        self.rejected('Set ANDROID_NDK_HOME')

    def test_cached_dependency_tree_is_preserved_and_rejected(self):
        source = self.build / 'build/_deps/imgui-src/imgui.cpp'
        source.parent.mkdir(parents=True)
        source.write_text('modified dependency', encoding='utf-8')
        self.rejected('Refusing reused FetchContent/CMake inputs')
        self.assertEqual(source.read_text(), 'modified dependency')

    def test_cmake_cache_cannot_redirect_dependency_sources(self):
        cache = self.build / 'build/CMakeCache.txt'
        cache.parent.mkdir()
        cache.write_text('FETCHCONTENT_SOURCE_DIR_IMGUI:PATH=/unverified/source\n', encoding='utf-8')
        self.rejected('Refusing reused FetchContent/CMake inputs')

    def test_overlapping_attempt_does_not_modify_active_candidate(self):
        lock = self.build / '.build.lock'
        lock.mkdir()
        result = subprocess.run(self.command,
                                env=self.environment, capture_output=True, text=True, timeout=20)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Build root is locked', result.stderr)
        self.assertEqual(json.loads(self.report.read_text()), self.previous)
        self.assertTrue(lock.is_dir())

    @unittest.skipIf(os.name == 'nt', 'Build-shell PID inheritance is a Linux runtime contract')
    def test_real_build_child_accepts_parent_lock_and_leaves_cleanup_to_parent(self):
        result = subprocess.run([self.bash, '-c',
                                 'set -eu; mkdir "$1/.build.lock"; '
                                 'printf "%s\\n" "$$" > "$1/.build.lock/owner"; '
                                 'if python3 "$2" --build-root "$1" --ndk "$3" --build-lock-owner "$$"; '
                                 'then exit 92; fi; '
                                 'test -f "$1/.build.lock/owner"; '
                                 'rm "$1/.build.lock/owner"; rmdir "$1/.build.lock"',
                                 'parent-lock-test', str(self.build), str(TOOLS / 'stage-upstream.py'),
                                 str(self.ndk)], env=self.environment,
                                capture_output=True, text=True, timeout=20)
        # No source checkout exists: reaching validation-pending proves the real
        # CLI acquired the inherited lock, then failed source validation safely.
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(json.loads(self.report.read_text())['status'], 'validation-pending')
        self.assertFalse((self.build / '.build.lock').exists())


if __name__ == '__main__':
    unittest.main(verbosity=2)
