#!/usr/bin/env python3
"""Read-only CI verification of committed Impacto runtime/source companions."""
import argparse
import hashlib
import importlib.util
import io
import json
import lzma
from pathlib import Path, PurePosixPath
import shutil
import sys
import tempfile
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
META = ROOT / 'engine/impacto'
ASSETS = ROOT / 'engine/src/main/assets/impacto-runtime'
spec = importlib.util.spec_from_file_location('impacto_packager', ROOT/'tools/impacto/package-runtime.py')
packager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packager)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def sha_bytes(value):
    return hashlib.sha256(value).hexdigest()


def load(path):
    return json.loads(path.read_text())


def safe_name(name):
    return name and not name.startswith('/') and '\\' not in name and '..' not in PurePosixPath(name).parts


def verify_resources(manifest):
    files = manifest['files']
    require(manifest['version'] == 1 and 0 < len(files) <= 4096, 'Invalid resource manifest version/count')
    require(files == sorted(files, key=lambda f: f['path']), 'Resource manifest is not sorted')
    expected = {f['path']: f for f in files}
    require(len(expected) == len(files), 'Duplicate resource manifest entry')
    require(all(safe_name(n) for n in expected), 'Unsafe resource manifest path')
    require(sum(f['size'] for f in files) <= 512*1024**2 and all(0 <= f['size'] <= 128*1024**2 for f in files), 'Resource bounds exceeded')
    require(manifest['generation'] == sha_bytes(packager.canonical(files)), 'Resource generation mismatch')
    identity = manifest['archive']
    require(identity['name'] == 'resources.zip.xz', 'Unexpected resource archive name')
    archive = ASSETS / identity['name']
    require(archive.stat().st_size == identity['size'] and packager.digest(archive) == identity['sha256'], 'Resource archive checksum/size mismatch')
    with tempfile.TemporaryFile() as unpacked:
        total = 0
        with lzma.open(archive, 'rb') as compressed:
            while chunk := compressed.read(1024*1024):
                total += len(chunk)
                require(total <= 514*1024**2, 'Expanded ZIP exceeds limit')
                unpacked.write(chunk)
        unpacked.seek(0)
        with zipfile.ZipFile(unpacked) as zipped:
            names = zipped.namelist()
            require(len(names) == len(set(names)) and set(names) == set(expected), 'Resource archive entries differ from manifest')
            for entry in zipped.infolist():
                item = expected[entry.filename]
                require(entry.compress_type == zipfile.ZIP_STORED and not entry.is_dir(), 'Expected regular stored ZIP entry')
                require(entry.file_size == item['size'], 'Resource file size mismatch: ' + entry.filename)
                require(sha_bytes(zipped.read(entry)) == item['sha256'], 'Resource file checksum mismatch: ' + entry.filename)
    for name, item in expected.items():
        if name.startswith('licenses/'):
            notice = META / name
            require(notice.is_file() and packager.digest(notice) == item['sha256'], 'Resource notice stale: ' + name)
    return expected


def verify_pack(data, native_sha):
    with zipfile.ZipFile(io.BytesIO(lzma.decompress(data, memlimit=1024*1024**2))) as archive:
        require(archive.namelist() == ['libimpacto.so'], 'Unexpected native runtime pack contents')
        require(sha_bytes(archive.read('libimpacto.so')) == native_sha, 'Runtime pack contains different library bytes')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--runtime-pack', type=Path)
    args = parser.parse_args()
    provenance = load(META/'runtime-provenance.json')
    lock = load(META/'source-lock.json')
    require(provenance['sourceLock'] == lock, 'Stale source lock in provenance')
    native = ROOT/'engine/src/main/jniLibs/arm64-v8a/libimpacto.so'
    require(native.stat().st_size == provenance['size'] and packager.digest(native) == provenance['sha256'], 'Native runtime checksum/size mismatch')
    _, needed = packager.inspect_native(native, args.ndk.resolve())
    require(needed == provenance['neededSystemLibraries'], 'Native dependency list changed')
    patches = {p.name: packager.digest(p) for p in sorted((META/'patches').glob('*.patch'))}
    require(patches == provenance['patches'], 'Native/Java patch provenance stale')
    java = {p.name: packager.digest(p) for p in sorted((ROOT/'engine/src/main/java/org/libsdl/impacto').glob('*.java'))}
    require(java == provenance['javaSources'], 'Private SDL Java provenance stale')
    for name, digest in provenance['buildInputs'].items():
        require(safe_name(name) and packager.digest(ROOT/name) == digest, 'Build input provenance stale: ' + name)
    profiles = load(META/'profiles.json')
    require(packager.digest(META/'profiles.json') == provenance['profilesSha256'], 'Canonical profiles changed')
    require((META/'profiles.json').read_bytes() == (ASSETS/'profiles.json').read_bytes(), 'Profile asset differs from canonical metadata')
    require(profiles['upstreamRevision'] == lock['impacto']['revision'], 'Profile revision differs from source lock')
    require({p['id'] for p in profiles['profiles']} == {'chlcc','chlcc-switch','cclcc','cclcc-switch','mo6tw','darling','dash','rne'} and len(profiles['profiles']) == 8, 'Profile allowlist changed')
    manifest = load(ASSETS/'manifest.json')
    require(manifest['upstreamRevision'] == lock['impacto']['revision'] and manifest['generation'] == provenance['resourceGeneration'], 'Resource provenance stale')
    resources = verify_resources(manifest)
    for profile in profiles['profiles']:
        require(profile['lua'] in resources, 'Missing profile entry point')
        for name, digest in profile['sourceHashes'].items():
            require(resources[name]['sha256'] == digest, 'Profile source hash mismatch: ' + name)
    index_path = META/'sources/index.json'
    require(packager.digest(index_path) == provenance['sourceIndexSha256'], 'Corresponding-source index checksum mismatch')
    index = load(index_path)
    require(index['sourceLock'] == lock and index['unstrippedRuntimeSha256'] == provenance['unstrippedSha256'], 'Corresponding-source/runtime association differs')
    expected_sums = {a['file']: a['sha256'] for a in index['archives']}
    expected_sums['index.json'] = packager.digest(index_path)
    actual_sums = {}
    for line in (META/'sources/SHA256SUMS').read_text().splitlines():
        digest, name = line.split('  ', 1)
        require(name not in actual_sums and safe_name(name), 'Invalid source checksum entry')
        actual_sums[name] = digest
    require(actual_sums == expected_sums, 'Source checksum list differs from index')
    for name, digest in expected_sums.items():
        require(packager.digest(META/'sources'/name) == digest, 'Source archive checksum mismatch: ' + name)
    for entry in index['archives']:
        require((META/'sources'/entry['file']).stat().st_size == entry['bytes'] < 100_000_000, 'Source archive size mismatch/limit')
    relink = META/'relink-report.json'
    require(packager.digest(relink) == provenance['relinkReportSha256'], 'Relink evidence checksum mismatch')
    require(load(relink)['originalUnstrippedRuntimeSha256'] == provenance['unstrippedSha256'] and provenance['validation']['replacementRelinkTested'], 'Relink evidence is not for this runtime')
    if args.runtime_pack:
        verify_pack(args.runtime_pack.read_bytes(), provenance['sha256'])
    if args.apk:
        with zipfile.ZipFile(args.apk) as apk:
            require('lib/arm64-v8a/libimpacto.so' not in apk.namelist(), 'Impacto accidentally shipped outside runtime pack too')
            verify_pack(apk.read('assets/engine-runtime/impacto.zip.xz'), provenance['sha256'])
            for name in ('resources.zip.xz', 'manifest.json', 'profiles.json'):
                require(sha_bytes(apk.read('assets/impacto-runtime/'+name)) == packager.digest(ASSETS/name), 'APK resource differs: ' + name)
    print(f"PASS: Impacto ELF/JNI/16KiB, {len(resources)} resources, 8 profiles, {len(index['archives'])} corresponding-source archives" + (' and APK bytes' if args.apk else ''))


if __name__ == '__main__':
    main()
