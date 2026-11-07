#!/usr/bin/env python3
"""Which English width budgets are too tight for a longer language: run it before starting one.

  budget_headroom.py                      the table: keys whose English x 1.3 exceeds the budget
  budget_headroom.py --growth 1.4         another growth factor
  budget_headroom.py --json               the same report as JSON
  budget_headroom.py --all                every key with a budget, tight or not
  budget_headroom.py --tags es,pt-BR      evidence from these languages only (default: every
                                          language with l10n/decisions/<tag>/)

Report only: it changes no file and calls nothing on the network.

Widths. Every English <string>/<plurals> of res/values/strings_<area>.xml with a volt:max is measured
exactly as pack.py measures english_width_dp, which is the number check.py's width check compares
with: the placeholders replaced by pack.sample_text's samples, then fontmetrics.measure_lines in
Saira (java.awt where a Java runtime is available, else the hmtx approximation; the report names
the method). A budget of more than one line (64dp@12sp*2) is measured as the narrowest width that
holds the text in that many lines of whole words; multiplying that by the growth factor is an
estimate, since a longer language also wraps differently. A plural's width is its widest form.

A key is tight when English width x growth > budget_dp. Rows sort by the growth the budget allows
(budget / English width), tightest first; under 1.0 the English itself does not fit.

Ladders. For a key in a volt:ladder group the report lists the other rungs, each with its own
budget and whether it still fits at the growth. A full rung marked volt:overflow="short" also gets
its _short rung measured at the full's budget (its sp and lines), as check.py measures the short
rung the screen shows where the full does not fit.

Evidence (the shipped languages' record, so a key marked here is proven tight, not estimated):
  short-rung    a decision's width check says the screen shows the short rung in place of the full;
  overflow      the key carries volt:overflow (in res/values or a language folder);
  merge-width   the judge merged a new text and its reason talks about width, or some draft failed
                the width check;
  fallback-width  English kept for width: a fallback-english decision, a key missing from
                res/<folder>/ whose drafts failed the width check, or a judge `fallback` ruling in
                l10n/work/<tag>/<area>/judge.jsonl whose reason talks about width (a later ruling
                may have replaced it; the report says when the key is translated now);
  over-budget   the decided text still exceeds its budget (accepted as a warning where the English
                does not fit either).
A width finding measured against a budget the key no longer has (the budget changed since, as
#292 and #299 changed some) is listed as "older <budget>" and proves nothing about the current one.
The shipped text's own width is listed too (observed growth = its width / the English width).
A key is proven when a shipped language has evidence on the key itself against its current budget
(the English volt:overflow marker alone, or a sibling rung's evidence, is shown but is not proof).
A proven key within the growth is listed in a second section: the growth factor is too small for it.
"""

import argparse
import collections
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fontmetrics  # noqa: E402
import gen_catalog  # noqa: E402
import pack as packlib  # noqa: E402
import translib  # noqa: E402

DEFAULT_GROWTH = 1.3
FORMAT = "volt-l10n-budget-headroom-v1"
SHORT_SHOWN = "the screen shows the short rung"
# How a judge's reason says the problem was the width (its rulings quote dp figures and budgets).
WIDTH_REASON = re.compile(r"\d(?:\.\d+)?\s?dp\b|\boverflow|\bbudget\b|\btruncat|\bdoes not fit\b|\bwider than\b|"
                          r"\btoo (?:long|wide)\b", re.IGNORECASE)
TRANSLATED = ("agreed", "judged", "safety-agreed", "safety-judged")
EVIDENCE_KINDS = ("short-rung", "overflow", "merge-width", "fallback-width", "over-budget")
# check.py's width message: "<width> at <sp>sp exceeds the <dp>dp budget[ in <n> lines of whole words]".
CHECK_BUDGET = re.compile(r" at (\d+(?:\.\d+)?)sp exceeds the (\d+(?:\.\d+)?)dp budget(?: in ([2-9]) lines)?")


def _width_findings(findings):
    return [f for f in findings or [] if f.get("check") == "width" and f.get("severity") != "info"]


def checked_budget(message):
    """(dp, sp, lines) a width finding was measured against, or None."""
    m = CHECK_BUDGET.search(message or "")
    return (float(m.group(2)), float(m.group(1)), int(m.group(3) or 1)) if m else None


# How a judge's ruling names the budget it measured against ("against the 96 dp budget", "overflows the
# 48dp badge", "over 90dp", "against 220 dp"); a measured width ("(94.0dp", "57.6dp at 10.5sp") is not one.
RULING_BUDGET = re.compile(r"(?:\bthe |\bagainst |\bover )(\d+(?:\.\d+)?)\s?dp\b(?! at)", re.IGNORECASE)


def ruling_budget_dp(reason):
    """The budget width (dp) a ruling's reason names, or None."""
    m = RULING_BUDGET.search(reason or "")
    return float(m.group(1)) if m else None


def _older_dp(reason, key, budgeted):
    """"<n>dp" when a ruling's reason names a budget width other than the key's current one, else None."""
    named = ruling_budget_dp(reason)
    if named is not None and key in budgeted and abs(named - budgeted[key][0]) > 0.01:
        return "%gdp" % named
    return None


def width_reason(text):
    return bool(text) and bool(WIDTH_REASON.search(text))


def _shipped_tags(paths):
    directory = os.path.join(paths.root, "l10n", "decisions")
    if not os.path.isdir(directory):
        return []
    return sorted(name for name in os.listdir(directory)
                  if translib.TAG_NAME.match(name) and os.path.isdir(os.path.join(directory, name)))


def _read_language_folder(paths, folder):
    """{key: entry} of res/<folder>/strings_*.xml (an empty dict when the folder has none)."""
    directory = paths.res(folder)
    out = {}
    if not os.path.isdir(directory):
        return out
    for name in sorted(os.listdir(directory)):
        if not translib.AREA_FILE.match(name):
            continue
        path = os.path.join(directory, name)
        entries, problems = gen_catalog.read_strings_file(path, paths.rel(path))
        if problems:
            raise translib.L10nError("\n".join(problems))
        for entry in entries:
            out[entry.name] = entry
    return out


def _evidence(tag, kind, detail, source, older_budget=None):
    """One piece of evidence. older_budget names the budget a width finding was measured against when
    that is no longer the key's budget (the finding was made before the budget changed)."""
    return collections.OrderedDict([("tag", tag), ("kind", kind), ("detail", detail), ("source", source),
                                    ("older_budget", older_budget)])


def _budget_text(budget):
    dp, sp, lines = budget
    return "%gdp@%gsp%s" % (dp, sp, "" if lines == 1 else "*%d" % lines)


def collect_evidence(paths, tag, folder, english_keys, budgeted):
    """({key: [evidence]}, {key: {quantity: text}} of the shipped text) for one language.

    english_keys is the set of every translatable English key, budgeted {key: (dp, sp, lines)} the
    keys with a volt:max."""
    evidence = collections.defaultdict(list)
    shipped = _read_language_folder(paths, folder) if folder else {}
    texts = {}
    for key, entry in shipped.items():
        if key in budgeted:
            texts[key] = collections.OrderedDict((q, it.text) for q, it in entry.items.items())
        if entry.meta.get("overflow"):
            evidence[key].append(_evidence(tag, "overflow", "volt:overflow=%s in res/%s" % (
                entry.meta["overflow"], folder), "res"))
    directory = os.path.join(paths.root, "l10n", "decisions", tag)
    decided = {}
    for name in sorted(os.listdir(directory)) if os.path.isdir(directory) else []:
        if not name.endswith(".jsonl"):
            continue
        area = name[:-len(".jsonl")]
        where = "l10n/decisions/%s/%s" % (tag, name)
        for row in translib.read_jsonl(os.path.join(directory, name)):
            key = row.get("key")
            if key not in english_keys:
                continue
            decided[key] = row.get("outcome")
            checks = _width_findings(row.get("checks"))
            for f in checks:
                message = f.get("message", "")
                then = checked_budget(message)
                older = _budget_text(then) if then and key in budgeted and then != budgeted[key] else None
                kind = "short-rung" if SHORT_SHOWN in message else "over-budget"
                evidence[key].append(_evidence(tag, kind, message, where, older))
            drafts_failed = [a for a in row.get("alternatives") or [] if _width_findings(a.get("findings"))]
            judge = row.get("judge") or {}
            if judge.get("verdict") == "merge" and (width_reason(judge.get("reason")) or drafts_failed):
                why = judge.get("reason") or ""
                if drafts_failed and not width_reason(why):
                    why = "%d draft(s) failed the width check; %s" % (len(drafts_failed), why)
                evidence[key].append(_evidence(tag, "merge-width", "«%s»: %s" % (
                    translib.text_value(row.get("kind"), translib.items_of(row.get("kind"), row.get("text")))
                    if row.get("text") is not None else "", why), where, _older_dp(judge.get("reason"), key, budgeted)))
            if row.get("outcome") not in TRANSLATED:
                if checks or drafts_failed or width_reason(row.get("reason")):
                    evidence[key].append(_evidence(tag, "fallback-width", "outcome %s: %s" % (
                        row.get("outcome"), row.get("reason") or ""), where))
            elif folder and shipped and key not in shipped and drafts_failed:
                evidence[key].append(_evidence(tag, "fallback-width", "missing from res/%s; %d draft(s) failed the "
                                               "width check" % (folder, len(drafts_failed)), where))
        judge_path = paths.work(tag, area, "judge.jsonl")
        if os.path.exists(judge_path):
            seen = set()
            for row in translib.read_jsonl(judge_path):
                key = row.get("key")
                if key not in english_keys or row.get("verdict") != "fallback" or not width_reason(row.get("reason")):
                    continue
                if key in seen:
                    continue
                seen.add(key)
                now = " (translated now: %s)" % decided[key] if decided.get(key) in TRANSLATED else ""
                evidence[key].append(_evidence(tag, "fallback-width", (row.get("reason") or "") + now,
                                               paths.rel(judge_path), _older_dp(row.get("reason"), key, budgeted)))
    return evidence, texts


def _rung(source):
    return source.meta.get("ladder") or None


def analyze(paths, growth=DEFAULT_GROWTH, tags=None, use_java=True, measurer=None):
    """The report as an ordered dict: every budgeted English key, measured, with ladders and evidence."""
    if growth <= 0:
        raise ValueError("--growth must be positive")
    sources, _ = translib.load_source(paths)
    rows = [s for area in sources for s in sources[area] if s.translatable]
    by_key = collections.OrderedDict((s.key, s) for s in rows)
    budgets = collections.OrderedDict()
    for s in rows:
        budget = translib.parse_max(s.meta.get("max"))
        if budget:
            budgets[s.key] = budget
    groups = {}
    for area in sources:
        for base, members in translib.ladder_groups([s for s in sources[area] if s.translatable]).items():
            for key in members:
                groups[key] = (base, members)

    languages = dict((lang.tag, lang) for lang in gen_catalog.load_languages(paths.lang_dir()))
    tags = _shipped_tags(paths) if tags is None else list(tags)
    evidence = collections.defaultdict(list)
    shipped_texts = {}
    for tag in tags:
        lang = languages.get(tag)
        found, texts = collect_evidence(paths, tag, lang.folder if lang else None, set(by_key), budgets)
        for key, items in found.items():
            evidence[key].extend(items)
        shipped_texts[tag] = (lang, texts)
    for s in rows:
        if s.meta.get("overflow"):
            evidence[s.key].insert(0, _evidence(None, "overflow", "volt:overflow=%s in the English catalog" %
                                                s.meta["overflow"], paths.rel(s.file)))

    # Measure everything in one go, the way pack.build_pack measures english_width_dp.
    measurer = measurer or fontmetrics.Measurer(paths.font(), use_java=use_java)
    requests, owners = [], []

    def ask(owner, size, text, formatted, lines):
        requests.append((size, packlib.sample_text(text, formatted), lines))
        owners.append(owner)

    for key, (_dp, sp, lines) in budgets.items():
        s = by_key[key]
        for text in s.items.values():
            ask(("en", key), sp, text, s.formatted, lines)
        if s.meta.get("overflow") == "short" and key + "_short" in by_key:
            short = by_key[key + "_short"]
            for text in short.items.values():
                ask(("short-at-full", key), sp, text, short.formatted, lines)
        for tag, (lang, texts) in shipped_texts.items():
            if key in texts and (lang is None or lang.font == "saira"):
                for text in texts[key].values():
                    ask((tag, key), sp, text, s.formatted, lines)
    widths = fontmetrics.measure_lines(measurer, requests) if requests else []
    measured = collections.defaultdict(float)
    for owner, width in zip(owners, widths):
        measured[owner] = max(measured[owner], width)

    def fits(width, budget):
        return width * growth <= budget + 0.01

    keys = []
    for key, (dp, sp, lines) in budgets.items():
        s = by_key[key]
        english = round(measured[("en", key)], 2)
        allowed = round(dp / english, 3) if english else None
        ladder = None
        if key in groups:
            base, members = groups[key]
            rungs = []
            for other in members:
                if other == key:
                    continue
                o = by_key[other]
                ob = budgets.get(other)
                ow = round(measured[("en", other)], 2) if ob else None
                rungs.append(collections.OrderedDict([
                    ("key", other), ("rung", _rung(o)), ("max", o.meta.get("max")),
                    ("english_width_dp", ow), ("needed_dp", round(ow * growth, 1) if ob else None),
                    ("fits", fits(ow, ob[0]) if ob else None)]))
            ladder = collections.OrderedDict([("group", base), ("rung", _rung(s)), ("rungs", rungs)])
            if s.meta.get("overflow") == "short" and ("short-at-full", key) in measured:
                w = round(measured[("short-at-full", key)], 2)
                ladder["short_at_this_budget"] = collections.OrderedDict([
                    ("key", key + "_short"), ("english_width_dp", w), ("needed_dp", round(w * growth, 1)),
                    ("fits", fits(w, dp))])
        observed = collections.OrderedDict()
        for tag, (lang, texts) in shipped_texts.items():
            if (tag, key) in measured and english:
                w = round(measured[(tag, key)], 2)
                observed[tag] = collections.OrderedDict([("width_dp", w), ("growth", round(w / english, 3)),
                                                         ("fits", w <= dp + 0.01)])
        group_evidence = []
        if key in groups:
            for other in groups[key][1]:
                if other != key:
                    for e in evidence.get(other, []):
                        item = collections.OrderedDict(e)
                        item["key"] = other
                        group_evidence.append(item)
        keys.append(collections.OrderedDict([
            ("key", key), ("area", s.area), ("kind", s.kind), ("max", s.meta.get("max")),
            ("budget_dp", dp), ("budget_sp", sp), ("budget_lines", lines), ("screen", s.meta.get("screen")),
            ("english", translib.text_value(s.kind, s.items)), ("english_width_dp", english),
            ("needed_dp", round(english * growth, 1)), ("allowed_growth", allowed),
            ("tight", not fits(english, dp)), ("ladder", ladder), ("observed", observed),
            ("evidence", evidence.get(key, [])), ("rung_evidence", group_evidence)]))
    for row in keys:
        row["proven"] = proven(row)
    keys.sort(key=lambda r: (r["allowed_growth"] if r["allowed_growth"] is not None else 99, r["key"]))
    return collections.OrderedDict([
        ("format", FORMAT), ("growth", growth), ("width_method", measurer.method),
        ("java_problem", measurer.java_problem), ("tags", tags), ("budgeted", len(keys)),
        ("tight", sum(1 for r in keys if r["tight"])), ("keys", keys)])


def proven(row):
    """Whether a shipped language needed help with this key's current budget: language evidence on the
    key itself, not only the English volt:overflow marker, a sibling rung's, or a finding made against
    an older budget."""
    return any(e["tag"] and not e["older_budget"] for e in row["evidence"])


def selected(report, everything=False):
    """(tight rows, proven-but-within-growth rows) as the table prints them."""
    if everything:
        return report["keys"], []
    tight = [r for r in report["keys"] if r["tight"]]
    within = [r for r in report["keys"] if not r["tight"] and r["proven"]]
    return tight, within


def _kinds(items):
    counts = collections.OrderedDict()
    for e in items:
        label = e["kind"] + ("" if not e["tag"] else ":" + e["tag"]) + (" (older %s)" % e["older_budget"]
                                                                         if e["older_budget"] else "")
        counts[label] = counts.get(label, 0) + 1
    return ", ".join(counts)


def _ladder_text(row, growth):
    ladder = row["ladder"]
    if not ladder:
        return "no ladder"
    parts = ["%s rung" % (ladder["rung"] or "full")]
    for r in ladder["rungs"]:
        if r["max"]:
            parts.append("%s %s %.0f/%gdp" % (r["rung"] or "full", "fits" if r["fits"] else "TIGHT",
                                              r["needed_dp"], translib.parse_max(r["max"])[0]))
        else:
            parts.append("%s (no budget)" % (r["rung"] or "full"))
    short = ladder.get("short_at_this_budget")
    if short:
        parts.append("short shown here %s %.0f/%gdp" % ("fits" if short["fits"] else "TIGHT", short["needed_dp"],
                                                       row["budget_dp"]))
    return "; ".join(parts)


def _print_rows(rows, growth, out):
    for row in rows:
        lines = "" if row["budget_lines"] == 1 else " x%d lines" % row["budget_lines"]
        out.write("%-44s %s %6.2f  %6.1f -> %6.1f / %5gdp@%gsp%s  [%s]\n" % (
            row["key"], "P" if row["proven"] else " ", row["allowed_growth"] or 0, row["english_width_dp"], row["needed_dp"], row["budget_dp"],
            row["budget_sp"], lines, row["area"]))
        english = row["english"] if isinstance(row["english"], str) else " | ".join(
            "%s: %s" % (q, t) for q, t in row["english"].items())
        out.write("    english: %s\n" % english.replace("\n", " / "))
        out.write("    ladder:  %s\n" % _ladder_text(row, growth))
        if row["observed"]:
            out.write("    shipped: %s\n" % ", ".join("%s %.1fdp x%.2f%s" % (
                tag, o["width_dp"], o["growth"], "" if o["fits"] else " OVER") for tag, o in row["observed"].items()))
        if row["evidence"]:
            out.write("    proven:  %s\n" % _kinds(row["evidence"]))
        if row["rung_evidence"]:
            out.write("    rungs:   %s\n" % "; ".join("%s %s" % (k, _kinds([e for e in row["rung_evidence"]
                                                                        if e["key"] == k]))
                                                    for k in collections.OrderedDict(
                                                        (e["key"], 1) for e in row["rung_evidence"])))


def print_report(report, everything=False, out=None):
    out = out or sys.stdout
    growth = report["growth"]
    tight, within = selected(report, everything)
    method = report["width_method"] + (" (%s)" % report["java_problem"] if report["java_problem"] else "")
    out.write("Budget headroom at growth x%g (%s; evidence from %s)\n" % (
        growth, method, ", ".join(report["tags"]) or "no language"))
    out.write("%d of %d budgeted keys are tight: English x%g exceeds the budget.\n" % (
        report["tight"], report["budgeted"], growth))
    out.write("Columns: key, P where a shipped language proved it tight, allowed growth (budget / English),\n"
              "English dp -> needed dp / budget [area]\n\n")
    _print_rows(tight, growth, out)
    if within:
        out.write("\nProven tight in a shipped language but within x%g (the growth factor is too small here):\n\n" %
                  growth)
        _print_rows(within, growth, out)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--root", help="repository root (default: this checkout)")
    parser.add_argument("--growth", type=float, default=DEFAULT_GROWTH,
                        help="expected length of the new language against English (default %g)" % DEFAULT_GROWTH)
    parser.add_argument("--tags", help="comma-separated languages to read evidence from (default: every "
                                       "l10n/decisions/<tag>/)")
    parser.add_argument("--all", action="store_true", help="list every budgeted key, tight or not")
    parser.add_argument("--json", action="store_true", help="print the report as JSON")
    parser.add_argument("--no-java", action="store_true", help="measure widths with the hmtx approximation")
    args = parser.parse_args(argv)
    paths = translib.Paths(args.root)
    tags = None
    try:
        if args.tags is not None:
            tags = [t for t in args.tags.split(",") if t]
            for tag in tags:
                translib.check_tag_area(tag, None)
        report = analyze(paths, args.growth, tags, use_java=not args.no_java)
    except (translib.L10nError, gen_catalog.CatalogError, fontmetrics.FontError, ValueError, OSError) as error:
        sys.stderr.write("budget_headroom.py: %s\n" % error)
        return 2
    if args.json:
        if not args.all:
            tight, within = selected(report)
            report = collections.OrderedDict(report)
            report["keys"] = tight
            report["proven_within_growth"] = within
        sys.stdout.write(json.dumps(report, ensure_ascii=False, indent=1) + "\n")
    else:
        print_report(report, args.all)
    return 0


if __name__ == "__main__":
    sys.exit(main())
