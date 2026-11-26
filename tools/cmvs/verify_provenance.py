#!/usr/bin/env python3
"""Verify the vendored CMVS runtime library and its build-time patch."""
import hashlib
import json
import pathlib
import sys


def main() -> int:
    root = pathlib.Path(__file__).resolve().parents[2]
    lib = root / "engine/src/main/jniLibs/arm64-v8a/libcmvs_android.so"
    manifest = lib.with_name("libcmvs_android.provenance.json")
    data = json.loads(manifest.read_text())
    raw = lib.read_bytes()
    errors = []
    if hashlib.sha256(raw).hexdigest() != data.get("sha256"):
        errors.append("sha256 mismatch")
    if len(raw) != data.get("size"):
        errors.append(f"size mismatch: {len(raw)} != {data.get('size')}")
    if data.get("dirty") is not True:
        errors.append("expected dirty=true for the local build-time patch")
    patch = root / "tools/cmvs/patches/0001-absolute-launch-paths.patch"
    expected_patches = [{
        "path": patch.relative_to(root).as_posix(),
        "sha256": hashlib.sha256(patch.read_bytes()).hexdigest(),
    }]
    if data.get("patches") != expected_patches:
        errors.append("build-time patch provenance mismatch")
    profiles = root / "app/src/main/res/values/cmvs_profiles.xml"
    if hashlib.sha256(profiles.read_bytes()).hexdigest() != data.get("profiles_sha256"):
        errors.append("CMVS profile menu provenance mismatch")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"ok sha256={data['sha256']} size={len(raw)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
