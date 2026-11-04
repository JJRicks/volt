#!/usr/bin/env python3
"""Generate Volt's typed text catalog from res/values/strings*.xml (plan sections 1.1 and 1.2).

Outputs, all into a build directory and never committed:
  <out>/com/jjricks/volt/T.java            accessors typed from positional placeholders, plus T.Source
  <out>/com/jjricks/volt/M.java            deferred messages (needs the hand-written Msg class, B0b)
  <out>/com/jjricks/volt/EnglishText.java  the English values baked into Java; T's default source
  <out>/com/jjricks/volt/AndroidText.java  only with --android: ids mapped to R.string / R.plurals
  <out>/com/jjricks/volt/AppLanguages.java the language table from l10n/lang/*.tsv
  <tsv>                                     en.expected.tsv, the English the round-trip compares with aapt

The generator refuses any string that aapt would silently store differently from what it says
(edge or repeated whitespace, raw line breaks, unknown escapes, stray quotes), so EnglishText and
the compiled resources agree. tools/l10n/roundtrip.py checks that against real aapt output. It also
refuses text resources in any res/values-* folder that is not an approved non-English language
folder from l10n/lang/*.tsv (values-en, values-night or values-v31 text would override the catalog).

Msg contract (B0b writes Msg.java): static Msg of(int id, Object... args) and
static Msg plural(int id, long quantity, Object... args). Rendering goes through
T.render(id, args) and T.renderPlural(id, quantity, args).
"""

import argparse
import collections
import keyword
import os
import re
import sys
import xml.parsers.expat

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l10nlib  # noqa: E402

VOLT_NS = "https://volt.jjricks.com/ns/l10n"
XLIFF_NS = "urn:oasis:names:tc:xliff:document:1.2"
TOOLS_NS = "http://schemas.android.com/tools"
PACKAGE = "com.jjricks.volt"
TAG_KEY = "l10n_tag"

QUANTITIES = ("zero", "one", "two", "few", "many", "other")
# The items an English plural in res/values may have: EnglishText serves "one" for exactly 1, "other" otherwise.
ENGLISH_QUANTITIES = ("one", "other")
LADDERS = ("full", "short", "compact")
SAFETY = ("lock", "ride", "alarm", "charge", "experimental", "owner-key", "pin", "privacy", "disclaimer")
VOLT_ATTRIBUTES = ("screen", "purpose", "max", "ladder", "safety", "dnt", "english-fix")
KEY_PATTERN = re.compile(r"^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$")
MAX_PATTERN = re.compile(r"^\d+(?:\.\d+)?dp@\d+(?:\.\d+)?sp$")
ESCAPES = {"n": "\n", "t": "\t", "'": "'", '"': '"', "\\": "\\", "@": "@", "?": "?", "#": "#"}
ASCII_SPACE = " \t\n\r\f\v"
JAVA_TYPES = {"s": "CharSequence", "d": "long", "f": "double"}
# Names T itself uses; a key must not camel-case to one of them.
RESERVED = {"install", "source", "language", "render", "renderPlural", "key", "isPlural", "isFormatted",
            "size", "raw", "rawPlural", "format", "english", "getClass", "hashCode", "equals",
            "toString", "notify", "notifyAll", "wait", "clone", "finalize"}
PLACEHOLDER = re.compile(r"%(?:(\d+)\$)?([-#+ 0,(]*)(\d+)?(?:\.(\d+))?([a-zA-Z%])")
CHUNK = 400

LANG_FIELDS = ("tag", "folder", "endonym", "wave", "status", "formality", "plurals", "quote_style",
               "percent_style", "decimal_style", "script", "font", "rtl", "unit_symbols", "drive_mode_names")
LANG_STATUS = ("held", "draft", "beta", "stable")


class CatalogError(Exception):
    def __init__(self, problems):
        Exception.__init__(self, "\n".join(problems))
        self.problems = problems


Item = collections.namedtuple("Item", "quantity raw text args problems line")
Entry = collections.namedtuple("Entry", "name kind items meta translatable formatted file line note")


# ---------------------------------------------------------------------------
# Decoding one resource string
# ---------------------------------------------------------------------------

def decode_resource_text(raw):
    """(text, problems, wrapped) for the character data of one <string> or <item>.

    text is what the developer wrote: escapes decoded, a whole-string "..." wrapper removed and
    whitespace kept exactly. problems lists every way aapt would store something else, or reject it.
    """
    problems = []
    units = []  # (text, escaped)
    i, n = 0, len(raw)
    while i < n:
        c = raw[i]
        if c != "\\":
            units.append((c, False))
            i += 1
            continue
        if i + 1 >= n:
            problems.append("ends with a lone backslash")
            units.append(("\\", True))
            i += 1
            continue
        e = raw[i + 1]
        if e in ESCAPES:
            units.append((ESCAPES[e], True))
            i += 2
        elif e == "u":
            digits = raw[i + 2:i + 6]
            if len(digits) == 4 and all(d in "0123456789abcdefABCDEF" for d in digits):
                # aapt keeps \u escapes as written, even spaces at the edges and quote marks.
                units.append((chr(int(digits, 16)), True))
                i += 6
            else:
                problems.append("bad \\u escape %r: aapt rejects it" % raw[i:i + 6])
                units.append((raw[i:i + 2], True))
                i += 2
        else:
            problems.append("unknown escape \\%s: aapt drops it silently" % e)
            units.append(("\\" + e, True))
            i += 2

    quotes = [k for k, (ch, esc) in enumerate(units) if ch == '"' and not esc]
    wrapped = len(units) >= 2 and quotes == [0, len(units) - 1]
    if wrapped:
        units = units[1:-1]
    elif quotes:
        # The text keeps the mark the writer typed; aapt drops it, so the round-trip fails too.
        problems.append('unescaped " that does not wrap the whole string: aapt reads it as a quote mark '
                        'and drops it; write \\" or wrap the whole string in "..."')

    if not wrapped and units:
        plain = [(ch, esc) for ch, esc in units]
        if any(ch == "'" and not esc for ch, esc in plain):
            problems.append("unescaped apostrophe: aapt rejects it; write \\' or wrap the whole string in \"...\"")
        if not plain[0][1] and plain[0][0] in ASCII_SPACE:
            problems.append("leading whitespace: aapt strips it unless the whole string is wrapped in \"...\"")
        if len(plain) > 0 and not plain[-1][1] and plain[-1][0] in ASCII_SPACE:
            problems.append("trailing whitespace: aapt strips it unless the whole string is wrapped in \"...\"")
        if any(ch in ASCII_SPACE and ch != " " and not esc for ch, esc in plain):
            problems.append("raw tab or line break: aapt turns it into a space; write \\n or \\t")
        elif any(plain[k] == (" ", False) and plain[k + 1] == (" ", False) for k in range(len(plain) - 1)):
            problems.append("repeated spaces: aapt collapses them to one unless the whole string is wrapped in \"...\"")
        if not plain[0][1] and plain[0][0] in "@?":
            problems.append("starts with %s: aapt reads a resource reference; write \\%s" % (plain[0][0], plain[0][0]))
    text = l10nlib.join_surrogates("".join(ch for ch, _ in units))
    return text, problems, wrapped


def analyze_format(text):
    """(args, problems): args maps placeholder index to its conversion (s, d or f)."""
    args, problems = {}, []
    at = text.find("%")
    while at >= 0:
        m = PLACEHOLDER.match(text, at)
        if not m:
            problems.append("lone %% at offset %d: write %%%% for a percent sign" % at)
            at = text.find("%", at + 1)
            continue
        index, flags, width, precision, conversion = m.groups()
        if conversion == "%":
            if index or flags or width or precision:
                problems.append("malformed %r: write %%%% for a percent sign" % m.group(0))
        elif not index and " " in flags:
            problems.append("lone %% followed by a space: aapt and Java read %r as a format; write %%%% for a "
                            "percent sign" % m.group(0))
        elif not index:
            problems.append("non-positional %r: write %%1$%s (positional only)" % (m.group(0), conversion))
        elif conversion not in JAVA_TYPES:
            problems.append("unsupported conversion %r: use s, d or f, and pass other values as %%n$s" % m.group(0))
        elif conversion == "s" and (flags or width or precision):
            # Fmt.compose finds each %n$s argument by private-use marks around it: a precision would cut
            # the end mark off (leaking a mark into the text), and padding or a flag would move or change it.
            problems.append("width, precision or flags on %r: a %%n$s argument is printed whole (Fmt.compose marks "
                            "its ends); write %%%s$s" % (m.group(0), index))
        else:
            i = int(index)
            if i < 1:
                problems.append("placeholder index must start at 1: %r" % m.group(0))
            elif i in args and args[i] != conversion:
                problems.append("%%%d$ is used as both %s and %s" % (i, args[i], conversion))
            else:
                args[i] = conversion
        at = text.find("%", m.end())
    return args, problems


def contiguous(args):
    return not args or sorted(args) == list(range(1, max(args) + 1))


# ---------------------------------------------------------------------------
# Reading strings*.xml
# ---------------------------------------------------------------------------

class _Node(object):
    __slots__ = ("tag", "attrs", "children", "line", "comment")

    def __init__(self, tag, attrs, line):
        self.tag, self.attrs, self.children, self.line, self.comment = tag, attrs, [], line, None


def _parse_xml(path):
    """A small tree with line numbers and the comment before each element. Text is str children."""
    parser = xml.parsers.expat.ParserCreate(namespace_separator=" ")
    root = _Node("#document", {}, 0)
    stack = [root]
    pending = {"comment": None}

    def start(tag, attrs):
        node = _Node(tag, attrs, parser.CurrentLineNumber)
        node.comment = pending["comment"]
        pending["comment"] = None
        stack[-1].children.append(node)
        stack.append(node)

    def end(tag):
        stack.pop()
        pending["comment"] = None

    def chars(data):
        parent = stack[-1]
        if parent.children and isinstance(parent.children[-1], str):
            parent.children[-1] += data
        else:
            parent.children.append(data)
        if data.strip():
            pending["comment"] = None

    def comment(data):
        pending["comment"] = data.strip()

    parser.StartElementHandler = start
    parser.EndElementHandler = end
    parser.CharacterDataHandler = chars
    parser.CommentHandler = comment
    parser.buffer_text = True
    with open(path, "rb") as handle:
        try:
            parser.ParseFile(handle)
        except xml.parsers.expat.ExpatError as error:
            raise CatalogError(["%s:%s: XML error: %s" % (path, error.lineno, error)])
    elements = [c for c in root.children if isinstance(c, _Node)]
    return elements[0] if elements else None


def _local(name):
    return name.split(" ", 1)[1] if " " in name else name


def _namespace(name):
    return name.split(" ", 1)[0] if " " in name else ""


def _flatten(node, where, problems):
    """Character data of a <string> or <item>, with <xliff:g> unwrapped; other markup is refused."""
    parts = []
    for child in node.children:
        if isinstance(child, str):
            parts.append(child)
        elif _namespace(child.tag) == XLIFF_NS and _local(child.tag) == "g":
            parts.append(_flatten(child, where, problems))
        else:
            problems.append("%s: markup <%s> is not supported in catalog text (only <xliff:g>)" % (where, _local(child.tag)))
    return "".join(parts)


def _read_meta(node, where, problems, allowed_plain):
    meta, plain = {}, {}
    for name, value in node.attrs.items():
        ns, local = _namespace(name), _local(name)
        if ns == VOLT_NS:
            if local not in VOLT_ATTRIBUTES:
                problems.append("%s: unknown attribute volt:%s (known: %s)" % (where, local, ", ".join(VOLT_ATTRIBUTES)))
            meta[local] = value
        elif ns == TOOLS_NS:
            continue
        elif ns:
            problems.append("%s: attribute from unknown namespace %s" % (where, ns))
        elif local in allowed_plain:
            plain[local] = value
        else:
            problems.append("%s: unsupported attribute %s" % (where, local))
    if "ladder" in meta and meta["ladder"] not in LADDERS:
        problems.append("%s: volt:ladder must be one of %s" % (where, ", ".join(LADDERS)))
    if "safety" in meta:
        for value in re.split(r"[,\s]+", meta["safety"].strip()):
            if value not in SAFETY:
                problems.append("%s: volt:safety %r is not one of %s" % (where, value, ", ".join(SAFETY)))
    if "max" in meta and not MAX_PATTERN.match(meta["max"]):
        problems.append("%s: volt:max must look like 96dp@14sp" % where)
    if "english-fix" in meta and meta["english-fix"] != "pending":
        problems.append("%s: volt:english-fix may only be \"pending\"" % where)
    return meta, plain


def _item(raw, quantity, where, line, formatted, problems):
    text, item_problems, _ = decode_resource_text(raw)
    args = {}
    if formatted:
        args, format_problems = analyze_format(text)
        item_problems = item_problems + format_problems
    for problem in item_problems:
        problems.append("%s: %s" % (where, problem))
    return Item(quantity, raw, text, args, item_problems, line)


def read_strings_file(path, display=None):
    """Entries of one strings*.xml file, and the problems found."""
    display = display or path
    problems, entries = [], []
    try:
        root = _parse_xml(path)
    except CatalogError as error:
        return [], error.problems
    if root is None or root.tag != "resources":
        return [], ["%s: the root element must be <resources>" % display]
    for node in root.children:
        if isinstance(node, str):
            if node.strip():
                problems.append("%s: text outside any element" % display)
            continue
        kind = _local(node.tag)
        where = "%s:%d" % (display, node.line)
        name = node.attrs.get("name", "")
        if kind not in ("string", "plurals"):
            problems.append("%s: <%s> is not allowed in a strings file (only <string> and <plurals>; "
                            "string-arrays are banned for UI text)" % (where, kind))
            continue
        where = "%s: %s" % (where, name or "<no name>")
        if not KEY_PATTERN.match(name):
            problems.append("%s: key names are lower_snake_case starting with a letter" % where)
        meta, plain = _read_meta(node, where, problems, ("name", "translatable", "formatted"))
        translatable = plain.get("translatable", "true") != "false"
        formatted = plain.get("formatted", "true") != "false"
        for attribute in ("translatable", "formatted"):
            if plain.get(attribute, "true") not in ("true", "false"):
                problems.append("%s: %s must be true or false" % (where, attribute))
        items = collections.OrderedDict()
        if kind == "string":
            raw = _flatten(node, where, problems)
            items["other"] = _item(raw, "other", where, node.line, formatted, problems)
        else:
            for child in node.children:
                if isinstance(child, str):
                    if child.strip():
                        problems.append("%s: text outside <item>" % where)
                    continue
                if _local(child.tag) != "item":
                    problems.append("%s: <plurals> holds only <item> elements" % where)
                    continue
                quantity = child.attrs.get("quantity", "")
                if quantity not in QUANTITIES:
                    problems.append("%s: quantity %r is not one of %s" % (where, quantity, ", ".join(QUANTITIES)))
                    continue
                if quantity in items:
                    problems.append("%s: quantity %s appears twice" % (where, quantity))
                    continue
                raw = _flatten(child, where, problems)
                items[quantity] = _item(raw, quantity, "%s[%s]" % (where, quantity), child.line, formatted, problems)
            if "other" not in items:
                problems.append("%s: <plurals> needs an \"other\" item" % where)
        entries.append(Entry(name, "plural" if kind == "plurals" else "string", items, meta, translatable,
                             formatted, display, node.line, node.comment))
    return entries, problems


def signature(entry):
    """{index: conversion} across every item of an entry."""
    merged = {}
    for item in entry.items.values():
        for index, conversion in item.args.items():
            merged.setdefault(index, conversion)
    return merged


def camel(name):
    parts = name.split("_")
    return parts[0] + "".join(p[:1].upper() + p[1:] for p in parts[1:])


def load_catalog(values_dir, display_root=None):
    """(entries sorted by name, tag entry or None). Raises CatalogError listing every problem."""
    problems, entries = [], []
    if not os.path.isdir(values_dir):
        raise CatalogError(["%s: not a directory" % values_dir])
    names = sorted(f for f in os.listdir(values_dir) if re.match(r"^strings.*\.xml$", f))
    for filename in names:
        path = os.path.join(values_dir, filename)
        inside = display_root and os.path.abspath(path).startswith(os.path.abspath(display_root) + os.sep)
        display = os.path.relpath(path, display_root) if inside else path
        file_entries, file_problems = read_strings_file(path, display)
        entries.extend(file_entries)
        problems.extend(file_problems)

    by_name = {}
    for entry in entries:
        if entry.name in by_name:
            first = by_name[entry.name]
            problems.append("%s:%d: %s: duplicate key (also %s:%d); aapt rejects it" % (
                entry.file, entry.line, entry.name, first.file, first.line))
        else:
            by_name[entry.name] = entry

    accessors = {}
    for entry in by_name.values():
        where = "%s:%d: %s" % (entry.file, entry.line, entry.name)
        args = signature(entry)
        conflicts = set()
        for item in entry.items.values():
            for index, conversion in item.args.items():
                if args[index] != conversion:
                    conflicts.add(index)
        for index in sorted(conflicts):
            problems.append("%s: %%%d$ has different conversions in different items" % (where, index))
        if not contiguous(args):
            problems.append("%s: placeholders must be numbered %%1$ to %%%d$ with no gaps" % (where, max(args)))
        if "dnt" in entry.meta:
            for token in [t for t in entry.meta["dnt"].split("|") if t]:
                if not any(token in item.text for item in entry.items.values()):
                    problems.append("%s: volt:dnt token %r does not appear in the text" % (where, token))
        if entry.kind == "plural":
            for quantity in entry.items:
                if quantity not in ENGLISH_QUANTITIES:
                    problems.append("%s: an English plural holds only \"one\" and \"other\" items; EnglishText never "
                                    "shows a %s item, but Android would on a phone whose first language has that "
                                    "category" % (where, quantity))
        if entry.name == TAG_KEY:
            if entry.kind != "string" or entry.items["other"].text != "en":
                problems.append("%s: the default l10n_tag must be the string \"en\"" % where)
            continue
        ladder = entry.meta.get("ladder")
        if ladder in ("short", "compact"):
            suffix = "_" + ladder
            base = by_name.get(entry.name[:-len(suffix)]) if entry.name.endswith(suffix) else None
            if base is None:
                problems.append("%s: a volt:ladder=\"%s\" rung must be named <full key>%s beside its full key" % (
                    where, ladder, suffix))
            else:
                base_args = signature(base)
                for index, conversion in args.items():
                    if base_args.get(index) != conversion:
                        problems.append("%s: rung placeholder %%%d$%s is not in %s" % (where, index, conversion, base.name))
        accessor = camel(entry.name)
        if keyword.iskeyword(accessor) or accessor in ("true", "false", "null") or accessor in RESERVED:
            problems.append("%s: key camel-cases to %r, which Java or T already uses" % (where, accessor))
        elif accessor in accessors:
            problems.append("%s: key camel-cases to %r, as %s does" % (where, accessor, accessors[accessor]))
        else:
            accessors[accessor] = entry.name
    if problems:
        raise CatalogError(problems)
    ordered = sorted(by_name.values(), key=lambda e: e.name)
    tag = by_name.get(TAG_KEY)
    return [e for e in ordered if e.name != TAG_KEY], tag


# ---------------------------------------------------------------------------
# Languages
# ---------------------------------------------------------------------------

Language = collections.namedtuple("Language", LANG_FIELDS)


def load_languages(lang_dir):
    problems, languages = [], []
    if not os.path.isdir(lang_dir):
        raise CatalogError(["%s: not a directory" % lang_dir])
    for filename in sorted(os.listdir(lang_dir)):
        if not filename.endswith(".tsv"):
            continue
        path = os.path.join(lang_dir, filename)
        try:
            _, rows = l10nlib.read_tsv(l10nlib.read_text(path), ("field", "value"))
        except ValueError as error:
            problems.append("%s: %s" % (path, error))
            continue
        values = {}
        for line, (field, value) in rows:
            if field not in LANG_FIELDS:
                problems.append("%s:%d: unknown field %s" % (path, line, field))
            elif field in values:
                problems.append("%s:%d: %s appears twice" % (path, line, field))
            else:
                values[field] = value
        missing = [f for f in LANG_FIELDS if f not in values]
        if missing:
            problems.append("%s: missing fields %s" % (path, ", ".join(missing)))
            continue
        lang = Language(**values)
        if lang.tag != filename[:-4]:
            problems.append("%s: tag %s does not match the file name" % (path, lang.tag))
        if lang.status not in LANG_STATUS:
            problems.append("%s: status must be one of %s" % (path, ", ".join(LANG_STATUS)))
        if lang.font not in ("saira", "system"):
            problems.append("%s: font must be saira or system" % path)
        if lang.rtl not in ("true", "false"):
            problems.append("%s: rtl must be true or false" % path)
        if not re.match(r"^values(-[A-Za-z0-9+]+)*$", lang.folder):
            problems.append("%s: folder must be values or values-<qualifier>" % path)
        plurals = lang.plurals.split(",")
        if "other" not in plurals or any(p not in QUANTITIES for p in plurals):
            problems.append("%s: plurals must list CLDR categories including other" % path)
        if lang.folder != "values" and re.match(r"^values(-[A-Za-z0-9+]+)*$", lang.folder):
            problems.extend("%s: %s" % (path, p) for p in folder_locale_problems(lang.folder))
        languages.append(lang)
    english = [l for l in languages if l.tag == "en"]
    if not english or english[0].folder != "values":
        problems.append("%s: en.tsv must exist with folder values" % lang_dir)
    if problems:
        raise CatalogError(problems)
    others = sorted((l for l in languages if l.tag != "en"), key=lambda l: (l.wave, l.tag))
    return english + others


# Legacy and equivalent language codes, as Android compares them (Locale.adjustLanguageCode, langsAreEquivalent).
MODERN_LANGUAGE = {"in": "id", "iw": "he", "ji": "yi", "tl": "fil"}
# CLDR parent regions (parentLocales) that are not just the language: AndroidText's folder resolution ranks a
# folder of the phone's own region first, then the folder without a region, so a folder for a region that other
# regions inherit from (es-419, pt-PT for pt-AO, zh-Hant-HK for zh-Hant-MO) would be ranked wrongly.
PARENT_REGIONS = {"pt": ("PT",), "zh": ("HK",)}


def folder_locale(folder):
    """(language, script, region) of a values-<language> folder as aapt reads it, or None for any other folder.

    values-es, values-pt-rBR and values-b+zh+Hans give (es, "", ""), (pt, "", BR) and (zh, Hans, ""). The language is
    the modern code (values-in gives id), the one AndroidText compares with the phone's.
    """
    if not folder.startswith("values-"):
        return None
    qualifier = folder[len("values-"):]
    if qualifier.startswith("b+"):
        parts = qualifier[2:].split("+")
        language, script, region = parts[0].lower(), "", ""
        if not re.match(r"^[a-z]{2,3}$", language):
            return None
        for part in parts[1:]:
            if re.match(r"^[A-Za-z]{4}$", part) and not script and not region:
                script = part[0].upper() + part[1:].lower()
            elif re.match(r"^(?:[A-Za-z]{2}|\d{3})$", part) and not region:
                region = part.upper()
            else:
                return None
    else:
        match = re.match(r"^([a-z]{2,3})(?:-r([A-Z]{2}))?$", qualifier)
        if not match:
            return None
        language, script, region = match.group(1), "", match.group(2) or ""
    return MODERN_LANGUAGE.get(language, language), script, region


def folder_locale_problems(folder):
    parsed = folder_locale(folder)
    if parsed is None:
        return ["folder %s is not a plain language folder (values-ll, values-ll-rRR or values-b+ll+Ssss+RR, "
                "with no other qualifier)" % folder]
    language, _, region = parsed
    if region.isdigit() or region in PARENT_REGIONS.get(language, ()):
        return ["folder %s names a region other regions inherit from (CLDR parentLocales); AndroidText's plural "
                "folder resolution does not model region parents (see l10n/README.md)" % folder]
    return []


TEXT_ELEMENTS = ("string", "plurals", "string-array")


def _text_resources(path):
    """Names of the text resources (string, plurals, string-array, item type=string) one values XML defines."""
    names, depth = [], [0]

    def start(name, attributes):
        depth[0] += 1
        if depth[0] == 2 and (name in TEXT_ELEMENTS or (name == "item" and attributes.get("type") in TEXT_ELEMENTS)):
            names.append("%s/%s" % (attributes.get("type", name), attributes.get("name", "?")))

    def end(name):
        depth[0] -= 1
    parser = xml.parsers.expat.ParserCreate()
    parser.StartElementHandler, parser.EndElementHandler = start, end
    with open(path, "rb") as handle:
        parser.ParseFile(handle)
    return names


def check_text_folders(res_dir, languages, display_root=None):
    """Problems: text resources outside res/values/ that are not an approved, non-English language folder.

    A qualified folder (values-en, values-en-rGB, values-night, values-v31, ...) would override the catalog
    English on every phone it matches, while EnglishText, the tests and the round-trip still see the default.
    Only language folders listed in l10n/lang/*.tsv may hold text, and never an English one.
    """
    allowed = set(l.folder for l in languages if l.tag != "en" and not l.tag.startswith("en-"))
    problems = []
    if not os.path.isdir(res_dir):
        return problems
    for folder in sorted(os.listdir(res_dir)):
        if not folder.startswith("values-") or folder in allowed:
            continue
        directory = os.path.join(res_dir, folder)
        if not os.path.isdir(directory):
            continue
        for filename in sorted(os.listdir(directory)):
            if not filename.endswith(".xml"):
                continue
            path = os.path.join(directory, filename)
            display = os.path.relpath(path, display_root) if display_root else path
            try:
                names = _text_resources(path)
            except xml.parsers.expat.ExpatError as error:
                problems.append("%s: not well-formed XML: %s" % (display, error))
                continue
            if names:
                problems.append("%s: %s in %s would replace the catalog English wherever %s matches; only language "
                                "folders from l10n/lang/*.tsv (not English) may hold text" % (
                                    display, ", ".join(names[:3]) + (" ..." if len(names) > 3 else ""), folder, folder))
    return problems


# script is the one the folder name gives ("" when it names none); writing is the l10n/lang script when it does not.
Folder = collections.namedtuple("Folder", "name tag language script region writing")


def load_folder_plurals(res_dir, languages, entries, display_root=None):
    """(folders, held): the language folders under res_dir that hold an English plural key, and for each such key
    the folders that hold it with their items, [(folder index, [(quantity, text), ...])].

    AndroidText decides from this which folder Android serves a plural from, and so whose plural rules apply
    (plan section 1.3). A text is None when aapt would store it differently from how it is written (AndroidText
    then keeps Android's own choice). Raises CatalogError when two folders could serve a key and AndroidText could
    not tell which one Android picks.
    """
    plural_names = set(e.name for e in entries if e.kind == "plural")
    folders, held, problems = [], collections.OrderedDict(), []
    for lang in languages:
        directory = os.path.join(res_dir, lang.folder)
        parsed = folder_locale(lang.folder)
        if lang.folder == "values" or parsed is None or not os.path.isdir(directory):
            continue
        found = collections.OrderedDict()
        for filename in sorted(os.listdir(directory)):
            if not filename.endswith(".xml"):
                continue
            path = os.path.join(directory, filename)
            display = os.path.relpath(path, display_root) if display_root else path
            try:
                root = _parse_xml(path)
            except CatalogError as error:
                problems.extend(error.problems)
                continue
            for node in (root.children if root is not None else []):
                if isinstance(node, str) or _local(node.tag) != "plurals" or node.attrs.get("name") not in plural_names:
                    continue
                items = []
                for child in node.children:
                    if isinstance(child, str) or _local(child.tag) != "item":
                        continue
                    ignored = []  # per item: one item's markup must not blank its plain siblings
                    text, item_problems, _ = decode_resource_text(_flatten(child, display, ignored))
                    items.append((child.attrs.get("quantity", ""), None if item_problems or ignored else text))
                found[node.attrs["name"]] = items
        if not found:
            continue
        folders.append(Folder(lang.folder, lang.tag, parsed[0], parsed[1], parsed[2], parsed[1] or lang.script))
        for name, items in found.items():
            held.setdefault(name, []).append((len(folders) - 1, items))
    for name, holders in held.items():
        groups = collections.OrderedDict()
        for index, _ in holders:
            folder = folders[index]
            groups.setdefault((folder.language, folder.writing), []).append(folder)
        for group in groups.values():
            regional = [f.name for f in group if f.region]
            if len(regional) > 1 and all(f.region for f in group):
                problems.append("%s: plural %s is in %s but not in their language's folder without a region; Android "
                                "would pick one of them for any other region by CLDR region distance, which "
                                "AndroidText does not model, so add it there too" % (
                                    res_dir, name, ", ".join(regional)))
    if problems:
        raise CatalogError(problems)
    return folders, held


# ---------------------------------------------------------------------------
# Java output
# ---------------------------------------------------------------------------

def java_string(value):
    out = ['"']
    for ch in value:
        o = ord(ch)
        if ch == "\\":
            out.append("\\\\")
        elif ch == '"':
            out.append('\\"')
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\t":
            out.append("\\t")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\b":
            out.append("\\b")
        elif ch == "\f":
            out.append("\\f")
        elif o < 0x20 or o == 0x7f:
            out.append("\\%03o" % o)
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)


def javadoc_text(value):
    """Text safe inside a Javadoc comment: no comment end, no unicode-escape trigger, HTML-escaped."""
    out = []
    for ch in value:
        if ch == "&":
            out.append("&amp;")
        elif ch == "<":
            out.append("&lt;")
        elif ch == ">":
            out.append("&gt;")
        elif ch == "@":
            out.append("&#64;")
        elif ch == "\\":
            out.append("&#92;")
        elif ch == "\n":
            out.append("&#92;n")
        elif ch == "\t":
            out.append("&#92;t")
        elif ord(ch) < 0x20 or ord(ch) == 0x7f or ch in (chr(0x2028), chr(0x2029)):
            out.append("&#%d;" % ord(ch))
        else:
            out.append(ch)
    return "".join(out).replace("*/", "*&#47;")


def constant(name):
    return name.upper()


def parameters(entry):
    args = signature(entry)
    return [(i, JAVA_TYPES[args[i]], "a%d" % i) for i in sorted(args)]


def flag(entry):
    formatted = entry.formatted and any("%" in item.text for item in entry.items.values())
    if entry.kind == "plural":
        return "q" if formatted else "p"
    return "f" if formatted else "s"


HEADER = ("// Generated by tools/l10n/gen_catalog.py from res/values/strings*.xml. Do not edit;\n"
          "// edit the XML and rebuild. This file lives in the build output and is never committed.\n")


def describe(entry):
    lines = []
    for quantity, item in entry.items.items():
        label = "" if entry.kind == "string" else quantity + ": "
        lines.append(" * %s\"%s\"" % (label, javadoc_text(item.text)))
    meta = ["%s %s" % (k, javadoc_text(v)) for k, v in sorted(entry.meta.items())]
    if not entry.translatable:
        meta.append("not translatable")
    if not entry.formatted:
        meta.append("formatted=false")
    doc = ["/**", " * {@code %s} (%s)<br>" % (entry.name, javadoc_text(os.path.basename(entry.file)))]
    doc.extend(line + "<br>" for line in lines)
    if meta:
        doc.append(" * " + "; ".join(meta) + "<br>")
    if entry.note:
        doc.append(" * " + javadoc_text(" ".join(entry.note.split())))
    doc.append(" */")
    return doc


def gen_t(entries, package):
    out = [HEADER, "package %s;\n" % package, "import java.util.Locale;\n",
           "/**",
           " * Volt's text catalog: one typed accessor per string or plural in res/values/strings*.xml.",
           " * Text comes from the installed {@link Source}; EnglishText until VoltApp installs AndroidText.",
           " * Any text containing % is always passed through String.format (Locale.ROOT, so digits stay",
           " * ASCII); numbers that need the user's locale go through Fmt and arrive as %n$s arguments.",
           " */",
           "public final class T {",
           "    private T() {}",
           "",
           "    /** Where catalog text comes from. Implementations return raw (unformatted) text. */",
           "    public interface Source {",
           "        /** The raw text of a string id, or null when this source does not have it. */",
           "        String string(int id);",
           "        /** The raw text of a plural id for this quantity, or null when this source does not have it. */",
           "        String plural(int id, long quantity);",
           "        /** The language the text resolved to: \"en\" for English or anything unrecognised. */",
           "        String language();",
           "    }",
           "",
           "    private static volatile Source source = EnglishText.INSTANCE;",
           "",
           "    /** Installs the text source; null restores English. */",
           "    public static void install(Source next) { source = next == null ? EnglishText.INSTANCE : next; }",
           "    public static Source source() { return source; }",
           "    public static String language() { return source.language(); }",
           "",
           "    /** Number of catalog ids (ids are 0 to size() - 1). */",
           "    public static int size() { return SIZE; }",
           "    /** The resource name of an id, for logs and tests. */",
           "    public static String key(int id) { return EnglishText.key(id); }",
           "    public static boolean isPlural(int id) { char f = FLAGS.charAt(id); return f == 'p' || f == 'q'; }",
           "    /** True when the text contains % and therefore always goes through String.format. */",
           "    public static boolean isFormatted(int id) { char f = FLAGS.charAt(id); return f == 'f' || f == 'q'; }",
           "",
           "    /** Renders a string id now. Msg.render() calls this at display time. */",
           "    public static String render(int id, Object... args) {",
           "        if (isPlural(id)) throw new IllegalArgumentException(key(id) + \" is a plural\");",
           "        String text = source.string(id);",
           "        if (text == null) text = EnglishText.INSTANCE.string(id);",
           "        return isFormatted(id) ? String.format(Locale.ROOT, text, args) : text;",
           "    }",
           "",
           "    /**",
           "     * Which plural item Android serves (plan section 1.3). Resources.getQuantityString picks an item by the",
           "     * plural rules of the configuration's first locale, and falls back to \"other\" when the resources lack",
           "     * that item, even while it serves another language's text: on a phone whose only language is Thai the",
           "     * English default would say \"1 minutes\", and on a Russian one \"21 minute\". AndroidText asks",
           "     * standIn for a quantity that makes Android serve the item the served language's own rules choose.",
           "     * Pure Java, so the JVM tests check it with any rules.",
           "     */",
           "    public static final class Plurals {",
           "        private Plurals() {}",
           "",
           "        /** A language's plural rules: the CLDR category (zero, one, two, few, many, other) of a quantity. */",
           "        public interface Rule { String select(long quantity); }",
           "",
           "        /** English as EnglishText reads it: \"one\" for exactly 1, \"other\" for every other quantity. */",
           "        public static final Rule ENGLISH = new Rule() {",
           "            @Override public String select(long quantity) { return quantity == 1 ? \"one\" : \"other\"; }",
           "        };",
           "        /** The items an English plural has (gen_catalog.py refuses any other in res/values). */",
           "        static final String[] ENGLISH_ITEMS = {\"one\", \"other\"};",
           "        /** standIn's answer when no int quantity makes Android serve the wanted item. */",
           "        public static final long NONE = Long.MIN_VALUE;",
           "",
           "        /** The item Android serves for a category: that item when the text has it, else \"other\". */",
           "        static String item(String category, String[] items) {",
           "            for (String item : items) if (item.equals(category)) return item;",
           "            return \"other\";",
           "        }",
           "",
           "        /** The item the served language's rules choose for quantity, as the served text has it. */",
           "        public static String wanted(long quantity, Rule served, String[] items) {",
           "            return item(served.select(quantity), items);",
           "        }",
           "",
           "        /**",
           "         * A quantity for Resources.getQuantityString that serves the item served (with the served text's",
           "         * items) chooses for quantity, when Android selects by android: quantity itself whenever it already",
           "         * does (so a phone whose first language has the served language's rules passes what it always",
           "         * did), else the first of 0 to 199 and the powers of ten from 1000 that does, else NONE.",
           "         */",
           "        public static long standIn(long quantity, Rule served, String[] items, Rule android) {",
           "            String want = wanted(quantity, served, items);",
           "            long same = Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, quantity));",
           "            if (item(android.select(same), items).equals(want)) return same;",
           "            for (long n = 0; n < 200; n++) if (item(android.select(n), items).equals(want)) return n;",
           "            for (long n = 1000; n <= 1000000000L; n *= 10) if (item(android.select(n), items).equals(want)) return n;",
           "            return NONE;",
           "        }",
           "    }",
           "",
           "    /** Renders a plural id for a quantity now. */",
           "    public static String renderPlural(int id, long quantity, Object... args) {",
           "        if (!isPlural(id)) throw new IllegalArgumentException(key(id) + \" is not a plural\");",
           "        String text = source.plural(id, quantity);",
           "        if (text == null) text = EnglishText.INSTANCE.plural(id, quantity);",
           "        return isFormatted(id) ? String.format(Locale.ROOT, text, args) : text;",
           "    }",
           ""]
    out.append("    static final int SIZE = %d;" % len(entries))
    out.append("    /** One flag per id: s plain string, f formatted string, p plain plural, q formatted plural. */")
    out.append("    static final String FLAGS = %s;" % java_string("".join(flag(e) for e in entries)))
    out.append("")
    for index, entry in enumerate(entries):
        out.extend("    " + line for line in describe(entry))
        out.append("    public static final int %s = %d;" % (constant(entry.name), index))
    out.append("")
    for entry in entries:
        params = parameters(entry)
        out.extend("    " + line for line in describe(entry))
        names = ", ".join(p[2] for p in params)
        if entry.kind == "plural":
            decl = ", ".join(["long quantity"] + ["%s %s" % (p[1], p[2]) for p in params])
            call = "renderPlural(%s, quantity%s)" % (constant(entry.name), ", " + names if names else "")
        else:
            decl = ", ".join("%s %s" % (p[1], p[2]) for p in params)
            call = "render(%s%s)" % (constant(entry.name), ", " + names if names else "")
        out.append("    public static String %s(%s) { return %s; }" % (camel(entry.name), decl, call))
    out.append("}")
    return "\n".join(out) + "\n"


def gen_m(entries, package):
    out = [HEADER, "package %s;\n" % package,
           "/** Deferred messages: the same accessors as T, returning a Msg that renders at display time. */",
           "public final class M {",
           "    private M() {}",
           ""]
    for entry in entries:
        params = parameters(entry)
        names = ", ".join(p[2] for p in params)
        if entry.kind == "plural":
            decl = ", ".join(["long quantity"] + ["%s %s" % (p[1], p[2]) for p in params])
            call = "Msg.plural(T.%s, quantity%s)" % (constant(entry.name), ", " + names if names else "")
        else:
            decl = ", ".join("%s %s" % (p[1], p[2]) for p in params)
            call = "Msg.of(T.%s%s)" % (constant(entry.name), ", " + names if names else "")
        out.append("    /** {@link T#%s} */" % camel(entry.name))
        out.append("    public static Msg %s(%s) { return %s; }" % (camel(entry.name), decl, call))
    out.append("}")
    return "\n".join(out) + "\n"


def _chunks(lines):
    return [lines[i:i + CHUNK] for i in range(0, len(lines), CHUNK)] or [[]]


def gen_english(entries, package):
    fills = []
    for index, entry in enumerate(entries):
        fills.append("KEYS[%d] = %s;" % (index, java_string(entry.name)))
        if entry.kind == "string":
            fills.append("OTHER[%d] = %s;" % (index, java_string(entry.items["other"].text)))
        else:
            fills.append("OTHER[%d] = %s;" % (index, java_string(entry.items["other"].text)))
            if "one" in entry.items:
                fills.append("ONE[%d] = %s;" % (index, java_string(entry.items["one"].text)))
    chunks = _chunks(fills)
    out = [HEADER, "package %s;\n" % package,
           "/**",
           " * The English catalog baked into Java: T's default source, and the fallback for any id the",
           " * installed source lacks. English plurals use \"one\" for exactly 1 and \"other\" otherwise.",
           " */",
           "public final class EnglishText implements T.Source {",
           "    public static final EnglishText INSTANCE = new EnglishText();",
           "    private static final String[] KEYS = new String[T.SIZE];",
           "    private static final String[] OTHER = new String[T.SIZE];",
           "    private static final String[] ONE = new String[T.SIZE];",
           "    static {"]
    out.extend("        fill%d();" % i for i in range(len(chunks)))
    out += ["    }",
            "",
            "    private EnglishText() {}",
            "",
            "    static String key(int id) { return id >= 0 && id < T.SIZE ? KEYS[id] : null; }",
            "",
            "    @Override public String string(int id) {",
            "        return id >= 0 && id < T.SIZE && !T.isPlural(id) ? OTHER[id] : null;",
            "    }",
            "",
            "    @Override public String plural(int id, long quantity) {",
            "        if (id < 0 || id >= T.SIZE || !T.isPlural(id)) return null;",
            "        return quantity == 1 && ONE[id] != null ? ONE[id] : OTHER[id];",
            "    }",
            "",
            "    @Override public String language() { return AppLanguages.ENGLISH; }"]
    for i, chunk in enumerate(chunks):
        out.append("")
        out.append("    private static void fill%d() {" % i)
        out.extend("        " + line for line in chunk)
        out.append("    }")
    out.append("}")
    return "\n".join(out) + "\n"


def _java_strings(values):
    return "{" + ", ".join("null" if v is None else java_string(v) for v in values) + "}"


def gen_android(entries, tag, package, folders=(), held=None):
    held = held or {}
    fills = ["IDS[%d] = R.%s.%s;" % (i, "plurals" if e.kind == "plural" else "string", e.name)
             for i, e in enumerate(entries)]
    for i, entry in enumerate(entries):
        holders = held.get(entry.name) if entry.kind == "plural" else None
        if not holders:
            continue
        fills.append("HELD[%d] = new int[]{%s};" % (i, ", ".join(str(index) for index, _ in holders)))
        fills.append("HELD_ITEMS[%d] = new String[][]{%s};" % (
            i, ", ".join(_java_strings([q for q, _ in items]) for _, items in holders)))
        fills.append("HELD_TEXTS[%d] = new String[][]{%s};" % (
            i, ", ".join(_java_strings([t for _, t in items]) for _, items in holders)))
    chunks = _chunks(fills)
    language = ("AppLanguages.resolve(resources.getString(R.string.%s))" % TAG_KEY) if tag else "AppLanguages.ENGLISH"
    out = [HEADER, "package %s;\n" % package,
           "import android.content.res.Resources;",
           "import java.util.Arrays;",
           "import java.util.Locale;\n",
           "/**",
           " * Catalog text from Android resources. Lookups are R constants, checked at compile time. A plural's item is",
           " * the one the plural rules of the folder Android serves it from choose (English rules for res/values, also",
           " * where a language folder leaves the key out), not the one the configuration's first locale would choose",
           " * (see T.Plurals).",
           " */",
           "public final class AndroidText implements T.Source {",
           "    private static final int[] IDS = new int[T.SIZE];",
           "    /**",
           "     * The res/values-* language folders that hold a plural (gen_catalog.py reads them at build time): the",
           "     * l10n/lang tag whose plural rules their text follows, and the language (modern code), script (\"\" when",
           "     * the folder name gives none) and region of the folder name.",
           "     */",
           "    private static final String[] FOLDER_TAGS = %s;" % _java_strings([f.tag for f in folders]),
           "    private static final String[] FOLDER_LANGUAGES = %s;" % _java_strings([f.language for f in folders]),
           "    private static final String[] FOLDER_SCRIPTS = %s;" % _java_strings([f.script for f in folders]),
           "    private static final String[] FOLDER_REGIONS = %s;" % _java_strings([f.region for f in folders]),
           "    /** Per plural id, the folders that hold it (FOLDER_* indices), null when only res/values does. */",
           "    private static final int[][] HELD = new int[T.SIZE][];",
           "    /** The items each of those folders has: quantity names, and texts (null when aapt stores it otherwise). */",
           "    private static final String[][][] HELD_ITEMS = new String[T.SIZE][][];",
           "    private static final String[][][] HELD_TEXTS = new String[T.SIZE][][];",
           "    static {"]
    out.extend("        fill%d();" % i for i in range(len(chunks)))
    out += ["    }",
            "",
            "    private final Resources resources;",
            "",
            "    public AndroidText(Resources resources) {",
            "        if (resources == null) throw new NullPointerException(\"resources\");",
            "        this.resources = resources;",
            "    }",
            "",
            "    @Override public String string(int id) {",
            "        return id >= 0 && id < T.SIZE && !T.isPlural(id) ? resources.getString(IDS[id]) : null;",
            "    }",
            "",
            "    @Override public String plural(int id, long quantity) {",
            "        if (id < 0 || id >= T.SIZE || !T.isPlural(id)) return null;",
            "        int q = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, quantity));",
            "        String served = served(id, quantity, q);",
            "        return served != null ? served : resources.getQuantityString(IDS[id], q);",
            "    }",
            "",
            "    /**",
            "     * The plural item the rules of the folder Android serves the key from choose, or null to keep Android's",
            "     * own choice. Android selects by the plural rules of the configuration's first locale",
            "     * (ResourcesImpl.getPluralRule), so this asks for a stand-in quantity that makes it serve the wanted item.",
            "     * The folder comes from the build-time table of folders that hold the key (see folder), never from the",
            "     * text: a translation whose items equal English's (French \"%1$d watt\") still takes its own rules. Text",
            "     * that is not one of that folder's items means the table does not describe these resources (a",
            "     * pseudo-locale build, an Android that resolves differently), and Android's choice stands. When no",
            "     * stand-in exists (the first locale has no such category: \"one\" on a Thai or Japanese phone), the",
            "     * English default comes from EnglishText, which tools/l10n/roundtrip.py proves is what aapt compiled;",
            "     * other text keeps Android's.",
            "     */",
            "    private String served(int id, long quantity, int q) {",
            "        try {",
            "            Locale first = resources.getConfiguration().getLocales().get(0);",
            "            if (first == null) return null;",
            "            String shown = resources.getQuantityString(IDS[id], q);",
            "            int k = folder(id, first);",
            "            T.Plurals.Rule rules = T.Plurals.ENGLISH;",
            "            String[] items = T.Plurals.ENGLISH_ITEMS;",
            "            if (k < 0) {",
            "                if (!shown.equals(EnglishText.INSTANCE.plural(id, 0)) && !shown.equals(EnglishText.INSTANCE.plural(id, 1))) return null;",
            "            } else {",
            "                if (!Arrays.asList(HELD_TEXTS[id][k]).contains(shown)) return null;",
            "                rules = icu(Locale.forLanguageTag(FOLDER_TAGS[HELD[id][k]]));",
            "                items = HELD_ITEMS[id][k];",
            "            }",
            "            long n = T.Plurals.standIn(quantity, rules, items, icu(first));",
            "            if (n == q) return shown;",
            "            if (n != T.Plurals.NONE) return resources.getQuantityString(IDS[id], (int) n);",
            "            return k < 0 ? EnglishText.INSTANCE.plural(id, quantity) : shown;",
            "        } catch (RuntimeException error) {",
            "            return null;",
            "        }",
            "    }",
            "",
            "    /**",
            "     * Which folder Android serves plural id from under locale, as an index into HELD[id], or -1 for res/values.",
            "     * The locale is the configuration's first, the one Android resolves every resource with: when the phone",
            "     * lists several languages, ResourcesImpl.updateConfiguration moves the first one Volt has resources for",
            "     * (LocaleList.getFirstMatchWithEnglishSupported) to the front. A key that locale's folders lack comes",
            "     * from res/values, never from a later language of the list. Among the folders that hold the key, as",
            "     * ResTable_config::match and isLocaleBetterThan pick: the language must be the locale's; the scripts",
            "     * (given or likely) must agree, or, when either is unknown, a folder's region must be the locale's; then",
            "     * the folder of the locale's region beats the one without a region, which beats another region",
            "     * (pt-PT is served values-pt-rBR's text when values-pt lacks the key). gen_catalog.py refuses the",
            "     * folders this cannot rank (CLDR parent regions, two regions without the region-less folder).",
            "     */",
            "    static int folder(int id, Locale locale) {",
            "        int[] held = HELD[id];",
            "        if (held == null) return -1;",
            "        String language = modern(locale.getLanguage());",
            "        String region = locale.getCountry();",
            "        String script = script(locale.getScript(), language, region);",
            "        int best = -1, bestRank = -1;",
            "        for (int k = 0; k < held.length; k++) {",
            "            int f = held[k];",
            "            if (!FOLDER_LANGUAGES[f].equals(language)) continue;",
            "            String folderScript = script(FOLDER_SCRIPTS[f], FOLDER_LANGUAGES[f], FOLDER_REGIONS[f]);",
            "            if (script.isEmpty() || folderScript.isEmpty()) {",
            "                if (!FOLDER_REGIONS[f].isEmpty() && !FOLDER_REGIONS[f].equals(region)) continue;",
            "            } else if (!folderScript.equals(script)) {",
            "                continue;",
            "            }",
            "            int rank = FOLDER_REGIONS[f].equals(region) ? 2 : FOLDER_REGIONS[f].isEmpty() ? 1 : 0;",
            "            if (rank > bestRank) { best = k; bestRank = rank; }",
            "        }",
            "        return best;",
            "    }",
            "",
            "    /** The language code Android compares (Locale.adjustLanguageCode and langsAreEquivalent), modern spelling. */",
            "    private static String modern(String language) {",
            "        switch (language) {",
            "            case \"in\": return \"id\";",
            "            case \"iw\": return \"he\";",
            "            case \"ji\": return \"yi\";",
            "            case \"tl\": return \"fil\";",
            "            default: return language;",
            "        }",
            "    }",
            "",
            "    /** The given script, else the likely one for the language and region (\"\" when ICU has none). */",
            "    private static String script(String given, String language, String region) {",
            "        if (!given.isEmpty()) return given;",
            "        try {",
            "            String tag = region.isEmpty() ? language : language + \"_\" + region;",
            "            return android.icu.util.ULocale.addLikelySubtags(new android.icu.util.ULocale(tag)).getScript();",
            "        } catch (RuntimeException error) {",
            "            return \"\";",
            "        }",
            "    }",
            "",
            "    /** Android's plural rules for a locale, as ResourcesImpl.getPluralRule builds them (API 24 and later). */",
            "    private static T.Plurals.Rule icu(Locale locale) {",
            "        final android.icu.text.PluralRules rules = android.icu.text.PluralRules.forLocale(locale);",
            "        return new T.Plurals.Rule() {",
            "            @Override public String select(long quantity) { return rules.select(quantity); }",
            "        };",
            "    }",
            "",
            "    /** The resolved language, parsed defensively: anything that is not an exact shipped tag is English. */",
            "    @Override public String language() { return %s; }" % language]
    for i, chunk in enumerate(chunks):
        out.append("")
        out.append("    private static void fill%d() {" % i)
        out.extend("        " + line for line in chunk)
        out.append("    }")
    out.append("}")
    return "\n".join(out) + "\n"


def gen_languages(languages, package):
    def arr(kind, values):
        return "{" + ", ".join(values) + "}"
    out = [HEADER.replace("res/values/strings*.xml", "l10n/lang/*.tsv"), "package %s;\n" % package,
           "/** Volt's language table, from l10n/lang/*.tsv. English first. */",
           "public final class AppLanguages {",
           "    private AppLanguages() {}",
           "",
           "    public static final String ENGLISH = \"en\";",
           "    static final String[] TAGS = %s;" % arr("String", [java_string(l.tag) for l in languages]),
           "    static final String[] FOLDERS = %s;" % arr("String", [java_string(l.folder) for l in languages]),
           "    static final String[] ENDONYMS = %s;" % arr("String", [java_string(l.endonym) for l in languages]),
           "    static final String[] STATUSES = %s;" % arr("String", [java_string(l.status) for l in languages]),
           "    static final String[] FONTS = %s;" % arr("String", [java_string(l.font) for l in languages]),
           "    static final boolean[] RTL = %s;" % arr("boolean", [l.rtl for l in languages]),
           "",
           "    public static int size() { return TAGS.length; }",
           "    public static String tag(int i) { return TAGS[i]; }",
           "    public static String endonym(int i) { return ENDONYMS[i]; }",
           "    public static boolean rtl(int i) { return RTL[i]; }",
           "    public static boolean systemFont(int i) { return \"system\".equals(FONTS[i]); }",
           "    /** Beta and stable languages ship: they appear in the picker and in locales_config. */",
           "    public static boolean shipped(int i) { return \"beta\".equals(STATUSES[i]) || \"stable\".equals(STATUSES[i]); }",
           "",
           "    /** Exact match only; -1 when the tag is not in the table. */",
           "    public static int indexOf(String tag) {",
           "        if (tag != null) for (int i = 0; i < TAGS.length; i++) if (TAGS[i].equals(tag)) return i;",
           "        return -1;",
           "    }",
           "",
           "    /** The l10n_tag parse: any value that is not exactly a shipped tag counts as English. */",
           "    public static String resolve(String tag) {",
           "        int i = indexOf(tag);",
           "        return i >= 0 && shipped(i) ? TAGS[i] : ENGLISH;",
           "    }",
           "}"]
    return "\n".join(out) + "\n"


def expected_tsv(entries, tag):
    rows = ["# English the build must compile byte for byte (tools/l10n/roundtrip.py). Generated; do not edit.",
            "kind\tname\tquantity\ttext"]
    every = list(entries) + ([tag] if tag else [])
    for entry in sorted(every, key=lambda e: e.name):
        for quantity in (QUANTITIES if entry.kind == "plural" else ("other",)):
            if quantity in entry.items:
                rows.append("%s\t%s\t%s\t%s" % (entry.kind, entry.name, quantity if entry.kind == "plural" else "",
                                                 l10nlib.escape_field(entry.items[quantity].text)))
    return "\n".join(rows) + "\n"


def read_expected_tsv(text):
    """{(kind, name, quantity): text} from en.expected.tsv."""
    _, rows = l10nlib.read_tsv(text, ("kind", "name", "quantity", "text"))
    return {(kind, name, quantity or "other"): l10nlib.unescape_field(value) for _, (kind, name, quantity, value) in rows}


def generate(res_dir, lang_dir, out_dir, tsv_path, android, package=PACKAGE, display_root=None):
    entries, tag = load_catalog(os.path.join(res_dir, "values"), display_root)
    languages = load_languages(lang_dir)
    folder_problems = check_text_folders(res_dir, languages, display_root)
    if folder_problems:
        raise CatalogError(folder_problems)
    folders, held = load_folder_plurals(res_dir, languages, entries, display_root)
    written = []
    if out_dir:
        java_dir = os.path.join(out_dir, *package.split("."))
        files = {"T.java": gen_t(entries, package), "M.java": gen_m(entries, package),
                 "EnglishText.java": gen_english(entries, package),
                 "AppLanguages.java": gen_languages(languages, package)}
        if android:
            files["AndroidText.java"] = gen_android(entries, tag, package, folders, held)
        for name, text in sorted(files.items()):
            path = os.path.join(java_dir, name)
            l10nlib.write_text(path, text)
            written.append(path)
    if tsv_path:
        l10nlib.write_text(tsv_path, expected_tsv(entries, tag))
        written.append(tsv_path)
    return entries, tag, written


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    parser.add_argument("--res", default=os.path.join(root, "res"), help="resource folder (default: res)")
    parser.add_argument("--lang", default=os.path.join(root, "l10n", "lang"), help="language table folder")
    parser.add_argument("--out", help="Java output root, for example $BUILD_DIR/generated")
    parser.add_argument("--tsv", help="where to write en.expected.tsv")
    parser.add_argument("--android", action="store_true", help="also write AndroidText.java (app build only)")
    parser.add_argument("--package", default=PACKAGE)
    parser.add_argument("--check", action="store_true", help="validate only; write nothing")
    args = parser.parse_args(argv)
    if not args.check and not args.out and not args.tsv:
        parser.error("give --out and/or --tsv, or --check")
    try:
        if args.check:
            entries, tag = load_catalog(os.path.join(args.res, "values"), root)
            languages = load_languages(args.lang)
            folder_problems = check_text_folders(args.res, languages, root)
            if folder_problems:
                raise CatalogError(folder_problems)
            load_folder_plurals(args.res, languages, entries, root)
            written = []
        else:
            entries, tag, written = generate(args.res, args.lang, args.out, args.tsv, args.android, args.package, root)
    except CatalogError as error:
        for problem in error.problems:
            print("gen_catalog: " + problem, file=sys.stderr)
        print("gen_catalog: %d problem(s); nothing usable was generated." % len(error.problems), file=sys.stderr)
        return 1
    plurals = sum(1 for e in entries if e.kind == "plural")
    print("gen_catalog: %d strings, %d plurals%s%s" % (
        len(entries) - plurals, plurals, ", l10n_tag" if tag else "",
        "" if args.check else "; wrote " + ", ".join(os.path.basename(p) for p in written)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
