"""Shared pieces of the translation tools (pack, check, council, merge): plan section 7.

Not a tool itself. It reads the English catalog by area, the language settings and glossaries,
reads and writes draft files, and defines the source hash that ties a translation to the English
it was made from.

Source hash (the <!-- src:<sha1> --> comment, decisions' source_sha1): SHA-1 of the UTF-8 bytes of
  kind NUL (quantity NUL text NUL)*
where kind is "string" or "plurals", a string has the single quantity "other", and a plural lists
its quantities in CLDR order (zero, one, two, few, many, other). The text is the decoded English the
user sees (escapes resolved, a "..." wrapper removed). Metadata does not count: a new width budget
does not make a translation stale, a new English wording does.
"""

import collections
import hashlib
import json
import os
import re
import sys
import unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_catalog  # noqa: E402
import l10nlib  # noqa: E402

TOOLS_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(TOOLS_DIR))
AREA_FILE = re.compile(r"^strings_([a-z][a-z0-9_]*)\.xml$")
AREA_NAME = re.compile(r"^[a-z][a-z0-9_]*$")
TAG_NAME = re.compile(r"^[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$")
DRAFT_NAME = re.compile(r"^[a-z][a-z0-9-]*$")
PROVIDERS = ("claude", "openai", "google")
QUANTITIES = gen_catalog.QUANTITIES
SAFETY_BLOCKING = ("lock", "alarm")
GLOSSARY_HEADER = ("id", "term", "definition", "pos", "dnt", "safety", "source")


class L10nError(Exception):
    pass


# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------

class Paths(object):
    """Where everything lives, relative to a repository root (tests pass a fixture root)."""

    def __init__(self, root=None):
        self.root = os.path.abspath(root or REPO_ROOT)

    def res(self, *parts):
        return os.path.join(self.root, "res", *parts)

    def values(self):
        return self.res("values")

    def lang_dir(self):
        return os.path.join(self.root, "l10n", "lang")

    def glossary_dir(self):
        return os.path.join(self.root, "l10n", "glossary")

    def style(self, tag):
        return os.path.join(self.root, "l10n", "style", tag + ".md")

    def pack(self, tag, area):
        return os.path.join(self.root, "l10n", "packs", tag, area + ".json")

    def work(self, tag, area, *parts):
        return os.path.join(self.root, "l10n", "work", tag, area, *parts)

    def drafts(self, tag, area):
        return self.work(tag, area, "drafts")

    def decisions(self, tag, area):
        return os.path.join(self.root, "l10n", "decisions", tag, area + ".jsonl")

    def font(self):
        return self.res("font", "saira_medium.ttf")

    def rel(self, path):
        return os.path.relpath(path, self.root)


def check_tag_area(tag, area):
    if not TAG_NAME.match(tag or ""):
        raise L10nError("language tag %r is not a BCP 47 tag such as es or pt-BR" % tag)
    if area is not None and not AREA_NAME.match(area or ""):
        raise L10nError("area %r is not a lower_snake_case area name" % area)


# ---------------------------------------------------------------------------
# The English source, by area
# ---------------------------------------------------------------------------

Source = collections.namedtuple("Source", "key kind items meta translatable formatted note area file line order")


def source_sha1(kind, items):
    """The source hash of one entry: see the module docstring. items maps quantity to text."""
    kind = "plurals" if kind in ("plural", "plurals") else "string"
    parts = [kind, "\0"]
    for quantity in QUANTITIES:
        if quantity in items:
            parts.extend([quantity, "\0", items[quantity], "\0"])
    return hashlib.sha1("".join(parts).encode("utf-8")).hexdigest()


def load_source(paths):
    """{area: [Source in file order]} and the area that holds the default l10n_tag (or None).

    Reads every res/values/strings_<area>.xml through gen_catalog, so the catalog must be valid. Only
    these files are text the app shows: reports, diagnostics and user data never live here (plan R4).
    """
    entries, _tag = gen_catalog.load_catalog(paths.values(), display_root=paths.root)
    by_area = collections.OrderedDict()
    tag_area = None
    # load_catalog leaves out l10n_tag; find its file separately.
    for filename in sorted(os.listdir(paths.values())):
        m = AREA_FILE.match(filename)
        if not m:
            continue
        file_entries, problems = gen_catalog.read_strings_file(os.path.join(paths.values(), filename))
        if problems:
            raise L10nError("\n".join(problems))
        if any(e.name == gen_catalog.TAG_KEY for e in file_entries):
            tag_area = m.group(1)
    for entry in entries:
        m = AREA_FILE.match(os.path.basename(entry.file))
        if not m:
            continue  # strings.xml (app_name) and other non-area files are not packed
        area = m.group(1)
        items = collections.OrderedDict((q, it.text) for q, it in entry.items.items())
        by_area.setdefault(area, []).append(Source(
            entry.name, entry.kind, items, dict(entry.meta), entry.translatable, entry.formatted,
            entry.note, area, entry.file, entry.line, 0))
    ordered = collections.OrderedDict()
    for area in sorted(by_area):
        rows = sorted(by_area[area], key=lambda s: s.line)
        ordered[area] = [s._replace(order=i) for i, s in enumerate(rows)]
    return ordered, tag_area


def source_signature(source):
    """{index: conversion} across the items of a source entry (empty when formatted="false")."""
    if not source.formatted:
        return {}
    merged = {}
    for text in source.items.values():
        args, _ = gen_catalog.analyze_format(text)
        for index, conversion in args.items():
            merged.setdefault(index, conversion)
    return merged


def safety_of(meta):
    value = (meta or {}).get("safety", "").strip()
    return [v for v in re.split(r"[,\s]+", value) if v] if value else []


def effective_safety(sources):
    """{key: [safety values]}: a ladder rung without its own volt:safety takes its full key's."""
    by_key = dict((s.key, s) for s in sources)
    out = {}
    for s in sources:
        own = safety_of(s.meta)
        base = by_key.get(ladder_base(s.key, s.meta))
        out[s.key] = own or (safety_of(base.meta) if base is not None else [])
    return out


def dnt_of(meta):
    return [t for t in (meta or {}).get("dnt", "").split("|") if t]


def parse_max(value):
    """(dp, sp) from a volt:max such as 96dp@14sp, or None."""
    if not value:
        return None
    m = re.match(r"^(\d+(?:\.\d+)?)dp@(\d+(?:\.\d+)?)sp$", value)
    return (float(m.group(1)), float(m.group(2))) if m else None


def ladder_base(key, meta):
    rung = (meta or {}).get("ladder")
    for suffix in ("_short", "_compact"):
        if rung == suffix[1:] and key.endswith(suffix):
            return key[:-len(suffix)]
    return key


def ladder_groups(sources):
    """{full key: [keys of the group in full, short, compact order]} for groups with more than one rung."""
    keys = set(s.key for s in sources)
    groups = collections.OrderedDict()
    for s in sources:
        base = ladder_base(s.key, s.meta)
        groups.setdefault(base, [])
        groups[base].append(s.key)
    out = collections.OrderedDict()
    for base, members in groups.items():
        if base in keys and len(members) > 1:
            order = {base: 0, base + "_short": 1, base + "_compact": 2}
            out[base] = sorted(members, key=lambda k: order.get(k, 3))
    return out


# ---------------------------------------------------------------------------
# Languages and glossaries
# ---------------------------------------------------------------------------

def load_language(paths, tag):
    languages = gen_catalog.load_languages(paths.lang_dir())
    for lang in languages:
        if lang.tag == tag:
            if tag == "en" or lang.folder == "values":
                raise L10nError("%s is the source language; nothing to translate" % tag)
            return lang
    raise L10nError("l10n/lang/%s.tsv does not exist; add it first (l10n/README.md, Running a language)" % tag)


def plural_categories(lang):
    return [p for p in lang.plurals.split(",") if p]


Term = collections.namedtuple("Term", "id term definition pos dnt safety targets")


def _read_glossary(path):
    try:
        _, rows = l10nlib.read_tsv(l10nlib.read_text(path), GLOSSARY_HEADER)
    except ValueError as error:
        raise L10nError("%s: %s (council.py glossary-check lists every problem)" % (path, error))
    return [fields for _, fields in rows]


def load_glossary(paths, tag):
    """[Term]: the English glossary with each term's approved forms in this language (targets).

    l10n/glossary/<tag>.tsv has the same columns as en.tsv; its term column holds the approved
    translation, with inflected forms separated by " | ". A row's id names the en.tsv term.
    """
    en_path = os.path.join(paths.glossary_dir(), "en.tsv")
    if not os.path.exists(en_path):
        return []
    targets = {}
    tag_path = os.path.join(paths.glossary_dir(), tag + ".tsv")
    if os.path.exists(tag_path):
        for fields in _read_glossary(tag_path):
            targets[fields[0]] = [f.strip() for f in fields[1].split("|") if f.strip()]
    terms = []
    for term_id, term, definition, pos, dnt, safety, _source in _read_glossary(en_path):
        terms.append(Term(term_id, term, definition, pos, dnt == "yes", safety if safety != "-" else "",
                          targets.get(term_id)))
    return terms


def _glossary_rows(path, where):
    """([(line, fields)], problems), reading on past a row with the wrong column count."""
    rows, problems = [], []
    header = None
    for number, line in enumerate(l10nlib.read_text(path).split("\n"), 1):
        line = line[:-1] if line.endswith("\r") else line
        if not line.strip() or line.startswith("#"):
            continue
        fields = line.split("\t")
        if header is None:
            header = fields
            if tuple(fields) != GLOSSARY_HEADER:
                return [], ["%s:%d: the header must be %s" % (where, number, " ".join(GLOSSARY_HEADER))]
        elif len(fields) != len(GLOSSARY_HEADER):
            problems.append("%s:%d: %d columns, a row has %d" % (where, number, len(fields), len(GLOSSARY_HEADER)))
        else:
            rows.append((number, fields))
    if header is None:
        problems.append("%s: no header" % where)
    return rows, problems


def _form_problems(where, term_id, column):
    """A term column's problems: empty forms, placeholders, and U+202F, which Saira cannot draw."""
    forms = [f.strip() for f in column.split("|")]
    out = []
    if not all(forms):
        out.append("%s: %s has an empty term" % (where, term_id))
    for form in forms:
        if gen_catalog.PLACEHOLDER.search(form):
            out.append("%s: %s: the term %r has a placeholder" % (where, term_id, form))
        if "\u202f" in form:
            out.append("%s: %s: the term %r has U+202F, which Saira has no glyph for; use U+00A0" % (
                where, term_id, form))
    return out


def english_glossary_problems(paths):
    """Problems of l10n/glossary/en.tsv as the glossary of every language relies on it."""
    path = os.path.join(paths.glossary_dir(), "en.tsv")
    if not os.path.exists(path):
        return ["%s does not exist" % paths.rel(path)]
    where = paths.rel(path)
    rows, problems = _glossary_rows(path, where)
    seen = set()
    for line, fields in rows:
        at = "%s:%d" % (where, line)
        if fields[0] in seen:
            problems.append("%s: %s appears twice" % (at, fields[0]))
        seen.add(fields[0])
        if fields[4] not in ("yes", "no"):
            problems.append("%s: %s: dnt must be yes or no" % (at, fields[0]))
        problems.extend(_form_problems(at, fields[0], fields[1]))
    return problems


def glossary_problems(paths, tag):
    """Every problem of l10n/glossary/<tag>.tsv against en.tsv; [] when the language has no glossary yet.

    load_glossary ignores a row whose id en.tsv lacks, so a mistyped id would quietly leave its term
    without a target; this is what catches that.
    """
    path = os.path.join(paths.glossary_dir(), tag + ".tsv")
    if tag == "en":
        return english_glossary_problems(paths)
    if not os.path.exists(path):
        return []
    problems = english_glossary_problems(paths)
    en_path = os.path.join(paths.glossary_dir(), "en.tsv")
    english = {}
    if os.path.exists(en_path):
        for _, fields in _glossary_rows(en_path, "en.tsv")[0]:
            english[fields[0]] = fields
    where = paths.rel(path)
    rows, row_problems = _glossary_rows(path, where)
    problems.extend(row_problems)
    seen = set()
    for line, fields in rows:
        at, term_id = "%s:%d" % (where, line), fields[0]
        if term_id in seen:
            problems.append("%s: %s appears twice" % (at, term_id))
        seen.add(term_id)
        if term_id not in english:
            problems.append("%s: %s is not an id in en.tsv" % (at, term_id))
        elif english[term_id][4] == "yes":
            problems.append("%s: %s is do-not-translate in en.tsv and takes no row" % (at, term_id))
        problems.extend(_form_problems(at, term_id, fields[1]))
    if not row_problems:
        for term_id, fields in english.items():
            if fields[4] != "yes" and term_id not in seen:
                problems.append("%s: no row for %s (%s)" % (where, term_id, fields[1]))
    return problems


def require_valid_glossary(paths, tag):
    """Refuse to build on an invalid l10n/glossary/<tag>.tsv (one that exists)."""
    problems = glossary_problems(paths, tag)
    if problems:
        raise L10nError("the %s glossary is invalid (council.py glossary-check --tag %s):\n  %s" % (
            tag, tag, "\n  ".join(problems)))


def glossary_orphans(paths, sources=None):
    """[Term] of en.tsv that match no translatable English string: information, not a problem."""
    if sources is None:
        sources, _ = load_source(paths)
    text = "\n".join(t for rows in sources.values() for s in rows if s.translatable for t in s.items.values())
    return [t for t in load_glossary(paths, "en") if not term_pattern(t.term, inflects(t)).search(text)]


def term_pattern(term, inflect=False):
    """A case-insensitive whole-word pattern for a glossary term. With inflect (a lock verb, see
    inflects), the English -s and -ing forms match too ("locks", "Unlocking…"); the -ed forms are
    rows of their own (Locked, Unlocked)."""
    body = re.escape(term).replace("\\ ", r"\s+")
    if inflect:
        body += "(?:s|ing)?"
    return re.compile(r"(?<![^\W_])" + body + r"(?![^\W_])", re.IGNORECASE | re.UNICODE)


def inflects(term):
    """Whether a glossary term matches its English -s and -ing forms: the one-word lock verbs (Lock,
    Unlock), so "before locking" and "This file unlocks your scooter" still require their targets."""
    return term.pos == "verb" and term.safety == "lock" and len(term.term.split()) == 1


def _participle(term, terms):
    """The row for a lock verb's -ed form (Locked for Lock), or None."""
    return next((t for t in terms if t.term.lower() == term.term.lower() + "ed"), None)


def nested_in_longer(term, others, text):
    """Whether every match of term in text lies inside a longer match of one of the other terms
    ("Lock" in "Lock duration", "Low" in "Low battery alert"): then the longer term is the one the
    text uses, and only its translation is required."""
    spans = [m.span() for m in term_pattern(term).finditer(text)]
    if not spans:
        return False
    longer = [m.span() for other in others if other.lower() != term.lower()
              for m in term_pattern(other).finditer(text)]
    return all(any(s <= a and b <= e and e - s > b - a for s, e in longer) for a, b in spans)


def _bare(text):
    return re.sub(r"[\W\d_]+", " ", text, flags=re.UNICODE).strip().lower()


def value_out_of_context(term, terms, text):
    """Whether a one-word adjective term is a setting value used here in another sense.

    A row whose id is another row's id plus a suffix, and whose term does not contain that row's
    term, names one value of that setting ("energy-recovery-off" is the Off level of Energy
    recovery). Such a common word ("Off", "Low", "High") is the setting value only in a text that
    is the word alone or names the setting; elsewhere ("Turn off charge alerts", "Low battery
    alert") it is an ordinary word and its level-name translation is not required.
    """
    if term.pos != "adjective" or len(term.term.split()) != 1:
        return False
    parents = [t for t in terms if term.id.startswith(t.id + "-") and t.term.lower() not in term.term.lower()]
    if not parents:
        return False
    if _bare(text) == _bare(term.term):
        return False
    parent = max(parents, key=lambda t: len(t.id))
    return not term_pattern(parent.term).search(text)


def glossary_hits(terms, text):
    """The terms a text uses: whole-word matches (lock verbs also in their -s and -ing forms, see
    inflects), without a term found only inside a longer one (nested_in_longer) or a setting value
    used in another sense (value_out_of_context)."""
    terms = list(terms)
    found = [t for t in terms if term_pattern(t.term, inflects(t)).search(text)]
    names = [t.term for t in found]
    hits = [t for t in found if not nested_in_longer(t.term, names, text) and
            not value_out_of_context(t, terms, text)]
    out = []
    for t in hits:
        participle = _participle(t, terms) if inflects(t) else None
        if t.targets and participle and participle.targets and not term_pattern(t.term).search(text):
            # Only an -s or -ing form: the -ed row's targets also translate it ("Locking…" as
            # "Wird gesperrt…").
            t = t._replace(targets=t.targets + [x for x in participle.targets if x not in t.targets])
        out.append(t)
    return out


# ---------------------------------------------------------------------------
# Resource text encoding
# ---------------------------------------------------------------------------

def encode_resource_text(text):
    """The resource spelling of a decoded text, which gen_catalog decodes back to exactly text.

    Backslashes, quotes and apostrophes are escaped; line breaks and tabs become \\n and \\t, other
    control characters \\uXXXX; a leading @ or ? is escaped; and a text with edge or repeated
    spaces is wrapped in "..." so aapt keeps them. The result still needs XML escaping.
    """
    out = []
    for i, ch in enumerate(text):
        if ch == "\\":
            out.append("\\\\")
        elif ch == "'":
            out.append("\\'")
        elif ch == '"':
            out.append('\\"')
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\t":
            out.append("\\t")
        elif ch in "@?#" and i == 0:
            out.append("\\" + ch)
        elif ord(ch) < 0x20 or ch in "\x7f\u2028\u2029" or 0xD800 <= ord(ch) <= 0xDFFF or ch in "\ufffe\uffff":
            out.append("\\u%04x" % ord(ch))
        else:
            out.append(ch)
    raw = "".join(out)
    # Only a plain space survives unescaped above, so it is the only whitespace aapt could strip or collapse.
    if text.startswith(" ") or text.endswith(" ") or "  " in text:
        raw = '"' + raw + '"'
    return raw


def xml_text(value):
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def xml_attr(value):
    return xml_text(value).replace('"', "&quot;")


# ---------------------------------------------------------------------------
# Draft files: l10n/work/<tag>/<area>/drafts/<name>.xml
# ---------------------------------------------------------------------------

# src: the source hash (source_sha1) of the English the entry translated, from volt:src; None if absent.
DraftEntry = collections.namedtuple("DraftEntry", "key kind items raw attempt problems src")
Draft = collections.namedtuple("Draft", "name provider model model_reported prompt entries path")


def draft_provider(name):
    for provider in PROVIDERS:
        if name == provider or name.startswith(provider + "-"):
            return provider
    raise L10nError("draft %r must be named claude-<config>, openai[-<x>] or google[-<x>]" % name)


def write_draft(path, name, model, entries, model_reported=None, prompt=None):
    """entries: [(key, kind, {quantity: text}, attempt, src)] in pack order.

    src is the source hash of the English the entry translated (volt:src); a draft entry without
    one, or with another than the current English's, is stale and never decides anything.
    """
    if not DRAFT_NAME.match(name):
        raise L10nError("draft name %r must be lower-case letters, digits and dashes" % name)
    provider = draft_provider(name)
    attrs = ['xmlns:volt="%s"' % gen_catalog.VOLT_NS, 'volt:provider="%s"' % provider,
             'volt:model="%s"' % xml_attr(model)]
    if model_reported and model_reported != model:
        attrs.append('volt:model-reported="%s"' % xml_attr(model_reported))
    if prompt:
        attrs.append('volt:prompt="%s"' % xml_attr(prompt))
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             "<!-- Draft %s: written by tools/l10n/council.py; staging only, never compiled. -->" % name,
             "<resources %s>" % " ".join(attrs)]
    for key, kind, items, attempt, src in entries:
        extra = ' volt:src="%s"' % xml_attr(src) if src else ""
        extra += ' volt:attempt="%d"' % attempt if attempt and attempt > 1 else ""
        if kind == "string":
            lines.append('    <string name="%s"%s>%s</string>' % (key, extra, xml_text(encode_resource_text(items["other"]))))
        else:
            lines.append('    <plurals name="%s"%s>' % (key, extra))
            for quantity in QUANTITIES:
                if quantity in items:
                    lines.append('        <item quantity="%s">%s</item>' % (
                        quantity, xml_text(encode_resource_text(items[quantity]))))
            lines.append("    </plurals>")
    lines.append("</resources>")
    l10nlib.write_text(path, "\n".join(lines) + "\n")


def read_draft(path):
    """A Draft. Each entry keeps its raw spelling and the problems aapt would have with it."""
    name = os.path.basename(path)[:-4]
    provider = draft_provider(name)
    try:
        root = gen_catalog._parse_xml(path)
    except gen_catalog.CatalogError as error:
        raise L10nError("\n".join(error.problems))
    if root is None or root.tag != "resources":
        raise L10nError("%s: the root element must be <resources>" % path)
    attrs = dict((gen_catalog._local(k), v) for k, v in root.attrs.items())
    if attrs.get("provider", provider) != provider:
        raise L10nError("%s: volt:provider %s does not match the file name" % (path, attrs.get("provider")))
    model = attrs.get("model", "").strip()
    if not model:
        raise L10nError("%s: volt:model (the exact model id) is required" % path)
    entries = collections.OrderedDict()
    for node in root.children:
        if isinstance(node, str):
            continue
        kind = gen_catalog._local(node.tag)
        key = node.attrs.get("name", "")
        problems = []
        if kind not in ("string", "plurals"):
            raise L10nError("%s:%d: <%s> is not allowed in a draft" % (path, node.line, kind))
        attempt, src = 1, None
        for attr, value in node.attrs.items():
            local = gen_catalog._local(attr)
            if local == "attempt" and gen_catalog._namespace(attr) == gen_catalog.VOLT_NS:
                attempt = int(value) if value.isdigit() else 1
            elif local == "src" and gen_catalog._namespace(attr) == gen_catalog.VOLT_NS:
                src = value.strip() or None
            elif local != "name":
                problems.append("attribute %s is not allowed in a draft" % local)
        items, raw = collections.OrderedDict(), collections.OrderedDict()
        if kind == "string":
            text_raw = gen_catalog._flatten(node, key, problems)
            text, item_problems, _ = gen_catalog.decode_resource_text(text_raw)
            items["other"], raw["other"] = text, text_raw
            problems.extend(item_problems)
        else:
            for child in node.children:
                if isinstance(child, str):
                    continue
                quantity = child.attrs.get("quantity", "")
                if gen_catalog._local(child.tag) != "item" or quantity not in QUANTITIES:
                    problems.append("<plurals> holds only <item quantity=...> with a CLDR category")
                    continue
                text_raw = gen_catalog._flatten(child, key, problems)
                text, item_problems, _ = gen_catalog.decode_resource_text(text_raw)
                items[quantity], raw[quantity] = text, text_raw
                problems.extend("[%s] %s" % (quantity, p) for p in item_problems)
        if key in entries:
            raise L10nError("%s:%d: %s appears twice" % (path, node.line, key))
        entries[key] = DraftEntry(key, "plural" if kind == "plurals" else "string", items, raw, attempt, problems,
                                  src)
    return Draft(name, provider, model, attrs.get("model-reported") or model, attrs.get("prompt"), entries, path)


def list_drafts(paths, tag, area):
    directory = paths.drafts(tag, area)
    if not os.path.isdir(directory):
        return []
    return [read_draft(os.path.join(directory, f)) for f in sorted(os.listdir(directory)) if f.endswith(".xml")]


# ---------------------------------------------------------------------------
# JSON lines and texts
# ---------------------------------------------------------------------------

def read_jsonl(path):
    rows = []
    if not os.path.exists(path):
        return rows
    for number, line in enumerate(l10nlib.read_text(path).split("\n"), 1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except ValueError as error:
            raise L10nError("%s:%d: not JSON: %s" % (path, number, error))
        if not isinstance(value, dict):
            raise L10nError("%s:%d: each line must be a JSON object" % (path, number))
        rows.append(value)
    return rows


def dump_json_line(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def write_jsonl(path, rows):
    l10nlib.write_text(path, "".join(dump_json_line(r) + "\n" for r in rows))


def write_json(path, value):
    l10nlib.write_text(path, json.dumps(value, ensure_ascii=False, indent=1, sort_keys=False) + "\n")


def text_value(kind, items):
    """The JSON form of a text: a string, or {quantity: text} for a plural."""
    if kind == "string":
        return items["other"]
    return collections.OrderedDict((q, items[q]) for q in QUANTITIES if q in items)


def items_of(kind, value):
    """{quantity: text} from the JSON form, or None when the shape does not match the kind."""
    if kind == "string":
        return collections.OrderedDict([("other", value)]) if isinstance(value, str) else None
    if not isinstance(value, dict) or not value:
        return None
    if any(q not in QUANTITIES or not isinstance(t, str) for q, t in value.items()):
        return None
    return collections.OrderedDict((q, value[q]) for q in QUANTITIES if q in value)


QUOTES = {"“": '"', "”": '"', "„": '"', "«": '"', "»": '"', "‹": "'",
          "›": "'", "‘": "'", "’": "'", "‚": "'", "`": "'"}


def normalize(text):
    """Agreement form (plan 7.2 step 4): NFC, whitespace runs as one space, one quote style."""
    text = unicodedata.normalize("NFC", text)
    text = "".join(QUOTES.get(ch, ch) for ch in text)
    text = re.sub(r"[\s\u00a0\u202f]+", " ", text)
    return text.strip()


def normalized_value(items):
    return tuple((q, normalize(t)) for q, t in sorted(items.items()))
