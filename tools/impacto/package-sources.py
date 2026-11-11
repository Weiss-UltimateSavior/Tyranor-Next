#!/usr/bin/env python3
"""Package the exact source inputs of a completed Impacto native build.

No network access, guessed dependency versions, or links in place of source.
Archives have stable ordering, modes and timestamps. Run again after the final
integration edits, and distribute every archive alongside the native runtime.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[2]
MAX_ARCHIVE_BYTES = 100_000_000
# Build outputs, never source inputs. Fonts/images/data are not executable code.
BINARY_SUFFIXES = {'.so', '.a', '.o', '.obj', '.dll', '.exe', '.dylib', '.class', '.jar', '.apk', '.aab', '.pyc'}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def digest(path: Path, algorithm: str = 'sha256') -> str:
    h = hashlib.new(algorithm)
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + '\n').encode()


def git(path: Path, *args: str) -> bytes:
    return subprocess.check_output(['git', '-C', str(path), *args])


def git_files(path: Path, untracked: bool = False) -> list[Path]:
    args = ['ls-files', '-z', '--cached']
    if untracked:
        args.extend(['--others', '--exclude-standard'])
    names = git(path, *args).decode().split('\0')
    return [path / name for name in sorted(set(names)) if name and (path / name).is_file()]


def tree_files(path: Path) -> list[Path]:
    return sorted(p for p in path.rglob('*') if p.is_file() and '.git' not in p.relative_to(path).parts)


def safe_source(path: Path) -> bool:
    return path.suffix.lower() not in BINARY_SUFFIXES and not re.search(r'\.so\.\d', path.name)


class Archive:
    def __init__(self, directory: Path, name: str):
        self.path = directory / f'{name}.tar.xz'
        self.root = name
        self.files: dict[str, tuple[Path | bytes, int]] = {}

    def add(self, name: str, value: Path | bytes, mode: int | None = None) -> None:
        require(not name.startswith('/') and '..' not in Path(name).parts, f'Unsafe archive path: {name}')
        require(name not in self.files, f'Duplicate archive path: {name}')
        if isinstance(value, Path):
            require(not value.is_symlink(), f'Symlink needs explicit review: {value}')
            mode = mode or (0o755 if value.stat().st_mode & 0o111 else 0o644)
        self.files[name] = (value, mode or 0o644)

    def tree(self, base: Path, prefix: str, files: list[Path] | None = None) -> None:
        for path in tree_files(base) if files is None else files:
            self.add(f'{prefix}/{path.relative_to(base).as_posix()}', path)

    def write(self) -> dict:
        with tarfile.open(self.path, 'w:xz', format=tarfile.PAX_FORMAT, preset=6) as archive:
            for name, (value, mode) in sorted(self.files.items()):
                header = tarfile.TarInfo(f'{self.root}/{name}')
                header.mode, header.mtime = mode, 0
                header.uid = header.gid = 0
                header.uname = header.gname = ''
                if isinstance(value, Path):
                    header.size = value.stat().st_size
                    with value.open('rb') as stream:
                        archive.addfile(header, stream)
                else:
                    header.size = len(value)
                    archive.addfile(header, io.BytesIO(value))
        size = self.path.stat().st_size
        require(size < MAX_ARCHIVE_BYTES, f'{self.path.name} exceeds the 100 MB archive limit; split this component before release')
        return {'file': self.path.name, 'bytes': size, 'sha256': digest(self.path), 'entries': len(self.files)}


def port_candidates(name: str, source: Path, build: Path, vcpkg: Path) -> list[Path]:
    return [build.parent / 'ports' / name, source / 'portfiles' / name,
            *sorted((vcpkg / 'buildtrees/versioning_/versions' / name).glob('*')),
            vcpkg / 'ports' / name]


def find_recipe(spdx: dict, candidates: list[Path]) -> Path:
    expected = {}
    for entry in spdx.get('files', []):
        if entry.get('SPDXID', '').startswith('SPDXRef-port-file-'):
            for checksum in entry.get('checksums', []):
                if checksum['algorithm'] == 'SHA256':
                    expected[entry['fileName'].removeprefix('./')] = checksum['checksumValue']
    require(expected and 'portfile.cmake' in expected, 'Installed SPDX has no verifiable port recipe')
    for directory in candidates:
        if directory.is_dir() and all((directory / name).is_file() and digest(directory / name) == value
                                     for name, value in expected.items()):
            return directory
    raise RuntimeError(f'No recipe matches installed SPDX hashes; candidates: {candidates}')


def normalized_spdx(spdx: dict) -> dict:
    # Remove only creation-time randomness, retaining every source/binary hash.
    value = json.loads(json.dumps(spdx))
    value.get('creationInfo', {}).pop('created', None)
    value['documentNamespace'] = 'https://spdx.org/spdxdocs/impacto-' + hashlib.sha256(value['name'].encode()).hexdigest()
    return value


def installed_packages(build: Path, triplet: str, source: Path) -> list[tuple[str, str, Path]]:
    root = build / 'vcpkg_installed'
    packages = []
    for spdx in sorted(root.glob('*/share/*/vcpkg.spdx.json')):
        architecture = spdx.parents[2].name
        packages.append((architecture, spdx.parent.name, spdx))
    targets = {name for architecture, name, _ in packages if architecture == triplet}
    manifest = json.loads((source / 'vcpkg.json').read_text())
    # This packager targets Android. Inactive Linux/Windows-only entries do not apply.
    direct = {item if isinstance(item, str) else item['name'] for item in manifest['dependencies']
              if isinstance(item, str) or item.get('platform', 'android') in ('android', '!windows') or 'platform' not in item}
    require(direct <= targets, f'Native dependencies are not fully installed: {sorted(direct - targets)}')
    require(packages, 'No installed vcpkg SPDX manifests found')
    return packages


def download_index(vcpkg: Path) -> dict[str, Path]:
    # Match bytes, not URL spelling: vcpkg can rename cached downloads.
    return {digest(path, 'sha512'): path for path in sorted((vcpkg / 'downloads').iterdir())
            if path.is_file() and not path.name.endswith(('.part', '.lock'))}


def source_resources(spdx: dict, downloads: dict[str, Path], recipe: Path, abi: Path) -> list[tuple[dict, Path]]:
    result = []
    for package in spdx.get('packages', []):
        if not package.get('SPDXID', '').startswith('SPDXRef-resource-'):
            continue
        sha512 = next((item['checksumValue'].lower() for item in package.get('checksums', [])
                       if item['algorithm'] == 'SHA512'), None)
        # vcpkg records downloads found by static port scanning, including
        # disabled branches and unresolved variables. Resolve only audited cases.
        if recipe.name == 'libpng' or 'libpng:' in spdx.get('name', ''):
            features = re.search(r'^features (.*)$', abi.read_text(), re.M) if abi.is_file() else None
            if package.get('name') == '${LIBPNG_APNG_PATCH_NAME}.gz':
                require(features is not None, 'Cannot establish libpng feature selection')
                if 'apng' not in features.group(1).split():
                    continue
        if 'vcpkg-tool-meson:' in spdx.get('name', '') and sha512 == '${download_sha512}':
            match = re.search(r'set\(download_sha512 ([0-9a-f]{128})\)',
                              (recipe / 'vcpkg-port-config.cmake').read_text())
            require(match is not None, 'Cannot resolve Meson source checksum from verified recipe')
            sha512 = match.group(1)
        require(sha512 is not None, f'Source resource has no SHA512: {package}')
        require(sha512 in downloads, f'Missing exact source download: {package.get("name")} ({sha512})')
        result.append((package, downloads[sha512]))
    return result


def verify_ffmpeg(vcpkg: Path, triplet: str) -> list[Path]:
    configs = sorted((vcpkg / 'buildtrees/ffmpeg').glob(f'{triplet}*/config.h'))
    require(configs, 'FFmpeg config.h is missing; cannot verify LGPL-only configuration')
    for path in configs:
        text = path.read_text()
        for flag in ('GPL', 'VERSION3', 'NONFREE'):
            require(re.search(rf'^#define CONFIG_{flag} 0$', text, re.M) is not None,
                    f'FFmpeg CONFIG_{flag} is not explicitly disabled: {path}')
    return configs


def copy_notice(directory: Path, name: str, contents: bytes) -> None:
    target = directory / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(contents)


def package_vcpkg(packages: list, source: Path, build: Path, vcpkg: Path,
                  downloads: dict, output: Path, notices: Path, triplet: str) -> list[dict]:
    records = []
    for architecture, name, manifest_path in packages:
        spdx = json.loads(manifest_path.read_text())
        recipe = find_recipe(spdx, port_candidates(name, source, build, vcpkg))
        abi = vcpkg / 'buildtrees' / name / f'{architecture}.vcpkg_abi_info.txt'
        resources = source_resources(spdx, downloads, recipe, abi)
        metadata = next(item for item in spdx['packages'] if item['SPDXID'] == 'SPDXRef-port')
        archive = Archive(output, f'vcpkg-{name}-{architecture}')
        archive.tree(recipe, 'port')
        archive.add('metadata/vcpkg.spdx.json', json_bytes(normalized_spdx(spdx)))
        copyright_path = manifest_path.parent / 'copyright'
        if not copyright_path.is_file() and name == 'boost-uninstall' and not resources and metadata.get('licenseConcluded') == 'MIT':
            # This empty helper installs only a CMake wrapper; its source is the
            # verified vcpkg recipe, under vcpkg's own MIT license.
            copyright_path = vcpkg / 'LICENSE.txt'
        require(copyright_path.is_file(), f'Missing installed license: {name}:{architecture}')
        archive.add('licenses/copyright', copyright_path)
        copy_notice(notices, f'{name}/{architecture}-copyright.txt', copyright_path.read_bytes())
        for _, resource in resources:
            archive.add('upstream/' + resource.name, resource)
        # Empty/meta/helper ports contain their entire source in the recipe. Any
        # download-based port must have a source resource, including indirect helpers.
        port_text = (recipe / 'portfile.cmake').read_text()
        if not resources:
            require(not re.search(r'vcpkg_(?:from_|download_distfile|extract_source_archive)', port_text),
                    f'{name} downloads source but its SPDX has no resources; review manually')
        package_dir = vcpkg / 'packages' / f'{name}_{architecture}'
        for filename in ('CONTROL', 'BUILD_INFO'):
            path = package_dir / filename
            if path.is_file():
                archive.add('metadata/' + filename, path)
        abi = vcpkg / 'buildtrees' / name / f'{architecture}.vcpkg_abi_info.txt'
        if abi.is_file():
            archive.add('metadata/vcpkg_abi_info.txt', abi)
        generated = []
        if name == 'ffmpeg' and architecture == triplet:
            for path in verify_ffmpeg(vcpkg, triplet):
                generated.extend([path, path.parent / 'config.asm', path.parent / 'ffbuild/config.mak'])
        for path in generated:
            if path.is_file():
                contents = path.read_bytes()
                for local, stable in ((str(build), '$IMPACTO_BUILD'), (str(vcpkg), '$VCPKG_ROOT'), (str(source), '$IMPACTO_SOURCE')):
                    contents = contents.replace(local.encode(), stable.encode())
                archive.add('configuration/' + path.relative_to(vcpkg / 'buildtrees' / name).as_posix(), contents)
        record = archive.write()
        record.update({'component': name, 'architecture': architecture,
                       'role': 'target dependency' if architecture == triplet else 'build dependency',
                       'version': metadata.get('versionInfo'), 'license': metadata.get('licenseConcluded'),
                       'upstream': [{ 'name': item.get('name'), 'location': item.get('downloadLocation'),
                                      'file': path.name, 'sha512': digest(path, 'sha512') }
                                    for item, path in resources]})
        records.append(record)
        print(f'Packaged {record["file"]} ({record["bytes"]:,} bytes)', flush=True)
    return records


def package_git_component(name: str, directory: Path, revision: str, output: Path,
                          *, excluded: set[str] | None = None) -> dict:
    require(git(directory, 'rev-parse', 'HEAD').decode().strip() == revision,
            f'{name} checkout does not match source-lock.json')
    archive = Archive(output, name)
    excluded_files = []
    for path in git_files(directory):
        relative = path.relative_to(directory)
        if (excluded and relative.parts[0] in excluded) or not safe_source(path):
            excluded_files.append({'file': relative.as_posix(), 'sha256': digest(path)})
        else:
            archive.add('source/' + relative.as_posix(), path)
    # Preserve tracked working-tree modifications and untracked required build inputs.
    for path in git_files(directory, True):
        relative = path.relative_to(directory)
        key = 'source/' + relative.as_posix()
        if key not in archive.files and safe_source(path) and not (excluded and relative.parts[0] in excluded):
            archive.add(key, path)
    archive.add('metadata/revision.txt', (revision + '\n').encode())
    archive.add('metadata/local-modifications.patch', git(directory, 'diff', '--binary', 'HEAD'))
    archive.add('metadata/excluded-files.json', json_bytes(excluded_files))
    archive.add('metadata/README.txt', b'The source directory contains the actual patched build input, not a pristine checkout. Do not reapply local-modifications.patch. See the release SOURCE-DISTRIBUTION.md for rebuilding.\n')
    record = archive.write()
    record.update({'component': name, 'revision': revision, 'excludedFiles': excluded_files})
    return record


def package_integration(output: Path) -> dict:
    archive = Archive(output, 'tyranor-integration')
    excluded = []
    for path in git_files(ROOT, True):
        relative = path.relative_to(ROOT)
        # Never recursively package previous outputs, notices, or local credentials.
        if relative.parts[:3] in (('engine', 'impacto', 'sources'), ('engine', 'impacto', 'licenses')):
            continue
        if any(part in {'build', '.gradle', '.idea', '__pycache__', '.impacto-build'} or part.startswith('.impacto-sources-') for part in relative.parts):
            continue
        if path.name == 'local.properties' or path.suffix.lower() in {'.jks', '.keystore'}:
            continue
        if relative.as_posix() in {'engine/src/main/assets/impacto-runtime/resources.zip.xz', 'engine/src/main/assets/impacto-runtime/manifest.json', 'engine/impacto/runtime-provenance.json', 'engine/impacto/elf-report.txt'}:
            excluded.append({'file': relative.as_posix(), 'reason': 'Generated runtime companion artifact, distributed separately with its final manifest'})
            continue
        if not safe_source(path):
            excluded.append({'file': relative.as_posix(), 'sha256': digest(path), 'reason': 'prebuilt code/build tool; not Impacto source'})
            continue
        archive.add('Tyranor-Next/' + relative.as_posix(), path)
    archive.add('metadata/excluded-prebuilt-files.json', json_bytes(excluded))
    archive.add('metadata/base-revision.txt', git(ROOT, 'rev-parse', 'HEAD'))
    record = archive.write()
    record.update({'component': 'Tyranor integration and application source',
                   'scope': 'Repository source and resources at packaging time; unrelated prebuilt engines and Gradle wrapper JAR are excluded and listed inside.'})
    return record


def package_build_support(vcpkg: Path, revision: str, output: Path) -> dict:
    require(git(vcpkg, 'rev-parse', 'HEAD').decode().strip() == revision, 'vcpkg checkout does not match source-lock.json')
    archive = Archive(output, 'vcpkg-build-support')
    for path in git_files(vcpkg):
        relative = path.relative_to(vcpkg)
        if relative.parts[0] in {'scripts', 'triplets', 'toolsrc'} or len(relative.parts) == 1:
            if safe_source(path):
                archive.add('vcpkg/' + relative.as_posix(), path)
    archive.add('metadata/revision.txt', (revision + '\n').encode())
    record = archive.write()
    record.update({'component': 'vcpkg build scripts and tool bootstrap', 'revision': revision,
                   'scope': 'Build scripts, triplets and bootstrap metadata; exact used ports are in each dependency archive.'})
    return record


def vendor_notices(source: Path, output: Path, notices: Path) -> dict:
    copy_notice(notices, 'impacto/LICENSE.txt', (source / 'LICENSE').read_bytes())
    copy_notice(notices, 'impacto/THIRDPARTY-upstream.md', (source / 'THIRDPARTY.md').read_bytes())
    # mspack's actual source headers specify 2.1, while upstream's combined
    # third-party document embeds the older 2.0 text. Ship the applicable text.
    lgpl = ROOT / 'engine/impacto/licenses/LGPL-2.1.txt'
    if not lgpl.is_file():
        lgpl = ROOT / 'engine/impacto/licenses/dependencies/mspack/COPYING.LIB'
    if not lgpl.is_file():
        lgpl = Path('/usr/share/common-licenses/LGPL-2.1')
    require(lgpl.is_file() and b'Version 2.1, February 1999' in lgpl.read_bytes(),
            'Provide engine/impacto/licenses/LGPL-2.1.txt with the complete LGPL 2.1 text')
    copy_notice(notices, 'mspack/COPYING.LIB', lgpl.read_bytes())
    copy_notice(notices, 'mspack/NOTICE.txt', b'The bundled vendor/mspack source headers license this code under GNU LGPL version 2.1. The LGPL 2.0 text embedded under mspack in upstream THIRDPARTY.md is not the applicable version. The complete corresponding mspack source is in the impacto source archive.\n')
    # Embed all delivered notices in one deterministic archive as well.
    archive = Archive(output, 'dependency-licenses')
    archive.tree(notices, 'licenses')
    return archive.write()


def refresh_integration(output: Path, build: Path, lock: dict) -> None:
    index = json.loads((output / 'index.json').read_text())
    require(index['sourceLock'] == lock and index['unstrippedRuntimeSha256'] == digest(build / 'libimpacto.so'),
            'Runtime/source lock changed: perform full source packaging')
    for record in index['archives']:
        require(digest(output / record['file']) == record['sha256'], 'Existing source archive checksum mismatch')
    with tempfile.TemporaryDirectory(prefix='.impacto-sources-', dir=output.parent) as temp:
        temp = Path(temp)
        record = package_integration(temp)
        index['archives'] = sorted([a for a in index['archives'] if a['file'] != record['file']] + [record], key=lambda a: a['file'])
        os.replace(temp / record['file'], output / record['file'])
    (output / 'index.json').write_bytes(json_bytes(index))
    (output / 'SHA256SUMS').write_text(''.join(f'{a["sha256"]}  {a["file"]}\n' for a in index['archives'])
                                    + f'{digest(output / "index.json")}  index.json\n')
    print('Refreshed Tyranor integration snapshot; all dependency archives verified and retained')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True, help='Patched Impacto Git checkout')
    parser.add_argument('--build', type=Path, required=True, help='Completed Impacto CMake build directory')
    parser.add_argument('--vcpkg', type=Path, required=True, help='Pinned vcpkg checkout, with downloads and buildtrees retained')
    parser.add_argument('--output', type=Path, default=ROOT / 'engine/impacto/sources')
    parser.add_argument('--triplet', default='arm64-android-impacto')
    parser.add_argument('--refresh-integration', action='store_true', help='Refresh only the final TYN snapshot after host/docs edits; native inputs must be unchanged')
    args = parser.parse_args()
    source, build, vcpkg, output = [path.resolve() for path in (args.source, args.build, args.vcpkg, args.output)]
    require((build / 'libimpacto.so').is_file(), 'No completed libimpacto.so build found; finish the native build before packaging source')
    lock = json.loads((ROOT / 'engine/impacto/source-lock.json').read_text())
    if args.refresh_integration:
        refresh_integration(output, build, lock)
        return
    packages = installed_packages(build, args.triplet, source)
    downloads = download_index(vcpkg)
    # Validate all source provenance before replacing a previous bundle.
    for architecture, name, path in packages:
        spdx = json.loads(path.read_text())
        recipe = find_recipe(spdx, port_candidates(name, source, build, vcpkg))
        source_resources(spdx, downloads, recipe, vcpkg / 'buildtrees' / name / f'{architecture}.vcpkg_abi_info.txt')
    verify_ffmpeg(vcpkg, args.triplet)
    for name in ('libatrac9', 'imgui', 'magic_enum'):
        require((build / '_deps' / f'{name}-src' / '.git').exists(), f'Missing fetched source checkout: {name}')
    output.parent.mkdir(parents=True, exist_ok=True)
    notices_destination = ROOT / 'engine/impacto/licenses/dependencies'
    with tempfile.TemporaryDirectory(prefix='.impacto-sources-', dir=output.parent) as temporary:
        temporary = Path(temporary)
        staged, notices = temporary / 'archives', temporary / 'notices'
        staged.mkdir(); notices.mkdir()
        records = package_vcpkg(packages, source, build, vcpkg, downloads, staged, notices, args.triplet)
        records.append(package_git_component('impacto', source, lock['impacto']['revision'], staged, excluded={'resources'}))
        for name in ('libatrac9', 'imgui', 'magic_enum'):
            directory = build / '_deps' / f'{name}-src'
            records.append(package_git_component(name, directory, lock[name]['revision'], staged))
            for path in git_files(directory):
                if re.match(r'(?i)^(license|copying|notice)([._-].*)?$', path.name):
                    copy_notice(notices, f'{name}/{path.relative_to(directory).as_posix()}', path.read_bytes())
        records.append(package_build_support(vcpkg, lock['vcpkg']['revision'], staged))
        records.append(package_integration(staged))
        records.append(vendor_notices(source, staged, notices))
        index = {'schemaVersion': 1, 'scope': 'Impacto native runtime, complete linked application source, target dependency closure, build recipes and Tyranor integration snapshot',
                 'unstrippedRuntimeSha256': digest(build / 'libimpacto.so'),
                 'triplet': args.triplet, 'sourceLock': lock, 'archives': sorted(records, key=lambda record: record['file']),
                 'systemPrerequisites': ['Android NDK ' + lock['android']['ndk'], 'CMake and Ninja', 'Python 3, Git and host build utilities', 'JDK and Android SDK for rebuilding the APK'],
                 'licenseConditions': {'ffmpeg': 'LGPL-2.1-or-later; CONFIG_GPL=CONFIG_VERSION3=CONFIG_NONFREE=0 verified',
                                       'mspack': 'LGPL-2.1-only, per bundled source headers'},
                 'distribution': 'Distribute every listed archive and SOURCE-DISTRIBUTION.md with the corresponding runtime. No written offer or external URL replaces these source files.'}
        (staged / 'index.json').write_bytes(json_bytes(index))
        (staged / 'SHA256SUMS').write_text(''.join(f'{record["sha256"]}  {record["file"]}\n' for record in index['archives'])
                                       + f'{digest(staged / "index.json")}  index.json\n')
        output.mkdir(parents=True, exist_ok=True)
        for path in staged.iterdir():
            os.replace(path, output / path.name)
        # Only remove obsolete generated archive names, never unrelated files.
        current = {record['file'] for record in records}
        for path in output.glob('*.tar.xz'):
            if path.name not in current:
                path.unlink()
        notices_destination.mkdir(parents=True, exist_ok=True)
        shutil.copytree(notices, notices_destination, dirs_exist_ok=True)
    print(f'Wrote {len(records)} source archives and verified index to {output}', flush=True)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(f'Source packaging failed: {error}') from error
