#!/usr/bin/env python3
"""Package pinned Impacto resources and validate the private ARM64 shared library.

The outer XZ stream contains a deterministic, uncompressed ZIP. Every entry is
listed and SHA-256 hashed in manifest.json for the separate atomic installer.
Use --resources-only before the native build completes.
"""
import argparse
import hashlib
import json
import lzma
import re
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
META = ROOT / "engine/impacto"
ASSETS = ROOT / "engine/src/main/assets/impacto-runtime"


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def run(*args):
    return subprocess.check_output(args, text=True)


def package_resources(source):
    lock = json.loads((META / "source-lock.json").read_text())
    actual_revision = run("git", "-C", str(source), "rev-parse", "HEAD").strip()
    if actual_revision != lock["impacto"]["revision"]:
        raise RuntimeError("Impacto revision differs from source-lock.json")
    inputs = {}
    for name in run("git", "-C", str(source), "ls-files", "-z", "resources", "profiles", "src/shaders").split("\0"):
        if not name:
            continue
        target = name.removeprefix("src/") if name.startswith("src/shaders/") else name
        item = source / name
        if not item.is_file() or item.is_symlink():
            raise RuntimeError(f"Not a regular tracked resource: {name}")
        # These assets are shipped unmodified under the upstream repository's
        # stated license scope. Detect accidental local resource modification.
        expected = run("git", "-C", str(source), "rev-parse", f"HEAD:{name}").strip()
        actual = run("git", "-C", str(source), "hash-object", str(item)).strip()
        if actual != expected:
            raise RuntimeError(f"Modified upstream resource: {name}")
        inputs[target] = item
    for item in sorted((META / "licenses").rglob("*")):
        if item.is_file():
            inputs["licenses/" + item.relative_to(META / "licenses").as_posix()] = item
    files = [{"path": name, "size": p.stat().st_size, "sha256": digest(p)} for name, p in sorted(inputs.items())]
    if not files or len(files) > 4096 or sum(f["size"] for f in files) > 512 * 1024**2:
        raise RuntimeError("Resource manifest exceeds installer bounds")
    if max(f["size"] for f in files) > 128 * 1024**2:
        raise RuntimeError("Resource file exceeds installer bound")
    ASSETS.mkdir(parents=True, exist_ok=True)
    archive = ASSETS / "resources.zip.xz"
    with tempfile.TemporaryDirectory(prefix="impacto-resources-") as temp:
        zip_path = Path(temp) / "resources.zip"
        with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_STORED) as z:
            for entry in files:
                info = zipfile.ZipInfo(entry["path"], date_time=(1980, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = 0o100644 << 16
                z.writestr(info, inputs[entry["path"]].read_bytes())
        staged = Path(temp) / archive.name
        with zip_path.open("rb") as src, lzma.open(staged, "wb", preset=6, check=lzma.CHECK_CRC64) as dst:
            shutil.copyfileobj(src, dst)
        shutil.copyfile(staged, archive)
    manifest = {"version": 1, "generation": hashlib.sha256(canonical(files)).hexdigest(),
                "upstreamRevision": actual_revision,
                "archive": {"name": archive.name, "size": archive.stat().st_size, "sha256": digest(archive)},
                "files": files}
    write_json(ASSETS / "manifest.json", manifest)
    shutil.copyfile(META / "profiles.json", ASSETS / "profiles.json")
    print(f"Resources: {len(files)} files, {sum(f['size'] for f in files)} bytes, archive {archive.stat().st_size} bytes")
    return manifest


def inspect_native(binary, ndk):
    tool = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    readelf = tool / "llvm-readelf"
    headers = run(str(readelf), "-h", "-l", "-d", "--wide", str(binary))
    if "AArch64" not in headers or "DYN (Shared object file)" not in headers:
        raise RuntimeError("Expected AArch64 shared ELF")
    loads = [line.split() for line in headers.splitlines() if line.strip().startswith("LOAD ")]
    if not loads or any(int(line[-1], 16) < 16384 for line in loads):
        raise RuntimeError("ELF LOAD segments do not support 16 KiB pages")
    needed = sorted(re.findall(r"\(NEEDED\).*?\[(.*?)\]", headers))
    allowed_system = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so",
                      "libmediandk.so", "libOpenSLES.so", "libaaudio.so", "libvulkan.so",
                      "libGLESv1_CM.so", "libGLESv2.so", "libGLESv3.so", "libEGL.so", "libz.so"}
    if not set(needed) <= allowed_system or "(TEXTREL)" in headers:
        raise RuntimeError(f"Unexpected shared dependency or text relocation: {needed}")
    symbols = run(str(tool / "llvm-nm"), "--dynamic", "--defined-only", str(binary))
    if "Java_org_libsdl_impacto_SDLActivity_" not in symbols or "SDL_main" not in symbols:
        raise RuntimeError("Missing private SDL JNI entry points or SDL_main")
    if "Java_org_libsdl_app_" in symbols or "Java_org_libsdl3_app_" in symbols:
        raise RuntimeError("Shared SDL JNI namespace leaked into Impacto")
    strings = run(str(tool / "llvm-strings"), str(binary))
    if "org/libsdl/app/" in strings or "org/libsdl3/app/" in strings:
        raise RuntimeError("Shared SDL Java class registration leaked into Impacto")
    for name in ("SDLActivity", "SDLInputConnection", "SDLAudioManager", "SDLControllerManager", "HIDDeviceManager"):
        if "org/libsdl/impacto/" + name not in strings:
            raise RuntimeError(f"Missing private Java registration: {name}")
    return headers, needed


def package_native(build, ndk, manifest):
    candidates = list(build.glob("libimpacto.so"))
    if len(candidates) != 1:
        raise RuntimeError(f"Expected one built libimpacto.so, found {candidates}")
    binary = candidates[0]
    headers, needed = inspect_native(binary, ndk)
    tool = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    destination = ROOT / "engine/src/main/jniLibs/arm64-v8a/libimpacto.so"
    destination.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(tool / "llvm-strip"), "--strip-unneeded", "-o", str(destination), str(binary)], check=True)
    (META / "elf-report.txt").write_text(headers)
    lock = json.loads((META / "source-lock.json").read_text())
    provenance = {"version": 1, "library": destination.name, "size": destination.stat().st_size,
                  "sha256": digest(destination), "unstrippedSha256": digest(binary),
                  "resourceGeneration": manifest["generation"], "sourceLock": lock,
                  "patches": {p.name: digest(p) for p in sorted((META / "patches").glob("*.patch"))},
                  "validation": {"aarch64": True, "privateSdlNamespace": True, "elfLoadAlignment": 16384,
                                 "devicePlaybackTested": False, "replacementRelinkTested": False}}
    relink = META / "relink-report.json"
    if relink.exists():
        report = json.loads(relink.read_text())
        if report.get("result") == "passed" and report.get("originalUnstrippedRuntimeSha256") == digest(binary):
            provenance["validation"]["replacementRelinkTested"] = True
            provenance["relinkReportSha256"] = digest(relink)
    provenance["profilesSha256"] = digest(META / "profiles.json")
    provenance["javaSources"] = {p.name: digest(p) for p in sorted((ROOT / "engine/src/main/java/org/libsdl/impacto").glob("*.java"))}
    provenance["buildInputs"] = {str(p.relative_to(ROOT)): digest(p) for p in sorted((ROOT / "tools/impacto").glob("*.py"))}
    for p in [*sorted((ROOT / "tools/impacto").glob("*.sh")), META / "triplets/arm64-android-impacto.cmake"]:
        provenance["buildInputs"][str(p.relative_to(ROOT))] = digest(p)
    provenance["neededSystemLibraries"] = needed
    source_index = META / "sources/index.json"
    if source_index.exists():
        provenance["sourceIndexSha256"] = digest(source_index)
    cache = (build / "CMakeCache.txt").read_text()
    command = re.search(r"^CMAKE_COMMAND:INTERNAL=(.+)$", cache, re.M)
    provenance["buildTools"] = {"ndk": (ndk / "source.properties").read_text().strip()}
    if command:
        provenance["buildTools"]["topLevelCmake"] = run(command.group(1), "--version").splitlines()[0]
    versions = sorted((build.parent / "vcpkg/downloads/tools").glob("cmake-*/cmake-*/bin/cmake"))
    provenance["buildTools"]["vcpkgCmake"] = [run(str(c), "--version").splitlines()[0] for c in versions]
    write_json(META / "runtime-provenance.json", provenance)
    print(f"Native: {destination.stat().st_size} bytes, SHA256 {digest(destination)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--build", type=Path)
    parser.add_argument("--vcpkg", type=Path)
    parser.add_argument("--ndk", type=Path)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--resources-only", action="store_true")
    mode.add_argument("--native-only", action="store_true", help="Validate/copy the binary against the existing resource manifest")
    args = parser.parse_args()
    if args.native_only:
        manifest = json.loads((ASSETS / "manifest.json").read_text())
        archive = ASSETS / manifest["archive"]["name"]
        if digest(archive) != manifest["archive"]["sha256"]:
            raise RuntimeError("Existing resource archive checksum mismatch")
    else:
        manifest = package_resources(args.source.resolve())
    if not args.resources_only:
        if not args.build or not args.ndk:
            parser.error("--build and --ndk are required unless --resources-only")
        package_native(args.build.resolve(), args.ndk.resolve(), manifest)


if __name__ == "__main__":
    main()
