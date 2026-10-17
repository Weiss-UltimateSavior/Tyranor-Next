#!/usr/bin/env python3
"""Fail-closed, stdlib-only static acceptance gate for Android Ethornell libraries."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
import zipfile


SYSTEM_LIBRARIES = frozenset({
    "libandroid.so", "liblog.so", "libdl.so", "libc.so", "libm.so", "libz.so",
    "libvulkan.so", "libEGL.so", "libGLESv2.so", "libGLESv3.so",
    "libOpenSLES.so", "libaaudio.so", "libmediandk.so", "libjnigraphics.so",
})
PAGE_SIZE = 16384


class ValidationError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise ValidationError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def required_abi(header):
    """Parse the deliberately restricted single-header C grammar, not a C parser.

    Every mention of an ABI-prefixed identifier outside comments must be a
    recognized int32_t/uint32_t function prototype. Unknown syntax fails closed.
    """
    text = re.sub(r"/\*.*?\*/|//[^\n]*", " ", header, flags=re.S)
    mentions = re.findall(r"\bethornell_android_[A-Za-z0-9_]+\b", text)
    prototypes = re.findall(
        r"\b(?:uint32_t|int32_t)\s+(ethornell_android_[A-Za-z0-9_]+)"
        r"\s*\(([^();{}]*)\)\s*;", text, flags=re.S,
    )
    names = [name for name, _ in prototypes]
    require(names and sorted(names) == sorted(mentions),
            "ABI header contains missing/unrecognized/duplicate prototypes")
    require(len(names) == len(set(names)), "duplicate ABI declaration")
    version = re.findall(r"^\s*#define\s+ETHORNELL_ANDROID_ABI_VERSION\s+(\d+)u?\s*$",
                         text, flags=re.M)
    require(len(version) == 1, "missing/unrecognized ABI version macro")
    return {"version": int(version[0]), "symbols": sorted(names)}


def run(command):
    result = subprocess.run([str(part) for part in command], capture_output=True,
                            text=True, env={**os.environ, "LC_ALL": "C"}, timeout=120)
    require(result.returncode == 0,
            f"tool failed: {command[0]}: {result.stderr.strip()}")
    require(not result.stderr.strip(), f"tool diagnostic: {result.stderr.strip()}")
    return result.stdout


def resolve_tools(ndk=None, host_tag=None, tool_bin=None):
    if tool_bin:
        directory = Path(tool_bin)
    else:
        require(ndk, "supply --ndk (or ANDROID_NDK_HOME) or --tool-bin")
        root = Path(ndk) / "toolchains/llvm/prebuilt"
        if host_tag:
            directory = root / host_tag / "bin"
        else:
            hosts = list(root.glob("*/bin"))
            require(len(hosts) == 1, "ambiguous/missing NDK host; supply --host-tag")
            directory = hosts[0]
    suffix = ".exe" if os.name == "nt" else ""
    tools = {name: directory / ("llvm-" + name + suffix) for name in ("readelf", "nm")}
    for path in tools.values():
        require(path.is_file(), f"missing tool: {path}")
    return tools


def parse_readelf(text):
    def field(label):
        values = re.findall(r"^\s*" + re.escape(label) + r":\s*(.+)$", text, re.M)
        require(len(values) == 1, f"readelf missing/duplicate {label}")
        return values[0].strip()

    require(field("Class") == "ELF64", "not ELF64")
    require(field("Machine") == "AArch64", "not AArch64")
    require(field("Data") == "2's complement, little endian", "not little endian")
    require(field("Type").startswith("DYN "), "not a shared object")
    segments = []
    for line in text.splitlines():
        match = re.match(r"^\s*(LOAD|GNU_RELRO)\s+"
                         r"(0x[0-9a-fA-F]+)\s+(0x[0-9a-fA-F]+)\s+"
                         r"(0x[0-9a-fA-F]+)\s+(0x[0-9a-fA-F]+)\s+"
                         r"(0x[0-9a-fA-F]+)\s+([RWE ]+)\s+(0x[0-9a-fA-F]+)\s*$", line)
        if match:
            kind, off, va, pa, size, mem, flags, align = match.groups()
            segments.append(dict(kind=kind, offset=int(off, 16), vaddr=int(va, 16),
                                 filesz=int(size, 16), memsz=int(mem, 16),
                                 align=int(align, 16)))
        elif re.match(r"^\s*(LOAD|GNU_RELRO)\s", line):
            raise ValidationError(f"unrecognized program header: {line}")
    require(any(s["kind"] == "LOAD" for s in segments), "no LOAD segments")
    needed = re.findall(r"\(NEEDED\)\s+Shared library:\s*\[([^\]]+)\]", text)
    require(len(needed) == len(set(needed)), "duplicate DT_NEEDED")
    require(len(needed) == len(re.findall(r"\(NEEDED\)", text)), "unparsed DT_NEEDED")
    symbols = {}
    require("Symbol table '.dynsym'" in text, "no dynamic symbol table")
    for line in text.splitlines():
        match = re.match(r"^\s*\d+:\s+[0-9a-fA-F]+\s+\d+\s+(\w+)\s+"
                         r"(\w+)\s+(\w+)\s+(\w+)\s+(\S+)(?:\s+\(\d+\))?\s*$", line)
        if match:
            kind, bind, visibility, index, name = match.groups()
            symbols[name] = dict(kind=kind, bind=bind, visibility=visibility, index=index)
        elif re.match(r"^\s*\d+:\s", line):
            # The mandatory zero/null symbol has no name. All other numbered
            # dynsym rows must parse, so formatting changes cannot hide glue.
            require(re.fullmatch(r"\s*0:\s+0+\s+0\s+NOTYPE\s+LOCAL\s+DEFAULT\s+UND\s*", line),
                    f"unrecognized dynamic symbol: {line}")
    require(symbols, "no parsed dynamic symbols")
    return {"segments": segments, "needed": needed, "symbols": symbols}


def parse_nm(text):
    symbols = {}
    for line in text.splitlines():
        if not line.strip():
            continue
        match = re.fullmatch(r"(\S+)\s+([A-Za-z?])(?:\s+[0-9a-fA-F]+){0,2}\s*", line)
        require(match is not None, f"unrecognized llvm-nm line: {line}")
        name, kind = match.groups()
        symbols[name] = kind
    require(symbols, "no nm dynamic symbols")
    return symbols


def binary_metadata(data):
    """Read actual ELF program headers and Android notes, independent of tool text."""
    require(len(data) >= 64 and data[:7] == b"\x7fELF\x02\x01\x01", "invalid ELF64 LE")
    elf_type, machine = struct.unpack_from("<HH", data, 16)
    require((elf_type, machine) == (3, 183), "ELF is not AArch64 ET_DYN")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phsize, count = struct.unpack_from("<HH", data, 54)
    require(phsize == 56 and 0 < count < 65535, "invalid program header table")
    require(phoff + phsize * count <= len(data), "truncated program header table")
    segments, notes = [], []
    for index in range(count):
        kind, flags, off, va, pa, size, mem, align = struct.unpack_from(
            "<IIQQQQQQ", data, phoff + index * phsize)
        require(off + size <= len(data), "segment exceeds file")
        if kind in (1, 0x6474e552):
            segments.append(dict(kind="LOAD" if kind == 1 else "GNU_RELRO",
                                 offset=off, vaddr=va, filesz=size, memsz=mem, align=align))
        if kind != 4:
            continue
        cursor, end = off, off + size
        while cursor < end:
            require(cursor + 12 <= end, "truncated ELF note")
            namesz, descsz, note_type = struct.unpack_from("<III", data, cursor)
            cursor += 12
            name_end = cursor + namesz
            desc_start = (name_end + 3) & ~3
            desc_end = desc_start + descsz
            require(desc_end <= end, "truncated ELF note payload")
            if data[cursor:name_end].rstrip(b"\0") == b"Android" and note_type == 1:
                require(descsz >= 4, "short Android API note")
                notes.append(struct.unpack_from("<I", data, desc_start)[0])
            cursor = (desc_end + 3) & ~3
            require(cursor <= end, "truncated ELF note padding")
    return {"segments": segments, "android_api_notes": notes}


def alignment_errors(segments):
    errors = []
    for index, segment in enumerate(segments):
        kind, align = segment["kind"], segment["align"]
        if kind == "LOAD":
            if align < PAGE_SIZE or align & (align - 1):
                errors.append(f"LOAD[{index}] p_align={align:#x} is not a >=16KB power of two")
            elif (segment["vaddr"] - segment["offset"]) % align:
                errors.append(f"LOAD[{index}] file/virtual addresses not congruent")
            if segment["filesz"] > segment["memsz"]:
                errors.append(f"LOAD[{index}] filesz exceeds memsz")
        elif kind == "GNU_RELRO":
            end = segment["vaddr"] + segment["memsz"]
            if end % PAGE_SIZE:
                errors.append(f"GNU_RELRO[{index}] end={end:#x} is not 16KB aligned")
    if not any(s["kind"] == "GNU_RELRO" for s in segments):
        errors.append("GNU_RELRO missing (project hardening gate)")
    return errors


def forbidden_symbol(name):
    name = name.split("@", 1)[0]
    return (name == "android_main" or name.startswith("ANativeActivity_")
            or name.startswith("GameActivity_") or name.startswith("android_app_")
            or name in {"app_dummy", "onNativeWindowCreated", "onNativeWindowDestroyed"}
            or "winit_android" in name or "android_activity" in name)


def inspect_library(path, tools, abi=None, api=26):
    data = path.read_bytes()
    output = run([tools["readelf"], "-h", "-lW", "-dW", "--dyn-syms", "-n", path])
    nm_output = run([tools["nm"], "--dynamic", "--extern-only", "--format=posix", path])
    parsed, binary, nm = parse_readelf(output), binary_metadata(data), parse_nm(nm_output)
    require(parsed["segments"] == binary["segments"], "readelf/binary program headers disagree")
    errors = alignment_errors(parsed["segments"])
    notes = binary["android_api_notes"]
    # Runtime must attest the selected baseline; bundled dependencies may have
    # an older baseline (e.g. NDK libc++), never a newer or missing one.
    valid_note = notes == [api] if abi else len(notes) == 1 and 1 <= notes[0] <= api
    if not valid_note:
        expected = f"exactly {api}" if abi else f"at most {api}"
        errors.append(f"expected one Android API {expected} note; got {notes}")
    symbols = parsed["symbols"]
    forbidden = sorted(name for name in set(symbols) | set(nm) if forbidden_symbol(name))
    if forbidden:
        errors.append(f"unexpected Android activity/winit glue: {forbidden}")
    if abi:
        for name in abi["symbols"]:
            symbol = symbols.get(name)
            if not (symbol and symbol["kind"] == "FUNC" and symbol["bind"] == "GLOBAL"
                    and symbol["visibility"] == "DEFAULT" and symbol["index"] != "UND"
                    and nm.get(name) == "T"):
                errors.append(f"missing/non-global/non-function ABI export: {name}")
        extras = sorted(name for name, symbol in symbols.items()
                        if name.startswith("ethornell_android_")
                        and symbol["index"] != "UND" and name not in abi["symbols"])
        if extras:
            errors.append(f"ABI-prefixed exports absent from contract header: {extras}")
    return {"name": path.name, "size": len(data), "sha256": sha256(data),
            "android_api_notes": binary["android_api_notes"], "needed": parsed["needed"],
            "segments": parsed["segments"], "errors": errors,
            "undefined_symbols": sorted(name for name, symbol in symbols.items()
                                        if symbol["index"] == "UND"),
            "exported_symbols": sorted(name for name, symbol in symbols.items()
                                       if symbol["index"] != "UND" and symbol["bind"] != "LOCAL"),
            "evidence": {"readelf": output, "nm": nm_output}}


def dependency_errors(needed, bundled, allowed):
    errors = []
    for name in needed:
        if name in SYSTEM_LIBRARIES:
            continue
        if not re.fullmatch(r"lib[A-Za-z0-9_+.-]+\.so", name) or "/" in name:
            errors.append(f"unsafe DT_NEEDED: {name}")
        elif name not in allowed:
            errors.append(f"DT_NEEDED not allowlisted: {name}")
        elif name not in bundled:
            errors.append(f"allowlisted DT_NEEDED absent from package: {name}")
    return errors


def source_manifest(root):
    revision = run(["git", "-C", root, "rev-parse", "HEAD"]).strip()
    status = run(["git", "-C", root, "status", "--porcelain=v1", "--untracked-files=all"])
    require(re.fullmatch(r"[0-9a-f]{40,64}", revision), "invalid source revision")
    return {"revision": revision, "dirty": bool(status), "status": status.splitlines()}


def verify(args):
    header = args.header.read_bytes()
    abi = required_abi(header.decode("utf-8"))
    tools = resolve_tools(args.ndk, args.host_tag, args.tool_bin)
    report = {"schema": 1, "scope": "static-elf-and-package-dependency-closure",
              "header_sha256": sha256(header), "abi": abi,
              "source": source_manifest(args.source_root),
              "tool_versions": {key: run([path, "--version"]) for key, path in tools.items()},
              "policy": {"api": args.api, "page_size": PAGE_SIZE,
                         "system_libraries": sorted(SYSTEM_LIBRARIES),
                         "allowed_bundled": sorted(args.allow_bundled)},
              "libraries": [], "errors": [],
              "not_verified": ["RTLD_NOW/device/API-symbol availability", "ABI struct layout/behavior",
                               "GPU/audio/VM/game compatibility", "APK ZIP alignment", "license"]}
    with tempfile.TemporaryDirectory(prefix="ethornell-verify-") as temporary:
        if args.apk:
            report["apk_sha256"] = sha256(args.apk.read_bytes())
            bundled = {}
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
            directory = args.package_dir or args.library.parent
            bundled = {path.name: path for path in directory.glob("*.so") if path.is_file()}
        require(args.library.name in bundled, "runtime absent from selected package")
        require(bundled[args.library.name].read_bytes() == args.library.read_bytes(),
                "runtime differs from packaged runtime")
        pending, visited = [args.library.name], set()
        while pending:
            name = pending.pop()
            if name in visited:
                continue
            visited.add(name)
            item = inspect_library(bundled[name], tools, abi if name == args.library.name else None, args.api)
            item["errors"].extend(dependency_errors(item["needed"], bundled, args.allow_bundled))
            report["libraries"].append(item)
            report["errors"].extend(f"{name}: {error}" for error in item["errors"])
            pending.extend(dep for dep in item["needed"]
                           if dep not in SYSTEM_LIBRARIES and dep in args.allow_bundled and dep in bundled)
    report["ok"] = not report["errors"]
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--header", type=Path, required=True)
    parser.add_argument("--source-root", type=Path, required=True)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--package-dir", type=Path, help="flat arm64-v8a directory; defaults to library parent")
    group.add_argument("--apk", type=Path, help="verify exact APK bytes and arm64-v8a dependency closure")
    parser.add_argument("--ndk", default=os.environ.get("ANDROID_NDK_HOME"))
    parser.add_argument("--host-tag", help="e.g. linux-x86_64, darwin-x86_64, windows-x86_64")
    parser.add_argument("--tool-bin", help="explicit NDK LLVM bin directory (overrides --ndk/--host-tag)")
    parser.add_argument("--api", type=int, default=26)
    parser.add_argument("--allow-bundled", action="append", default=[], help="repeat for each approved non-system soname")
    parser.add_argument("--manifest", type=Path, help="JSON output file; otherwise stdout")
    args = parser.parse_args()
    try:
        report = verify(args)
    except (ValidationError, OSError, UnicodeError, subprocess.SubprocessError,
            zipfile.BadZipFile, struct.error) as error:
        report = {"schema": 1, "ok": False, "errors": [str(error)]}
    output = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.manifest:
        args.manifest.write_text(output, encoding="utf-8")
    else:
        sys.stdout.write(output)
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
