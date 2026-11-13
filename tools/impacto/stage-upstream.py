#!/usr/bin/env python3
"""Stage an upstream candidate; never overwrite the application's runtime."""
import argparse
from contextlib import contextmanager
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def inspect_elf(elf):
    if 'AArch64' not in elf or 'DYN (Shared object file)' not in elf:
        raise RuntimeError('Expected an ARM64 shared library')
    loads = [line.split() for line in elf.splitlines() if line.strip().startswith('LOAD ')]
    if not loads or any(int(line[-1], 16) < 16384 for line in loads):
        raise RuntimeError('Expected 16 KiB ELF LOAD alignment')
    # Only the dynamic symbol table is supplied: these must be exported symbols.
    exported = {fields[7] for line in elf.splitlines()
                if len(fields := line.split()) >= 8 and fields[0].endswith(':')
                and fields[3] == 'FUNC' and fields[4] in {'GLOBAL', 'WEAK'}
                and fields[5] in {'DEFAULT', 'PROTECTED'} and fields[6] != 'UND'}
    if not any(name.startswith('Java_org_libsdl_app_SDLActivity_') for name in exported) or 'SDL_main' not in exported:
        raise RuntimeError('Missing upstream SDL JNI namespace or SDL_main')
    if 'Java_org_libsdl_impacto_' in elf:
        raise RuntimeError('Refusing a private patched SDL candidate')


@contextmanager
def build_lock(build, inherited_owner=None):
    lock = build / '.build.lock'
    if inherited_owner is not None:
        # Only the direct child of the owning build shell may reuse its lock.
        # The child must leave ownership and cleanup with the parent.
        if (inherited_owner != os.getppid()
                or (lock / 'owner').read_text(encoding='ascii').strip() != str(inherited_owner)):
            raise RuntimeError('Inherited build lock does not belong to the parent process')
        yield
        return
    try:
        lock.mkdir()
    except FileExistsError as error:
        raise RuntimeError(f'Build root is locked: {lock}') from error
    try:
        (lock / 'owner').write_text(str(os.getpid()) + '\n', encoding='ascii')
        yield
    finally:
        (lock / 'owner').unlink(missing_ok=True)
        lock.rmdir()


@contextmanager
def publication_lock(stage):
    # Exclusive creation is portable to the Windows regression runner as well.
    # A crash leaves a lock behind: fail closed rather than guessing its owner is
    # dead and racing another publisher. Remove it only after checking processes.
    lock = stage / '.publish.lock'
    try:
        descriptor = os.open(lock, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError as error:
        raise RuntimeError(f'Candidate publication is already locked: {lock}; '
                           'remove a stale lock only after confirming no publisher is running') from error
    try:
        os.write(descriptor, str(os.getpid()).encode('ascii'))
        yield
    finally:
        os.close(descriptor)
        lock.unlink()


def source_report(build):
    spec = importlib.util.spec_from_file_location('verify_upstream', Path(__file__).with_name('verify-upstream.py'))
    verifier = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(verifier)
    return verifier.verify(build / 'source', ROOT / 'engine/impacto/source-lock.json')


def stage_candidate(build, ndk, inherited_owner=None):
    stage = build / 'candidate'
    stage.mkdir(parents=True, exist_ok=True)
    library = build / 'build/libimpacto.so'
    tool = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf'
    with build_lock(build, inherited_owner), publication_lock(stage):
        # Lock before invalidating metadata, so a rejected concurrent invocation
        # cannot change the active publisher's report.
        (stage / 'candidate.json').write_text(
            json.dumps({'version': 1, 'status': 'validation-pending', 'releaseReady': False}) + '\n',
            encoding='utf-8')
        source = source_report(build)
        with tempfile.TemporaryDirectory(prefix='.stage-', dir=stage) as temporary:
            temporary = Path(temporary)
            snapshot = temporary / library.name
            shutil.copyfile(library, snapshot)
            # All inspection and identity fields describe this private snapshot,
            # never a build output that another process can replace afterwards.
            elf = subprocess.check_output([str(tool), '-h', '-l', '--dyn-syms', '--wide', str(snapshot)], text=True)
            inspect_elf(elf)
            report = {
                'version': 1, 'status': 'candidate-not-integrated', 'engineSource': source,
                'sourceLock': json.loads((ROOT / 'engine/impacto/source-lock.json').read_text()),
                'tyranorEnginePatches': [], 'tyranorDependencyPatches': [],
                'upstreamDependencyRecipe': 'Retained, including the upstream LibAtrac9 CMake copy',
                'library': library.name, 'sha256': digest(snapshot), 'elfLoadAlignment': 16384,
                'recipeSha256': {name: digest(ROOT / name) for name in [
                    'tools/impacto/build-upstream.sh', 'engine/impacto/host-build/pin-dependencies.cmake',
                    'engine/impacto/triplets/arm64-android-impacto.cmake']},
                'validation': {'hostIntegrated': False, 'gameplayTested': False,
                               'correspondingSourcesPackaged': False, 'releaseReady': False},
            }
            (temporary / 'elf-report.txt').write_text(elf, encoding='utf-8')
            (temporary / 'candidate.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
            os.replace(snapshot, stage / library.name)
            os.replace(temporary / 'elf-report.txt', stage / 'elf-report.txt')
            # Publish valid metadata last. Interrupted publication stays pending.
            os.replace(temporary / 'candidate.json', stage / 'candidate.json')
    print(f'Candidate staged at {stage}; not installed into TYN or approved for distribution.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-root', type=Path, required=True)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--build-lock-owner', type=int, help=argparse.SUPPRESS)
    args = parser.parse_args()
    stage_candidate(args.build_root.resolve(), args.ndk, args.build_lock_owner)


if __name__ == '__main__':
    main()
