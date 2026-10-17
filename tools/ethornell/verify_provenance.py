#!/usr/bin/env python3
"""Fail-closed provenance/hash gate for the vendored Ethornell runtime.

Consumes platform/android/build.sh's actual manifest schema. This verifies
source availability and consistency, not a signed build attestation or rebuild.
No fetched source code, build script, or git hook is executed.
"""

import argparse
import json
import os
from pathlib import Path, PurePosixPath
import re
import struct
import subprocess
import sys
import tempfile
import zipfile

import verify_native as native


SOURCE_REPOSITORY = "https://github.com/Weiss-UltimateSavior/ethornell-rs.git"
SOURCE_HEADER = "platform/android/include/ethornell_android.h"
NDK_VERSION = "28.0.13004108"
RUNTIME = Path("engine/src/main/jniLibs/arm64-v8a/libethornell.so")
HEADER = Path("engine/src/main/cpp/ethornell_android.h")
INVENTORY_POLICY = "cargo-workspace-build-inputs-v1"
# Versioned contract shared with the engine builder; changes require a new policy.
EXCLUDED_PARTS = frozenset({"target", "dist", "raw", ".git", "testcase", "reverse", "out"})
EXCLUDED_SUFFIXES = frozenset({".so", ".dll", ".exe", ".ttf", ".ttc", ".otf", ".arc",
                               ".sud", ".gdb", ".cad", ".bcd", ".zip", ".7z"})
require = native.require


def object_pairs(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"duplicate JSON key: {key}")
        result[key] = value
    return result


def read_manifest(path):
    value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=object_pairs)
    require(isinstance(value, dict), "provenance must be a JSON object")
    return value


def valid_path(name):
    require(isinstance(name, str) and name and "\\" not in name and "\0" not in name,
            "invalid source file path")
    path = PurePosixPath(name)
    require(path.parts and not path.is_absolute() and path.as_posix() == name
            and all(part not in (".", "..", ".git") for part in path.parts),
            f"unsafe source file path: {name}")
    return name


def hash_value(value, label):
    require(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value),
            f"invalid SHA256: {label}")
    return value


def inventory_includes(name):
    path = PurePosixPath(valid_path(name))
    return not any(part in EXCLUDED_PARTS for part in path.parts) and path.suffix.lower() not in EXCLUDED_SUFFIXES


def verify_compile_includes(root, files):
    """Conservative literal Rust include closure, not a Rust/build.rs interpreter.

    Unknown macro syntax (concat/env/generated paths included) fails closed.
    Comments may conservatively cause rejection; never silently ignore macros.
    Every included regular file is independently present in the policy inventory.
    """
    for name in files:
        if PurePosixPath(name).suffix != ".rs":
            continue
        text = (root / name).read_text(encoding="utf-8")
        for mention in re.finditer(r"\binclude(?:_bytes|_str)?(?:\s|/\*.*?\*/|//[^\n]*\n)*!", text, re.S):
            match = re.match(r'\s*\(\s*(?:"([^"\\\n]*)"|r(#+)?"(.*?)"\2)\s*,?\s*\)',
                             text[mention.end():], flags=re.S)
            # Raw strings without hashes are handled explicitly (no optional backref).
            if match is None:
                raw = re.match(r'\s*\(\s*r"([^"\n]*)"\s*,?\s*\)', text[mention.end():])
                target = raw.group(1) if raw else None
            else:
                target = match.group(1) if match.group(1) is not None else match.group(3)
            require(target is not None and target and "\\" not in target and "\0" not in target,
                    f"unresolved compile-time include in {name}; explicit audited policy support required")
            require(not PurePosixPath(target).is_absolute(),
                    f"absolute compile-time include path requires policy review: {name}: {target}")
            path = (root / name).parent / target
            resolved = path.resolve()
            require(root in resolved.parents and not path.is_symlink(),
                    f"compile-time include outside source root or symlink: {name}: {target}")
            relative = resolved.relative_to(root).as_posix()
            require(relative in files,
                    f"compile-time include missing/excluded from source inventory: {name}: {target}")


def preflight(manifest, library, header, strict=True):
    """Check local bytes and reject unreproducible claims before any network I/O."""
    require(library.is_file(), f"missing formal runtime: {library}; no skip is permitted")
    require(header.is_file(), f"missing vendored ABI header: {header}")
    require(type(manifest.get("abi")) is int, "missing/invalid ABI version")
    abi = native.required_abi(header.read_text(encoding="utf-8"))
    require(manifest["abi"] == abi["version"], "manifest/vendored ABI version mismatch")
    for key, expected in (("target", "aarch64-linux-android"), ("api", 26), ("ndk", NDK_VERSION)):
        require(type(manifest.get(key)) is type(expected) and manifest[key] == expected,
                f"build provenance requires {key}={expected}")
    require(isinstance(manifest.get("rustc"), str) and manifest["rustc"].startswith("rustc "),
            "missing rustc build version")
    revision = manifest.get("source_revision")
    require(isinstance(revision, str) and re.fullmatch(r"[0-9a-f]{40}", revision),
            "source_revision must be a full Git commit SHA, never HEAD/tag/branch")
    require(type(manifest.get("dirty")) is bool, "missing/invalid dirty source provenance flag")
    if strict:
        require(manifest["dirty"] is False,
                "dirty source provenance: commit and publish the exact engine sources, then rebuild; "
                "a base revision cannot reproduce dirty changes")
        require(manifest.get("source_inventory_policy") == INVENTORY_POLICY,
                f"missing/unsupported source_inventory_policy; builder must declare {INVENTORY_POLICY}")
    elif "source_inventory_policy" in manifest:
        require(manifest["source_inventory_policy"] == INVENTORY_POLICY, "unsupported source_inventory_policy")
    files = manifest.get("source_file_sha256")
    require(isinstance(files, dict) and files, "missing source_file_sha256 inventory")
    for name, digest in files.items():
        valid_path(name)
        hash_value(digest, name)
    require(SOURCE_HEADER in files and "Cargo.lock" in files,
            "source inventory must contain the ABI header and Cargo.lock")
    if manifest.get("source_inventory_policy") == INVENTORY_POLICY:
        require("Cargo.toml" in files, "source inventory must contain root Cargo.toml")
        require(all(inventory_includes(name) for name in files), "source inventory contains policy-excluded files")
    require(native.sha256(header.read_bytes()) == files[SOURCE_HEADER],
            "vendored ABI header SHA256 differs from build-time source header")
    require(hash_value(manifest.get("cargo_lock_sha256"), "Cargo.lock") == files["Cargo.lock"],
            "Cargo.lock hash fields disagree")
    require(native.sha256(library.read_bytes()) == hash_value(manifest.get("library_sha256"), "library"),
            "runtime SHA256 differs from build provenance")
    require(type(manifest.get("library_bytes")) is int
            and manifest["library_bytes"] == library.stat().st_size,
            "runtime byte size differs from build provenance")
    # Some future builders may add an explicit origin; it must not redirect CI.
    if "source_repository" in manifest:
        require(manifest["source_repository"] == SOURCE_REPOSITORY, "unapproved source repository")
    return revision


def git(root, *arguments):
    # Avoid inherited git directories/config and never recurse into submodules.
    env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
    env.update(LC_ALL="C", GIT_TERMINAL_PROMPT="0", GIT_CONFIG_NOSYSTEM="1",
               GIT_CONFIG_GLOBAL=os.devnull, GIT_LFS_SKIP_SMUDGE="1")
    command = ["git", "-c", "core.hooksPath=/dev/null", "-c", "protocol.file.allow=never",
               "-C", str(root), *arguments]
    result = subprocess.run(command, capture_output=True, env=env, timeout=180)
    require(result.returncode == 0,
            f"engine source git {arguments[0]} failed: {result.stderr.decode('utf-8', 'replace').strip()}")
    return result.stdout.decode("utf-8")


def fetch_source(destination, revision):
    # New directory only: cannot mutate/delete the App or a developer checkout.
    destination.mkdir(parents=True, exist_ok=False)
    git(destination, "init", "--quiet")
    git(destination, "remote", "add", "origin", SOURCE_REPOSITORY)
    git(destination, "fetch", "--depth=1", "--no-tags", "origin", revision)
    git(destination, "-c", "advice.detachedHead=false", "checkout", "--detach", "FETCH_HEAD")


def verify_source(root, manifest, app_root):
    root = root.resolve()
    require(root != app_root.resolve(), "App checkout cannot stand in for the engine source repository")
    require(Path(git(root, "rev-parse", "--show-toplevel").strip()).resolve() == root,
            "--source-root must be the actual engine Git root")
    require(git(root, "remote", "get-url", "origin").strip() == SOURCE_REPOSITORY,
            "engine origin must be the approved public fork URL (including .git)")
    require(git(root, "rev-parse", "HEAD").strip() == manifest["source_revision"],
            "engine checkout differs from build revision")
    require(not git(root, "status", "--porcelain=v1", "--untracked-files=all").strip(),
            "engine source checkout is dirty; strict gate requires exact clean sources")
    require(manifest.get("source_inventory_policy") == INVENTORY_POLICY,
            "strict source verification requires the explicit supported inventory policy")
    entries = git(root, "ls-files", "--stage", "-z").split("\0")
    files = {}
    for entry in filter(None, entries):
        metadata, name = entry.split("\t", 1)
        mode, _, stage = metadata.split()
        require(mode in ("100644", "100755") and stage == "0",
                f"unsupported source mode/stage (symlink/submodule/conflict): {name}")
        valid_path(name)
        if not inventory_includes(name):
            # A source/build file cannot be hidden merely by moving it to an
            # output/reference directory. Actual raw reverse-engineering C is
            # intentionally outside this Cargo/Rust source boundary.
            require(PurePosixPath(name).suffix.lower() not in {".rs", ".sh", ".h", ".hpp", ".toml"}
                    and PurePosixPath(name).name != "Cargo.lock",
                    f"source/build input in excluded directory requires policy review: {name}")
            continue
        path = root / name
        require(path.is_file() and not path.is_symlink() and root in path.resolve().parents,
                f"source file is not a contained regular file: {name}")
        files[name] = native.sha256(path.read_bytes())
    require(files == manifest["source_file_sha256"],
            "published revision file inventory/hashes differ from build provenance")
    verify_compile_includes(root, files)
    return {"repository": SOURCE_REPOSITORY, "revision": manifest["source_revision"],
            "dirty": False, "inventory_policy": INVENTORY_POLICY, "files_verified": len(files)}


def local_static(args):
    """Reuse native primitives without fabricating a --source-root for that CLI.

    Only source-free packaging orchestration is duplicated. All ELF parsing,
    symbol/16KB/API checks and dependency policy remain in verify_native.py.
    """
    tools = native.resolve_tools(args.ndk, args.host_tag)
    abi = native.required_abi(args.header.read_text(encoding="utf-8"))
    report = {"scope": "local-static-elf-and-package-dependency-closure",
              "libraries": [], "errors": [],
              "tool_versions": {key: native.run([path, "--version"]) for key, path in tools.items()},
              "policy": {"api": 26, "page_size": native.PAGE_SIZE,
                         "allowed_bundled": ["libc++_shared.so"],
                         "system_libraries": sorted(native.SYSTEM_LIBRARIES)}}
    with tempfile.TemporaryDirectory(prefix="ethornell-local-static-") as temporary:
        bundled = {}
        if args.apk:
            report["apk_sha256"] = native.sha256(args.apk.read_bytes())
            with zipfile.ZipFile(args.apk) as archive:
                for entry in archive.infolist():
                    if not re.fullmatch(r"lib/arm64-v8a/lib[A-Za-z0-9_+.-]+\.so", entry.filename):
                        continue
                    name = entry.filename.rsplit("/", 1)[1]
                    require(name not in bundled, f"duplicate APK library: {name}")
                    require(entry.file_size <= 512 * 1024 * 1024, "APK library exceeds 512MiB budget")
                    path = Path(temporary) / name
                    path.write_bytes(archive.read(entry))
                    bundled[name] = path
        else:
            bundled = {path.name: path for path in args.library.parent.glob("*.so") if path.is_file()}
        require(args.library.name in bundled, "runtime absent from selected package")
        require(bundled[args.library.name].read_bytes() == args.library.read_bytes(),
                "runtime differs from packaged runtime")
        pending, visited = [args.library.name], set()
        allowed = ["libc++_shared.so"]
        while pending:
            name = pending.pop()
            if name in visited:
                continue
            visited.add(name)
            item = native.inspect_library(bundled[name], tools, abi if name == args.library.name else None, 26)
            item["errors"].extend(native.dependency_errors(item["needed"], bundled, allowed))
            report["libraries"].append(item)
            report["errors"].extend(f"{name}: {error}" for error in item["errors"])
            pending.extend(dep for dep in item["needed"]
                           if dep not in native.SYSTEM_LIBRARIES and dep in allowed and dep in bundled)
    report["ok"] = not report["errors"]
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, default=RUNTIME)
    parser.add_argument("--header", type=Path, default=HEADER)
    parser.add_argument("--provenance", type=Path, help="defaults to library.with_suffix('.provenance.json')")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--source-root", type=Path, help="exact clean engine Git checkout, not the App")
    group.add_argument("--fetch-source", type=Path, help="new directory for approved fork at exact manifest SHA")
    group.add_argument("--local-static", action="store_true",
                       help="source-free development ELF/hash check; always release_ready=false, never release acceptance")
    parser.add_argument("--ndk", help="also run native ELF/closure gate with this NDK")
    parser.add_argument("--host-tag", default="linux-x86_64")
    parser.add_argument("--apk", type=Path, help="also verify APK exact runtime bytes/closure (requires --ndk)")
    parser.add_argument("--report", type=Path, help="write JSON evidence; otherwise stdout")
    args = parser.parse_args()
    scope = "ethornell-local-static" if args.local_static else "ethornell-source-provenance-consistency"
    report = {"schema": 1, "scope": scope, "ok": False,
              "release_ready": False, "source_status": "pending",
              "errors": [], "not_verified": ["signed build attestation", "byte-for-byte rebuild",
                   "build command/features/toolchain reproducibility", "runtime build_info/device behavior",
                   "arbitrary build.rs I/O, generated inputs and external Cargo dependency source closure"]}
    try:
        require(args.library.is_file(), f"missing formal runtime: {args.library}; no skip is permitted")
        provenance = args.provenance or args.library.with_suffix(".provenance.json")
        manifest = read_manifest(provenance)
        report["provenance_sha256"] = native.sha256(provenance.read_bytes())
        revision = preflight(manifest, args.library, args.header, strict=not args.local_static)
        require(not args.apk or args.ndk, "--apk requires --ndk")
        if args.local_static:
            require(args.ndk, "--local-static requires --ndk; provenance-only checks cannot count as static ELF acceptance")
            report["source"] = {"revision": revision, "dirty": manifest["dirty"],
                                "inventory_policy": manifest.get("source_inventory_policy", "undeclared-legacy-pending"),
                                "evidence": "build-manifest claim only; source not fetched or verified"}
            report["pending"] = ["strict release source provenance: fetch exact clean published revision and verify all source bytes"]
        else:
            source_root = args.source_root or args.fetch_source
            if args.fetch_source:
                fetch_source(source_root, revision)
            report["source"] = verify_source(source_root, manifest, Path(__file__).resolve().parents[2])
            report["source_status"] = "verified-consistency-not-attestation"
        report["library_sha256"] = native.sha256(args.library.read_bytes())
        report["header_sha256"] = native.sha256(args.header.read_bytes())
        if args.ndk:
            properties = (Path(args.ndk) / "source.properties").read_text(encoding="utf-8")
            require(re.search(r"^Pkg.Revision\s*=\s*28\.0\.13004108\s*$", properties, re.M),
                    "native gate requires official NDK 28.0.13004108")
            if args.local_static:
                report["native"] = local_static(args)
            else:
                native_args = argparse.Namespace(library=args.library, header=args.header, source_root=source_root,
                    ndk=args.ndk, host_tag=args.host_tag, tool_bin=None, api=26, apk=args.apk,
                    package_dir=None, allow_bundled=["libc++_shared.so"])
                report["native"] = native.verify(native_args)
            report["errors"].extend(report["native"]["errors"])
        report["ok"] = not report["errors"]
        report["release_ready"] = report["ok"] and not args.local_static and bool(args.ndk)
    except (ValueError, KeyError, TypeError, OSError, UnicodeError, subprocess.SubprocessError,
            zipfile.BadZipFile, struct.error) as error:
        report["errors"].append(str(error))
    output = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.report:
        args.report.write_text(output, encoding="utf-8")
    else:
        sys.stdout.write(output)
    if not report["ok"]:
        print("Ethornell gate failed: " + "; ".join(report["errors"]), file=sys.stderr)
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
