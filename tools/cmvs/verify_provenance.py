#!/usr/bin/env python3
"""Fail-closed hash/size check for the vendored CMVS runtime library."""
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
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"ok sha256={data['sha256']} size={len(raw)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
