#!/usr/bin/env python3
"""Read-only CRI CPK inspector for Impacto data-compatibility triage.

It never writes next to the archive and never prints file contents: only
structure, sizes and the first four bytes of members (grouped by extension).
It restates what CommitteeOfZero/impacto's CpkArchive (the pinned revision's
src/io/cpkarchive.cpp) assumes, so a mismatch shows up without running the
engine. A clean report is a static check, NOT proof of game compatibility.
"""
from __future__ import annotations

import argparse
import collections
import json
import os
import struct
import sys

# Column types the pinned upstream reader fetches with std::get<T>(row[...]).
# A different stored type throws std::bad_variant_access at mount time.
UPSTREAM_HEADER_TYPES = {
    "Files": "u32", "Version": "u16", "Revision": "u16", "Align": "u16",
    "ContentOffset": "u64", "TocOffset": "u64", "EtocOffset": "u64", "ItocOffset": "u64",
}
UPSTREAM_TOC_TYPES = {
    "ID": "u32", "FileOffset": "u64", "FileSize": "u32", "ExtractSize": "u32",
    "FileName": "str",
}
TYPE_NAMES = {0: "u8", 1: "s8", 2: "u16", 3: "s16", 4: "u32", 5: "s32", 6: "u64",
              7: "s64", 8: "f32", 9: "f64", 10: "str", 11: "data"}
STRUCT_FMT = {0: ">B", 1: ">b", 2: ">H", 3: ">h", 4: ">I", 5: ">i", 6: ">Q", 7: ">q",
              8: ">f", 9: ">d"}
MAX_UTF_BYTES = 256 * 1024 * 1024
MAX_ROWS = 1 << 22
LAYLA = b"CRILAYLA"


class CpkError(Exception):
    pass


def _need(buf, off, size):
    if off < 0 or size < 0 or off + size > len(buf):
        raise CpkError(f"truncated or out-of-range read at {off}+{size} of {len(buf)}")


def _cstr(buf, strings_off, rel):
    start = strings_off + rel
    _need(buf, start, 1)
    end = buf.find(b"\0", start)
    if end < 0:
        raise CpkError("unterminated string")
    return buf[start:end].decode("utf-8", "replace")


def _read_value(buf, pos, typ, strings_off, data_off):
    if typ in STRUCT_FMT:
        size = struct.calcsize(STRUCT_FMT[typ])
        _need(buf, pos, size)
        return struct.unpack_from(STRUCT_FMT[typ], buf, pos)[0], pos + size
    if typ == 10:
        _need(buf, pos, 4)
        return _cstr(buf, strings_off, struct.unpack_from(">I", buf, pos)[0]), pos + 4
    if typ == 11:
        _need(buf, pos, 8)
        off, size = struct.unpack_from(">II", buf, pos)
        _need(buf, data_off + off, size)
        return bytes(buf[data_off + off:data_off + off + size]), pos + 8
    raise CpkError(f"unknown @UTF value type {typ}")


def _decrypt(data):
    """CRI's @UTF mask. Upstream: val=0x655f, byte ^= val&0xFF, val*=0x4115 (low byte: 0x5F, *0x15)."""
    key = 0x5F
    for i, b in enumerate(data):
        data[i] = b ^ key
        key = (key * 0x15) & 0xFF
    return data


def parse_utf(block):
    """Parse an @UTF table. Returns (columns, rows, notes); columns are (name, storage, type).

    Like upstream, a block whose first four bytes are not '@UTF' is tried again after the mask.
    """
    block = bytearray(block)
    notes = []
    if block[:4] != b"@UTF":
        block = _decrypt(block)
        notes.append("masked")
        if block[:4] != b"@UTF":
            raise CpkError("missing @UTF magic (also after unmasking)")
    _need(block, 4, 4)
    size = struct.unpack_from(">I", block, 4)[0]
    _need(block, 8, size)
    t = bytes(block[8:8 + size])
    _need(t, 0, 24)
    ver, rows_off, str_off, data_off, name_off, ncols, row_w, nrows = struct.unpack_from(">HHIIIHHI", t, 0)
    if ver:
        # Upstream reads bytes 8..11 as one u32 row offset; a non-zero first half would shift it.
        notes.append(f"@UTF field at byte 8 is non-zero ({ver})")
    _cstr(t, str_off, name_off)
    if nrows > MAX_ROWS or (nrows and row_w == 0 and nrows > 1):
        raise CpkError(f"implausible row count {nrows} (row width {row_w})")
    pos = 24
    cols = []
    for _ in range(ncols):
        _need(t, pos, 5)
        flag = t[pos]
        name = _cstr(t, str_off, struct.unpack_from(">I", t, pos + 1)[0])
        pos += 5
        storage, typ = flag & 0xF0, flag & 0x0F
        const = None
        if storage == 0x30:
            const, pos = _read_value(t, pos, typ, str_off, data_off)
        cols.append((name, storage, typ, const))
    _need(t, rows_off, nrows * row_w)
    rows = []
    for r in range(nrows):
        q = rows_off + r * row_w
        row = {}
        for name, storage, typ, const in cols:
            if storage == 0x30:
                row[name] = const
            elif storage == 0x50:
                row[name], q = _read_value(t, q, typ, str_off, data_off)
            else:
                row[name] = None  # 0x10: stored as default/zero
        rows.append(row)
    return [(n, s, TYPE_NAMES.get(t_, "?")) for n, s, t_, _ in cols], rows, notes


def _chunk(f, offset, magic):
    if not isinstance(offset, int) or offset < 0 or offset > (1 << 62):
        raise CpkError(f"bad {magic!r} chunk offset {offset!r}")
    f.seek(offset)
    head = f.read(16)
    if len(head) != 16 or head[:4] != magic:
        raise CpkError(f"expected {magic!r} chunk at {offset}")
    size = struct.unpack_from("<Q", head, 8)[0]
    if size > MAX_UTF_BYTES:
        raise CpkError(f"{magic!r} chunk too large: {size}")
    data = f.read(size)
    if len(data) != size:
        raise CpkError(f"{magic!r} chunk truncated")
    return bytes(data)


def _int(row, key):
    value = row.get(key)
    if not isinstance(value, int):
        raise CpkError(f"column {key} has no integer value")
    return value


def inspect(path, sniff_limit=200):
    with open(path, "rb") as f:
        raw = _chunk(f, 0, b"CPK ")
        hcols, hrows, notes = parse_utf(raw)
        if not hrows:
            raise CpkError("CPK header table has no row")
        hdr = hrows[0]
        report = {
            "file": os.path.basename(path), "bytes": os.fstat(f.fileno()).st_size,
            "header_masked": "masked" in notes,
            "header": {k: hdr.get(k) for k in ("Version", "Revision", "Align", "Files",
                                               "ContentOffset", "TocOffset", "ItocOffset",
                                               "EtocOffset", "Tvers") if k in hdr},
            "problems": [], "files": 0,
        }
        problems = report["problems"]
        problems.extend(f"header: {n}" for n in notes if n != "masked")
        types = {n: t for n, _, t in hcols}
        for name, want in UPSTREAM_HEADER_TYPES.items():
            if types.get(name) != want:
                problems.append(f"header {name}: stored {types.get(name)}, upstream reads {want}")
        if problems:
            return report
        toc_off, content, align = hdr["TocOffset"], hdr["ContentOffset"], hdr["Align"]
        if not toc_off:
            problems.append("no TOC")
            return report
        tcols, trows, tnotes = parse_utf(_chunk(f, toc_off, b"TOC "))
        problems.extend(f"TOC: {n}" for n in tnotes if n != "masked")
        ttypes = {n: t for n, _, t in tcols}
        for name, want in UPSTREAM_TOC_TYPES.items():
            if ttypes.get(name) != want:
                problems.append(f"TOC {name}: stored {ttypes.get(name)}, upstream reads {want}")
        if problems:
            return report
        report["files"] = len(trows)
        if hdr["Files"] != len(trows):
            problems.append(f"header Files={hdr['Files']} but TOC has {len(trows)} rows "
                            "(upstream sizes its table from Files)")
        ids = [_int(r, "ID") for r in trows]
        if len(set(ids)) != len(ids):
            problems.append("duplicate TOC IDs (upstream merges them into one entry)")
        if align == 0:
            problems.append("Align is 0 (upstream's padding step divides by it)")
        report["compressed_members"] = sum(
            1 for r in trows if r["ExtractSize"] and r["ExtractSize"] != r["FileSize"])
        report["ids_contiguous_from_zero"] = sorted(ids) == list(range(len(ids)))

        base = min(content, toc_off)
        # Upstream only recomputes offsets when ItocOffset != 0, and ignores ReadItoc's result,
        # so an unreadable ITOC leaves the raw FileOffset values in force.
        itoc_ok = False
        if hdr["ItocOffset"]:
            try:
                icols, irows, _ = parse_utf(_chunk(f, hdr["ItocOffset"], b"ITOC"))
                itoc_ok = True
                names = {c[0] for c in icols}
                if "DataL" in names or "DataH" in names:
                    problems.append("ITOC has DataL/DataH: upstream takes sizes from there, not checked here")
                    itoc_ok = False
            except CpkError as exc:
                problems.append(f"ITOC unreadable ({exc}); upstream ignores that and keeps raw TOC offsets")
        report["itoc"] = itoc_ok
        bad, first_bad = 0, None
        if itoc_ok:
            # Back to back in ID order from ContentOffset, padded to Align (ReadItoc's tail loop).
            cursor = content
            for r in sorted(trows, key=lambda x: x["ID"]):
                size = r["FileSize"] or r["ExtractSize"]
                real = base + r["FileOffset"]
                if real != cursor:
                    bad += 1
                    first_bad = first_bad or {"id": r["ID"], "name": r["FileName"],
                                              "real": real, "upstream": cursor}
                cursor += size
                if align and size % align:
                    cursor += align - size % align
        else:
            # Upstream then uses FileOffset as an absolute position.
            for r in sorted(trows, key=lambda x: x["ID"]):
                real = base + r["FileOffset"]
                if real != r["FileOffset"]:
                    bad += 1
                    first_bad = first_bad or {"id": r["ID"], "name": r["FileName"],
                                              "real": real, "upstream": r["FileOffset"]}
        report["upstream_layout_mismatches"] = bad
        report["upstream_layout_rule"] = "itoc-sequential" if itoc_ok else "raw-file-offset"
        if first_bad:
            report["upstream_layout_first_mismatch"] = first_bad

        by_ext = collections.defaultdict(collections.Counter)
        layla = 0
        for i, r in enumerate(sorted(trows, key=lambda x: x["ID"])):
            f.seek(base + r["FileOffset"])
            head = f.read(8)
            if head == LAYLA and r["ExtractSize"] in (0, r["FileSize"]):
                layla += 1  # upstream Open() treats these as compressed although the TOC does not
            if i < sniff_limit:
                by_ext[os.path.splitext(r["FileName"])[1].lower() or "(none)"][head[:4].hex()] += 1
        report["layla_magic_not_marked_compressed"] = layla
        report["members"] = {e: {"count": sum(c.values()), "magic4": dict(c.most_common(4))}
                             for e, c in by_ext.items()}
        report["ids"] = [[r["ID"], r["FileName"], r["FileSize"]] for r in sorted(trows, key=lambda x: x["ID"])]
    return report


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("cpk", nargs="+")
    ap.add_argument("--ids", action="store_true", help="include the full ID -> name -> size list")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)
    reports, failed = [], False
    for path in args.cpk:
        try:
            rep = inspect(path)
        except (CpkError, OSError, struct.error, IndexError, KeyError, TypeError,
                ValueError, OverflowError) as exc:
            rep, failed = {"file": os.path.basename(path), "error": f"{type(exc).__name__}: {exc}"}, True
        if not args.ids:
            rep.pop("ids", None)
        reports.append(rep)
    if args.json:
        print(json.dumps(reports, ensure_ascii=False, indent=2))
    else:
        for rep in reports:
            print(json.dumps(rep, ensure_ascii=False))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
