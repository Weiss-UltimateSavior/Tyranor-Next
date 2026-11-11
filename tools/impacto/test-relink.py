#!/usr/bin/env python3
"""Relink a throwaway runtime with one source-modified LGPL mspack archive.

Uses the completed build's compiler commands and application objects; this is
not a fresh offline rebuild. The shipped library and original archives remain
unchanged. The modification is an exported test function in system.c.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
MARKER = 'impacto_lgpl_relink_probe'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--build', type=Path, required=True)
    parser.add_argument('--ndk', type=Path, required=True)
    args = parser.parse_args()
    source, build, ndk = args.source.resolve(), args.build.resolve(), args.ndk.resolve()
    binary = build / 'libimpacto.so'
    original = build / 'libmspack.a'
    before = {str(p): sha(p) for p in (binary, original)}
    tools = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    commands = json.loads((build / 'compile_commands.json').read_text())
    entry = next(c for c in commands if c['file'].endswith('/vendor/mspack/system.c'))
    cache = (build / 'CMakeCache.txt').read_text()
    ninja = next(line.split('=', 1)[1] for line in cache.splitlines() if line.startswith('CMAKE_MAKE_PROGRAM:FILEPATH=') or line.startswith('CMAKE_MAKE_PROGRAM:UNINITIALIZED='))
    link = subprocess.check_output([ninja, '-C', str(build), '-t', 'commands', 'libimpacto.so'], text=True).splitlines()[-1]
    with tempfile.TemporaryDirectory(prefix='relink-test-', dir=build.parent) as temporary:
        temporary = Path(temporary)
        modified_source = temporary / 'system.c'
        modified_source.write_text((source / 'vendor/mspack/system.c').read_text() + '\nint ' + MARKER + '(void) { return 1; }\n')
        modified_object = temporary / 'system.c.o'
        compile_args = shlex.split(entry['command'])
        compile_args[compile_args.index('-o') + 1] = str(modified_object)
        compile_args[compile_args.index('-c') + 1] = str(modified_source)
        subprocess.run(compile_args, cwd=build, check=True)
        modified_archive = temporary / 'libmspack-modified.a'
        shutil.copyfile(original, modified_archive)
        subprocess.run([str(tools / 'llvm-ar'), 'r', str(modified_archive), str(modified_object)], check=True)
        subprocess.run([str(tools / 'llvm-ranlib'), str(modified_archive)], check=True)
        tokens = shlex.split(link)
        if tokens[:2] != [':', '&&'] or tokens[-2:] != ['&&', ':']:
            raise RuntimeError('Unexpected Ninja link command form')
        tokens = tokens[2:-2]
        result = temporary / 'libimpacto-relink-test.so'
        tokens[tokens.index('-o') + 1] = str(result)
        replacement_count = 0
        for i, token in enumerate(tokens):
            if Path(token).name == 'libmspack.a':
                tokens[i] = str(modified_archive); replacement_count += 1
            if token.endswith('CMakeFiles/impacto.dir/link.d'):
                tokens[i] = str(temporary / 'link.d')
        if replacement_count != 1:
            raise RuntimeError('Expected exactly one mspack archive in link command')
        tokens.append('-Wl,-u,' + MARKER)
        subprocess.run(tokens, cwd=build, check=True)
        symbols = subprocess.check_output([str(tools / 'llvm-nm'), '--dynamic', '--defined-only', str(result)], text=True)
        if MARKER not in symbols:
            raise RuntimeError('Modified LGPL library marker missing after relink')
        if before != {str(p): sha(p) for p in (binary, original)}:
            raise RuntimeError('Relink test changed an original build artifact')
        report = {'version': 1, 'result': 'passed', 'library': 'vendored mspack (LGPL-2.1)',
                  'method': 'Compiled source-modified system.c, replaced that member in a copied static archive, and relinked a separate runtime using existing application objects.',
                  'probeSymbol': MARKER, 'originalUnstrippedRuntimeSha256': sha(binary),
                  'modifiedRuntimeSha256': sha(result), 'originalArtifactsUnchanged': True,
                  'freshOfflineRebuild': False, 'androidExecutionTested': False}
        (ROOT / 'engine/impacto/relink-report.json').write_text(json.dumps(report, indent=2) + '\n')
        print('PASS: source-modified mspack archive relinked; probe exported; original artifacts unchanged')


if __name__ == '__main__':
    main()
