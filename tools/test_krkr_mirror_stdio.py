#!/usr/bin/env python3
"""Compile real production mirror freopen bodies and exercise Linux FILE semantics.

Run: python3 tools/test_krkr_mirror_stdio.py
This requires a Linux C++17 host compiler; it does not validate Android providers.
"""

import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]


def definition(source: str, signature: str) -> str:
    start = source.index(signature)
    brace = source.index("{", start)
    depth = 0
    for position in range(brace, len(source)):
        if source[position] == "{":
            depth += 1
        elif source[position] == "}":
            depth -= 1
            if depth == 0:
                return source[start:position + 1]
    raise RuntimeError(f"Incomplete production definition: {signature}")


def main() -> None:
    if not sys.platform.startswith("linux"):
        raise SystemExit("This native regression requires a Linux host with /proc/self/fd")
    compiler = shlex.split(os.environ.get("CXX", "")) or [
        shutil.which("g++") or shutil.which("clang++") or ""
    ]
    if not compiler[0]:
        raise SystemExit("A C++17 host compiler is required; native regressions were not run")
    source = (ROOT / "engine/src/main/cpp/krkr_bridge.cpp").read_text(encoding="utf-8")
    signatures = [
        "int fopenModeToFlags(const char* mode) {",
        "const char* compatibleStreamMode(int fd, int flags, const char* requestedMode) {",
        "FILE* hookFopenCommon(FopenFn original, const char* rawPath, const char* mode) {",
        "FILE* hookedFreopenCommon(const char* path, const char* mode, FILE* stream, FreopenFn original) {",
    ]
    with tempfile.TemporaryDirectory(prefix="krkr-mirror-stdio-") as folder:
        directory = Path(folder)
        (directory / "krkr_mirror_stdio_under_test.inc").write_text(
            "\n\n".join(definition(source, signature) for signature in signatures) + "\n",
            encoding="utf-8",
        )
        executable = directory / "krkr-mirror-stdio-test"
        subprocess.run(
            compiler + [
                "-std=c++17", "-Wall", "-Wextra", "-Werror",
                "-I", str(directory),
                str(ROOT / "engine/src/test/cpp/krkr_mirror_stdio_test.cpp"),
                "-o", str(executable),
            ],
            check=True,
        )
        subprocess.run([str(executable), str(directory)], check=True)


if __name__ == "__main__":
    main()
