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

    def owner_decisions(self, tag):
        return os.path.join(self.root, "l10n", "owner-decisions", tag + ".jsonl")

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
    """(dp, sp, lines) from a volt:max such as 96dp@14sp (one line) or 64dp@12sp*2 (up to two lines of
    whole words, each within 64dp), or None."""
    if not value:
        return None
    m = re.match(r"^(\d+(?:\.\d+)?)dp@(\d+(?:\.\d+)?)sp(?:\*([2-9]))?$", value)
    return (float(m.group(1)), float(m.group(2)), int(m.group(3) or 1)) if m else None


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
CLAUDE_ONLY_SOURCE = "claude-only"  # how a glossary row's source starts until the council has judged it


def _read_glossary(path):
    try:
        _, rows = l10nlib.read_tsv(l10nlib.read_text(path), GLOSSARY_HEADER)
    except ValueError as error:  # a bad header or row, or text that is not UTF-8
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
    try:
        text = l10nlib.read_text(path)
    except UnicodeDecodeError as error:
        return [], ["%s: not UTF-8: %s" % (where, error)]
    for number, line in enumerate(text.split("\n"), 1):
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
    """A term column's problems: empty forms, placeholders, a second term in one row (";" or "(",
    since each form must match as written), and U+202F, which Saira cannot draw."""
    forms = [f.strip() for f in column.split("|")]
    out = []
    if not all(forms):
        out.append("%s: %s has an empty term" % (where, term_id))
    if ";" in column or "(" in column:
        out.append("%s: %s: a term holds no \";\" or \"(\"; an alias gets a row or a \" | \" form of its own" % (
            where, term_id))
    for form in forms:
        if gen_catalog.PLACEHOLDER.search(form):
            out.append("%s: %s: the term %r has a placeholder" % (where, term_id, form))
    for form in column.split("|"):  # not stripped: strip() takes a U+202F at either end of a form too
        if "\u202f" in form:
            out.append("%s: %s: the term %r has U+202F, which Saira has no glyph for; use U+00A0" % (
                where, term_id, form.strip(" ")))
    return out


def _row_problems(where, fields):
    """The columns every glossary row must have, whatever the language; _form_problems does the term."""
    term_id, _, definition, pos, dnt, safety, source = fields
    out = []
    if not re.match(r"^[a-z][a-z0-9-]*$", term_id):
        out.append("%s: %r is not a lower-case id" % (where, term_id))
    for name, value in (("definition", definition), ("pos", pos), ("source", source)):
        if not value:
            out.append("%s: %s has an empty %s" % (where, term_id, name))
    if ";" in pos:
        out.append("%s: %s: one part of speech per row" % (where, term_id))
    if dnt not in ("yes", "no"):
        out.append("%s: %s: dnt must be yes or no" % (where, term_id))
    if safety != "-" and safety not in gen_catalog.SAFETY:
        out.append("%s: %s: safety %r is not - or a known value" % (where, term_id, safety))
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
        problems.extend(_row_problems(at, fields))
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
        problems.extend(_row_problems(at, fields))
        problems.extend(_form_problems(at, term_id, fields[1]))
    if not row_problems:
        for term_id, fields in english.items():
            if fields[4] != "yes" and term_id not in seen:
                problems.append("%s: no row for %s (%s)" % (where, term_id, fields[1]))
    return problems


def committed_glossary_problems(paths):
    """{file name: problems} of every l10n/glossary/*.tsv with any, for CI (test_repository_files)."""
    out = collections.OrderedDict()
    directory = paths.glossary_dir()
    for name in sorted(os.listdir(directory)) if os.path.isdir(directory) else []:
        if name.endswith(".tsv"):
            problems = glossary_problems(paths, name[:-4])
            if problems:
                out[name] = problems
    return out


def claude_only_glossary_rows(paths, tag):
    """The ids of l10n/glossary/<tag>.tsv rows no council judged: their source says claude-only."""
    path = os.path.join(paths.glossary_dir(), tag + ".tsv")
    if not os.path.exists(path):
        return []
    return [fields[0] for _, fields in _glossary_rows(path, path)[0] if fields[6].startswith(CLAUDE_ONLY_SOURCE)]


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


# Glossary terms whose words mean something else in some texts: "Stop to change" is stopping the
# scooter on a row that can't change while riding, but in "Recording label · stop to change" the
# recording is what stops. There the term is not used, and the Stop term (silence an alarm, end a
# recording) applies to the text instead.
OTHER_SENSE = {"stop to change": re.compile(r"(?<![^\W_])recordings?(?![^\W_])", re.IGNORECASE | re.UNICODE)}


def used_in_another_sense(term, text):
    """Whether a text that matches a glossary term uses its words in another sense (OTHER_SENSE)."""
    rule = OTHER_SENSE.get(term.lower())
    return bool(rule and rule.search(text))


def glossary_hits(terms, text):
    """The terms a text uses: whole-word matches (lock verbs also in their -s and -ing forms, see
    inflects), without a term used in another sense (used_in_another_sense), a term found only
    inside a longer one (nested_in_longer) or a setting value used in another sense
    (value_out_of_context)."""
    terms = list(terms)
    found = [t for t in terms if term_pattern(t.term, inflects(t)).search(text) and
             not used_in_another_sense(t.term, text)]
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
# model_reported: the model the provider reported for this entry's call (volt:model-reported on the
# element), when it differs from the draft's; None means the draft's model_reported.
DraftEntry = collections.namedtuple("DraftEntry", "key kind items raw attempt problems src model_reported",
                                    defaults=(None,))
Draft = collections.namedtuple("Draft", "name provider model model_reported prompt entries path")


def entry_reported(draft, key):
    """The model the provider reported for the call that drafted draft.entries[key]."""
    return draft.entries[key].model_reported or draft.model_reported


def entry_reported_map(draft):
    """{key: reported model} of every entry of draft (None: {}), for write_draft's entry_reported."""
    return dict((k, entry_reported(draft, k)) for k in draft.entries) if draft is not None else {}


def drafter_models(draft):
    """Every model id the draft names: the model asked, the reported one, and each entry's own."""
    return set([draft.model, draft.model_reported]) | set(
        e.model_reported for e in draft.entries.values() if e.model_reported)


def draft_provider(name):
    for provider in PROVIDERS:
        if name == provider or name.startswith(provider + "-"):
            return provider
    raise L10nError("draft %r must be named claude-<config>, openai[-<x>] or google[-<x>]" % name)


def write_draft(path, name, model, entries, model_reported=None, prompt=None, entry_reported=None):
    """entries: [(key, kind, {quantity: text}, attempt, src)] in pack order.

    entry_reported {key: model} names the model the provider reported for each entry's call; an entry
    whose model differs from the draft's reported one (model_reported, else model) records it as
    volt:model-reported, so a later top-up or redraft by a newer version never hides who drafted it.

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
    file_reported = model_reported or model
    for key, kind, items, attempt, src in entries:
        extra = ' volt:src="%s"' % xml_attr(src) if src else ""
        extra += ' volt:attempt="%d"' % attempt if attempt and attempt > 1 else ""
        own = (entry_reported or {}).get(key)
        extra += ' volt:model-reported="%s"' % xml_attr(own) if own and own != file_reported else ""
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
        attempt, src, own = 1, None, None
        for attr, value in node.attrs.items():
            local = gen_catalog._local(attr)
            if local == "attempt" and gen_catalog._namespace(attr) == gen_catalog.VOLT_NS:
                attempt = int(value) if value.isdigit() else 1
            elif local == "src" and gen_catalog._namespace(attr) == gen_catalog.VOLT_NS:
                src = value.strip() or None
            elif local == "model-reported" and gen_catalog._namespace(attr) == gen_catalog.VOLT_NS:
                own = value.strip() or None
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
                                  src, own)
    reported = attrs.get("model-reported") or model
    for key, entry in entries.items():
        if entry.model_reported == reported:
            entries[key] = entry._replace(model_reported=None)
    return Draft(name, provider, model, reported, attrs.get("prompt"), entries, path)


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


OWNER_DECIDED = "owner-decided"  # the owner chose the text (l10n/owner-decisions/<tag>.jsonl); no model did
TRANSLATED = ("agreed", "judged", "safety-agreed", "safety-judged", OWNER_DECIDED)
OWNER_DECISION_FORMAT = "volt-l10n-owner-decision-v1"
OWNER_DECISION_FIELDS = ("format", "area", "key", "source_sha1", "english", "text", "decided", "reason")


def why_english(source, decision, categories):
    """Why merge.py leaves this key to English (a short reason), or None when it writes the decision.

    The one definition of a translated key: merge.py's render_file writes by it and the beta bar counts by it.
    categories are the language's plural categories.
    """
    if decision is None:
        return "no decision"
    if decision.get("outcome") not in TRANSLATED:
        return "falls back to English"
    if decision.get("source_sha1") != source_sha1(source.kind, source.items):
        return "the English changed since the decision"
    items = items_of(source.kind, decision.get("text"))
    if items is None:
        return "the decision's text has the wrong shape"
    needed = categories if source.kind == "plural" else ["other"]
    missing = [q for q in needed if q not in items]
    if missing:
        return "missing plural forms %s" % ", ".join(missing)
    blank = [q for q in needed if not items[q].strip() and source.items.get(q, source.items["other"]).strip()]
    if blank:
        return "the decision's text is blank"
    return None


def decided_areas(paths, tag):
    """The areas with a decisions file for this language, sorted."""
    directory = os.path.dirname(paths.decisions(tag, "x"))
    return sorted(f[:-6] for f in os.listdir(directory) if f.endswith(".jsonl")) if os.path.isdir(directory) else []


def load_owner_decisions(paths, tag):
    """{key: row} of l10n/owner-decisions/<tag>.jsonl, the owner's texts that council.py decide must keep.

    {} when the language has none. A malformed file stops the caller (L10nError): an owner decision is
    never silently dropped."""
    path = paths.owner_decisions(tag)
    pins = collections.OrderedDict()
    for row in read_jsonl(path):
        where = "%s: %s" % (paths.rel(path), row.get("key"))
        if row.get("format") != OWNER_DECISION_FORMAT:
            raise L10nError("%s: format must be %s" % (where, OWNER_DECISION_FORMAT))
        if sorted(row) != sorted(OWNER_DECISION_FIELDS):
            raise L10nError("%s: the fields must be exactly %s" % (where, ", ".join(OWNER_DECISION_FIELDS)))
        if not all(isinstance(row[f], str) and row[f] for f in OWNER_DECISION_FIELDS if f != "text"):
            raise L10nError("%s: every field must be filled in" % where)
        if not re.match(r"^\d{4}-\d{2}-\d{2}$", row["decided"]):
            raise L10nError("%s: decided must be a date, YYYY-MM-DD" % where)
        if row["key"] in pins:
            raise L10nError("%s: listed twice" % where)
        pins[row["key"]] = row
    return pins


def owner_pin_problems(paths, tag, sources, area=None):
    """Why an owner decision of this language (of one area, if given) cannot be kept, one line each.

    The owner chose a text for one English sentence, so a changed English stops here until the owner
    has chosen again; lock and alarm keys keep the council's safety path, which ship_check requires."""
    categories = plural_categories(load_language(paths, tag))
    problems = []
    for key, pin in load_owner_decisions(paths, tag).items():
        if area is not None and pin["area"] != area:
            continue
        where = "%s: %s" % (paths.rel(paths.owner_decisions(tag)), key)
        rows = [s for s in sources.get(pin["area"], []) if s.translatable]
        source = next((s for s in rows if s.key == key), None)
        if source is None:
            problems.append("%s: no such translatable key in area %s" % (where, pin["area"]))
            continue
        why = why_english(source, dict(pin, outcome=OWNER_DECIDED), categories)
        if why == "the English changed since the decision":
            problems.append("%s: the English changed since the owner decided (now %r): ask the owner, then update this "
                            "line, or remove it only if they withdraw the decision" % (where, source.items["other"]))
        elif why:
            problems.append("%s: %s" % (where, why))
        hit = [x for x in effective_safety(rows).get(key, []) if x in SAFETY_BLOCKING]
        if hit:
            problems.append("%s: a %s key must keep the council's safety path" % (where, ", ".join(hit)))
    return problems


def owner_decision_problems(paths, tag, sources):
    """owner_pin_problems, plus every decision row out of step with the owner's lines, in both directions.

    A line needs its owner-decided row, with the owner's text, from the English the line was decided on; an
    owner-decided row needs its line. merge.py checks this before it writes anything, and ship_check through
    drift_problems. Empty for a language with neither owner lines nor owner-decided rows."""
    problems = owner_pin_problems(paths, tag, sources)
    pins = load_owner_decisions(paths, tag)
    pin_file = paths.rel(paths.owner_decisions(tag))
    rows = {}
    for area in decided_areas(paths, tag):
        for row in read_jsonl(paths.decisions(tag, area)):
            rows[(area, row.get("key"))] = row
    decide = "run council.py decide --tag %s --area %%s, then merge.py" % tag
    for key, pin in pins.items():
        row = rows.get((pin["area"], key))
        if row is None or row.get("outcome") != OWNER_DECIDED or row.get("text") != pin["text"]:
            problems.append("%s: %s has %s, not the owner's text: %s" % (
                pin_file, key, "no decision" if row is None else "outcome %s, text %r" % (
                    row.get("outcome"), row.get("text")), decide % pin["area"]))
        elif row.get("source_sha1") != pin["source_sha1"]:
            problems.append("%s: %s was decided from an older English than the owner's line, so merge.py would leave "
                            "it out: %s" % (pin_file, key, decide % pin["area"]))
    for (area, key), row in rows.items():
        pin = pins.get(key)
        if row.get("outcome") == OWNER_DECIDED and (pin is None or pin["area"] != area):
            problems.append("%s: %s is owner-decided but %s has no line for it in area %s: restore the owner's line. "
                            "Never remove one to get past this; if the owner has withdrawn the decision, %s" % (
                                paths.rel(paths.decisions(tag, area)), key, pin_file, area, decide % area))
    return problems


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
