#!/usr/bin/env python3
"""Repair the en-XA text that aapt --pseudo-localize mangles (VOLT_PSEUDO_LOCALES=1 builds only).

  pseudo_fix.py --apk <apk from aapt --pseudo-localize>

aapt's pseudo-localizer takes every % for the start of a placeholder and scans on for a letter that
ends one, without stopping at the end of the text. In a formatted="false" string such as "Battery %"
or "Battery charge (%)", no letter follows, so it copies the terminator into the placeholder. The
compiled en-XA value is cut there: an opening mark, no closing bracket, and NUL characters to the
length the whole text would have had: "[βåţţéŕý »%" NUL NUL... ar-XB is not affected.

aapt is a binary and refuses a values-en-rXA folder next to --pseudo-localize, and the English in
res/ must not change, so the repair happens on its output. Each such string is rewritten in its own
slot of resources.arsc as the text aapt meant, without the mark, since this % is not a placeholder:
"[βåţţéŕý % one two]". The length of the NUL run gives the length of aapt's filler words. The file
keeps its size and every other byte, the release APK is never touched, and any NUL left in any
configuration fails the build.
"""

import argparse
import os
import struct
import sys
import tempfile
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import roundtrip  # noqa: E402

ARSC = "resources.arsc"
OPEN_MARK = "»".encode("utf-8")
CLOSE_MARK = "«".encode("utf-8")
# aapt's k_expansion_string, which it cuts at a word end to lengthen short text.
FILLER = (b"one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen "
          b"sixteen seventeen eighteen nineteen twenty")
# What aapt meant after the terminator it copied, besides the filler: NUL, the closing mark, a space, ].
LOST_BESIDES_FILLER = 1 + len(CLOSE_MARK) + 1 + 1


def repaired_utf8(raw):
    """The text a mangled UTF-8 pool string should have been, or None when raw is not one."""
    text = raw.rstrip(b"\0")
    lost = len(raw) - len(text)
    mark = text.rfind(OPEN_MARK)
    if not lost or b"\0" in text or not text.startswith(b"[") or mark < 0:
        return None
    head, placeholder = text[:mark], text[mark + len(OPEN_MARK):]
    if not placeholder.startswith(b"%") or CLOSE_MARK in placeholder:
        return None
    filler = FILLER[:max(0, lost - LOST_BESIDES_FILLER)].rstrip()
    return head + placeholder + (b" " + filler if filler else b"") + b"]"


def _encode_length8(value, width):
    if width == 1:
        return bytes([value])
    return bytes([0x80 | (value >> 8), value & 0xFF])


def repair_arsc(data):
    """(bytes of the same length, [repaired text]) for the bytes of a resources.arsc."""
    out = bytearray(data)
    chunk_type, header_size = struct.unpack_from("<HH", out, 0)
    if chunk_type != roundtrip.RES_TABLE:
        raise roundtrip.ArscError("not a resource table")
    pool = header_size
    if struct.unpack_from("<H", out, pool)[0] != roundtrip.RES_STRING_POOL:
        raise roundtrip.ArscError("the resource table does not start with its string pool")
    pool_header = struct.unpack_from("<H", out, pool + 2)[0]
    count, _, flags, strings_start, _ = struct.unpack_from("<IIIII", out, pool + 8)
    if not flags & roundtrip.UTF8_FLAG:
        # minSdkVersion 26 gives a UTF-8 pool; a UTF-16 one means something else changed, so stop and look.
        raise roundtrip.ArscError("expected aapt's UTF-8 string pool")
    repaired = []
    for offset in struct.unpack_from("<%dI" % count, out, pool + pool_header):
        slot = pool + strings_start + offset
        _, after_chars = roundtrip._length8(out, slot)
        length, start = roundtrip._length8(out, after_chars)
        text = repaired_utf8(bytes(out[start:start + length]))
        if text is None:
            continue
        decoded = text.decode("utf-8")
        units = len(decoded.encode("utf-16-le")) // 2
        out[slot:after_chars] = _encode_length8(units, after_chars - slot)
        out[after_chars:start] = _encode_length8(len(text), start - after_chars)
        out[start:start + length] = text + b"\0" * (length - len(text))
        repaired.append(decoded)
    return bytes(out), repaired


def nul_texts(entries):
    """["config/name[quantity]"] of every compiled text that holds a NUL character."""
    found = []
    for entry in entries:
        values = [("", entry.value)] if entry.value is not None else sorted((entry.bag or {}).items())
        for quantity, value in values:
            if value.text is not None and "\0" in value.text:
                found.append("%s/%s%s" % (entry.config, entry.name, "[%s]" % quantity if quantity else ""))
    return found


def repair_apk(path):
    """Rewrite the APK at path with a repaired table, keeping every entry's order and compression."""
    with zipfile.ZipFile(path) as archive:
        members = [(info, archive.read(info.filename)) for info in archive.infolist()]
    table = [data for info, data in members if info.filename == ARSC]
    if len(table) != 1:
        raise roundtrip.ArscError("%s has no single %s" % (path, ARSC))
    fixed, repaired = repair_arsc(table[0])
    handle, temporary = tempfile.mkstemp(dir=os.path.dirname(os.path.abspath(path)), suffix=".apk")
    os.close(handle)
    try:
        with zipfile.ZipFile(temporary, "w") as archive:
            for info, data in members:
                archive.writestr(info, fixed if info.filename == ARSC else data)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.remove(temporary)
    return repaired, nul_texts(roundtrip.read_arsc(fixed))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--apk", required=True)
    args = parser.parse_args(argv)
    try:
        repaired, left = repair_apk(args.apk)
    except (OSError, zipfile.BadZipFile, roundtrip.ArscError, struct.error) as error:
        print("pseudo_fix.py: %s" % error, file=sys.stderr)
        return 2
    print("pseudo_fix: repaired %d en-XA text(s) aapt cut at a bare %%" % len(repaired))
    for name in left:
        print("pseudo_fix: %s still holds a NUL character" % name, file=sys.stderr)
    return 1 if left else 0


if __name__ == "__main__":
    sys.exit(main())
