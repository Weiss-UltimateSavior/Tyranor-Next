#!/usr/bin/env python3
"""Export fixed Git blobs and metadata for a filter generator from a separate upstream clone."""
import argparse
import json
import subprocess
from pathlib import Path, PurePosixPath


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    records = json.loads(args.manifest.read_text(encoding="utf-8"))["sources"]
    if len(records) != 1:
        raise ValueError("Prepare one upstream repository at a time")
    source = records[0]
    revision = source["revision"]
    def git(*parts):
        return subprocess.check_output(["git", "-C", str(args.repository), *parts])
    actual = git("rev-parse", revision + "^{commit}").decode().strip()
    if actual != revision:
        raise ValueError("Manifest must contain the full fixed commit")
    output = args.output.resolve()
    repository = args.repository.resolve()
    if output == repository or output.is_relative_to(repository) or repository.is_relative_to(output):
        raise ValueError("Output must be outside the upstream clone")
    entries = [*source["files"], source["license"]]
    blobs = []
    for entry in entries:
        relative = PurePosixPath(entry["path"])
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError("Invalid source path")
        blob = git("rev-parse", revision + ":" + str(relative)).decode().strip()
        if blob != entry["gitBlob"]:
            raise ValueError("Manifest blob does not match fixed upstream tree: " + str(relative))
        data = git("cat-file", "blob", blob)
        target = output.joinpath(*relative.parts)
        if not target.resolve().is_relative_to(output):
            raise ValueError("Source path escapes output")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        blobs.append({"path": str(relative), "sha": blob})
    output.mkdir(parents=True, exist_ok=True)
    (output / "origin.json").write_text(json.dumps({"repository": source["repository"], "revision": revision}, indent=2) + "\n", encoding="utf-8")
    (output / "tree.json").write_text(json.dumps({"tree": blobs}, indent=2) + "\n", encoding="utf-8")
    print("Prepared fixed source blobs:", len(blobs))


if __name__ == "__main__":
    main()
