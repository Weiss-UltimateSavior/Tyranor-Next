#!/usr/bin/env python3
"""Check the private SDL sources are exactly reproduced by the audited patch."""
import argparse
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
RELATIVE = Path('android/app/src/main/java/org/libsdl/app')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    args = parser.parse_args()
    source = args.source.resolve()
    names = subprocess.check_output(['git', '-C', str(source), 'ls-tree', '-r', '--name-only', 'HEAD', str(RELATIVE)], text=True).splitlines()
    with tempfile.TemporaryDirectory(prefix='impacto-java-') as temp:
        temp = Path(temp)
        for name in names:
            item = temp / name
            item.parent.mkdir(parents=True, exist_ok=True)
            item.write_bytes(subprocess.check_output(['git', '-C', str(source), 'show', 'HEAD:' + name]))
        subprocess.run(['git', 'apply', str(ROOT/'engine/impacto/patches/sdl-java-private-host.patch')], cwd=temp, check=True)
        generated = {p.name: p.read_bytes() for p in (temp/RELATIVE).glob('*.java')}
        actual = {p.name: p.read_bytes() for p in (ROOT/'engine/src/main/java/org/libsdl/impacto').glob('*.java')}
        if generated != actual:
            raise RuntimeError('Private SDL Java sources differ from the audited patch')
        print(f'PASS: {len(actual)} private SDL Java sources match pinned source + patch')


if __name__ == '__main__':
    main()
