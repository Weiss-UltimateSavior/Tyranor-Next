#!/usr/bin/env python3
"""Verify pinned VN-SR GLES3-MRT source exports and publish host assets directly.

Standard-library-only, no weight loading, network, or shared executor changes.
Publishes upstream `shaders/gles-mrt/` exports unchanged after structural and provenance checks.
"""
import argparse
import hashlib
import json
from pathlib import Path

PINS = {
    'vnsr-c08-x2.glsl': '9243ae6f1aa6a2f4bb29fddd5531a436de847be9c0825906e1acc583b5407767',
    'vnsr-c12-x2.glsl': '1be932e182b58ab433e4c414690d8d093c503019394afa676d8a8116e7f296ca',
    'vnsr-c16-x2.glsl': '9d40950c11582675bc727874fcaadc7e800d253f90c1384d39312ef7aecd496b',
}
LICENSE_SHA256 = '6d725fbc95d8f6760ac2ab1087f99e4447056dbce7617fcb5c996947581cd20c'
SOURCE_META = {
    'repository': 'https://github.com/AAAA1111-Lab/VN-SR',
    'revision': '4a9896612874ebfdc2a7393864445af32818cf4e',
    'version': '0.1.0',
}
ASSET_DIRECTORY = 'vnsr'


def retained_bytes(data: bytes) -> bytes:
    """Structural guard only: pinned upstream gles-mrt bytes pass through unchanged."""
    text = data.decode('utf-8')
    if 'int index=phase.y*2+phase.x;' not in text:
        raise ValueError('Unexpected reconstruction')
    if 'void hook() {' not in text or 'fragColor0=' not in text:
        raise ValueError('Expected GLES3 MRT fused shader body')
    return data


def main():
    """Check pinned source/license bytes, then publish or verify all three host shaders."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    assets = root / 'engine/src/main/assets'
    license_data = (assets / 'vnsr/LICENSE-MIT').read_bytes()
    if hashlib.sha256(license_data).hexdigest() != LICENSE_SHA256:
        raise ValueError('Pinned license differs')
    manifest_path = root / 'engine/patches/vnsr-filters.json'
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    if len(manifest['sources']) != 1:
        raise ValueError('Unexpected additional graphics sources: ' + str(len(manifest['sources'])))
    source = manifest['sources'][0]
    if source.get('verification') != 'retained' or source['license'].get('spdx') != 'MIT':
        raise ValueError('Invalid retained source or license metadata')
    mismatched = [key for key, value in SOURCE_META.items() if source.get(key) != value]
    if mismatched:
        raise ValueError('Pinned source metadata differs: ' + ', '.join(mismatched))
    if manifest.get('assetDirectory') != ASSET_DIRECTORY:
        raise ValueError('Pinned asset directory differs: ' + str(manifest.get('assetDirectory')))
    if source['license'].get('path') != 'LICENSE':
        raise ValueError('Pinned license path differs: ' + str(source['license'].get('path')))
    if source['license'].get('sha256') != LICENSE_SHA256 or source['license'].get('localPath') != 'engine/src/main/assets/vnsr/LICENSE-MIT':
        raise ValueError('License manifest differs')
    expected_sources = {'shaders/gles-mrt/' + name: (digest, 'engine/patches/vnsr/sources/' + name) for name, digest in PINS.items()}
    actual_sources = {item['path']: (item['sha256'], item.get('localPath')) for item in source['files']}
    if len(source['files']) != len(PINS) or actual_sources != expected_sources:
        raise ValueError('Source manifest differs')
    generated = {'vnsr/LICENSE-MIT': LICENSE_SHA256}
    for name, expected in PINS.items():
        data = (root / 'engine/patches/vnsr/sources' / name).read_bytes()
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError('Pinned source differs: ' + name)
        output = retained_bytes(data)
        target = assets / 'vnsr' / name
        generated['vnsr/' + name] = hashlib.sha256(output).hexdigest()
        if args.check:
            if target.read_bytes() != output:
                raise ValueError('Generated shader differs: ' + name)
        else:
            target.write_bytes(output)
    if args.check:
        listed = {item['file']: item['sha256'] for item in manifest['generated']}
        if len(manifest['generated']) != len(generated) or listed != generated:
            raise ValueError('Generated manifest differs')
    else:
        manifest['generated'] = [{'file': name, 'sha256': digest} for name, digest in generated.items()]
        manifest_bytes = (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
        manifest_path.write_bytes(manifest_bytes)
    print('Verified' if args.check else 'Generated', 'three VN-SR host shaders')


if __name__ == '__main__':
    main()
