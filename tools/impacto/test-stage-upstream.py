#!/usr/bin/env python3
"""Exercise ELF gates and snapshot publication without commercial game assets."""
import importlib.util
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('stage_upstream', Path(__file__).with_name('stage-upstream.py'))
stage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage)

ELF = '''Type: DYN (Shared object file)
Machine: AArch64
LOAD 0x0000 0x0000 0x0000 0x1234 0x1234 R E 0x4000
1: 00000001 32 FUNC GLOBAL DEFAULT 12 SDL_main
2: 00000002 32 FUNC GLOBAL DEFAULT 12 Java_org_libsdl_app_SDLActivity_nativeInit
'''


class CandidateTest(unittest.TestCase):
    def test_original_namespace_accepted(self):
        stage.inspect_elf(ELF)

    def test_wrong_architecture_rejected(self):
        with self.assertRaises(RuntimeError):
            stage.inspect_elf(ELF.replace('AArch64', 'X86-64'))

    def test_executable_rejected(self):
        with self.assertRaises(RuntimeError):
            stage.inspect_elf(ELF.replace('DYN (Shared object file)', 'EXEC (Executable file)'))

    def test_small_page_alignment_rejected(self):
        with self.assertRaises(RuntimeError):
            stage.inspect_elf(ELF.replace('0x4000', '0x1000'))

    def test_missing_load_rejected(self):
        with self.assertRaises(RuntimeError):
            stage.inspect_elf('\n'.join(line for line in ELF.splitlines() if not line.startswith('LOAD')))

    def test_old_private_namespace_rejected(self):
        with self.assertRaises(RuntimeError):
            stage.inspect_elf(ELF.replace('org_libsdl_app', 'org_libsdl_impacto'))

    def test_mixed_namespaces_rejected(self):
        with self.assertRaises(RuntimeError):
            stage.inspect_elf(ELF + '3: 0003 32 FUNC GLOBAL DEFAULT 12 Java_org_libsdl_impacto_SDLActivity_nativeInit\n')

    def test_undefined_entry_point_rejected(self):
        with self.assertRaises(RuntimeError):
            stage.inspect_elf(ELF.replace('DEFAULT 12 SDL_main', 'DEFAULT UND SDL_main'))

    def test_local_entry_point_rejected(self):
        with self.assertRaises(RuntimeError):
            stage.inspect_elf(ELF.replace('GLOBAL DEFAULT 12 SDL_main', 'LOCAL DEFAULT 12 SDL_main'))


class SnapshotPublicationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='impacto-stage-test-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.build = self.root / 'build-root'
        self.library = self.build / 'build/libimpacto.so'
        self.library.parent.mkdir(parents=True)
        self.library.write_bytes(b'original checked library')
        self.candidate = self.build / 'candidate'
        self.candidate.mkdir()
        (self.candidate / 'libimpacto.so').write_bytes(b'previous candidate')
        (self.candidate / 'candidate.json').write_text(
            json.dumps({'status': 'candidate-not-integrated', 'sha256': 'old'}), encoding='utf-8')
        for name in ['tools/impacto/build-upstream.sh',
                     'engine/impacto/host-build/pin-dependencies.cmake',
                     'engine/impacto/triplets/arm64-android-impacto.cmake']:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture recipe\n', encoding='utf-8')
        (self.root / 'engine/impacto/source-lock.json').write_text('{}', encoding='utf-8')
        self.root_patch = patch.object(stage, 'ROOT', self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)
        self.source_patch = patch.object(stage, 'source_report', return_value={'clean': True})
        self.source_patch.start()
        self.addCleanup(self.source_patch.stop)

    def assert_no_temporary_publication(self):
        self.assertFalse((self.candidate / '.publish.lock').exists())
        self.assertFalse(list(self.candidate.glob('.stage-*')))

    def test_checked_snapshot_and_hash_survive_source_replacement(self):
        checked = self.library.read_bytes()

        def readelf(command, **kwargs):
            inspected = Path(command[-1])
            self.assertNotEqual(inspected, self.library)
            self.assertTrue(inspected.is_relative_to(self.candidate))
            self.assertEqual(inspected.read_bytes(), checked)
            # Model another build replacing its output after ELF inspection.
            replacement = self.library.with_suffix('.replacement')
            replacement.write_bytes(b'new unchecked build output')
            replacement.replace(self.library)
            return ELF

        with patch.object(stage.subprocess, 'check_output', side_effect=readelf):
            stage.stage_candidate(self.build, self.root / 'ndk')
        self.assertEqual((self.candidate / 'libimpacto.so').read_bytes(), checked)
        report = json.loads((self.candidate / 'candidate.json').read_text())
        self.assertEqual(report['sha256'], hashlib.sha256(checked).hexdigest())
        self.assertEqual(report['status'], 'candidate-not-integrated')
        self.assert_no_temporary_publication()

    def test_rejected_snapshot_keeps_old_bytes_but_invalidates_old_report(self):
        with patch.object(stage.subprocess, 'check_output', return_value=ELF.replace('AArch64', 'X86-64')):
            with self.assertRaises(RuntimeError):
                stage.stage_candidate(self.build, self.root / 'ndk')
        self.assertEqual((self.candidate / 'libimpacto.so').read_bytes(), b'previous candidate')
        self.assertEqual(json.loads((self.candidate / 'candidate.json').read_text())['status'],
                         'validation-pending')
        self.assert_no_temporary_publication()

    def test_second_publisher_cannot_modify_active_publication(self):
        def readelf(command, **kwargs):
            pending = (self.candidate / 'candidate.json').read_bytes()
            with self.assertRaisesRegex(RuntimeError, 'already locked'):
                stage.stage_candidate(self.build, self.root / 'ndk')
            self.assertEqual((self.candidate / 'candidate.json').read_bytes(), pending)
            return ELF

        with patch.object(stage.subprocess, 'check_output', side_effect=readelf):
            stage.stage_candidate(self.build, self.root / 'ndk')
        self.assertEqual((self.candidate / 'libimpacto.so').read_bytes(), self.library.read_bytes())
        self.assert_no_temporary_publication()

    def test_source_verification_failure_releases_publication_lock(self):
        with patch.object(stage, 'source_report', side_effect=RuntimeError('dirty source')):
            with self.assertRaisesRegex(RuntimeError, 'dirty source'):
                stage.stage_candidate(self.build, self.root / 'ndk')
        self.assertEqual(json.loads((self.candidate / 'candidate.json').read_text())['status'],
                         'validation-pending')
        self.assert_no_temporary_publication()


if __name__ == '__main__':
    unittest.main(verbosity=2)
