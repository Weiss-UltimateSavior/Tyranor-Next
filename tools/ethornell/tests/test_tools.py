"""No NDK, Rust build, device, commercial asset, or third-party Python needed."""

import argparse
import binascii
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import generate_fixture as fixture
import verify_native as native


HEADER = """#define ETHORNELL_ANDROID_ABI_VERSION 1u
/* int32_t ethornell_android_ignore(void); */
uint32_t ethornell_android_abi_version(void);
int32_t ethornell_android_step(
 uint64_t handle, uint64_t time);
"""


def elf(api=26, align=0x4000, relro_end=0x4000):
    data = bytearray(0x5000)
    data[:7] = b"\x7fELF\x02\x01\x01"
    struct.pack_into("<HH", data, 16, 3, 183)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 3)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, len(data), len(data), align)
    struct.pack_into("<IIQQQQQQ", data, 120, 0x6474e552, 4, 0x3000, 0x3000,
                     0x3000, 0x1000, relro_end - 0x3000, 1)
    note = struct.pack("<III", 8, 4, 1) + b"Android\0" + struct.pack("<I", api)
    struct.pack_into("<IIQQQQQQ", data, 176, 4, 4, 0x200, 0x200, 0x200, len(note), len(note), 4)
    data[0x200:0x200 + len(note)] = note
    return bytes(data)


def readelf_text(needed=(), exports=None):
    if exports is None:
        exports = ["ethornell_android_abi_version", "ethornell_android_step"]
    text = """ELF Header:
 Class: ELF64
 Data: 2's complement, little endian
 Type: DYN (Shared object file)
 Machine: AArch64
 LOAD 0x000000 0x000000 0x000000 0x005000 0x005000 R E 0x4000
 GNU_RELRO 0x003000 0x003000 0x003000 0x001000 0x001000 R 0x1
 Symbol table '.dynsym' contains 3 entries:
 0: 0000000000000000 0 NOTYPE LOCAL DEFAULT UND
 1: 0000000000001000 4 FUNC GLOBAL DEFAULT 1 ethornell_android_abi_version
"""
    # Construct exports independently of the boilerplate rows.
    text = text[:text.index(" 1:")]
    for index, name in enumerate(exports, 1):
        text += f" {index}: 0000000000001000 4 FUNC GLOBAL DEFAULT 1 {name}\n"
    for name in needed:
        text += f" 0x0000000000000001 (NEEDED) Shared library: [{name}]\n"
    return text


class HeaderTests(unittest.TestCase):
    def test_comments_multiline_and_version(self):
        abi = native.required_abi(HEADER)
        self.assertEqual(abi["version"], 1)
        self.assertEqual(len(abi["symbols"]), 2)

    def test_unrecognized_declaration_fails_closed(self):
        for declaration in ["void ethornell_android_new(void);", "int32_t ethornell_android_new(void)",
                            "extern void (*ethornell_android_new)(void);",
                            "int32_t ethornell_android_step(uint64_t, uint64_t);"]:
            with self.subTest(declaration=declaration), self.assertRaises(native.ValidationError):
                native.required_abi(HEADER + declaration)

    def test_missing_version(self):
        with self.assertRaises(native.ValidationError):
            native.required_abi(HEADER.replace("1u", "VERSION"))


class ElfTests(unittest.TestCase):
    def test_real_binary_note_and_segments(self):
        metadata = native.binary_metadata(elf())
        self.assertEqual(metadata["android_api_notes"], [26])
        self.assertEqual(metadata["segments"], native.parse_readelf(readelf_text())["segments"])
        self.assertEqual(native.alignment_errors(metadata["segments"]), [])

    def test_reject_wrong_arch_class_and_short_table(self):
        for data in [b"not an elf", elf()[:100], elf().replace(b"\xb7\0", b"\x3e\0", 1),
                     elf().replace(b"ELF\x02", b"ELF\x01", 1)]:
            with self.subTest(length=len(data)), self.assertRaises(native.ValidationError):
                native.binary_metadata(data)

    def test_truncated_note(self):
        data = bytearray(elf())
        struct.pack_into("<I", data, 0x204, 0xffff)
        with self.assertRaisesRegex(native.ValidationError, "note payload"):
            native.binary_metadata(data)

    def test_bad_load_and_relro_are_computed(self):
        for data, expected in [(elf(align=0x1000), "p_align"),
                               (elf(align=0x5000), "power of two"),
                               (elf(relro_end=0x5000), "end=0x5000")]:
            with self.subTest(expected=expected):
                errors = native.alignment_errors(native.binary_metadata(data)["segments"])
                self.assertTrue(any(expected in error for error in errors), errors)

    def test_load_congruence_and_memsize(self):
        segments = native.binary_metadata(elf())["segments"]
        segments[0]["offset"] = 4
        segments[0]["memsz"] = 1
        errors = native.alignment_errors(segments)
        self.assertTrue(any("congruent" in item for item in errors))
        self.assertTrue(any("filesz" in item for item in errors))

    def test_relro_missing_is_project_gate(self):
        segments = [native.binary_metadata(elf())["segments"][0]]
        self.assertIn("GNU_RELRO missing (project hardening gate)", native.alignment_errors(segments))

    def test_readelf_different_spacing_and_undefined(self):
        text = readelf_text(["libc.so"])
        text += " 3: 0000000000000000 0 NOTYPE GLOBAL DEFAULT UND android_main\n"
        result = native.parse_readelf(text)
        self.assertEqual(result["needed"], ["libc.so"])
        self.assertEqual(result["symbols"]["android_main"]["index"], "UND")

    def test_readelf_missing_or_unknown_rows_fail(self):
        for text in [readelf_text().replace("AArch64", "x86-64"),
                     readelf_text().replace("0x4000", "2**14"),
                     readelf_text().replace("Class: ELF64", ""),
                     readelf_text() + " (NEEDED) unknown format\n",
                     readelf_text() + " 3: unexpected new symbol format android_main\n"]:
            with self.subTest(text=text[-60:]), self.assertRaises(native.ValidationError):
                native.parse_readelf(text)

    def test_nm_posix_and_weak_undefined(self):
        self.assertEqual(native.parse_nm("foo T 1000 4\ngetrandom w 0 0\nbar U 0 0\n"),
                         {"foo": "T", "getrandom": "w", "bar": "U"})
        with self.assertRaises(native.ValidationError):
            native.parse_nm("unexpected nm warning words")

    def test_glue_defined_or_undefined_is_forbidden(self):
        for name in ["android_main", "ANativeActivity_onCreate", "ANativeActivity_finish",
                     "android_app_create", "GameActivity_onCreate", "_ZN13winit_android5entryE"]:
            self.assertTrue(native.forbidden_symbol(name), name)
        for name in ["ANativeWindow_acquire", "ethornell_android_step"]:
            self.assertFalse(native.forbidden_symbol(name), name)


class PolicyTests(unittest.TestCase):
    def test_system_and_explicitly_bundled_dependencies(self):
        self.assertEqual(native.dependency_errors(["libc.so", "libc++_shared.so"],
                                                 {"libc++_shared.so"}, ["libc++_shared.so"]), [])
        self.assertIn("absent", native.dependency_errors(["libx.so"], {}, ["libx.so"])[0])
        self.assertIn("not allowlisted", native.dependency_errors(["libx.so"], {"libx.so"}, [])[0])
        self.assertIn("unsafe", native.dependency_errors(["../libx.so"], {}, ["../libx.so"])[0])

    def test_ci_host_tool_paths(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp) / "toolchains/llvm/prebuilt/linux-x86_64/bin"
            directory.mkdir(parents=True)
            suffix = ".exe" if os.name == "nt" else ""
            for name in ["llvm-readelf", "llvm-nm"]:
                (directory / (name + suffix)).touch()
            self.assertEqual(native.resolve_tools(tmp, "linux-x86_64")["nm"].parent, directory)
            self.assertEqual(native.resolve_tools(tool_bin=directory)["nm"].parent, directory)
            with self.assertRaises(native.ValidationError):
                native.resolve_tools(tmp, "darwin-x86_64")

    def test_source_dirty_recorded_not_claimed_clean(self):
        with patch.object(native, "run", side_effect=["a" * 40 + "\n", " M source.rs\n?? new.rs\n"]):
            source = native.source_manifest("unused")
        self.assertTrue(source["dirty"])
        self.assertEqual(len(source["status"]), 2)


class FixtureTests(unittest.TestCase):
    def test_exact_header_and_ret(self):
        bp = fixture.make_bp()
        self.assertEqual(bp, struct.pack("<IIII", 16, 1, 0, 0) + b"\x17")
        self.assertEqual(fixture.parse_bp(bp)["module_size"], 1)
        self.assertEqual(fixture.parse_bp(fixture.make_bp("request-close"))["code_hex"], "806917")

    def test_bad_bp_sizes_and_unknown_code(self):
        for data in [b"", fixture.make_bp()[:-1], fixture.make_bp() + b"\0",
                     fixture.make_bp()[:-1] + b"\x18"]:
            with self.subTest(data=data), self.assertRaises(ValueError):
                fixture.parse_bp(data)

    def test_archive_roundtrip_and_raw_offsets(self):
        bp, png = fixture.make_bp(), fixture.make_checker_png()
        data = fixture.make_packfile([("ipl._bp", bp), ("checker.png", png)])
        self.assertEqual(data[:16], b"PackFile    " + struct.pack("<I", 2))
        self.assertEqual(struct.unpack_from("<II", data, 32), (0, 17))
        self.assertEqual(struct.unpack_from("<II", data, 64), (17, len(png)))
        self.assertEqual(fixture.parse_packfile(data), {"ipl._bp": bp, "checker.png": png})

    def test_archive_rejects_truncation_overlap_and_trailing(self):
        original = fixture.make_packfile([("ipl._bp", fixture.make_bp())])
        bad_offset = bytearray(original)
        struct.pack_into("<I", bad_offset, 32, 1)
        for data in [original[:15], original[:32], original[:-1], original + b"x", bad_offset]:
            with self.subTest(length=len(data)), self.assertRaises(ValueError):
                fixture.parse_packfile(data)

    def test_unsafe_names_and_duplicates(self):
        for name in ["../x", "x/y", "x\\y", "x:y", "a" * 16, "a\0b", ""]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                fixture.make_packfile([(name, b"x")])
        with self.assertRaises(ValueError):
            fixture.make_packfile([("x", b"a"), ("x", b"b")])

    def test_png_crc_dimensions_and_pixels(self):
        data = fixture.make_checker_png()
        self.assertEqual(data[:8], b"\x89PNG\r\n\x1a\n")
        cursor, chunks = 8, {}
        while cursor < len(data):
            size = struct.unpack_from(">I", data, cursor)[0]
            kind, payload = data[cursor + 4:cursor + 8], data[cursor + 8:cursor + 8 + size]
            crc = struct.unpack_from(">I", data, cursor + 8 + size)[0]
            self.assertEqual(crc, binascii.crc32(kind + payload) & 0xffffffff)
            chunks[kind] = payload
            cursor += 12 + size
        self.assertEqual(struct.unpack(">IIBBBBB", chunks[b"IHDR"]), (4, 4, 8, 6, 0, 0, 0))
        rows = zlib.decompress(chunks[b"IDAT"])
        self.assertEqual(len(rows), 4 * (1 + 4 * 4))
        self.assertEqual(rows[1:5], b"\xff\0\0\xff")
        self.assertEqual(rows[-4:], b"\xff\xff\xff\xff")

    def test_generation_manifest_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "new"
            manifest = fixture.generate(output)
            archive = (output / "system.arc").read_bytes()
            self.assertEqual(manifest["files"]["system.arc"]["sha256"], hashlib.sha256(archive).hexdigest())
            self.assertEqual(json.loads((output / "fixture.json").read_text()), manifest)
            with self.assertRaises(FileExistsError):
                fixture.generate(output)
            self.assertEqual((output / "system.arc").read_bytes(), archive)

    def test_render_exact_bp_bytes_and_code_data_boundary(self):
        # Independently specified encoding, not a second call to the assembler.
        # Header module_size includes trailing string data (106 bytes); the
        # Rust parser ends instructions after the final 17 at absolute 0x62.
        expected = bytes.fromhex(
            "10 00 00 00 6a 00 00 00 00 00 00 00 00 00 00 00 "
            "10 00 04 20 11 "                   # frame += 4
            "02 00 00 10 00 90 03 "             # memory budget 1048576
            "00 01 90 01 00 01 90 08 "          # scheduler/display on
            "01 e8 03 90 07 "                   # sync hold 1000ms
            "00 01 05 38 00 05 40 00 90 10 "    # slot/archive/resource load
            "90 50 04 04 00 0a 02 "             # create -> save returned handle
            "04 04 00 08 02 "                   # reload handle
            "01 00 02 01 e8 00 00 01 00 01 00 00 01 80 08 90 56 "
            "04 04 00 08 02 00 01 90 54 "       # reload handle, show
            "00 01 90 00 "                      # full redraw
            "10 00 04 21 11 17 "                # frame -= 4, ret
            "73 79 73 74 65 6d 2e 61 72 63 00 "
            "63 68 65 63 6b 65 72 2e 70 6e 67 00"
        )
        bp = fixture.make_bp("render")
        self.assertEqual(bp, expected)
        self.assertEqual(len(bp), 122)
        self.assertEqual(fixture.parse_bp(bp)["module_size"], 106)
        code_end = bp.rindex(b"\x17") + 1
        self.assertEqual(code_end, 99)
        self.assertEqual(bp[code_end:], b"system.arc\0checker.png\0")

    def test_render_native_argument_order_and_handle_dataflow(self):
        # A small independent structural interpreter checks only this fixture's
        # stack effects/relative strings. It is NOT the Rust VM or a GPU test.
        bp = fixture.make_bp("render")
        code_end = bp.rindex(b"\x17") + 1
        cursor, base, stack, locals_, calls = 16, 0, [], {}, []
        returned_handle = object()  # deliberately not a guessed native handle
        argc = {0x03: 1, 0x01: 1, 0x08: 1, 0x07: 1, 0x10: 3,
                0x50: 0, 0x56: 7, 0x54: 2, 0x00: 1}
        while cursor < code_end:
            offset = cursor
            op = bp[cursor]
            cursor += 1
            if op in (0x00, 0x01, 0x02):
                width, fmt = {0x00: (1, "<b"), 0x01: (2, "<h"), 0x02: (4, "<i")}[op]
                stack.append(struct.unpack_from(fmt, bp, cursor)[0])
                cursor += width
            elif op == 0x05:
                target = offset + struct.unpack_from("<h", bp, cursor)[0]
                cursor += 2
                self.assertGreaterEqual(target, code_end)
                stack.append(bp[target:bp.index(b"\0", target)].decode("ascii"))
            elif op == 0x10:
                stack.append(base)
            elif op == 0x11:
                base = stack.pop()
            elif op in (0x20, 0x21):
                right, left = stack.pop(), stack.pop()
                stack.append(left + right if op == 0x20 else left - right)
            elif op == 0x04:
                displacement = struct.unpack_from("<h", bp, cursor)[0]
                cursor += 2
                stack.append(("frame-pointer", base - displacement))
            elif op in (0x08, 0x0a):
                self.assertEqual(bp[cursor], 2)  # DWORD, not byte/word
                cursor += 1
                pointer = stack.pop()
                self.assertEqual(pointer, ("frame-pointer", 0))
                if op == 0x0a:
                    locals_[pointer] = stack.pop()  # pointer first, then value
                else:
                    stack.append(locals_[pointer])
            elif op == 0x90:
                selector = bp[cursor]
                cursor += 1
                count = argc[selector]
                args = [stack.pop() for _ in range(count)][::-1]
                calls.append((selector, args))
                if selector == 0x50:
                    stack.append(returned_handle)
            elif op == 0x17:
                self.assertEqual(cursor, code_end)
                self.assertEqual(base, 0)
                self.assertEqual(stack, [])
            else:
                self.fail(f"unexpected fixture opcode {op:#x} at {offset:#x}")
        self.assertEqual(calls, [
            (0x03, [1048576]), (0x01, [1]), (0x08, [1]), (0x07, [1000]),
            (0x10, [1, "system.arc", "checker.png"]), (0x50, []),
            (0x56, [returned_handle, 512, 232, 1, 1, 0, 2176]),
            (0x54, [returned_handle, 1]), (0x00, [1]),
        ])
        self.assertIs(locals_[("frame-pointer", 0)], returned_handle)

    def test_render_png_and_expected_rgba_probes(self):
        png = fixture.make_checker_png(256)
        cursor, chunks = 8, {}
        while cursor < len(png):
            size = struct.unpack_from(">I", png, cursor)[0]
            kind, payload = png[cursor + 4:cursor + 8], png[cursor + 8:cursor + 8 + size]
            self.assertEqual(struct.unpack_from(">I", png, cursor + 8 + size)[0],
                             binascii.crc32(kind + payload) & 0xffffffff)
            chunks[kind] = payload
            cursor += 12 + size
        self.assertEqual(struct.unpack(">IIBBBBB", chunks[b"IHDR"]), (256, 256, 8, 6, 0, 0, 0))
        rows = zlib.decompress(chunks[b"IDAT"])
        self.assertEqual(len(rows), 256 * (1 + 256 * 4))
        self.assertTrue(all(rows[y * 1025] == 0 for y in range(256)))
        expectation = fixture.render_expectation()
        x, y, width, height = expectation["sprite_rect_xywh"]
        self.assertEqual([x, y, width, height], [512, 232, 256, 256])
        self.assertEqual(expectation["logical_canvas"], [1280, 720])
        self.assertIn("execution pending", expectation["verification_status"])
        for probe in expectation["probes"]:
            px, py = probe["logical_xy"]
            if x <= px < x + width and y <= py < y + height:
                offset = (py - y) * 1025 + 1 + (px - x) * 4
                actual = list(rows[offset:offset + 4])
            else:
                actual = [0, 0, 0, 255]
            self.assertEqual(actual, probe["expected_rgba"])
        # Tablet letterbox: expected content viewport is x=0,y=150,w=1600,h=900.
        scale = min(1600 / 1280, 1200 / 720)
        ox, oy = (1600 - 1280 * scale) / 2, (1200 - 720 * scale) / 2
        self.assertEqual((scale, ox, oy), (1.25, 0, 150))
        red = expectation["probes"][0]["logical_xy"]
        self.assertEqual((ox + red[0] * scale, oy + red[1] * scale), (720, 520))

    def test_render_generation_references_packaged_image_and_exports_raw_bp(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "render"
            manifest = fixture.generate(output, "render")
            entries = fixture.parse_packfile((output / "system.arc").read_bytes())
            self.assertEqual(set(entries), {"ipl._bp", "checker.png"})
            self.assertEqual(entries["ipl._bp"], fixture.make_bp("render"))
            self.assertEqual(entries["checker.png"], fixture.make_checker_png(256))
            self.assertEqual(bytes.fromhex(manifest["raw_bp_hex"]), entries["ipl._bp"])
            self.assertEqual(manifest["render_expectation"], fixture.render_expectation())
            self.assertNotIn("checker.png is not referenced by BP; no texture-display claim",
                             manifest["limitations"])
            for name, payload in entries.items():
                self.assertEqual(manifest["entries"][name]["sha256"], hashlib.sha256(payload).hexdigest())

    def test_ret_and_close_generation_stay_unchanged(self):
        for profile, code_hex in [("ret", "17"), ("request-close", "806917")]:
            with self.subTest(profile=profile), tempfile.TemporaryDirectory() as tmp:
                output = Path(tmp) / profile
                manifest = fixture.generate(output, profile)
                entries = fixture.parse_packfile((output / "system.arc").read_bytes())
                self.assertEqual(entries["ipl._bp"], struct.pack("<IIII", 16, len(bytes.fromhex(code_hex)), 0, 0)
                                 + bytes.fromhex(code_hex))
                self.assertEqual(entries["checker.png"], fixture.make_checker_png())
                self.assertNotIn("raw_bp_hex", manifest)
                self.assertNotIn("render_expectation", manifest)
                self.assertIn("checker.png is not referenced by BP; no texture-display claim",
                              manifest["limitations"])

    def test_checker_rejects_invalid_render_sizes(self):
        for size in [0, 1, 3, -2, 1026, 2.5, "256"]:
            with self.subTest(size=size), self.assertRaises(ValueError):
                fixture.make_checker_png(size)

    def _trace_user_data_structure(self, phase, load_status=1, loaded_marker=None, save_ok=True):
        """Symbolic stack/branch audit ONLY, not a VM/SDC/persistence emulator.

        The load result is injected; no file is made or serialization claimed.
        Independent native counts, output order, pointer/copy and predicate
        decoding catch malformed BP and accidental success on error statuses.
        """
        bp = fixture.make_bp("user-data-" + phase)
        end = bp.rindex(b"\x17") + 1
        instructions, pc = {}, 16
        while pc < end:
            offset, opcode = pc, bp[pc]
            pc += 1
            operand = None
            if opcode in (0x00, 0x01, 0x02):
                width, fmt = {0x00: (1, "<b"), 0x01: (2, "<h"), 0x02: (4, "<i")}[opcode]
                operand = struct.unpack_from(fmt, bp, pc)[0]
                pc += width
            elif opcode in (0x04, 0x05):
                operand = struct.unpack_from("<h", bp, pc)[0]
                pc += 2
                if opcode == 0x05:
                    target = offset + operand
                    self.assertGreaterEqual(target, end)
                    operand = bp[target:bp.index(b"\0", target)].decode("ascii")
            elif opcode in (0x08, 0x0a, 0x15, 0x80):
                operand = bp[pc]
                pc += 1
            elif opcode == 0x0b:
                count = bp[pc]
                operand = bp[pc + 1:pc + 1 + count]
                self.assertEqual(len(operand), count)
                pc += 1 + count
            else:
                self.assertIn(opcode, (0x10, 0x11, 0x20, 0x21, 0x30, 0x14, 0x17))
            instructions[offset] = (opcode, operand, pc)
        self.assertEqual(pc, end)
        self.assertEqual(instructions[end - 1][0], 0x17)
        # Every branch target is a real instruction offset, not a data byte or
        # code-relative value. Matches Rust's labels/jump_target_index contract.
        offsets = list(instructions)
        for index, offset in enumerate(offsets):
            opcode, operand, _ = instructions[offset]
            if opcode in (0x14, 0x15):
                previous_op, target, _ = instructions[offsets[index - 1]]
                self.assertEqual(previous_op, 0x02)
                self.assertIn(target, instructions)
        stack, memory, calls, titles, saved_blocks = [], {}, [], [], []
        base, pc, steps, close = 0, 16, 0, False
        # Symbolic global bytes, intentionally NOT a serialized .gdb file.
        global_bytes = bytearray(128)
        marker = bytes.fromhex("4247492d534d4f4b452d563100a55a01")
        if loaded_marker is not None:
            global_bytes[64:80] = loaded_marker

        def pop_pointer():
            pointer = stack.pop()
            self.assertIsInstance(pointer, tuple)
            self.assertEqual(pointer[0], "local")
            return pointer[1]

        while pc < end:
            steps += 1
            self.assertLess(steps, 200)
            opcode, operand, next_pc = instructions[pc]
            if opcode in (0x00, 0x01, 0x02, 0x05):
                stack.append(operand)
            elif opcode == 0x04:
                stack.append(("local", base - operand))
            elif opcode == 0x10:
                stack.append(base)
            elif opcode == 0x11:
                base = stack.pop()
            elif opcode in (0x20, 0x21, 0x30):
                right, left = stack.pop(), stack.pop()
                stack.append(left + right if opcode == 0x20 else
                             left - right if opcode == 0x21 else int(left == right))
            elif opcode == 0x0b:
                destination = pop_pointer()
                for index, byte in enumerate(operand):
                    memory[destination + index] = byte
            elif opcode in (0x08, 0x0a):
                self.assertEqual(operand, 2)
                destination = pop_pointer()
                if opcode == 0x0a:
                    data = struct.pack("<i", stack.pop())
                    for index, byte in enumerate(data):
                        memory[destination + index] = byte
                else:
                    data = bytes(memory.get(destination + index, 0) for index in range(4))
                    stack.append(struct.unpack("<i", data)[0])
            elif opcode == 0x15:
                destination, condition = stack.pop(), stack.pop()
                self.assertIn(operand, (0, 1))
                if (condition != 0 if operand == 0 else condition == 0):
                    next_pc = destination
            elif opcode == 0x14:
                next_pc = stack.pop()
            elif opcode == 0x80:
                argc = {0x80: 0, 0x81: 0, 0x82: 3, 0x83: 3,
                        0x66: 1, 0x68: 1, 0x69: 0}[operand]
                args = [stack.pop() for _ in range(argc)][::-1]
                calls.append((operand, args))
                if operand == 0x80:
                    stack.extend([123, -456, load_status])  # x, y, status!
                elif operand == 0x81:
                    saved_blocks.append(bytes(global_bytes[64:80]))
                    stack.append(int(save_ok))
                elif operand == 0x82:
                    offset, pointer, length = args
                    self.assertEqual((offset, pointer, length), (64, ("local", 0), 16))
                    global_bytes[offset:offset + length] = bytes(memory[pointer[1] + i] for i in range(length))
                elif operand == 0x83:
                    pointer, offset, length = args  # different from WRITE order
                    self.assertEqual((pointer, offset, length), (("local", 0), 64, 16))
                    for index, byte in enumerate(global_bytes[offset:offset + length]):
                        memory[pointer[1] + index] = byte
                elif operand == 0x66:
                    titles.append(args[0])
                elif operand == 0x68:
                    self.assertEqual(args, [1])
                elif operand == 0x69:
                    close = True
            elif opcode == 0x17:
                self.assertEqual(next_pc, end)
                self.assertEqual(base, 0)
                self.assertEqual(stack, [])
            pc = next_pc
        self.assertTrue(close)
        self.assertEqual(len(titles), 1)
        self.assertEqual(struct.unpack("<i", bytes(memory[i] for i in range(16, 20)))[0], 123)
        self.assertEqual(struct.unpack("<i", bytes(memory[i] for i in range(20, 24)))[0], -456)
        return {"title": titles[0], "calls": calls, "saved_blocks": saved_blocks,
                "instruction_opcodes": [row[1] for row in instructions.values() if row[0] == 0x80]}

    def test_user_data_write_uses_vm_save_and_checks_preexisting_and_failure(self):
        result = self._trace_user_data_structure("write")
        self.assertEqual(result["title"], "ETHORNELL_USER_DATA_WRITE_OK")
        self.assertEqual(result["saved_blocks"], [bytes.fromhex("4247492d534d4f4b452d563100a55a01")])
        self.assertEqual([op for op, _ in result["calls"]], [0x80, 0x82, 0x81, 0x66, 0x68, 0x69])
        for status in (0, 2):
            with self.subTest(status=status):
                refused = self._trace_user_data_structure("write", load_status=status)
                self.assertEqual(refused["title"], "ETHORNELL_USER_DATA_WRITE_REFUSED_EXISTING")
                self.assertEqual(refused["saved_blocks"], [])
                self.assertNotIn(0x81, [op for op, _ in refused["calls"]])
        failed = self._trace_user_data_structure("write", save_ok=False)
        self.assertEqual(failed["title"], "ETHORNELL_USER_DATA_WRITE_FAILED")

    def test_user_data_read_compares_every_marker_dword_and_never_saves(self):
        marker = bytes.fromhex("4247492d534d4f4b452d563100a55a01")
        good = self._trace_user_data_structure("read", load_status=0, loaded_marker=marker)
        self.assertEqual(good["title"], "ETHORNELL_USER_DATA_READ_OK")
        self.assertEqual([op for op, _ in good["calls"]], [0x80, 0x83, 0x66, 0x68, 0x69])
        self.assertNotIn(0x81, good["instruction_opcodes"])
        self.assertNotIn(0x82, good["instruction_opcodes"])
        self.assertEqual(good["saved_blocks"], [])
        for index in range(16):
            bad = bytearray(marker)
            bad[index] ^= 1
            with self.subTest(marker_byte=index):
                result = self._trace_user_data_structure("read", load_status=0, loaded_marker=bad)
                self.assertEqual(result["title"], "ETHORNELL_USER_DATA_READ_MISMATCH")
        for status in (1, 2):
            with self.subTest(load_status=status):
                result = self._trace_user_data_structure("read", load_status=status)
                self.assertEqual(result["title"], "ETHORNELL_USER_DATA_READ_LOAD_FAILED")
                self.assertNotIn(0x83, [op for op, _ in result["calls"]])

    def test_user_data_generation_never_creates_or_packages_a_save(self):
        with tempfile.TemporaryDirectory() as tmp:
            for phase in ("write", "read"):
                output = Path(tmp) / phase
                manifest = fixture.generate(output, "user-data-" + phase)
                self.assertEqual({path.name for path in output.iterdir()}, {"system.arc", "fixture.json"})
                archive = fixture.parse_packfile((output / "system.arc").read_bytes())
                self.assertEqual(set(archive), {"ipl._bp", "checker.png"})
                self.assertEqual(archive["ipl._bp"], bytes.fromhex(manifest["raw_bp_hex"]))
                expected = manifest["user_data_expectation"]
                self.assertEqual(expected["guest_file"], "BGI.gdb")
                self.assertEqual(expected["host_file"], "<same HostConfig.user_root>/BGI.gdb")
                self.assertEqual(expected["marker_global_offset"], 64)
                self.assertEqual(expected["save_content_expectation"]["decoded_marker_offset"], 0x464)
                self.assertEqual(expected["success_events"][0]["text"], f"ETHORNELL_USER_DATA_{phase.upper()}_OK")
                self.assertIn("pending", expected["verification_status"])
                self.assertIn("NOT certified", expected["save_content_expectation"]["pc_compatibility"])
                with self.assertRaises(FileExistsError):
                    fixture.generate(output, "user-data-" + phase)


class IntegrationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.library = self.root / "libethornell.so"
        self.library.write_bytes(elf())
        self.header = self.root / "abi.h"
        self.header.write_text(HEADER)
        self.tools = {"readelf": "fake-readelf", "nm": "fake-nm"}

    def inspect(self, data=None, text=None, nm=None):
        if data is not None:
            self.library.write_bytes(data)
        with patch.object(native, "run", side_effect=[
                text if text is not None else readelf_text(),
                nm if nm is not None else "ethornell_android_abi_version T 1000 4\nethornell_android_step T 1000 4\n"]):
            return native.inspect_library(self.library, self.tools, native.required_abi(HEADER))

    def test_positive_static_inspection(self):
        item = self.inspect()
        self.assertEqual(item["errors"], [])
        self.assertEqual(item["sha256"], hashlib.sha256(elf()).hexdigest())

    def test_api_note_not_grep(self):
        self.assertTrue(any("API exactly 26" in e for e in self.inspect(data=elf(api=27))["errors"]))

    def test_dependency_older_api_note_accepted(self):
        self.library.write_bytes(elf(api=21))
        with patch.object(native, "run", side_effect=[readelf_text(), "helper T 1000 4\n"]):
            item = native.inspect_library(self.library, self.tools)
        self.assertEqual(item["errors"], [])

    def test_required_export_missing_or_undefined(self):
        for text in [readelf_text(exports=["ethornell_android_abi_version"]),
                     readelf_text().replace("DEFAULT 1 ethornell_android_step", "DEFAULT UND ethornell_android_step"),
                     readelf_text().replace("FUNC GLOBAL DEFAULT 1 ethornell_android_step",
                                            "OBJECT GLOBAL DEFAULT 1 ethornell_android_step")]:
            self.assertTrue(any("ABI export" in e for e in self.inspect(text=text)["errors"]))

    def test_nm_and_readelf_must_agree_on_abi(self):
        item = self.inspect(nm="ethornell_android_abi_version T 1000 4\nethornell_android_step U 0 0\n")
        self.assertTrue(item["errors"])

    def test_new_export_must_be_added_to_contract(self):
        item = self.inspect(text=readelf_text(exports=["ethornell_android_abi_version",
                                                     "ethornell_android_step", "ethornell_android_new"]))
        self.assertTrue(any("absent from contract" in error for error in item["errors"]))

    def test_program_header_cross_check(self):
        with self.assertRaisesRegex(native.ValidationError, "disagree"):
            self.inspect(text=readelf_text().replace("0x4000", "0x1000"))

    def test_apk_hash_exact_bytes_closure_and_missing_dependency(self):
        apk = self.root / "app.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("lib/arm64-v8a/libethornell.so", elf())
            archive.writestr("lib/arm64-v8a/libhelper.so", elf())
        args = argparse.Namespace(header=self.header, source_root=self.root, ndk=None, host_tag=None,
                                  tool_bin=None, api=26, allow_bundled=["libhelper.so"],
                                  apk=apk, library=self.library, package_dir=None)
        def inspect(path, tools, abi, api):
            return {"name": path.name, "errors": [], "needed": ["libhelper.so"]
                    if path.name == "libethornell.so" else ["libc.so"]}
        with patch.object(native, "resolve_tools", return_value=self.tools), \
                patch.object(native, "source_manifest", return_value={"revision": "a" * 40, "dirty": True}), \
                patch.object(native, "run", return_value="fake version"), \
                patch.object(native, "inspect_library", side_effect=inspect):
            report = native.verify(args)
            self.assertTrue(report["ok"])
            self.assertEqual(len(report["libraries"]), 2)
            self.assertEqual(report["header_sha256"], hashlib.sha256(HEADER.encode()).hexdigest())
            self.assertEqual(report["apk_sha256"], hashlib.sha256(apk.read_bytes()).hexdigest())
            self.library.write_bytes(elf(api=27))
            with self.assertRaisesRegex(native.ValidationError, "differs"):
                native.verify(args)

    @unittest.skipIf(os.name == "nt", "fake POSIX executable smoke; Windows path resolution tested separately")
    def test_cli_runs_configured_tools_and_emits_manifest(self):
        bindir = self.root / "custom host bin"
        bindir.mkdir()
        for name, output in [("readelf", readelf_text()), ("nm", "ethornell_android_abi_version T 1000 4\nethornell_android_step T 1000 4\n")]:
            path = bindir / ("llvm-" + name)
            path.write_text("#!" + sys.executable + "\nimport sys\nprint(" + repr(output) + ")\n")
            path.chmod(0o755)
        script = Path(native.__file__)
        command = [sys.executable, str(script), "--library", str(self.library), "--header", str(self.header),
                   "--source-root", str(script.parents[2]), "--tool-bin", str(bindir)]
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(json.loads(result.stdout)["ok"])
        self.library.write_bytes(elf(api=25))
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertFalse(json.loads(result.stdout)["ok"])


if __name__ == "__main__":
    unittest.main()
