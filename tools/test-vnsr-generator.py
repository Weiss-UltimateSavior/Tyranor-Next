#!/usr/bin/env python3
"""Check that reproducibility verification fails closed when provenance is edited."""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


def check_retained_mrt_guards():
    """Verify retained_bytes accepts valid upstream gles-mrt exports and rejects malformed inputs."""
    root = Path(__file__).resolve().parents[1]
    import importlib.util
    spec = importlib.util.spec_from_file_location(
        "generate_vnsr_filters", root / "tools" / "generate-vnsr-filters.py")
    gen = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(gen)
    name = "vnsr-c08-x2.glsl"
    raw = (root / "engine/patches/vnsr/sources" / name).read_bytes()
    assert gen.retained_bytes(raw) == raw, name

    for label, needle, repl, expected_msg in (
        ("missing reconstruction index", b"int index=phase.y*2+phase.x;", b"int index=0;", "Unexpected reconstruction"),
        ("missing void hook", b"void hook() {", b"vec4 hook() {", "Expected GLES3 MRT fused shader body"),
        ("missing fragColor0", b"fragColor0=", b"return ", "Expected GLES3 MRT fused shader body"),
    ):
        assert needle in raw, label
        mutated = raw.replace(needle, repl)
        try:
            gen.retained_bytes(mutated)
        except ValueError as failure:
            assert expected_msg in str(failure), (label, failure)
        else:
            raise AssertionError(f"{label} must fail with ValueError")
    print("PASS: retained MRT structural guards")


def main():
    """Mutate isolated copies; never alter the working tree's pinned records."""
    check_retained_mrt_guards()
    root = Path(__file__).resolve().parents[1]
    with tempfile.TemporaryDirectory(prefix="vnsr-generator-") as temp:
        target = Path(temp)
        for folder in ("engine/src/main/assets/vnsr", "engine/patches/vnsr/sources"):
            shutil.copytree(root/folder,target/folder)
        (target/"tools").mkdir()
        shutil.copyfile(root/"tools/generate-vnsr-filters.py",target/"tools/generate-vnsr-filters.py")
        manifest_path = target/"engine/patches/vnsr-filters.json"
        original = (root/"engine/patches/vnsr-filters.json").read_text(encoding="utf-8")
        parsed = json.loads(original)
        names = [Path(item["localPath"]).name for item in parsed["sources"][0]["files"]]
        cases = [
            "valid", "license id", "license hash", "retained gate",
            "repository", "revision", "version", "asset directory", "license path",
            "license local path", "extra source", "license bytes",
            "generated entry dropped", "generated entry duplicated"
        ]
        for idx, entry in enumerate(parsed["generated"]):
            cases.append(f"generated hash:{idx}:{entry['file']}")
        for idx, entry in enumerate(parsed["sources"][0]["files"]):
            cases.append(f"source path:{idx}:{entry['path']}")
            cases.append(f"source hash:{idx}:{entry['path']}")
        for name in names:
            cases.append(f"asset bytes:{name}")
        for name in names:
            cases.append(f"source bytes:{name}")
        cases.append(f"asset missing:{names[0]}")

        for kind in cases:
            data = json.loads(original)
            source = data["sources"][0]
            if kind.startswith("source path:"):
                idx = int(kind.split(":")[1])
                del source["files"][idx]["localPath"]
            elif kind == "license id": source["license"]["spdx"] = "GPL-3.0-only"
            elif kind == "license hash": source["license"]["sha256"] = "0"*64
            elif kind == "retained gate": del source["verification"]
            elif kind == "repository": source["repository"] = "https://github.com/AAAA1111-Lab/vnsr-mirror"
            elif kind == "revision": source["revision"] = "0"*40
            elif kind == "version": source["version"] = "0.2.0"
            elif kind == "asset directory": data["assetDirectory"] = "vnsr-rewritten"
            elif kind == "license path": source["license"]["path"] = "LICENSE.txt"
            elif kind == "license local path": source["license"]["localPath"] = "engine/src/main/assets/vnsr/LICENSE-OTHER"
            elif kind == "extra source": data["sources"].append({"description": "descriptive only", "verification": "external-only"})
            elif kind == "generated entry dropped": data["generated"] = data["generated"][:-1]
            elif kind == "generated entry duplicated": data["generated"].append(dict(data["generated"][0]))
            elif kind.startswith("generated hash:"):
                idx = int(kind.split(":")[1])
                data["generated"][idx]["sha256"] = "0"*64
            elif kind.startswith("source hash:"):
                idx = int(kind.split(":")[1])
                source["files"][idx]["sha256"] = "0"*64
            manifest_path.write_text(json.dumps(data), encoding="utf-8")

            # Restore pinned bytes so each kind fails only for its own tampered surface.
            assets_dir = target/"engine/src/main/assets/vnsr"
            sources_dir = target/"engine/patches/vnsr/sources"
            shutil.copyfile(root/"engine/src/main/assets/vnsr/LICENSE-MIT", assets_dir/"LICENSE-MIT")
            for name in names:
                shutil.copyfile(root/"engine/patches/vnsr/sources"/name, sources_dir/name)
                shutil.copyfile(root/"engine/src/main/assets/vnsr"/name, assets_dir/name)

            if kind.startswith("asset bytes:"):
                tname = kind.split(":", 1)[1]
                (assets_dir/tname).write_bytes((assets_dir/tname).read_bytes()+b" ")
            elif kind.startswith("source bytes:"):
                tname = kind.split(":", 1)[1]
                (sources_dir/tname).write_bytes((sources_dir/tname).read_bytes()+b" ")
            elif kind == "license bytes":
                (assets_dir/"LICENSE-MIT").write_bytes((assets_dir/"LICENSE-MIT").read_bytes()+b" ")
            elif kind.startswith("asset missing:"):
                tname = kind.split(":", 1)[1]
                (assets_dir/tname).unlink()

            result = subprocess.run([sys.executable,str(target/"tools/generate-vnsr-filters.py"),"--check"],capture_output=True,text=True)
            assert (result.returncode == 0) == (kind == "valid"), (kind,result.stdout,result.stderr)
            if kind != "valid":
                err = (result.stderr or result.stdout).strip()
                assert err, f"Expected diagnostic error message for tampered case {kind}"
            print("PASS: "+kind)

        # Verify generate idempotency: running generator twice leaves pinned bytes intact and --check passes.
        manifest_path.write_text(original, encoding="utf-8")
        assets_dir = target/"engine/src/main/assets/vnsr"
        sources_dir = target/"engine/patches/vnsr/sources"
        shutil.copyfile(root/"engine/src/main/assets/vnsr/LICENSE-MIT", assets_dir/"LICENSE-MIT")
        for name in names:
            shutil.copyfile(root/"engine/patches/vnsr/sources"/name, sources_dir/name)
            shutil.copyfile(root/"engine/src/main/assets/vnsr"/name, assets_dir/name)
        for run_idx in range(2):
            gen_res = subprocess.run([sys.executable,str(target/"tools/generate-vnsr-filters.py")],capture_output=True,text=True)
            assert gen_res.returncode == 0, (run_idx, gen_res.stdout, gen_res.stderr)
        chk_res = subprocess.run([sys.executable,str(target/"tools/generate-vnsr-filters.py"),"--check"],capture_output=True,text=True)
        assert chk_res.returncode == 0, (chk_res.stdout, chk_res.stderr)
        print("PASS: generator idempotency")


if __name__ == "__main__":
    main()
