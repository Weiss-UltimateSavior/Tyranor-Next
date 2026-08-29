#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
APP_SRC = ROOT / "app" / "src" / "main"
STRING_FILES = [
    APP_SRC / "res" / "values" / "strings.xml",
    APP_SRC / "res" / "values-ja" / "strings.xml",
    APP_SRC / "res" / "values-en" / "strings.xml",
]

VISIBLE_CJK_STRING = re.compile(r'"[^"\n]*[\u3040-\u30ff\u3400-\u9fff][^"\n]*"')
RESOURCE_KEY = re.compile(r'<string\s+name="([^"]+)"')


def resource_keys(path: Path) -> set[str]:
    return set(RESOURCE_KEY.findall(path.read_text(encoding="utf-8")))


def check_resource_parity() -> list[str]:
    errors: list[str] = []
    key_sets = {path: resource_keys(path) for path in STRING_FILES}
    all_keys = set().union(*key_sets.values())
    for path, keys in key_sets.items():
        missing = sorted(all_keys - keys)
        if missing:
            errors.append(f"{path.relative_to(ROOT)} is missing {len(missing)} string keys:")
            errors.extend(f"  - {key}" for key in missing)
    return errors


def check_kotlin_cjk_literals() -> list[str]:
    errors: list[str] = []
    java_root = APP_SRC / "java"
    for path in sorted(java_root.rglob("*.kt")):
        for index, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if VISIBLE_CJK_STRING.search(line):
                errors.append(f"{path.relative_to(ROOT)}:{index}: {line.strip()}")
    return errors


def main() -> int:
    errors = check_resource_parity() + check_kotlin_cjk_literals()
    if errors:
        print("Hardcoded UI string check failed:")
        print("\n".join(errors))
        return 1
    print("Hardcoded UI string check passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
