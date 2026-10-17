#!/usr/bin/env python3
"""Generate original, deterministic BGI format smoke data (not a real game)."""

import argparse
import binascii
import hashlib
import json
from pathlib import Path
import struct
import zlib


def _make_render_module():
    """Assemble original BP against the current public parser/native contracts.

    Evidence (ethornell-rs crates, source inspection; not an execution claim):
    - script/src/lib.rs::parse_bp_program: 00=i8, 01=i16, 02=i32;
      05=i16 displacement FROM OPCODE ADDRESS, strings after final 17/ret.
    - vm/src/lib.rs::{execute_instruction,take_dispatch_call_frame}: parameters
      are pushed in source order; native results are pushed back. move_arg
      pops POINTER then VALUE (unlike move), load width 2 reads a DWORD.
    - vm/src/lib.rs::tests::mediation_program_registration_and_call_preserve_the_native_stack:
      load_base / add / store_base reserves locals; 04 displacement addresses
      frame_base-displacement; 0a 02 stores a native result without leaving it.
    - vm/src/native_call.rs: Graph90:10(bitmap, archive, resource);
      50()->sprite; 56(sprite,x,y,bitmap,blend,alpha,priority);
      54(sprite,enabled); 00(full_redraw). None of these take an argc immediate.
    - app/src/native_graph.rs and tests::sprite_draw_gate_does_not_latch_indicator_hidden_across_bitmap_replacement:
      a fresh sprite's draw gate is FALSE; blend=1, transparency=0 is opaque.
    - app/src/lib.rs::{graph_sync_load_active,call_graph}: 90:07 hold=1000
      makes the first slash-free 90:10 synchronous in this scheduler pass.
    """
    code, fixups = bytearray(), []

    def integer(value):
        if -128 <= value <= 127:
            code.extend(b"\x00" + struct.pack("<b", value))
        elif -32768 <= value <= 32767:
            code.extend(b"\x01" + struct.pack("<h", value))
        else:
            code.extend(b"\x02" + struct.pack("<i", value))

    def string(name):
        fixups.append((len(code), name))
        code.extend(b"\x05\0\0")

    def graph(selector, *args):
        for value in args:
            integer(value)
        code.extend(bytes((0x90, selector)))

    def sprite():
        code.extend(bytes.fromhex("04 04 00 08 02"))  # load local DWORD

    # One 4-byte local holds the RETURNED sprite handle; no guessed 0x80000000.
    code.extend(bytes.fromhex("10 00 04 20 11"))  # base += 4
    graph(0x03, 1024 * 1024)  # bitmap memory budget: checker uses 262144 bytes
    graph(0x01, 1)           # graph scheduler gate
    graph(0x08, 1)           # display enabled
    graph(0x07, 1000)        # first resource load synchronous, not an async wait
    integer(1)              # bitmap slot 1
    string("system.arc")
    string("checker.png")
    graph(0x10)             # consumes (1, "system.arc", "checker.png")
    graph(0x50)             # no args -> actual sprite handle
    code.extend(bytes.fromhex("04 04 00 0a 02"))  # store result in local
    sprite()
    graph(0x56, 512, 232, 1, 1, 0, 2176)  # native-sized 256x256, opaque
    sprite()
    graph(0x54, 1)          # show through the real sprite draw gate
    graph(0x00, 1)          # coalesced full redraw request
    code.extend(bytes.fromhex("10 00 04 21 11 17"))  # base -= 4; top-level ret
    for name in ("system.arc", "checker.png"):
        target = len(code)
        code.extend(name.encode("ascii") + b"\0")
        for offset, referred_name in fixups:
            if referred_name == name:
                struct.pack_into("<h", code, offset + 1, target - offset)
    return bytes(code)


USER_DATA_MARKER = bytes.fromhex("42 47 49 2d 53 4d 4f 4b 45 2d 56 31 00 a5 5a 01")
USER_DATA_OFFSET = 64


def _make_user_data_module(phase):
    """Original BP using the REAL VM GDB serializer, not a fixture-made save.

    Source contracts checked in current ethornell-rs:
    vm/src/lib.rs::try_builtin_sys_with_api:
      80:80 () -> window_x, window_y, status; 80:81 () -> bool;
      80:82 (offset, src_ptr, length); 80:83 (dst_ptr, offset, length).
    vm/src/user_data.rs::{save_global_user_data,load_global_user_data,
      copy_to_global_data,copy_from_global_data,serialize_global_data} and
      tests::global_payload_round_trips_memory_names_and_flags.
    vm/src/lib.rs::execute_instruction: 0b count/payload consumes pointer;
      15 predicate pops destination THEN condition (0=nonzero, 1=zero);
      02 absolute parser byte offset + 14 jumps to a real instruction label.
    app/src/lib.rs::{write_file_bytes,read_user_file_bytes} and
      file_policy.rs::{parent,read_user}: unprefixed BGI.gdb is under user_root.

    Actual Rust/Android execution remains a separate acceptance step. No save
    bytes are serialized here, in Python tests, or in the generated resources.
    """
    if phase not in ("write", "read"):
        raise ValueError("unknown user-data phase")
    code, labels, branches, strings = bytearray(), {}, [], []
    frame_size = 32  # [0..16) payload, 16=x, 20=y, 24=status

    def integer(value):
        if -128 <= value <= 127:
            code.extend(b"\x00" + struct.pack("<b", value))
        elif -32768 <= value <= 32767:
            code.extend(b"\x01" + struct.pack("<h", value))
        else:
            code.extend(b"\x02" + struct.pack("<i", value))

    def pointer(offset):
        code.extend(b"\x04" + struct.pack("<H", frame_size - offset))

    def store(offset):
        pointer(offset)
        code.extend(b"\x0a\x02")  # move_arg: pop pointer then value

    def load(offset):
        pointer(offset)
        code.extend(b"\x08\x02")

    def target(label):
        branches.append((len(code), label))
        code.extend(b"\x02\0\0\0\0")

    def branch(label, predicate):
        target(label)
        code.extend(bytes((0x15, predicate)))

    def title(text):
        strings.append((len(code), text))
        code.extend(b"\x05\0\0\x80\x66")

    def outcome(label, text):
        labels[label] = len(code)
        title(text)
        target("finish")
        code.append(0x14)

    def status_from_global_load():
        code.extend(b"\x80\x80")
        store(24)  # last output = status, then window_y, then window_x
        store(20)
        store(16)

    code.extend(bytes.fromhex("10 00 20 20 11"))  # base += 32
    status_from_global_load()
    load(24)
    if phase == "write":
        integer(1)  # preflight requires missing file, not an existing save
        code.append(0x30)  # eq
        branch("preexisting", 1)
        pointer(0)
        code.extend(bytes((0x0b, len(USER_DATA_MARKER))) + USER_DATA_MARKER)
        integer(USER_DATA_OFFSET)
        pointer(0)
        integer(len(USER_DATA_MARKER))
        code.extend(b"\x80\x82\x80\x81")
        branch("save_failed", 1)  # actual VM serializer's boolean return
        outcome("success", "ETHORNELL_USER_DATA_WRITE_OK")
        outcome("preexisting", "ETHORNELL_USER_DATA_WRITE_REFUSED_EXISTING")
        outcome("save_failed", "ETHORNELL_USER_DATA_WRITE_FAILED")
    else:
        branch("load_failed", 0)  # missing=1, decode/format failure=2
        pointer(0)
        integer(USER_DATA_OFFSET)
        integer(len(USER_DATA_MARKER))
        code.extend(b"\x80\x83")
        # Compare actual read-back DWORDs, not a host-written success marker.
        for offset in range(0, len(USER_DATA_MARKER), 4):
            load(offset)
            integer(struct.unpack_from("<i", USER_DATA_MARKER, offset)[0])
            code.append(0x30)  # eq
            branch("mismatch", 1)
        outcome("success", "ETHORNELL_USER_DATA_READ_OK")
        outcome("load_failed", "ETHORNELL_USER_DATA_READ_LOAD_FAILED")
        outcome("mismatch", "ETHORNELL_USER_DATA_READ_MISMATCH")
    labels["finish"] = len(code)
    integer(1)
    code.extend(bytes.fromhex("80 68 80 69 10 00 20 21 11 17"))
    # 68=allow native close, 69=post close; HostRuntime emits Title before
    # processing pending close. Both pass AND fail close: Title is mandatory.
    for offset, label in branches:
        struct.pack_into("<I", code, offset + 1, 16 + labels[label])
    for offset, text in strings:
        target_offset = len(code)
        code.extend(text.encode("ascii") + b"\0")
        struct.pack_into("<h", code, offset + 1, target_offset - offset)
    return bytes(code)


PROFILES = {
    "ret": bytes.fromhex("17"),
    "request-close": bytes.fromhex("80 69 17"),
    "render": _make_render_module(),
    "user-data-write": _make_user_data_module("write"),
    "user-data-read": _make_user_data_module("read"),
}


def make_bp(profile="ret"):
    # ethornell-script::detect_code_range: header_size + module_size == file size.
    code = PROFILES[profile]
    return struct.pack("<IIII", 16, len(code), 0, 0) + code


def parse_bp(data):
    if len(data) < 17:
        raise ValueError("short fixture BP")
    header, size, reserved1, reserved2 = struct.unpack_from("<IIII", data)
    if header != 16 or size != len(data) - header or reserved1 or reserved2:
        raise ValueError("invalid fixture BP header")
    code = data[header:]
    if code not in PROFILES.values():
        raise ValueError("unknown fixture instruction sequence")
    return {"header_size": header, "module_size": size, "code_hex": code.hex()}


def png_chunk(kind, payload):
    return (struct.pack(">I", len(payload)) + kind + payload
            + struct.pack(">I", binascii.crc32(kind + payload) & 0xffffffff))


def make_checker_png(size=4):
    # Keep the existing no-argument 4x4 fixture bytes; render uses 256x256 so
    # probes stay far from texel boundaries even in a downscaled viewport.
    if not isinstance(size, int) or size < 2 or size > 1024 or size % 2:
        raise ValueError("checker size must be an even integer in 2..1024")
    colors = [(255, 0, 0, 255), (0, 255, 0, 255),
              (0, 0, 255, 255), (255, 255, 255, 255)]
    half = size // 2
    rows = b"".join(b"\0" + b"".join(bytes(colors[(y // half) * 2 + x // half])
                                    for x in range(size)) for y in range(size))
    return (b"\x89PNG\r\n\x1a\n"
            + png_chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
            + png_chunk(b"IDAT", zlib.compress(rows, 9)) + png_chunk(b"IEND", b""))


def render_expectation():
    """Readback contract for a future *real* VM/GPU harness, not test results."""
    return {
        "verification_status": "source-contract-derived; Rust VM and GPU execution pending",
        "logical_canvas": [1280, 720],
        "recommended_surface_px": [1280, 720],
        "sprite_rect_xywh": [512, 232, 256, 256],
        "texture_size": [256, 256],
        "background_rgba": [0, 0, 0, 255],
        "probes": [
            {"logical_xy": [576, 296], "expected_rgba": [255, 0, 0, 255]},
            {"logical_xy": [704, 296], "expected_rgba": [0, 255, 0, 255]},
            {"logical_xy": [576, 424], "expected_rgba": [0, 0, 255, 255]},
            {"logical_xy": [704, 424], "expected_rgba": [255, 255, 255, 255]},
            {"logical_xy": [128, 128], "expected_rgba": [0, 0, 0, 255]},
        ],
        "viewport": {
            "origin": "top-left; Surface physical pixels, not dp",
            "scale": "max(0.01, min(surface_width / 1280, surface_height / 720))",
            "offset_x": "(surface_width - 1280 * scale) / 2",
            "offset_y": "(surface_height - 720 * scale) / 2",
            "probe_mapping": "surface_xy = offset_xy + logical_xy * scale",
            "letterbox_rgba": [0, 0, 0, 255],
            "readback": "Normalize BGRA/RGBA and bottom-up GPU rows to top-left RGBA before comparison",
        },
        "suggested_channel_tolerance": 3,
        "suggested_probe_patch_px": [3, 3],
        "expected_native_calls": [
            {"opcode": "90:03", "args": [1048576]},
            {"opcode": "90:01", "args": [1]},
            {"opcode": "90:08", "args": [1]},
            {"opcode": "90:07", "args": [1000]},
            {"opcode": "90:10", "args": [1, "system.arc", "checker.png"]},
            {"opcode": "90:50", "args": [], "result": "sprite handle H"},
            {"opcode": "90:56", "args": ["H", 512, 232, 1, 1, 0, 2176]},
            {"opcode": "90:54", "args": ["H", 1]},
            {"opcode": "90:00", "args": [1]},
        ],
        "expected_vm": "Top-level ret: VmStopReason::Completed with halted=true, empty operand stack, frame base restored; no close request",
        "step_protocol": [
            "Create with resource_root=this fixture and a separate writable user_root; provide a valid font even though no text is drawn",
            "Attach a real Surface, clear pause reasons; explicit silent mode is appropriate for this audio-free fixture",
            "step(t0) establishes time baseline; step(t0 + 16000000ns) should execute the synchronous bootstrap and present",
            "Require successful VM/native calls AND real pixel readback matching all probes; black-only/non-crashing is failure",
            "No C ABI VM-completion event is assumed; independently observe report/diagnostics in HostRuntime harness",
        ],
        "evidence": [
            "ethornell-script/src/lib.rs::{parse_bp_program,detect_bp_code_end}",
            "ethornell-vm/src/lib.rs::{take_dispatch_call_frame,execute_instruction}",
            "ethornell-vm/src/lib.rs::tests::mediation_program_registration_and_call_preserve_the_native_stack",
            "ethornell-vm/src/native_call.rs::{GRAPH90_LOAD_BITMAP_PARAMETERS,GRAPH90_CONFIGURE_SPRITE_SINGLE_BITMAP_PARAMETERS}",
            "ethornell-app/src/lib.rs::{graph_sync_load_active,call_graph,load_graph_image_resource_target}",
            "ethornell-app/src/lib.rs::tests::sprite_draw_gate_does_not_latch_indicator_hidden_across_bitmap_replacement",
            "ethornell-app/src/native_graph.rs::{graph90_allocate_object,graph90_sync_sprite_primary_layer}",
            "ethornell-image/src/lib.rs::{decode_image,decode_standard_image}",
            "ethornell-app/src/frame_presenter.rs::{prepare_frame,compose_commands}",
            "ethornell-app/src/host_runtime.rs::HostRuntime::step_inner",
            "ethornell-render/src/lib.rs::viewport_transform",
        ],
    }


def user_data_expectation(phase):
    """Two-session VM-serialization protocol; not PC-format certification."""
    if phase not in ("write", "read"):
        raise ValueError("unknown user-data phase")
    return {
        "verification_status": "source-contract-derived; real Rust VM serialization/reopen execution pending",
        "phase": phase,
        "serializer": "VM Sys80:81 -> serialize_global_data -> encode_sdc -> SysApi::write_file_bytes",
        "loader": "VM Sys80:80 -> SysApi::read_user_file_bytes -> decode_sdc -> install_global_data",
        "guest_file": "BGI.gdb",
        "host_file": "<same HostConfig.user_root>/BGI.gdb",
        "path_policy": "Unprefixed user-file hooks resolve under user_root (equivalent to bgi-user/BGI.gdb), NOT resource_root or bgi-resource/UserData",
        "marker_global_offset": USER_DATA_OFFSET,
        "marker_hex": USER_DATA_MARKER.hex(),
        "success_events": [
            {"kind": 4, "code": 0, "text": f"ETHORNELL_USER_DATA_{phase.upper()}_OK"},
            {"kind": 3, "code": 0, "text": ""},
        ],
        "failure_title_prefix": f"ETHORNELL_USER_DATA_{phase.upper()}_",
        "failure_titles": (["ETHORNELL_USER_DATA_WRITE_REFUSED_EXISTING", "ETHORNELL_USER_DATA_WRITE_FAILED"]
                           if phase == "write" else
                           ["ETHORNELL_USER_DATA_READ_LOAD_FAILED", "ETHORNELL_USER_DATA_READ_MISMATCH"]),
        "save_content_expectation": {
            "outer_magic_hex": b"SDC FORMAT 1.00\0".hex(),
            "outer_header_bytes": 32,
            "decoded_magic_hex": b"BURIKO GDB 3.00\0".hex(),
            "decoded_marker_offset": 0x424 + USER_DATA_OFFSET,
            "encoding": "Current Rust literal-block SDC serializer, checksum + obfuscation; not a raw marker file",
            "pc_compatibility": "NOT certified; no original PC save interchange or game compatibility claim",
        },
        "protocol": [
            "Use generated write/read roots as separate resources; one fresh dedicated user_root shared across both sessions; never point either profile at real user saves",
            "Generator creates system.arc/fixture.json ONLY; no BGI.gdb, no user_root, no synthesized persisted bytes",
            "Write profile first calls 80:80 and requires missing=1; status=0/2 refuses existing/invalid saves without writing; empty/unreadable files may also appear missing, so a fresh test-only root is mandatory",
            "Attach a real Surface, unpause; step(t0), step(t0+16000000ns); provide a valid system/user font; silent mode is appropriate",
            "Require exact WRITE_OK Title then Closed; absent title, wrong title or native error is failure even if Closed arrives",
            "Destroy writer and release writer lock; observe VM-created SDC file under user_root and absence under resource_root; Java/Python must not create/replace the save",
            "Create a NEW HostRuntime/ABI session using read resource_root and the SAME unchanged user_root; attach Surface and advance active ticks",
            "Require exact READ_OK Title then Closed; read profile has NO save opcode and cannot create or repair BGI.gdb",
            "Negative control: read against a DIFFERENT fresh empty test-only user_root must emit READ_LOAD_FAILED, never READ_OK",
            "Optionally corrupt only a copy inside a newly allocated disposable test directory; expected LOAD_FAILED or MISMATCH, never alter user data",
        ],
        "evidence": [
            "ethornell-vm/src/lib.rs::{try_builtin_sys_with_api,execute_instruction,jump_target_index}",
            "ethornell-vm/src/user_data.rs::{copy_to_global_data,save_global_user_data,load_global_user_data,copy_from_global_data}",
            "ethornell-vm/src/user_data.rs::{serialize_global_data,install_global_data,encode_sdc,decode_sdc}",
            "ethornell-vm/src/user_data.rs::tests::global_payload_round_trips_memory_names_and_flags",
            "ethornell-app/src/lib.rs::{write_file_bytes,read_user_file_bytes,call_sys}",
            "ethornell-app/src/file_policy.rs::{parent,atomic_write,read_user}",
            "ethornell-app/src/host_runtime.rs::{step_inner,request_close}",
        ],
    }


def make_packfile(entries):
    if not entries or len(entries) > 1024:
        raise ValueError("invalid fixture entry count")
    index, payload, seen = bytearray(), bytearray(), set()
    for name, data in entries:
        encoded = name.encode("ascii")
        if (not encoded or len(encoded) >= 16 or name in seen
                or any(character in name for character in "\0/\\:")):
            raise ValueError("invalid fixture entry name")
        seen.add(name)
        index.extend(encoded.ljust(16, b"\0"))
        index.extend(struct.pack("<IIII", len(payload), len(data), 0, 0))
        payload.extend(data)
    return b"PackFile    " + struct.pack("<I", len(entries)) + index + payload


def parse_packfile(data):
    """Strict subset validator, not a general production archive decoder."""
    if len(data) < 16 or data[:12] != b"PackFile    ":
        raise ValueError("invalid PackFile header")
    count = struct.unpack_from("<I", data, 12)[0]
    base = 16 + count * 32
    if not 0 < count <= 1024 or base > len(data):
        raise ValueError("invalid PackFile index size")
    entries, cursor = {}, base
    for number in range(count):
        start = 16 + number * 32
        raw_name = data[start:start + 16]
        name = raw_name.split(b"\0", 1)[0].decode("ascii")
        relative, size, zero1, zero2 = struct.unpack_from("<IIII", data, start + 16)
        if (not name or len(name) >= 16 or name in entries
                or raw_name != name.encode().ljust(16, b"\0")
                or any(character in name for character in "\0/\\:")
                or zero1 or zero2 or base + relative != cursor
                or cursor + size > len(data)):
            raise ValueError("invalid/non-contiguous fixture entry")
        entries[name] = data[cursor:cursor + size]
        cursor += size
    if cursor != len(data):
        raise ValueError("trailing PackFile bytes")
    return entries


def generate(output, profile="ret"):
    bp = make_bp(profile)
    image = make_checker_png(256 if profile == "render" else 4)
    archive = make_packfile([("ipl._bp", bp), ("checker.png", image)])
    entries = parse_packfile(archive)
    parse_bp(entries["ipl._bp"])
    # Never overwrite a game directory, even if --output was selected by mistake.
    output.mkdir(parents=True, exist_ok=False)
    (output / "system.arc").write_bytes(archive)
    manifest = {
        "schema": 1, "fixture": "original-generated-bgi-smoke", "profile": profile,
        "origin": "Generated solely by tools/ethornell/generate_fixture.py; no commercial assets",
        "format_evidence": ["ethornell-archive/src/lib.rs::read_packfile_index",
                            "ethornell-script/src/lib.rs::detect_code_range",
                            "ethornell-script/src/vm_opcode.rs::0x17",
                            "ethornell-vm/src/native_call.rs::SYS_REQUEST_WINDOW_CLOSE"],
        "expected": ("BP ret ends top-level VM; not proof of host closed event"
                     if profile == "ret" else
                     "Sys80:69 posts close request, then ret; host close-mode policy applies"),
        "limitations": ["Not a real game or compatibility evidence", "No text/audio/save coverage",
                        "checker.png is not referenced by BP; no texture-display claim",
                        "No clear/redraw/present assertion", "Runtime execution not certified by generation"],
        "files": {"system.arc": {"size": len(archive), "sha256": hashlib.sha256(archive).hexdigest()}},
        "entries": {name: {"size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                    for name, data in entries.items()},
        "bp": parse_bp(bp),
    }
    if profile == "render":
        manifest["expected"] = (
            "Load checker.png through Graph90:10; create/configure/show a real native Sprite; "
            "request redraw and ret; expect four colored quadrants on black after an active host tick")
        manifest["limitations"] = [
            "Not a real game or compatibility evidence", "No text/audio/save coverage",
            "PNG is a portable ethornell-rs input, not a claim that original BGI supports PNG",
            "Source-contract expectation only; generation/Python tests do not certify Rust VM or GPU execution",
        ]
        manifest["raw_bp_hex"] = bp.hex()
        manifest["render_expectation"] = render_expectation()
    elif profile.startswith("user-data-"):
        phase = profile.removeprefix("user-data-")
        manifest["expected"] = (
            f"VM global user-data {phase}; exact phase-specific Title then Closed required; "
            "two NEW sessions sharing one fresh test-only user_root prove reopen semantics")
        manifest["limitations"] = [
            "Synthetic VM serialization fixture, not a real game or PC-save compatibility evidence",
            "No persistence is performed by generation or Python structural tests; real VM execution pending",
            "No graphics/audio/text/save-manager import-export coverage",
            "Only use a fresh dedicated user_root; never run writer over real saves",
        ]
        manifest["raw_bp_hex"] = bp.hex()
        manifest["user_data_expectation"] = user_data_expectation(phase)
    (output / "fixture.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="new directory, must not already exist")
    parser.add_argument("--profile", choices=PROFILES, default="ret")
    args = parser.parse_args()
    manifest = generate(args.output, args.profile)
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
