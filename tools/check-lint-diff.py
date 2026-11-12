#!/usr/bin/env python3
"""Report Android lint errors on added or changed lines."""

import argparse
from pathlib import Path
import re
import subprocess
import sys
from xml.etree import ElementTree


ROOT = Path(__file__).resolve().parents[1]
HUNK = re.compile(r"^@@ .* \+(\d+)(?:,(\d+))? @@")


def changed_lines(base: str) -> dict[str, list[range]]:
    sources = ("app/src", "engine/src")
    git = ["git", "-c", "core.quotepath=false"]
    diff = subprocess.check_output(git + [
        "diff", "--no-ext-diff", "--unified=0", base, "--", *sources,
    ], cwd=ROOT, text=True, encoding="utf-8")
    changed: dict[str, list[range]] = {}
    current = None
    for line in diff.splitlines():
        if line.startswith("+++ "):
            path = line[4:].split("\t", 1)[0]
            current = path.removeprefix("b/") if path.startswith("b/") else None
        elif current and (hunk := HUNK.match(line)):
            start, count = int(hunk[1]), int(hunk[2] or 1)
            changed.setdefault(current, []).append(range(start, start + count))
    untracked = subprocess.check_output(git + [
        "ls-files", "--others", "--exclude-standard", "-z", "--", *sources,
    ], cwd=ROOT, text=True, encoding="utf-8")
    for path in untracked.split("\0"):
        if path:
            changed[path] = [range(0, sys.maxsize)]
    return changed


def relative_source(name: str, report: Path) -> str:
    path = Path(name)
    if not path.is_absolute():
        candidate = ROOT / path
        path = candidate if candidate.exists() else report.parents[2] / path
    try:
        return path.resolve().relative_to(ROOT).as_posix()
    except ValueError:
        return ""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True)
    parser.add_argument("reports", nargs="+", type=Path)
    args = parser.parse_args()
    changed = changed_lines(args.base)
    failures = 0
    total = 0
    for report in args.reports:
        report = report.resolve()
        for issue in ElementTree.parse(report).getroot().findall("issue"):
            if issue.get("severity") not in {"Error", "Fatal"}:
                continue
            total += 1
            for location in issue.findall("location"):
                path = relative_source(location.get("file", ""), report)
                line = int(location.get("line", "0"))
                intervals = changed.get(path, ())
                if intervals and (line == 0 or any(line in interval for interval in intervals)):
                    print(f"{path}:{line}: {issue.get('id')}: {issue.get('message')}")
                    failures += 1
                    break
    print(f"Lint errors in reports: {total}. Errors on changed sources: {failures}.")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
