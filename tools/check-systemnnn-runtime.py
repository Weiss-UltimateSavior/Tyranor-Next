#!/usr/bin/env python3
"""Verify the bundled SystemNNN runtime against its source and license record."""

import argparse
from hashlib import sha256
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys

ENGINE = Path(__file__).resolve().parents[1] / "engine/src/main"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nm", default=shutil.which("llvm-nm") or shutil.which("nm"),
                        help="path to llvm-nm or GNU nm")
    args = parser.parse_args()
    if args.nm is None:
        parser.error("install nm or pass --nm with the Android NDK's llvm-nm")
    note = (ENGINE / "assets/licenses/systemnnn/ffmpeg-source.txt").read_text(encoding="utf-8")
    source = re.search(
        r"^Emberfall native build source: https://github\.com/PorisRora/SystemNNN-Emberfall/tree/([0-9a-f]{40})$",
        note, re.MULTILINE,
    )
    expected = re.search(r"^libsystemnnn\.so: ([0-9]+) bytes, SHA-256 ([0-9a-f]{64})$", note, re.MULTILINE)
    if source is None or expected is None:
        print("SystemNNN runtime must have a pinned source commit, size and SHA-256.", file=sys.stderr)
        return 1
    library = ENGINE / "jniLibs/arm64-v8a/libsystemnnn.so"
    data = library.read_bytes()
    if len(data) < 64 or data[:7] != b"\x7fELF\x02\x01\x01" or struct.unpack_from("<HH", data, 16) != (3, 183):
        print("Expected an ELF64 AArch64 shared library for SystemNNN.", file=sys.stderr)
        return 1
    if len(data) != int(expected[1]) or sha256(data).hexdigest() != expected[2]:
        print("SystemNNN runtime size or SHA-256 does not match ffmpeg-source.txt.", file=sys.stderr)
        return 1
    exported = set(subprocess.check_output(
        [args.nm, "-D", "--defined-only", "-j", str(library)], text=True,
    ).splitlines())
    missing = []
    java = ENGINE / "java/com/tyranor/runtime/systemnnn"
    for name in ("NativeGame", "NativeUiPreview", "MoviePlayback"):
        methods = re.findall(r"\bstatic\s+native\s+[\w\[\]]+\s+(\w+)\s*\(",
                             (java / f"{name}.java").read_text(encoding="utf-8"))
        for method in methods:
            symbol = f"Java_com_tyranor_runtime_systemnnn_{name}_{method.replace('_', '_1')}"
            if symbol not in exported:
                missing.append(symbol)
    if missing:
        print("Missing SystemNNN JNI exports:\n" + "\n".join(missing), file=sys.stderr)
        return 1
    print(f"Verified SystemNNN ARM64 runtime from Emberfall {source[1]}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
