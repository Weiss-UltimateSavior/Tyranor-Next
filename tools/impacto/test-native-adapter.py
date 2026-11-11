#!/usr/bin/env python3
"""Host-side negative tests for the patched native launch boundary (not playback)."""
import argparse
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True, help='Pinned and patched Impacto source')
    args = parser.parse_args()
    source = args.source.resolve()
    with tempfile.TemporaryDirectory(prefix='impacto-native-test-') as tmp:
        temp = Path(tmp).resolve()
        cpp = temp / 'test.cpp'
        cpp.write_text('#include "tyranor_paths.h"\nint main(int c,char**v){try{Impacto::Tyranor::ValidateLaunch(c,v);return 0;}catch(...){return 2;}}\n')
        exe = temp / 'validate'
        subprocess.run(['c++', '-std=c++20', '-Wall', '-Wextra', '-Werror', '-I', str(source/'src'), str(cpp), '-o', str(exe)], check=True)
        resources = temp / 'runtime'
        for name in ('profiles', 'resources', 'shaders'):
            (resources/name).mkdir(parents=True)
        bp, gc, uc = temp/'basepaths.lua', temp/'game.lua', temp/'user.toml'
        bp.write_text('-- trusted generated basepaths\n')
        gc.write_text('-- trusted generated game definitions\n')
        good = ['--tyranor-resource-root', str(resources), '-bp', str(bp), '-gc', str(gc), '-uc', str(uc), '-g', 'chlcc']
        cases = [('valid with new user config', good, 0)]
        for extra in (['-p', 'custom'], ['--patch', 'custom'], ['--patch=custom'], ['--game', 'chlcc'], ['unexpected']):
            cases.append(('unsupported arguments ' + repr(extra), good + extra, 2))
        for flag in ('--tyranor-resource-root', '-bp', '-gc', '-uc', '-g'):
            i = good.index(flag)
            cases.append(('missing ' + flag, good[:i] + good[i+2:], 2))
            cases.append(('duplicate ' + flag, good + good[i:i+2], 2))
        for flag, value in (('-bp', str(temp/'missing.lua')), ('-gc', str(temp)), ('-bp', 'relative.lua'), ('-uc', str(temp/'missing'/'user.toml')), ('--tyranor-resource-root', 'relative')):
            bad = good.copy(); bad[bad.index(flag)+1] = value
            cases.append(('invalid ' + flag + '=' + value, bad, 2))
        link = temp/'runtime-link'; link.symlink_to(resources, target_is_directory=True)
        bad = good.copy(); bad[1] = str(link)
        cases.append(('symlink runtime root', bad, 2))
        incomplete = temp/'incomplete'; incomplete.mkdir()
        bad = good.copy(); bad[1] = str(incomplete)
        cases.append(('incomplete runtime', bad, 2))
        for name, values, code in cases:
            result = subprocess.run([str(exe), *values], capture_output=True)
            if result.returncode != code:
                raise AssertionError(f'{name}: expected {code}, got {result.returncode}')
        # Re-check existing user configuration and deletion before second phase.
        uc.write_text('# persistent user settings\n')
        subprocess.run([str(exe), *good], check=True)
        bp.unlink()
        if subprocess.run([str(exe), *good]).returncode != 2:
            raise AssertionError('Deleted explicit basepaths was accepted')
        profile = (source/'src/profile/profile.cpp').read_text()
        strict = profile.index('if (RequireExplicitPaths)')
        fallback = profile.index('// Override', strict)
        if 'Tyranor::RequireReadableFile(path);\n      return;' not in profile[strict:fallback]:
            raise AssertionError('Missing fail-closed profile path branch')
        private = ROOT/'engine/src/main/java/org/libsdl/impacto'
        for java in private.glob('*.java'):
            if 'org.libsdl.app' in java.read_text() or 'org.libsdl3.app' in java.read_text():
                raise AssertionError(f'Shared Java namespace in {java}')
        print(f'PASS: {len(cases)+2} native launch cases, strict profile branch and private Java namespaces')


if __name__ == '__main__':
    main()
