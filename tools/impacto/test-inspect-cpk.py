#!/usr/bin/env python3
"""Regression tests for inspect-cpk.py using tiny synthetic archives (no game data)."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.dont_write_bytecode = True

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("inspect_cpk", HERE / "inspect-cpk.py")
inspect_cpk = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inspect_cpk)

ALIGN = 2048
TYPES = {"u16": (2, ">H"), "u32": (4, ">I"), "s32": (5, ">i"), "u64": (6, ">Q"), "str": (10, None)}


def utf_table(columns, rows, ver=0):
    """columns: (name, storage, type, const); storage 0x50 = per row, 0x30 = constant."""
    strings = bytearray(b"<NULL>\0")
    offsets = {"<NULL>": 0}

    def s(text):
        if text not in offsets:
            offsets[text] = len(strings)
            strings.extend(text.encode() + b"\0")
        return offsets[text]

    def enc(typ, value):
        code, fmt = TYPES[typ]
        return struct.pack(">I", s(value)) if typ == "str" else struct.pack(fmt, value)

    coldefs, rowbuf = bytearray(), bytearray()
    for name, storage, typ, const in columns:
        coldefs += bytes([storage | TYPES[typ][0]]) + struct.pack(">I", s(name))
        if storage == 0x30:
            coldefs += enc(typ, const)
    row_w = 0
    for row in rows:
        start = len(rowbuf)
        for name, storage, typ, _ in columns:
            if storage == 0x50:
                rowbuf += enc(typ, row[name])
        row_w = len(rowbuf) - start
    name_off = s("T")
    rows_off = 24 + len(coldefs)
    str_off = rows_off + len(rowbuf)
    data_off = str_off + len(strings)
    table = struct.pack(">HHIIIHHI", ver, rows_off, str_off, data_off, name_off,
                        len(columns), row_w, len(rows)) + coldefs + rowbuf + strings
    return b"@UTF" + struct.pack(">I", len(table)) + table


def chunk(magic, utf, encrypted=False, flag=None):
    """flag defaults to what CRI writes (0 = masked, 0xFF = plain); override to test magic-based unmasking."""
    if encrypted:
        key, out = 0x5F, bytearray()
        for b in utf:
            out.append(b ^ key)
            key = (key * 0x15) & 0xFF
        utf = bytes(out)
    return magic + struct.pack("<IQ", (0 if encrypted else 0xFF) if flag is None else flag, len(utf)) + utf


def build_cpk(files, *, shift=None, id_type="u32", encrypted=False, flag=None, with_itoc=True,
              files_field=None, dup_id=False, ver=0, itoc_magic=b"ITOC", layla=False):
    """files: [(name, bytes)] with IDs 0..n-1. shift=(index, delta) corrupts one TOC offset."""
    toc_off, itoc_off, content_off = 2048, 4096, 6144
    offsets, cursor = [], content_off
    for _, data in files:
        offsets.append(cursor)
        cursor += len(data) + (-len(data)) % ALIGN
    body = bytearray(b"\0" * (cursor - content_off))
    for (_, data), off in zip(files, offsets):
        body[off - content_off:off - content_off + len(data)] = data
    toc_rows = []
    for i, ((name, data), off) in enumerate(zip(files, offsets)):
        delta = shift[1] if shift and shift[0] == i else 0
        toc_rows.append({"FileName": name, "FileSize": len(data), "ExtractSize": len(data),
                         "FileOffset": off - toc_off + delta, "ID": 0 if (dup_id and i == 1) else i})
    toc = utf_table([("DirName", 0x30, "str", ""), ("FileName", 0x50, "str", None),
                     ("FileSize", 0x50, "u32", None), ("ExtractSize", 0x50, "u32", None),
                     ("FileOffset", 0x50, "u64", None), ("ID", 0x50, id_type, None)], toc_rows)
    itoc = utf_table([("ID", 0x50, "s32", None), ("TocIndex", 0x50, "s32", None)],
                     [{"ID": i, "TocIndex": i} for i in range(len(files))])
    header = utf_table([(n, 0x50, t, None) for n, t in (
        ("ContentOffset", "u64"), ("TocOffset", "u64"), ("EtocOffset", "u64"),
        ("ItocOffset", "u64"), ("Files", "u32"), ("Version", "u16"),
        ("Revision", "u16"), ("Align", "u16"))],
        [{"ContentOffset": content_off, "TocOffset": toc_off, "EtocOffset": 0,
          "ItocOffset": itoc_off if with_itoc else 0,
          "Files": len(files) if files_field is None else files_field,
          "Version": 7, "Revision": 14, "Align": ALIGN}], ver=ver)
    image = bytearray(content_off)
    blobs = [(0, chunk(b"CPK ", header, encrypted, flag)), (toc_off, chunk(b"TOC ", toc, encrypted, flag))]
    if with_itoc:
        blobs.append((itoc_off, chunk(itoc_magic, itoc, encrypted, flag)))
    for off, blob in blobs:
        assert off + len(blob) <= off + ALIGN
        image[off:off + len(blob)] = blob
    if layla:
        body[0:8] = b"CRILAYLA"
    return bytes(image) + bytes(body)


class InspectCpkTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.files = [("a.scx", b"SC3\0" + b"x" * 5000), ("b.png", b"\x89PNG" + b"y" * 10),
                      ("c.scx", b"SC3\0")]

    def write(self, name, blob):
        path = Path(self.tmp.name) / name
        path.write_bytes(blob)
        return str(path)

    def test_clean_archive_matches_upstream_layout(self):
        rep = inspect_cpk.inspect(self.write("ok.cpk", build_cpk(self.files)))
        self.assertEqual(rep["problems"], [])
        self.assertEqual(rep["files"], 3)
        self.assertEqual(rep["upstream_layout_mismatches"], 0)
        self.assertTrue(rep["ids_contiguous_from_zero"])
        self.assertEqual(rep["members"][".scx"], {"count": 2, "magic4": {"53433300": 2}})
        self.assertEqual(rep["compressed_members"], 0)

    def test_layout_mismatch_is_reported(self):
        rep = inspect_cpk.inspect(self.write("shift.cpk", build_cpk(self.files, shift=(1, ALIGN))))
        self.assertGreaterEqual(rep["upstream_layout_mismatches"], 1)
        self.assertEqual(rep["upstream_layout_first_mismatch"]["name"], "b.png")

    def test_column_type_mismatch_blocks_layout_claims(self):
        rep = inspect_cpk.inspect(self.write("u16.cpk", build_cpk(self.files, id_type="u16")))
        self.assertTrue(any("TOC ID" in p for p in rep["problems"]))
        self.assertNotIn("upstream_layout_mismatches", rep)

    def test_masked_tables_are_decoded_like_upstream(self):
        rep = inspect_cpk.inspect(self.write("enc.cpk", build_cpk(self.files, encrypted=True)))
        self.assertTrue(rep["header_masked"])
        self.assertEqual(rep["problems"], [])
        self.assertEqual(rep["files"], 3)
        self.assertEqual(rep["upstream_layout_mismatches"], 0)

    def test_unmasking_follows_magic_not_flag(self):
        # upstream tests for '@UTF', not for the chunk flag: both odd combinations must still parse
        for name, kwargs in (("flag-ff-masked.cpk", dict(encrypted=True, flag=0xFF)),
                             ("flag-0-plain.cpk", dict(encrypted=False, flag=0))):
            rep = inspect_cpk.inspect(self.write(name, build_cpk(self.files, **kwargs)))
            self.assertEqual(rep["files"], 3, name)
            self.assertEqual(rep["header_masked"], kwargs["encrypted"], name)

    def test_without_itoc_upstream_uses_raw_offsets(self):
        rep = inspect_cpk.inspect(self.write("noitoc.cpk", build_cpk(self.files, with_itoc=False)))
        self.assertEqual(rep["upstream_layout_rule"], "raw-file-offset")
        self.assertEqual(rep["upstream_layout_mismatches"], 3)  # TOC offsets are relative to 2048

    def test_unreadable_itoc_falls_back_to_raw_and_is_reported(self):
        rep = inspect_cpk.inspect(self.write("badtoc.cpk", build_cpk(self.files, itoc_magic=b"XXXX")))
        self.assertTrue(any("ITOC unreadable" in p for p in rep["problems"]))
        self.assertEqual(rep["upstream_layout_rule"], "raw-file-offset")

    def test_files_count_and_duplicate_ids_are_flagged(self):
        rep = inspect_cpk.inspect(self.write("files.cpk", build_cpk(self.files, files_field=99)))
        self.assertTrue(any("Files=99" in p for p in rep["problems"]))
        rep = inspect_cpk.inspect(self.write("dup.cpk", build_cpk(self.files, dup_id=True)))
        self.assertTrue(any("duplicate" in p for p in rep["problems"]))

    def test_nonzero_utf_version_field_is_flagged(self):
        rep = inspect_cpk.inspect(self.write("ver.cpk", build_cpk(self.files, ver=1)))
        self.assertTrue(any("non-zero" in p for p in rep["problems"]))

    def test_unmarked_layla_members_are_counted(self):
        rep = inspect_cpk.inspect(self.write("layla.cpk", build_cpk(self.files, layla=True)))
        self.assertEqual(rep["layla_magic_not_marked_compressed"], 1)
        self.assertEqual(rep["compressed_members"], 0)

    def test_truncated_archive_fails_cleanly(self):
        blob = build_cpk(self.files)
        path = self.write("cut.cpk", blob[:2060])
        proc = subprocess.run([sys.executable, "-I", str(HERE / "inspect-cpk.py"), path],
                              capture_output=True, text=True)
        self.assertEqual(proc.returncode, 1)
        self.assertIn("error", json.loads(proc.stdout.splitlines()[0]))

    def test_hostile_tables_fail_cleanly_without_traceback_or_hang(self):
        cases = {}
        # zero-row header, huge row count with zero width, TocOffset beyond the signed range
        empty_hdr = utf_table([("Files", 0x50, "u32", None)], [])
        cases["zero-row"] = chunk(b"CPK ", empty_hdr).ljust(2048, b"\0")
        huge = bytearray(utf_table([("Files", 0x50, "u32", None)], [{"Files": 1}]))
        struct.pack_into(">I", huge, 8 + 20, 0x20000000)   # nrows
        struct.pack_into(">H", huge, 8 + 18, 0)            # row width
        cases["huge-rows"] = chunk(b"CPK ", bytes(huge)).ljust(2048, b"\0")
        # TocOffset claims an offset far beyond the file (and beyond the signed 64-bit range)
        overflow = build_cpk(self.files)
        cases["toc-offset-overflow"] = overflow[:16] + overflow[16:].replace(
            struct.pack(">Q", 2048), struct.pack(">Q", (1 << 63) + 5), 1)
        for name, blob in cases.items():
            proc = subprocess.run([sys.executable, "-I", str(HERE / "inspect-cpk.py"), self.write(name + ".cpk", blob)],
                                  capture_output=True, text=True, timeout=30)
            self.assertEqual(proc.returncode, 1, name + proc.stderr)
            self.assertNotIn("Traceback", proc.stderr, name)

    def test_not_a_cpk_fails_cleanly(self):
        path = self.write("junk.cpk", b"NOPE" * 100)
        with self.assertRaises(inspect_cpk.CpkError):
            inspect_cpk.inspect(path)

    def test_archive_is_not_modified_and_no_files_appear(self):
        path = self.write("ro.cpk", build_cpk(self.files))
        before = hashlib.sha256(Path(path).read_bytes()).hexdigest()
        mtime = os.stat(path).st_mtime_ns
        listing = sorted(os.listdir(self.tmp.name))
        proc = subprocess.run([sys.executable, "-I", str(HERE / "inspect-cpk.py"), path, "--ids"],
                              capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(hashlib.sha256(Path(path).read_bytes()).hexdigest(), before)
        self.assertEqual(os.stat(path).st_mtime_ns, mtime)
        self.assertEqual(sorted(os.listdir(self.tmp.name)), listing)
        self.assertEqual(json.loads(proc.stdout.splitlines()[0])["ids"][0][:2], [0, "a.scx"])


if __name__ == "__main__":
    unittest.main()
