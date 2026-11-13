#!/usr/bin/env python3
"""Require a complete, unmodified checkout of the locked Impacto revision.

This check never cleans, patches, checks out, or writes into the source tree.
Build products and optional reports must live outside that tree.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
UPSTREAM_URL = "https://github.com/CommitteeOfZero/impacto.git"


def git(source, *arguments):
    environment = os.environ.copy()
    environment["GIT_OPTIONAL_LOCKS"] = "0"
    environment["GIT_NO_REPLACE_OBJECTS"] = "1"
    result = subprocess.run(
        ["git", "-C", str(source), "--no-optional-locks", *arguments],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=environment,
    )
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", errors="replace").strip()
                           or f"Git command failed: {arguments[0]}")
    return result.stdout.decode("utf-8", errors="surrogateescape")


def verify_tree(source, revision):
    if Path(git(source, "rev-parse", "--show-toplevel").strip()).resolve() != source:
        raise RuntimeError(f"Source must be the checkout root: {source}")
    actual = git(source, "rev-parse", "HEAD").strip()
    if actual != revision:
        raise RuntimeError(f"Revision mismatch: expected {revision}, found {actual}")

    # Git status intentionally respects skip-worktree and assume-unchanged bits.
    # Reject those bits so sparse/missing or hidden modified inputs cannot pass.
    flags = git(source, "ls-files", "-v", "-z").split("\0")
    hidden = [entry for entry in flags if entry and entry[0] != "H"]
    if hidden:
        raise RuntimeError(f"Sparse or hidden index entries are not allowed: {hidden[:8]!r}")
    status = git(source, "status", "--porcelain=v1", "-z", "--untracked-files=all",
                 "--ignored=matching", "--ignore-submodules=none")
    if status:
        raise RuntimeError(f"Source checkout is not clean: {status.split(chr(0))[:12]!r}")

    submodules = []
    for entry in git(source, "ls-files", "--stage", "-z").split("\0"):
        if not entry:
            continue
        metadata, name = entry.split("\t", 1)
        mode, object_id, stage = metadata.split()
        if stage != "0":
            raise RuntimeError(f"Unmerged index entry: {name}")
        if mode == "160000":
            child = (source / name).resolve()
            if not child.is_relative_to(source):
                raise RuntimeError(f"Submodule escaped source checkout: {name}")
            # Also rejects uninitialized submodules (whose Git root is the parent)
            # and ignored build inputs that top-level status does not enumerate.
            submodules.append({"path": name, **verify_tree(child, object_id)})
    return {"revision": actual, "tree": git(source, "rev-parse", "HEAD^{tree}").strip(),
            "clean": True, "submodules": submodules}


def verify(source, lock):
    source = source.resolve()
    metadata = json.loads(lock.read_text(encoding="utf-8"))
    upstream = metadata["impacto"]
    if upstream.get("url") != UPSTREAM_URL:
        raise RuntimeError(f"Expected official Impacto URL: {UPSTREAM_URL}")
    revision = upstream.get("revision")
    if not isinstance(revision, str) or not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise RuntimeError("Impacto revision must be a full lowercase 40-character Git SHA")
    return {"version": 1, "url": UPSTREAM_URL, **verify_tree(source, revision)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--lock", type=Path, default=ROOT / "engine/impacto/source-lock.json")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    report = args.report.resolve() if args.report else None
    if report and report.is_relative_to(args.source.resolve()):
        parser.error("Report must be outside the source checkout")
    try:
        result = verify(args.source, args.lock)
    except (OSError, ValueError, KeyError, TypeError, RuntimeError) as error:
        if report:
            report.parent.mkdir(parents=True, exist_ok=True)
            report.write_text(json.dumps({"version": 1, "clean": False, "error": str(error)},
                                         ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
        print(f"Impacto source verification failed: {error}", file=sys.stderr)
        return 1
    output = json.dumps(result, ensure_ascii=True, indent=2) + "\n"
    if report:
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(output, encoding="utf-8")
    print(output, end="")
    return 0


if __name__ == "__main__":
    sys.exit(main())
