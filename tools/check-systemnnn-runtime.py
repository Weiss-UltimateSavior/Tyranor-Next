#!/usr/bin/env python3
"""Verify the bundled SystemNNN runtime against its source and license record."""

import argparse
from hashlib import sha256
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT / "engine/src/main"


def sdk_directories():
    try:
        properties = (ROOT / "local.properties").read_text(encoding="utf-8", errors="replace")
    except OSError:
        properties = ""
    configured = re.search(r"^[ \t]*sdk\.dir[ \t]*=[ \t]*([^\r\n]+)", properties, re.MULTILINE)
    if configured and (directory := configured[1].strip()):
        yield Path(re.sub(r"\\([\\:= ])", r"\1", directory))
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if directory := os.environ.get(variable):
            yield Path(directory)
    if sys.platform == "win32":
        if directory := os.environ.get("LOCALAPPDATA"):
            yield Path(directory) / "Android/Sdk"
    elif sys.platform == "darwin":
        yield Path.home() / "Library/Android/sdk"
    elif sys.platform == "linux":
        yield Path.home() / "Android/Sdk"


def nm_candidates():
    if llvm_nm := shutil.which("llvm-nm"):
        yield llvm_nm
    ndks = []
    for variable in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"):
        if directory := os.environ.get(variable):
            ndks.append(Path(directory))
    for sdk in sdk_directories():
        ndks.extend(sorted((sdk / "ndk").glob("*"), reverse=True))
        ndks.append(sdk / "ndk-bundle")
    host = "windows" if sys.platform == "win32" else sys.platform
    executable = "llvm-nm.exe" if host == "windows" else "llvm-nm"
    for ndk in ndks:
        for llvm_nm in sorted((ndk / "toolchains/llvm/prebuilt").glob(f"{host}-*/bin/{executable}")):
            yield str(llvm_nm)
    if nm := shutil.which("nm"):
        yield nm


def read_exports(library: Path, override):
    failures = {}
    candidates = (override,) if override is not None else nm_candidates()
    for nm in candidates:
        if nm in failures:
            continue
        try:
            output = subprocess.check_output(
                [nm, "-D", "--defined-only", "-j", str(library)],
                stderr=subprocess.PIPE, text=True, errors="replace",
            )
            return set(output.splitlines())
        except OSError as error:
            failures[nm] = str(error)
        except subprocess.CalledProcessError as error:
            stderr = error.stderr.strip().splitlines()
            failures[nm] = stderr[0] if stderr else f"exit status {error.returncode}"
    print("No compatible nm could read the SystemNNN ELF exports. "
          "Install the Android NDK or pass --nm with the path to its llvm-nm.", file=sys.stderr)
    for nm, detail in failures.items():
        print(f"  {nm}: {detail}", file=sys.stderr)
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nm", help="path to a compatible llvm-nm or GNU nm (override auto-discovery)")
    args = parser.parse_args()
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
    exported = read_exports(library, args.nm)
    if exported is None:
        return 1
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
