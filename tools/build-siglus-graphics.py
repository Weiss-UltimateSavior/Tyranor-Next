#!/usr/bin/env python3
"""Build the complete pinned Siglus library with downstream-only graphics patches."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import struct
import sys
import urllib.request
import tarfile
import tempfile
from pathlib import Path


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def run(arguments, cwd=None, env=None):
    result = subprocess.run([str(a) for a in arguments], cwd=cwd, env=env,
                            capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode:
        raise RuntimeError(f"{arguments[0]} failed: {result.stderr or result.stdout}")
    return result.stdout.strip()


def symbols(nm, library):
    return {line.split()[-1] for line in run([nm, "--dynamic", "--defined-only", library]).splitlines()}


def build_key(root, manifest):
    # Installation metadata does not alter source or the compiled artifact.
    # Include all actual build inputs and the verifier/workflow implementation.
    fields = ("upstream", "revision", "archiveSha256", "cargoLockSha256", "rustVersion",
              "rustCommit", "target", "androidApi", "ndkVersion", "package", "patches",
              "geometryAbi", "geometryWords", "previousExportsFile", "previousExportsSha256")
    inputs = {key: manifest[key] for key in fields}
    inputs["toolSha256"] = sha256(root / "tools/build-siglus-graphics.py")
    inputs["workflowSha256"] = sha256(root / ".github/workflows/siglus-graphics-ci.yml")
    return hashlib.sha256(json.dumps(inputs, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def verify_references(root, manifest):
    reference = root / manifest["previousExportsFile"]
    if sha256(reference) != manifest["previousExportsSha256"]:
        raise ValueError("Previous ABI reference hash differs from the manifest")
    previous = {line.split()[-1] for line in reference.read_text(encoding="utf-8").splitlines() if line.strip()}
    if not previous:
        raise ValueError("Previous ABI reference is empty")
    patches = root / "engine/patches/siglus"
    for entry in manifest["patches"]:
        if sha256(patches / entry["file"]) != entry["sha256"]:
            raise ValueError(f"Patch hash mismatch: {entry['file']}")
    return previous


def inspect_elf(library):
    """Read ELF64/AArch64 exports and LOAD alignment without executing target code."""
    data = library.read_bytes()
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Not a little-endian ELF64 library")
    header = struct.unpack_from("<16sHHIQQQIHHHHHH", data)
    if header[1] != 3 or header[2] != 183:
        raise ValueError("Library must be an AArch64 shared object")
    def table(offset, size, count, minimum):
        if size < minimum or offset > len(data) or count > (len(data) - offset) // size:
            raise ValueError("Invalid ELF table bounds")
    table(header[5], header[9], header[10], 56)
    table(header[6], header[11], header[12], 64)
    loads = []
    for index in range(header[10]):
        segment = struct.unpack_from("<IIQQQQQQ", data, header[5] + index * header[9])
        if segment[0] == 1:
            if segment[2] > len(data) or segment[5] > len(data) - segment[2]:
                raise ValueError("Invalid ELF LOAD bounds")
            alignment = segment[7]
            if alignment < 16384 or alignment & (alignment - 1) or segment[2] % alignment != segment[3] % alignment:
                raise ValueError("Invalid ELF LOAD page alignment")
            loads.append(alignment)
    if not loads or any(alignment < 16384 for alignment in loads):
        raise ValueError("Library is not aligned for 16 KiB Android pages")
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, header[6] + index * header[11])
                for index in range(header[12])]
    def section_bytes(section):
        if section[4] > len(data) or section[5] > len(data) - section[4]:
            raise ValueError("Invalid ELF section bounds")
        return data[section[4]:section[4] + section[5]]
    exports = set()
    for section in sections:
        if section[1] != 11:  # SHT_DYNSYM
            continue
        if section[6] >= len(sections) or section[9] < 24 or section[5] % section[9]:
            raise ValueError("Invalid ELF dynamic symbol table")
        strings = section_bytes(sections[section[6]])
        entries = section_bytes(section)
        for offset in range(0, len(entries), section[9]):
            name, info, _, index, _, _ = struct.unpack_from("<IBBHQQ", entries, offset)
            if not index or info >> 4 not in (1, 2):
                continue
            end = strings.find(b"\0", name)
            if end < name:
                raise ValueError("Invalid ELF symbol name")
            exports.add(strings[name:end].decode("utf-8"))
    if not exports:
        raise ValueError("Shared library has no defined dynamic exports")
    return exports, loads


def verify_staged(root, build, manifest, baseline):
    # A cache hit is accepted only for this exact source/patch/toolchain/verifier
    # key. Revalidate bytes and ELF; never trust a prior 'success' flag alone.
    previous = verify_references(root, manifest)
    name = "baseline" if baseline else "patched"
    staged = build / "staged"
    library = staged / f"libsiglus-{name}.so"
    report = json.loads((staged / f"{name}-build.json").read_text(encoding="utf-8"))
    if report.get("buildKey") != build_key(root, manifest) or report.get("buildKind") != name:
        raise ValueError("Staged build provenance differs from the current build inputs")
    if sha256(library) != report.get("librarySha256") or library.stat().st_size != report.get("bytes"):
        raise ValueError("Staged library differs from its verified build report")
    fields = ("upstream", "revision", "archiveSha256", "cargoLockSha256", "rustVersion", "rustCommit",
              "target", "androidApi", "ndkVersion", "package", "patches", "geometryAbi", "geometryWords",
              "previousExportsFile", "previousExportsSha256")
    if any(report.get(field) != manifest[field] for field in fields):
        raise ValueError("Staged source, patch, toolchain or ABI provenance differs")
    rust = report.get("rust", "")
    if f"release: {manifest['rustVersion']}\n" not in rust + "\n" or f"commit-hash: {manifest['rustCommit']}\n" not in rust + "\n":
        raise ValueError("Staged Rust toolchain provenance differs")
    exports, loads = inspect_elf(library)
    required = set(previous)
    if not baseline:
        required.add(manifest["geometryAbi"])
    if required - exports:
        raise ValueError(f"Missing staged shared library ABI: {sorted(required - exports)}")
    if baseline and manifest["geometryAbi"] in exports:
        raise ValueError("Unpatched baseline unexpectedly includes downstream geometry ABI")
    if exports != set(report.get("exports", [])) or loads != report.get("loadAlignments"):
        raise ValueError("Staged ELF metadata differs from the build report")
    print(f"Verified cached {name}: {len(exports)} exports, SHA256 {report['librarySha256']}")


def download_archive(url, destination):
    request = urllib.request.Request(url, headers={"User-Agent": "Tyranor-Next-Siglus-build"})
    pending = destination.with_suffix(destination.suffix + ".download")
    try:
        with urllib.request.urlopen(request, timeout=120) as response, pending.open("wb") as output:
            shutil.copyfileobj(response, output)
        pending.replace(destination)
    finally:
        pending.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path)
    parser.add_argument("--cmake-bin", type=Path)
    parser.add_argument("--cargo-home", type=Path)
    parser.add_argument("--rustup-home", type=Path)
    parser.add_argument("--toolchain", help="Exact Rust release by default; complete version/commit still verified")
    parser.add_argument("--build-dir", type=Path, default=Path("build/siglus-stage-a"))
    parser.add_argument("--print-build-key", action="store_true")
    parser.add_argument("--verify-staged", action="store_true", help="Revalidate cached artifacts without rebuilding")
    parser.add_argument("--jobs", type=int, default=6)
    parser.add_argument("--baseline-only", action="store_true")
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--install", action="store_true", help="Copy verified staged library into jniLibs")
    args = parser.parse_args()
    if args.prepare_only and args.install:
        parser.error("--prepare-only cannot be combined with --install")
    if args.jobs < 1 or (args.baseline_only and args.install):
        parser.error("jobs must be positive; baseline builds cannot be installed")
    root = Path(__file__).resolve().parents[1]
    patches = root / "engine/patches/siglus"
    manifest = json.loads((patches / "manifest.json").read_text(encoding="utf-8"))
    build = (root / args.build_dir).resolve()
    if (root / "build").resolve() not in build.parents:
        parser.error("build-dir must stay inside the project's build directory")
    if args.print_build_key:
        if args.verify_staged or args.prepare_only or args.baseline_only or args.install:
            parser.error("print-build-key cannot be combined with build/verification modes")
        verify_references(root, manifest)
        print(build_key(root, manifest))
        return
    if args.verify_staged:
        if args.install or args.prepare_only:
            parser.error("verify-staged cannot install or prepare source")
        verify_staged(root, build, manifest, args.baseline_only)
        return
    if any(value is None for value in (args.ndk, args.cmake_bin, args.cargo_home, args.rustup_home)):
        parser.error("build/prepare requires ndk, cmake-bin, cargo-home and rustup-home")
    if os.name != "nt" and not sys.platform.startswith("linux"):
        parser.error("Only pinned Windows and Linux NDK hosts are supported")
    toolchain = args.toolchain or manifest["rustVersion"]
    verify_references(root, manifest)
    build.mkdir(parents=True, exist_ok=True)
    archive = build / f"siglus-{manifest['revision'][:7]}.tar.gz"
    if not archive.is_file():
        if manifest["upstream"] != "https://github.com/xmoezzz/siglus_rs":
            raise ValueError("Unexpected source repository; update the build contract explicitly")
        download_archive(f"https://codeload.github.com/xmoezzz/siglus_rs/tar.gz/{manifest['revision']}", archive)
    if sha256(archive) != manifest["archiveSha256"]:
        raise ValueError("Source archive differs from the pinned upstream; no files changed")
    source = build / "source"
    if not source.exists():
        source.mkdir()
        with tarfile.open(archive, "r:gz") as bundle:
            for member in bundle:
                relative = Path(*Path(member.name).parts[1:])
                if not relative.parts:
                    continue
                target = (source / relative).resolve()
                if source.resolve() not in target.parents or not (member.isdir() or member.isfile()):
                    raise ValueError(f"Unsafe source archive entry: {member.name}")
                if member.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with bundle.extractfile(member) as data, target.open("wb") as output:
                        shutil.copyfileobj(data, output)
                    target.chmod(member.mode & 0o777)
        run(["git", "init"], source)
        run(["git", "config", "core.autocrlf", "false"], source)
        run(["git", "add", "--force", "--all"], source)
        run(["git", "-c", "user.name=Siglus build snapshot", "-c", "user.email=build@localhost",
             "commit", "-m", f"build: upstream archive {manifest['revision']}"], source)
    if sha256(source / "Cargo.lock") != manifest["cargoLockSha256"]:
        raise ValueError("Unexpected Cargo.lock; refusing to mix upstream dependency trees")
    # Compare Git blob hashes in one tree walk; no mutable remote HEAD is used.
    tree = subprocess.run(["git", "ls-tree", "-r", "-z", "HEAD"], cwd=source,
                          capture_output=True, check=True).stdout
    blobs = {}
    for item in tree.split(b"\0"):
        if not item:
            continue
        entry, relative = item.split(b"\t", 1)
        blobs[relative.decode("utf-8")] = entry.split()[2].decode("ascii")
    archive_files = set()
    with tarfile.open(archive, "r:gz") as bundle:
        for member in bundle:
            if not member.isfile():
                continue
            relative = Path(*Path(member.name).parts[1:]).as_posix()
            archive_files.add(relative)
            digest = hashlib.sha1(f"blob {member.size}\0".encode())
            with bundle.extractfile(member) as data:
                for block in iter(lambda: data.read(1024 * 1024), b""):
                    digest.update(block)
            if blobs.get(relative) != digest.hexdigest():
                raise ValueError(f"Build baseline does not match upstream archive: {relative}")
    if set(blobs) != archive_files:
        raise ValueError("Build baseline has unexpected files outside the upstream archive")
    if run(["git", "ls-files", "--others", "--exclude-standard"], source):
        raise ValueError("Untracked source files in build tree; inspect before rebuilding")
    patch_paths = []
    for entry in manifest["patches"]:
        patch = patches / entry["file"]
        if sha256(patch) != entry["sha256"]:
            raise ValueError(f"Patch hash mismatch: {patch}")
        patch_paths.append(patch)
    applied = subprocess.run(["git", "apply", "--reverse", "--check", *map(str, patch_paths)],
                             cwd=source, capture_output=True).returncode == 0
    if args.baseline_only:
        if applied or run(["git", "diff", "HEAD", "--name-only"], source):
            raise ValueError("Baseline build requires a clean source tree")
    elif not applied:
        if run(["git", "diff", "--name-only"], source):
            raise ValueError("Unexpected source edits before applying downstream patches")
        run(["git", "apply", "--check", *patch_paths], source)
        run(["git", "apply", *patch_paths], source)
    # Reject other edits even when reverse --check could still accept this patch.
    if not args.baseline_only:
        expected = set()
        for patch in patch_paths:
            expected.update(re.findall(r"^\+\+\+ b/(.+)$", patch.read_text(encoding="utf-8"), re.M))
        actual = set(run(["git", "diff", "--name-only"], source).splitlines())
        if actual != expected:
            raise ValueError(f"Unexpected patched source files: {actual ^ expected}")
        # Reverse into a temporary index and compare the complete baseline tree.
        temporary_index = build / "verify-index"
        if temporary_index.exists():
            temporary_index.unlink()
        index_env = os.environ.copy()
        index_env["GIT_INDEX_FILE"] = str(temporary_index)
        try:
            run(["git", "read-tree", "HEAD"], source, index_env)
            run(["git", "add", "--all"], source, index_env)
            run(["git", "apply", "--cached", "--reverse", *patch_paths], source, index_env)
            if run(["git", "write-tree"], source, index_env) != run(["git", "rev-parse", "HEAD^{tree}"], source):
                raise ValueError("Patched tree contains additional changes")
        finally:
            temporary_index.unlink(missing_ok=True)
    ndk = args.ndk.resolve()
    ndk_properties = (ndk / "source.properties").read_text(encoding="utf-8")
    if f"Pkg.Revision = {manifest['ndkVersion']}" not in ndk_properties:
        raise ValueError("NDK version differs from build manifest")
    windows = os.name == "nt"
    suffix = ".exe" if windows else ""
    host = "windows-x86_64" if windows else "linux-x86_64"
    ndk_bin = ndk / f"toolchains/llvm/prebuilt/{host}/bin"
    env = os.environ.copy()
    env.update(CARGO_HOME=str(args.cargo_home.resolve()), RUSTUP_HOME=str(args.rustup_home.resolve()))
    host_bin = build / "host-bin"
    host_bin.mkdir(exist_ok=True)
    if windows:
        # Rust's GNU host requires this conventional raw-dylib helper name.
        shutil.copy2(ndk_bin / "llvm-dlltool.exe", host_bin / "dlltool.exe")
    env["PATH"] = os.pathsep.join(map(str, [args.cargo_home.resolve() / "bin", host_bin,
                                             ndk_bin, args.cmake_bin.resolve()])) + os.pathsep + env["PATH"]
    def rust_tool(name):
        local = args.cargo_home.resolve() / "bin" / f"{name}{suffix}"
        executable = str(local) if local.is_file() else shutil.which(name, path=env["PATH"])
        if not executable:
            raise ValueError(f"Rust tool unavailable: {name}")
        return executable
    rust_info = run([rust_tool("rustc"), f"+{toolchain}", "-Vv"], env=env)
    if f"release: {manifest['rustVersion']}\n" not in rust_info + "\n" or f"commit-hash: {manifest['rustCommit']}\n" not in rust_info + "\n":
        raise ValueError("Rust version/commit differs from build manifest")
    target = manifest["target"]
    platform = f"{target}{manifest['androidApi']}"
    env["CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"] = str(ndk_bin / f"clang{suffix}")
    env.pop("CARGO_ENCODED_RUSTFLAGS", None)
    env["RUSTFLAGS"] = (f"-Clink-arg=--target={platform} -Clink-arg=-Wl,-z,max-page-size=16384 "
                        "-Clink-arg=-Wl,-z,common-page-size=16384")
    env["CC_aarch64_linux_android"] = str(ndk_bin / f"clang{suffix}")
    env["CXX_aarch64_linux_android"] = str(ndk_bin / f"clang++{suffix}")
    env["CFLAGS_aarch64_linux_android"] = f"--target={platform}"
    env["CXXFLAGS_aarch64_linux_android"] = f"--target={platform}"
    env["AR_aarch64_linux_android"] = str(ndk_bin / f"llvm-ar{suffix}")
    clang_library = "libclang.dll" if windows else "libclang.so*"
    clang_directories = [ndk_bin, ndk_bin.parent / "lib", ndk_bin.parent / "lib64"]
    clang_directory = next((directory for directory in clang_directories
                            if any(directory.glob(clang_library))), None)
    if clang_directory is None:
        raise ValueError("Pinned NDK libclang is unavailable")
    env["LIBCLANG_PATH"] = str(clang_directory)
    if args.prepare_only:
        print(f"Verified source, patches and toolchain: {source}")
        return
    name = "baseline" if args.baseline_only else "patched"
    log = build / f"{name}-build.log"
    target_dir = source / "target"
    command = [rust_tool("cargo"), f"+{toolchain}", "build",
               "--locked", "--release", "--target", target, "--target-dir", str(target_dir),
               "-p", manifest["package"], "--lib",
               "-j", str(args.jobs)]
    print(f"Building {name}; log: {log}", flush=True)
    with log.open("w", encoding="utf-8") as stream:
        result = subprocess.run(command, cwd=source, env=env, stdout=stream, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(f"Cargo failed; inspect {log}")
    built = target_dir / f"{target}/release/libgame_launcher.so"
    installed = root / "engine/src/main/jniLibs/arm64-v8a/libsiglus.so"
    reference = root / manifest["previousExportsFile"]
    if sha256(reference) != manifest["previousExportsSha256"]:
        raise ValueError("Previous ABI reference hash differs from the manifest")
    previous = {line.split()[-1] for line in reference.read_text(encoding="utf-8").splitlines()}
    exports = symbols(ndk_bin / f"llvm-nm{suffix}", built)
    parsed_exports, load_alignments = inspect_elf(built)
    if exports != parsed_exports:
        raise ValueError("Independent ELF parser and pinned llvm-nm disagree on defined exports")
    # Preserve every previously exported ABI, including Android/JNI entry points.
    required = set(previous)
    if args.baseline_only:
        required.discard(manifest["geometryAbi"])
    else:
        required.add(manifest["geometryAbi"])
    if required - exports:
        raise ValueError(f"Missing shared library ABI: {sorted(required - exports)}")
    if args.baseline_only and manifest["geometryAbi"] in exports:
        raise ValueError("Unpatched baseline unexpectedly contains downstream geometry ABI")
    elf = run([ndk_bin / f"llvm-readelf{suffix}", "--program-headers", "--wide", built])
    loads = [line for line in elf.splitlines() if line.strip().startswith("LOAD ")]
    if not loads or any(int(line.split()[-1], 16) < 16384 for line in loads):
        raise ValueError("Library is not aligned for 16 KiB Android pages")
    stage = build / "staged"
    stage.mkdir(exist_ok=True)
    destination = stage / f"libsiglus-{name}.so"
    shutil.copy2(built, destination)
    report = dict(manifest, buildKind=name, buildKey=build_key(root, manifest),
                  sourceTree=run(["git", "rev-parse", "HEAD^{tree}"], source),
                  librarySha256=sha256(destination),
                  bytes=destination.stat().st_size, rust=rust_info, host=host,
                  exports=sorted(exports), loadAlignments=load_alignments, requiredAbi=sorted(required),
                  missingPreviousExports=sorted(previous - exports), addedExports=sorted(exports - previous))
    (stage / f"{name}-build.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (stage / f"{name}-exports.txt").write_text("\n".join(sorted(exports)) + "\n", encoding="utf-8")
    (stage / f"{name}-elf.txt").write_text(elf + "\n", encoding="utf-8")
    if args.install:
        expected_hash = manifest.get("librarySha256")
        if not isinstance(expected_hash, str) or not re.fullmatch(r"[0-9a-f]{64}", expected_hash):
            raise ValueError("Manifest must pin librarySha256 before installation")
        if report["librarySha256"] != expected_hash:
            raise ValueError("Built library differs from pinned librarySha256; installed library unchanged")
        # Verify the copied staging file and the final destination, not only the compiler output.
        if sha256(destination) != expected_hash:
            raise ValueError("Staged library changed before installation; installed library unchanged")
        with tempfile.NamedTemporaryFile(dir=installed.parent, prefix="libsiglus-", suffix=".so.tmp",
                                         delete=False) as output:
            pending = Path(output.name)
        try:
            shutil.copy2(destination, pending)
            if sha256(pending) != expected_hash:
                raise ValueError("Installation copy SHA-256 differs; installed library unchanged")
            pending.replace(installed)
            if sha256(installed) != expected_hash:
                raise ValueError("Installed library SHA-256 differs from the verified staged library")
        finally:
            pending.unlink(missing_ok=True)
    print(f"Verified {len(required)} shared ABI exports; staged: {destination}")
    print(f"SHA256: {report['librarySha256']}")


if __name__ == "__main__":
    main()