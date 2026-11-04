#!/usr/bin/env python3
"""Verify and publish the three pinned VN-SR GLES exports with declared top-left logical coordinates.

Standard-library-only, no weight loading, network, or shared executor changes.
"""
import argparse
import hashlib
import json
from pathlib import Path

PINS = {'vnsr-c08-x2.glsl': 'bd59396a8d0355ebf9748e9c4c10dbcde6f761bc07d3a74d47ed88edcf42c470', 'vnsr-c12-x2.glsl': 'b3927528e96671a5342b736a536382737f0dd3b4f9984964dd8f854a8c94a228', 'vnsr-c16-x2.glsl': '029a71c924ee6e648556dfee18bb60060bf5989aa37764851515e15c60cea4e5'}
LICENSE_SHA256 = '6d725fbc95d8f6760ac2ab1087f99e4447056dbce7617fcb5c996947581cd20c'
SOURCE_META = {'repository': 'https://github.com/AAAA1111-Lab/VN-SR', 'revision': '014d9145b935b1ead465f795b91b7bdf68176fcf', 'version': '0.1.0'}
ASSET_DIRECTORY = 'vnsr'


def retained_bytes(data):
    """Structural guard only: the pinned bytes pass through unchanged for the coordinate wrapper."""
    text = data.decode("utf-8")
    if "int index=phase.y*2+phase.x;" not in text:
        raise ValueError("Unexpected reconstruction")
    return data



def main():
    """Check pinned source/license bytes, then generate or verify all three host shaders."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    assets = root / "engine/src/main/assets"
    license_data = (assets / "vnsr/LICENSE-MIT").read_bytes()
    if hashlib.sha256(license_data).hexdigest() != LICENSE_SHA256:
        raise ValueError("Pinned license differs")
    manifest_path = root / "engine/patches/vnsr-filters.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if len(manifest["sources"]) != 1:
        raise ValueError("Unexpected additional graphics sources: " + str(len(manifest["sources"])))
    source = manifest["sources"][0]
    if source.get("verification") != "retained" or source["license"].get("spdx") != "MIT":
        raise ValueError("Invalid retained source or license metadata")
    mismatched = [key for key, value in SOURCE_META.items() if source.get(key) != value]
    if mismatched:
        raise ValueError("Pinned source metadata differs: " + ", ".join(mismatched))
    if manifest.get("assetDirectory") != ASSET_DIRECTORY:
        raise ValueError("Pinned asset directory differs: " + str(manifest.get("assetDirectory")))
    if source["license"].get("path") != "LICENSE":
        raise ValueError("Pinned license path differs: " + str(source["license"].get("path")))
    if source["license"].get("sha256") != LICENSE_SHA256 or source["license"].get("localPath") != "engine/src/main/assets/vnsr/LICENSE-MIT":
        raise ValueError("License manifest differs")
    expected_sources = {"shaders/gles/" + name: (digest, "engine/patches/vnsr/sources/" + name) for name, digest in PINS.items()}
    actual_sources = {item["path"]: (item["sha256"], item.get("localPath")) for item in source["files"]}
    if len(source["files"]) != len(PINS) or actual_sources != expected_sources:
        raise ValueError("Source manifest differs")
    generated = {"vnsr/LICENSE-MIT": LICENSE_SHA256}
    for name, expected in PINS.items():
        data = (root / "engine/patches/vnsr/sources" / name).read_bytes()
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError("Pinned source differs: " + name)
        output = retained_bytes(data)
        target = assets / "vnsr" / name
        generated["vnsr/" + name] = hashlib.sha256(output).hexdigest()
        if args.check:
            if target.read_bytes() != output:
                raise ValueError("Generated shader differs: " + name)
        else:
            target.write_bytes(output)
    if args.check:
        listed = {item["file"]: item["sha256"] for item in manifest["generated"]}
        if len(manifest["generated"]) != len(generated) or listed != generated:
            raise ValueError("Generated manifest differs")
    else:
        manifest["generated"] = [{"file": name, "sha256": digest} for name, digest in generated.items()]
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Verified" if args.check else "Generated", "three VN-SR host shaders")


if __name__ == "__main__":
    main()
