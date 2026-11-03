#!/usr/bin/env python3
"""G3, the aapt round-trip: compiled default English must equal the generator's English.

Reads the default-configuration strings and plurals that aapt actually compiled, straight from the
resources.arsc inside an APK (a lossless read of the binary table; `aapt dump --values resources`
escapes some characters and not others), and compares them with en.expected.tsv from
gen_catalog.py. It fails on any difference, which catches aapt's silent rewriting: stripped edge
spaces, collapsed whitespace, dropped escapes and quote marks. It also fails on compiled strings the
catalog does not know (a <string> outside strings*.xml) and on styled strings (markup spans).

  roundtrip.py --apk build/resources.apk --expected build/l10n/en.expected.tsv
  roundtrip.py --dump aapt-dump.txt --expected en.expected.tsv     (aapt dump text instead)
  roundtrip.py --configs --apk some.apk                             (list configurations)
"""

import argparse
import os
import struct
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l10nlib  # noqa: E402
import gen_catalog  # noqa: E402

RES_STRING_POOL = 0x0001
RES_TABLE = 0x0002
RES_TABLE_PACKAGE = 0x0200
RES_TABLE_TYPE = 0x0201
UTF8_FLAG = 0x100
FLAG_COMPLEX = 0x0001
FLAG_COMPACT = 0x0008
TYPE_FLAG_SPARSE = 0x01
TYPE_FLAG_OFFSET16 = 0x02
TYPE_STRING = 0x03
NO_ENTRY = 0xFFFFFFFF
PLURAL_KEYS = {0x01000004: "other", 0x01000005: "zero", 0x01000006: "one", 0x01000007: "two",
               0x01000008: "few", 0x01000009: "many"}


class ArscError(ValueError):
    pass


class Value(object):
    """One compiled value: text for a string, or a reference/other typed value."""
    __slots__ = ("text", "styled", "data_type", "data")

    def __init__(self, text=None, styled=False, data_type=None, data=None):
        self.text, self.styled, self.data_type, self.data = text, styled, data_type, data

    def describe(self):
        if self.text is not None:
            return repr(self.text) + (" (styled)" if self.styled else "")
        return "value type 0x%02x data 0x%08x" % (self.data_type, self.data)


class Entry(object):
    """One resource in one configuration. value is a Value, or {quantity: Value} for a plural bag."""
    __slots__ = ("type_name", "name", "config", "value", "bag")

    def __init__(self, type_name, name, config, value, bag):
        self.type_name, self.name, self.config, self.value, self.bag = type_name, name, config, value, bag


def _u16(data, at):
    return struct.unpack_from("<H", data, at)[0]


def _u32(data, at):
    return struct.unpack_from("<I", data, at)[0]


def _length8(data, at):
    first = data[at]
    if first & 0x80:
        return ((first & 0x7F) << 8) | data[at + 1], at + 2
    return first, at + 1


def _length16(data, at):
    first = _u16(data, at)
    if first & 0x8000:
        return ((first & 0x7FFF) << 16) | _u16(data, at + 2), at + 4
    return first, at + 2


def _decode_utf8(raw):
    try:
        return l10nlib.join_surrogates(raw.decode("utf-8", "surrogatepass"))
    except UnicodeDecodeError:
        # Some aapt builds write supplementary characters as CESU-8 surrogate pairs.
        return l10nlib.join_surrogates(raw.decode("utf-8", "surrogateescape"))


def read_string_pool(data, at):
    """(strings, styled_indexes) of the ResStringPool chunk at offset at."""
    chunk_type, header_size, size = struct.unpack_from("<HHI", data, at)
    if chunk_type != RES_STRING_POOL:
        raise ArscError("expected a string pool at 0x%x" % at)
    count, style_count, flags, strings_start, styles_start = struct.unpack_from("<IIIII", data, at + 8)
    offsets = struct.unpack_from("<%dI" % count, data, at + header_size)
    style_offsets = struct.unpack_from("<%dI" % style_count, data, at + header_size + 4 * count)
    strings = []
    for offset in offsets:
        p = at + strings_start + offset
        if flags & UTF8_FLAG:
            _, p = _length8(data, p)
            length, p = _length8(data, p)
            strings.append(_decode_utf8(bytes(data[p:p + length])))
        else:
            length, p = _length16(data, p)
            strings.append(l10nlib.join_surrogates(bytes(data[p:p + 2 * length]).decode("utf-16-le", "surrogatepass")))
    styled = set()
    for index, offset in enumerate(style_offsets):
        if _u32(data, at + styles_start + offset) != NO_ENTRY:
            styled.add(index)
    return strings, styled


def describe_config(data, at):
    """'default', or a short qualifier string such as 'en-rXA', 'ar-rXB' or 'v26'."""
    size = _u32(data, at)
    body = bytes(data[at + 4:at + size])
    if not any(body):
        return "default"
    parts = []

    def letters(two, base):
        if not two[0]:
            return ""
        if two[0] & 0x80:  # packed three-letter code
            first = two[1] & 0x1F
            second = ((two[1] & 0xE0) >> 5) | ((two[0] & 0x03) << 3)
            third = (two[0] & 0x7C) >> 2
            return "".join(chr(base + c) for c in (first, second, third))
        return two.decode("ascii", "replace")

    language = letters(body[4:6], ord("a"))
    country = letters(body[6:8], ord("0"))
    if language:
        parts.append(language)
    if country:
        parts.append("r" + country)
    density = _u16(data, at + 14) if size >= 16 else 0
    if density:
        parts.append({0xFFFE: "anydpi", 0xFFFF: "nodpi"}.get(density, "%ddpi" % density))
    sdk = _u16(data, at + 24) if size >= 28 else 0
    if sdk:
        parts.append("v%d" % sdk)
    rest = bytearray(body)
    for i in (4, 5, 6, 7, 10, 11, 20, 21):  # language, country, density, sdk already described
        if i < len(rest):
            rest[i] = 0
    if any(rest):
        parts.append("+other")
    return "-".join(parts) if parts else "other"


def read_arsc(data):
    """Every entry of a resources.arsc as a list of Entry."""
    data = memoryview(bytes(data))
    chunk_type, header_size, size = struct.unpack_from("<HHI", data, 0)
    if chunk_type != RES_TABLE:
        raise ArscError("not a resource table")
    at = header_size
    values, styled = None, set()
    entries = []
    while at < size:
        kind, h_size, c_size = struct.unpack_from("<HHI", data, at)
        if kind == RES_STRING_POOL and values is None:
            values, styled = read_string_pool(data, at)
        elif kind == RES_TABLE_PACKAGE:
            entries.extend(_read_package(data, at, values or [], styled))
        if c_size <= 0:
            raise ArscError("corrupt chunk size at 0x%x" % at)
        at += c_size
    return entries


def _read_package(data, at, values, styled):
    header_size, size = _u16(data, at + 2), _u32(data, at + 4)
    type_names, _ = read_string_pool(data, at + _u32(data, at + 268))
    key_names, _ = read_string_pool(data, at + _u32(data, at + 276))
    entries = []
    p = at + header_size
    while p < at + size:
        kind, h_size, c_size = struct.unpack_from("<HHI", data, p)
        if kind == RES_TABLE_TYPE:
            entries.extend(_read_type(data, p, type_names, key_names, values, styled))
        p += c_size
    return entries


def _value(data, at, values, styled):
    data_type, raw = data[at + 3], _u32(data, at + 4)
    if data_type == TYPE_STRING:
        return Value(values[raw], raw in styled)
    return Value(None, False, data_type, raw)


def _read_type(data, at, type_names, key_names, values, styled):
    header_size = _u16(data, at + 2)
    type_id, flags = data[at + 8], data[at + 9]
    count, entries_start = _u32(data, at + 12), _u32(data, at + 16)
    config = describe_config(data, at + 20)
    type_name = type_names[type_id - 1]
    offsets = []
    if flags & TYPE_FLAG_SPARSE:
        for i in range(count):
            index, offset = struct.unpack_from("<HH", data, at + header_size + 4 * i)
            offsets.append((index, offset * 4))
    elif flags & TYPE_FLAG_OFFSET16:
        for i in range(count):
            offset = _u16(data, at + header_size + 2 * i)
            if offset != 0xFFFF:
                offsets.append((i, offset * 4))
    else:
        for i in range(count):
            offset = _u32(data, at + header_size + 4 * i)
            if offset != NO_ENTRY:
                offsets.append((i, offset))
    entries = []
    for _, offset in offsets:
        e = at + entries_start + offset
        size, entry_flags = _u16(data, e), _u16(data, e + 2)
        if entry_flags & FLAG_COMPACT:
            name = key_names[size]
            entries.append(Entry(type_name, name, config, Value(None, False, entry_flags >> 8, _u32(data, e + 4)), None))
            continue
        name = key_names[_u32(data, e + 4)]
        if entry_flags & FLAG_COMPLEX:
            bag_count = _u32(data, e + 12)
            bag = {}
            m = e + size
            for _ in range(bag_count):
                key = _u32(data, m)
                bag[PLURAL_KEYS.get(key, "0x%08x" % key)] = _value(data, m + 4, values, styled)
                m += 12
            entries.append(Entry(type_name, name, config, None, bag))
        else:
            entries.append(Entry(type_name, name, config, _value(data, e + size, values, styled), None))
    return entries


def read_apk(path):
    with zipfile.ZipFile(path) as archive:
        return read_arsc(archive.read("resources.arsc"))


def configurations(entries):
    return sorted(set(e.config for e in entries))


def compiled_english(entries):
    """({(kind, name, quantity): Value}, problems) for default-configuration strings and plurals."""
    result, problems = {}, []
    for entry in entries:
        if entry.config != "default":
            continue
        if entry.type_name == "string":
            if entry.value is None:
                problems.append("string/%s is a bag, not text" % entry.name)
                continue
            result[("string", entry.name, "other")] = entry.value
        elif entry.type_name == "plurals":
            for quantity, value in (entry.bag or {}).items():
                result[("plural", entry.name, quantity)] = value
    return result, problems


def read_dump(text):
    """The same mapping from `aapt dump --values resources` output (default configuration only).

    aapt prints strings inside double quotes and escapes only \\, " and line breaks; a tab or other
    control character is printed raw. Use read_apk when possible: it reads the bytes aapt wrote.
    """
    import re
    result = {}
    config, current = None, None
    for line in text.split("\n"):
        stripped = line.strip()
        if stripped.startswith("config "):
            config = stripped[len("config "):].rstrip(":")
            continue
        m = re.match(r"resource 0x[0-9a-f]+ [^:]+:(string|plurals)/([A-Za-z0-9_.]+): (.*)$", stripped)
        if m:
            current = (m.group(1), m.group(2)) if config == "(default)" else None
            continue
        if current is None:
            continue
        m = re.match(r"(?:#\d+ \(Key=0x([0-9a-f]+)\): )?\(string\d+\) \"(.*)\"$", stripped)
        if not m and line.startswith(" ") and "(string" in line:
            m = re.match(r"\s*(?:#\d+ \(Key=0x([0-9a-f]+)\): )?\(string\d+\) \"(.*)\"$", line)
        if m:
            value = _undump(m.group(2))
            if current[0] == "string":
                result[("string", current[1], "other")] = Value(value)
                current = None
            else:
                result[("plural", current[1], PLURAL_KEYS.get(int(m.group(1), 16), m.group(1)))] = Value(value)
    return result


def _undump(value):
    out, i = [], 0
    while i < len(value):
        c = value[i]
        if c == "\\" and i + 1 < len(value):
            out.append({"n": "\n", '"': '"', "\\": "\\"}.get(value[i + 1], "\\" + value[i + 1]))
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def explain(expected, actual):
    """Why aapt's text differs from the expected text, in words."""
    space = " \t\n\r\f\v"
    collapsed = " ".join(expected.split(None)) if expected.strip(space) else ""
    if actual == expected.strip(space):
        return "aapt stripped leading or trailing whitespace; wrap the whole string in \"...\""
    if actual == collapsed or actual == " ".join(expected.replace("\t", " ").replace("\n", " ").split(" ")):
        return "aapt collapsed or trimmed whitespace; wrap the whole string in \"...\""
    if sorted(actual) != sorted(expected) and len(actual) < len(expected) and \
            set(expected) - set(actual) <= set("\"'\\ rqf"):
        return "aapt dropped quote marks, apostrophes or escapes"
    for i, (a, b) in enumerate(zip(expected, actual)):
        if a != b:
            return "first difference at offset %d: expected %r, aapt has %r" % (i, expected[i:i + 12], actual[i:i + 12])
    return "aapt's text is %s than expected" % ("shorter" if len(actual) < len(expected) else "longer")


def compare(expected, compiled):
    """Problems, one line each, comparing {(kind, name, quantity): text} with compiled Values."""
    problems = []
    for key in sorted(set(expected) | set(compiled)):
        kind, name, quantity = key
        label = "%s/%s%s" % ("plurals" if kind == "plural" else "string", name,
                             "[%s]" % quantity if kind == "plural" else "")
        if key not in compiled:
            problems.append("%s: in the catalog but not compiled by aapt" % label)
            continue
        value = compiled[key]
        if key not in expected:
            problems.append("%s: compiled by aapt but not in the catalog (only res/values/strings*.xml is read)" % label)
            continue
        if value.text is None:
            problems.append("%s: compiled as %s, not text" % (label, value.describe()))
        elif value.styled:
            problems.append("%s: compiled with style spans; catalog text must be plain" % label)
        elif value.text != expected[key]:
            problems.append("%s: expected %r, aapt compiled %r: %s" % (
                label, expected[key], value.text, explain(expected[key], value.text)))
    return problems


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--apk", help="an APK (or resources.apk) holding resources.arsc")
    source.add_argument("--arsc", help="a resources.arsc file")
    source.add_argument("--dump", help="output of aapt dump --values resources")
    parser.add_argument("--expected", help="en.expected.tsv from gen_catalog.py")
    parser.add_argument("--configs", action="store_true", help="list the configurations and exit")
    args = parser.parse_args(argv)
    if args.dump:
        if args.configs:
            parser.error("--configs needs --apk or --arsc")
        compiled, problems = read_dump(l10nlib.read_text(args.dump)), []
    else:
        try:
            entries = read_apk(args.apk) if args.apk else read_arsc(open(args.arsc, "rb").read())
        except (ArscError, KeyError, struct.error, zipfile.BadZipFile) as error:
            print("roundtrip: cannot read the resource table: %s" % error, file=sys.stderr)
            return 2
        if args.configs:
            print("\n".join(configurations(entries)))
            return 0
        compiled, problems = compiled_english(entries)
    if not args.expected:
        parser.error("--expected is required")
    expected = gen_catalog.read_expected_tsv(l10nlib.read_text(args.expected))
    problems += compare(expected, compiled)
    if problems:
        for problem in problems:
            print("roundtrip: " + problem, file=sys.stderr)
        print("roundtrip: FAILED, %d difference(s) between the catalog English and what aapt compiled." % len(problems),
              file=sys.stderr)
        return 1
    strings = sum(1 for k in expected if k[0] == "string")
    print("roundtrip: %d strings and %d plural items compiled exactly as the catalog says." % (
        strings, len(expected) - strings))
    return 0


if __name__ == "__main__":
    sys.exit(main())
