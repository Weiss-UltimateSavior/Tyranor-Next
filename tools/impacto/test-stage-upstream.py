#!/usr/bin/env python3
"""Check candidate rejection using readelf-shaped fixtures, not real gameplay."""
import importlib.util
from pathlib import Path
import unittest

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


if __name__ == '__main__':
    unittest.main(verbosity=2)
