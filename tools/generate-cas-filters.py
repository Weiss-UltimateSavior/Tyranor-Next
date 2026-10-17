#!/usr/bin/env python3
"""Regenerate the pinned cas GLES hooks without other optional filter sources."""
import argparse
import hashlib
import json
from pathlib import Path

REVISION = "9fabcc9a2c45f958aff55ddfda337e74ef894b7f"
LEVELS = [("low", 0.2), ("standard", 0.4), ("high", 0.8)]

def digest(data):
    return hashlib.sha256(data).hexdigest()


def checked(source, relative):
    origin = json.loads((source / "origin.json").read_text(encoding="utf-8"))
    expected = REVISION
    if origin["revision"] != expected:
        raise ValueError("Unexpected source revision")
    tree = json.loads((source / "tree.json").read_text(encoding="utf-8"))["tree"]
    blob = next(entry["sha"] for entry in tree if entry["path"] == relative)
    data = (source / relative).read_bytes()
    if hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest() != blob:
        raise ValueError("Source differs from fixed Git blob: " + relative)
    return data, {"path": relative, "gitBlob": blob, "sha256": digest(data)}


def hook(description, binds, save, body):
    return ("//!DESC " + description + "\n//!HOOK MAIN\n"
            + "".join("//!BIND " + name + "\n" for name in binds)
            + ("//!SAVE " + save + "\n" if save else "") + body + "\n")


def cas(source):
    a, a_info = checked(source, "ffx-cas/ffx_a.h")
    c, c_info = checked(source, "ffx-cas/ffx_cas.h")
    results = []
    # Retain the upstream MIT headers and actual CasSetup/CasFilter algorithms.
    # GLES 3.0 lacks desktop bitfieldExtract/Insert; these helpers are not CAS math.
    adapted_a = a.decode("utf-8").replace(
        "bitfieldExtract(src,ASU1(off),ASU1(bits))",
        "((src >> off) & (bits >= 32u ? 0xffffffffu : ((1u << bits)-1u)))").replace(
        "bitfieldInsert(src,ins,0,ASU1(bits))",
        "ABfi(src,ins,bits >= 32u ? 0xffffffffu : ((1u << bits)-1u))")
    common = "#define A_GPU 1\n#define A_GLSL 1\n" + adapted_a
    common += "\nAF3 CasLoad(ASU2 p){return texelFetch(u_MAIN,clamp(p,ivec2(0),ivec2(MAIN_size)-ivec2(1)),0).rgb;}\n"
    common += "void CasInput(inout AF1 r,inout AF1 g,inout AF1 b){}\n" + c.decode("utf-8").replace("const1[3]=0;", "const1[3]=0u;")
    for scaling in [False, True]:
        for level, sharpness in LEVELS:
            name = ("CAS_Scaling_" if scaling else "CAS_") + level
            body = common + ("\nvec4 hook(){\n AU4 c0,c1;\n"
                f" CasSetup(c0,c1,{sharpness},MAIN_size.x,MAIN_size.y,OUTPUT_size.x,OUTPUT_size.y);\n"
                f" AF3 rgb; CasFilter(rgb.r,rgb.g,rgb.b,AU2(gl_FragCoord.xy),c0,c1,{'false' if scaling else 'true'});\n"
                " return vec4(rgb,1.0);\n}\n")
            results.append((name, hook(name, ["MAIN"], None, body)))
    body = common + ("\nuniform float u_SHARPNESS;\nuniform bool u_OUTPUT_SIZED;\n"
        "vec4 hook(){ AU4 c0,c1;\n"
        " CasSetup(c0,c1,u_SHARPNESS,MAIN_size.x,MAIN_size.y,OUTPUT_size.x,OUTPUT_size.y);\n"
        " AF3 rgb; CasFilter(rgb.r,rgb.g,rgb.b,AU2(gl_FragCoord.xy),c0,c1,!u_OUTPUT_SIZED);\n"
        " return vec4(rgb,1.0);\n}\n")
    results.append(("CAS", hook("CAS configurable sharpness", ["MAIN"], None, body)))
    return results, [a_info, c_info]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sources", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    files, inputs = cas(args.sources)
    generated = [("cas/" + name + ".glsl", text.encode("utf-8")) for name, text in files]
    license_data, license_info = checked(args.sources, "LICENSE.txt")
    generated.append(("cas/LICENSE-MIT", license_data))
    manifest = {"sources": [{"repository": "https://github.com/GPUOpen-Effects/FidelityFX-CAS", "revision": REVISION,
        "license": {"spdx": "MIT", **license_info}, "files": inputs}], "generated": []}
    for relative, data in generated:
        output = root / "engine/src/main/assets" / relative
        if args.check:
            if output.read_bytes() != data: raise ValueError("Generated asset differs: " + relative)
        else:
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(data)
        manifest["generated"].append({"file": relative, "sha256": digest(data)})
    path = root / "engine/patches/cas-filters.json"
    if args.check:
        if json.loads(path.read_text(encoding="utf-8")) != manifest: raise ValueError("Generated manifest differs")
    else:
        path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8", newline="\n")
    print("Verified" if args.check else "Generated", len(generated), "assets")

if __name__ == "__main__":
    main()
