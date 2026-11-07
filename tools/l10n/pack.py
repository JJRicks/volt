#!/usr/bin/env python3
"""Build translation work packs (plan 7.1 and 7.2 step 1).

  pack.py --tag es --area common          l10n/packs/es/common.json
  pack.py --tag es --all                  one pack per area
  pack.py --tag es --area common --stdout

A pack holds, per English key: the key, its English text, its volt:* metadata and note, the measured
English width and its budget (budget_dp at budget_sp on up to budget_lines lines; with more than one
line, english_width_dp is the narrowest width that holds the English in that many lines of whole
words; overflow "short" on a full rung whose screen shows the group's _short rung where the full
does not fit, see check.py's width check), glossary hits with the approved term in the language,
neighbouring strings, and any current translation (with whether it is stale). Plus the language's settings and style guide. Packs are generated and never committed (l10n/.gitignore).

Only UI strings go into a pack: the entries of res/values/strings_<area>.xml, without
translatable="false" keys and without l10n_tag. Reports, diagnostics and user data never live in
those files (plan R4 and section 8), and the pack reads nothing else. PACK_ENTRY_FIELDS is the whole
of what a model ever sees about a string.
"""

import argparse
import collections
import hashlib
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fontmetrics  # noqa: E402
import gen_catalog  # noqa: E402
import l10nlib  # noqa: E402
import translib  # noqa: E402

FORMAT = "volt-l10n-pack-v1"
NEIGHBOURS = 2
PACK_ENTRY_FIELDS = ("key", "kind", "english", "source_sha1", "formatted", "placeholders", "screen", "purpose", "max",
                     "ladder", "ladder_group", "overflow", "safety", "dnt", "note", "english_width_dp", "budget_dp",
                     "budget_sp", "budget_lines", "glossary", "neighbours", "current", "status")
LANGUAGE_FIELDS = ("tag", "folder", "endonym", "formality", "plurals", "quote_style", "percent_style",
                   "decimal_style", "script", "font", "rtl", "unit_symbols", "drive_mode_names")
# Placeholder stand-ins for measuring a template: the same for English and every language, so a
# budget compares like with like.
SAMPLE = {"s": "8888", "d": "888", "f": "88.8"}


def sample_text(text, formatted=True):
    """The text with each placeholder replaced by its sample and %% by %, for measuring."""
    if not formatted:
        return text
    out, at = [], 0
    for m in gen_catalog.PLACEHOLDER.finditer(text):
        out.append(text[at:m.start()])
        conversion = m.group(5)
        out.append("%" if conversion == "%" else SAMPLE.get(conversion, "8888"))
        at = m.end()
    out.append(text[at:])
    return "".join(out)


def language_settings(lang):
    """The language's settings as a pack shows them."""
    language = collections.OrderedDict((f, getattr(lang, f)) for f in LANGUAGE_FIELDS)
    language["plurals"] = translib.plural_categories(lang)
    return language


def read_current(paths, lang, area):
    """{key: (kind, {quantity: text}, src_sha1 or None)} from res/<folder>/strings_<area>.xml, if any."""
    path = paths.res(lang.folder, "strings_%s.xml" % area)
    if not os.path.exists(path):
        return {}
    entries, problems = gen_catalog.read_strings_file(path, paths.rel(path))
    if problems:
        raise translib.L10nError("\n".join(problems))
    current = {}
    for entry in entries:
        note = (entry.note or "").strip()
        sha = note[4:].strip() if note.startswith("src:") else None
        items = collections.OrderedDict((q, it.text) for q, it in entry.items.items())
        current[entry.name] = (entry.kind, items, sha)
    return current


def glossary_hits(terms, text):
    """The glossary terms a text uses, as a pack entry shows them."""
    return [collections.OrderedDict([
        ("id", term.id), ("term", term.term), ("definition", term.definition),
        ("dnt", term.dnt), ("safety", term.safety or None), ("target", term.targets)])
        for term in translib.glossary_hits(terms, text)]


def build_pack(paths, tag, area, measurer=None, sources=None):
    """The pack for one language and area, as an ordered dict ready for JSON."""
    translib.check_tag_area(tag, area)
    lang = translib.load_language(paths, tag)
    if sources is None:
        sources, _ = translib.load_source(paths)
    if area not in sources:
        raise translib.L10nError("no res/values/strings_%s.xml (areas: %s)" % (area, ", ".join(sources) or "none"))
    rows = [s for s in sources[area] if s.translatable]
    terms = translib.load_glossary(paths, tag)
    current = read_current(paths, lang, area)
    groups = translib.ladder_groups(rows)
    safety = translib.effective_safety(rows)
    group_of = {}
    for base, members in groups.items():
        for key in members:
            group_of[key] = members
    if measurer is None:
        measurer = fontmetrics.Measurer(paths.font())

    requests, owners = [], []
    for index, s in enumerate(rows):
        budget = translib.parse_max(s.meta.get("max"))
        size, lines = (budget[1], budget[2]) if budget else (14.0, 1)
        for text in s.items.values():
            requests.append((size, sample_text(text, s.formatted), lines))
            owners.append(index)
    widths = fontmetrics.measure_lines(measurer, requests)
    width_of = collections.defaultdict(float)
    for owner, width in zip(owners, widths):
        width_of[owner] = max(width_of[owner], width)

    entries = []
    for index, s in enumerate(rows):
        budget = translib.parse_max(s.meta.get("max"))
        sha = translib.source_sha1(s.kind, s.items)
        text = " ".join(s.items.values())
        hits = glossary_hits(terms, text)
        neighbours = []
        for other in rows[max(0, index - NEIGHBOURS):index] + rows[index + 1:index + 1 + NEIGHBOURS]:
            neighbours.append(collections.OrderedDict([
                ("key", other.key), ("english", translib.text_value(other.kind, other.items))]))
        existing = current.get(s.key)
        current_value, status = None, "new"
        if existing:
            stale = existing[2] != sha
            current_value = collections.OrderedDict([
                ("text", translib.text_value(existing[0], existing[1])), ("src_sha1", existing[2]), ("stale", stale)])
            status = "stale" if stale else "current"
        args = translib.source_signature(s)
        entry = collections.OrderedDict([
            ("key", s.key), ("kind", s.kind), ("english", translib.text_value(s.kind, s.items)),
            ("source_sha1", sha), ("formatted", s.formatted),
            ("placeholders", collections.OrderedDict((str(i), args[i]) for i in sorted(args))),
            ("screen", s.meta.get("screen")), ("purpose", s.meta.get("purpose")), ("max", s.meta.get("max")),
            ("ladder", s.meta.get("ladder")), ("ladder_group", group_of.get(s.key)), ("overflow", s.meta.get("overflow")),
            ("safety", safety[s.key]), ("dnt", translib.dnt_of(s.meta)), ("note", s.note),
            ("english_width_dp", round(width_of[index], 2)),
            ("budget_dp", budget[0] if budget else None), ("budget_sp", budget[1] if budget else None),
            ("budget_lines", budget[2] if budget else None),
            ("glossary", hits), ("neighbours", neighbours), ("current", current_value), ("status", status)])
        assert tuple(entry) == PACK_ENTRY_FIELDS
        entries.append(entry)

    style_path = paths.style(tag)
    style = l10nlib.read_text(style_path) if os.path.exists(style_path) else None
    language = language_settings(lang)
    pack = collections.OrderedDict([
        ("format", FORMAT), ("tag", tag), ("area", area), ("folder", lang.folder),
        ("source_file", "res/values/strings_%s.xml" % area), ("language", language),
        ("style_guide", style), ("width_method", measurer.method), ("entries", entries)])
    body = json.dumps(pack, ensure_ascii=False, sort_keys=True)
    pack["pack_sha1"] = hashlib.sha1(body.encode("utf-8")).hexdigest()
    return pack


def load_pack(paths, tag, area):
    path = paths.pack(tag, area)
    if not os.path.exists(path):
        raise translib.L10nError("%s does not exist; run tools/l10n/pack.py --tag %s --area %s" % (
            paths.rel(path), tag, area))
    pack = json.loads(l10nlib.read_text(path))
    if pack.get("format") != FORMAT:
        raise translib.L10nError("%s: not a %s pack" % (path, FORMAT))
    return pack


def write_pack(paths, pack):
    path = paths.pack(pack["tag"], pack["area"])
    translib.write_json(path, pack)
    return path


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", help="repository root (default: this checkout)")
    parser.add_argument("--tag", required=True)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--area")
    group.add_argument("--all", action="store_true", help="every res/values/strings_<area>.xml")
    parser.add_argument("--stdout", action="store_true", help="print the pack instead of writing it")
    parser.add_argument("--no-java", action="store_true", help="measure with the hmtx approximation only")
    args = parser.parse_args(argv)
    paths = translib.Paths(args.root)
    try:
        translib.require_valid_glossary(paths, args.tag)
        sources, _ = translib.load_source(paths)
        areas = list(sources) if args.all else [args.area]
        measurer = fontmetrics.Measurer(paths.font(), use_java=not args.no_java)
        for area in areas:
            pack = build_pack(paths, args.tag, area, measurer, sources)
            if args.stdout:
                sys.stdout.write(json.dumps(pack, ensure_ascii=False, indent=1) + "\n")
            else:
                path = write_pack(paths, pack)
                counts = collections.Counter(e["status"] for e in pack["entries"])
                print("%s: %d strings (%s), widths by %s" % (
                    paths.rel(path), len(pack["entries"]),
                    ", ".join("%d %s" % (counts[k], k) for k in ("new", "stale", "current") if counts[k]) or "none",
                    pack["width_method"]))
        if not areas:
            print("no res/values/strings_<area>.xml files yet; nothing to pack")
        if measurer.java_problem:
            print("note: widths use the hmtx approximation: %s" % measurer.java_problem)
    except (translib.L10nError, gen_catalog.CatalogError, fontmetrics.FontError) as error:
        print("pack.py: %s" % error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
