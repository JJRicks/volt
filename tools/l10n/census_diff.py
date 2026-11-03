#!/usr/bin/env python3
"""G7, the screen text census diff: two TextCensus dumps must hold the same text once clock, duration
and date values are masked. Bounds differences are reported but never fail.

The census format (written by android-tests/TextCensus.java, added in B0b): one UTF-8 file per
captured screen, named <variant>-<label>.census.tsv. The first line is exactly

  volt-text-census-v1

then optional lines starting with #, then one record per line with four tab-separated fields:

  field     what the text is: text, desc (contentDescription), hint, tooltip, state (stateDescription),
            error, title (window or dialog title), message (dialog message), button (dialog button),
            item (popup or list item), toast, notif-title, notif-text, notif-subtext, notif-action,
            notif-channel
  locator   where it is: tag=<view tag> when the view has a String tag, else the class path from the
            window root with child indexes, e.g. DecorView/LinearLayout[0]/TextView[2]; for a
            notification, notification:<channel>/<id>; for a dialog, dialog[<n>]
  bounds    x,y,width,height on screen in pixels, or - when there are none
  value     the text

In locator and value, backslash, tab, line feed and carriage return are written as \\\\, \\t, \\n and \\r.
Records are in view-tree order (depth first), then dialogs, then notifications.

  census_diff.py BASE HEAD [--show-bounds]      BASE and HEAD are census files or folders of them
  census_diff.py --mask FILE                    print a census with the masks applied
"""

import argparse
import collections
import difflib
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l10nlib  # noqa: E402

MAGIC = "volt-text-census-v1"
SUFFIX = ".census.tsv"
FIELDS = ("text", "desc", "hint", "tooltip", "state", "error", "title", "message", "button", "item", "toast",
          "notif-title", "notif-text", "notif-subtext", "notif-action", "notif-channel")
BOUNDS = re.compile(r"^-?\d+,-?\d+,\d+,\d+$")

Record = collections.namedtuple("Record", "field locator bounds value line")

MONTHS_SHORT = ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Sept", "Oct", "Nov", "Dec")
MONTHS_LONG = ("January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
               "November", "December")
DAYS_SHORT = ("Mon", "Tue", "Tues", "Wed", "Thu", "Thur", "Thurs", "Fri", "Sat", "Sun")
DAYS_LONG = ("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")


def _words(*groups):
    every = [w for g in groups for w in g]
    return "(?:%s)" % "|".join(sorted(set(every), key=len, reverse=True))


MONTH = r"(?:%s|%s|%s)\.?" % (_words(MONTHS_LONG), _words(MONTHS_SHORT), _words(m.upper() for m in MONTHS_SHORT))
WEEKDAY = r"(?:%s|%s|%s),?" % (_words(DAYS_LONG), _words(DAYS_SHORT), _words(d.upper() for d in DAYS_SHORT))
SPACE = "[ \u00a0\u202f]"
AMPM = r"(?:[AaPp]\.?[Mm]\.?)(?![A-Za-z])"
UNITS = ("y", "yr", "yrs", "year", "years", "mo", "mos", "month", "months", "w", "wk", "wks", "week", "weeks",
         "d", "day", "days", "h", "hr", "hrs", "hour", "hours", "m", "min", "mins", "minute", "minutes",
         "s", "sec", "secs", "second", "seconds", "ms")
# Singular and plural spellings of one unit. They may differ between base and head only when the number
# differs too ("1 minute ago" then "2 minutes ago"); with the same number the words must match, so a
# preserved English bug such as "1 seconds left" cannot change unseen.
PLURAL_PAIRS = {"yrs": "yr", "years": "year", "mos": "mo", "months": "month", "wks": "wk", "weeks": "week",
                "days": "day", "hrs": "hr", "hours": "hour", "mins": "min", "minutes": "minute", "secs": "sec",
                "seconds": "second"}

# Each pattern finds one clock, duration or date value. Inside it, every digit run becomes <n>, every
# month or weekday word becomes its form (<MMM>, <MMMM>, <MMM:upper>, <EEE>, <EEEE>), AM/PM becomes
# <a> in its own spelling class, and every other character, unit words included, stays as written.
PATTERNS = [
    re.compile(r"\b\d{4}-\d{2}-\d{2}(?:[T ]\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?Z?)?\b"),
    re.compile(r"\b\d{4}-\d{3}\b"),
    re.compile(r"\b\d{1,2}/\d{1,2}(?:/\d{2,4})?\b"),
    re.compile(r"\b(?:%s%s)?%s%s\d{1,2}(?:,%s\d{4})?\b" % (WEEKDAY, SPACE, MONTH, SPACE, SPACE)),
    re.compile(r"\b\d{1,2}%s%s(?:%s\d{4})?(?![A-Za-z])" % (SPACE, MONTH, SPACE)),
    re.compile(r"\b\d{1,2}:\d{2}(?::\d{2})?(?:%s?%s)?" % (SPACE, AMPM)),
    re.compile(r"\b\d+(?:\.\d+)?%s?(?:%s)\b" % (SPACE, "|".join(sorted(UNITS, key=len, reverse=True)))),
]
_WORD = re.compile(r"[A-Za-z]+\.?|\d+(?:\.\d+)?")
_DURATION = re.compile(r"^(\d+(?:\.\d+)?)(%s?)([A-Za-z]+)$" % SPACE)


def _form(word):
    bare = word.rstrip(".")
    dot = "." if word.endswith(".") else ""
    if bare in MONTHS_LONG and bare != "May":
        return "<MMMM>" + dot
    if bare in MONTHS_SHORT:
        return "<MMM>" + dot
    if bare in [m.upper() for m in MONTHS_SHORT]:
        return "<MMM:upper>" + dot
    if bare in DAYS_LONG:
        return "<EEEE>" + dot
    if bare in DAYS_SHORT:
        return "<EEE>" + dot
    if bare in [d.upper() for d in DAYS_SHORT]:
        return "<EEE:upper>" + dot
    if re.match(r"^[AaPp]\.?[Mm]\.?$", word):
        return "<a:%s>" % ("upper" if word[0].isupper() else "lower") + ("." if "." in word else "")
    return word


def _shape(value):
    return _WORD.sub(lambda m: "<n>" if m.group(0)[0].isdigit() else _form(m.group(0)), value)


def masked(value):
    """(comparison key, durations): the key has values masked and plural unit spellings merged; durations
    lists (number, unit as written) for the extra singular/plural check."""
    out, durations, at = [], [], 0
    spans = []
    for pattern in PATTERNS:
        for m in pattern.finditer(value):
            if not any(m.start() < e and s < m.end() for s, e in spans):
                spans.append((m.start(), m.end()))
    for s, e in sorted(spans):
        out.append(value[at:s])
        piece = value[s:e]
        duration = _DURATION.match(piece)
        if duration and duration.group(3) in UNITS:
            unit = duration.group(3)
            durations.append((duration.group(1), unit))
            out.append("<n>" + duration.group(2) + PLURAL_PAIRS.get(unit, unit) + ("(s)" if unit in PLURAL_PAIRS or
                                                                                    unit in PLURAL_PAIRS.values() else ""))
        else:
            out.append(_shape(piece))
        at = e
    out.append(value[at:])
    return "".join(out), tuple(durations)


def mask(value):
    return masked(value)[0]


def same_after_masking(base_value, head_value):
    """True when two texts differ only in clock, duration and date values."""
    a, a_durations = masked(base_value)
    b, b_durations = masked(head_value)
    if a != b:
        return False
    for (n1, u1), (n2, u2) in zip(a_durations, b_durations):
        if n1 == n2 and u1 != u2:
            return False
    return True


class CensusError(ValueError):
    pass


def parse(text, source="census"):
    lines = text.split("\n")
    if not lines or lines[0].rstrip("\r") != MAGIC:
        raise CensusError("%s: the first line must be %s" % (source, MAGIC))
    records = []
    for number, line in enumerate(lines[1:], 2):
        line = line.rstrip("\r")
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) != 4:
            raise CensusError("%s:%d: expected 4 tab-separated fields, found %d" % (source, number, len(fields)))
        field, locator, bounds, value = fields
        if field not in FIELDS:
            raise CensusError("%s:%d: unknown field %r" % (source, number, field))
        if bounds != "-" and not BOUNDS.match(bounds):
            raise CensusError("%s:%d: bounds must be x,y,width,height or -" % (source, number))
        try:
            records.append(Record(field, l10nlib.unescape_field(locator), bounds, l10nlib.unescape_field(value), number))
        except ValueError as error:
            raise CensusError("%s:%d: %s" % (source, number, error))
    return records


def write(records):
    """The census text for records (field, locator, bounds, value); the format's reference writer."""
    out = [MAGIC]
    for field, locator, bounds, value in records:
        out.append("\t".join((field, l10nlib.escape_field(locator), bounds, l10nlib.escape_field(value))))
    return "\n".join(out) + "\n"


def compare(base, head):
    """(text_differences, bounds_differences) between two record lists."""
    a = [(r.field, r.locator, mask(r.value)) for r in base]
    b = [(r.field, r.locator, mask(r.value)) for r in head]
    text, bounds = [], []
    matcher = difflib.SequenceMatcher(None, a, b, autojunk=False)
    for tag, i1, i2, j1, j2 in matcher.get_opcodes():
        if tag == "equal":
            for i, j in zip(range(i1, i2), range(j1, j2)):
                if not same_after_masking(base[i].value, head[j].value):
                    text.append("- %s %s: %s" % (base[i].field, base[i].locator, l10nlib.json_string(base[i].value)))
                    text.append("+ %s %s: %s" % (head[j].field, head[j].locator, l10nlib.json_string(head[j].value)))
                elif base[i].bounds != head[j].bounds:
                    bounds.append("%s %s: %s -> %s" % (base[i].field, base[i].locator, base[i].bounds, head[j].bounds))
            continue
        for i in range(i1, i2):
            text.append("- %s %s: %s" % (base[i].field, base[i].locator, l10nlib.json_string(base[i].value)))
        for j in range(j1, j2):
            text.append("+ %s %s: %s" % (head[j].field, head[j].locator, l10nlib.json_string(head[j].value)))
    return text, bounds


def _collect(path):
    if os.path.isdir(path):
        found = {}
        for directory, _, names in os.walk(path):
            for name in names:
                if name.endswith(SUFFIX):
                    full = os.path.join(directory, name)
                    found[os.path.relpath(full, path)] = full
        return found
    return {os.path.basename(path): path}


def diff_paths(base_path, head_path):
    """(text problems, bounds notes, screens compared)."""
    base_files, head_files = _collect(base_path), _collect(head_path)
    single = not os.path.isdir(base_path) and not os.path.isdir(head_path)
    if single:
        pairs = [("", base_path, head_path)]
    else:
        pairs = []
        for name in sorted(set(base_files) | set(head_files)):
            pairs.append((name, base_files.get(name), head_files.get(name)))
    problems, notes = [], []
    for name, a, b in pairs:
        if a is None or b is None:
            problems.append("%s: only in %s" % (name, "head" if a is None else "base"))
            continue
        try:
            text, bounds = compare(parse(l10nlib.read_text(a), a), parse(l10nlib.read_text(b), b))
        except CensusError as error:
            problems.append(str(error))
            continue
        label = name or os.path.basename(b)
        problems.extend("%s: %s" % (label, t) for t in text)
        notes.extend("%s: %s" % (label, t) for t in bounds)
    return problems, notes, len(pairs)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("paths", nargs="*", help="BASE HEAD")
    parser.add_argument("--mask", metavar="FILE", help="print FILE with masks applied")
    parser.add_argument("--show-bounds", action="store_true", help="list each bounds difference")
    args = parser.parse_args(argv)
    if args.mask:
        for record in parse(l10nlib.read_text(args.mask), args.mask):
            print("%s\t%s\t%s" % (record.field, record.locator, mask(record.value)))
        return 0
    if len(args.paths) != 2:
        parser.error("give BASE and HEAD")
    problems, notes, screens = diff_paths(args.paths[0], args.paths[1])
    for problem in problems:
        print("census_diff: " + problem, file=sys.stderr)
    if args.show_bounds:
        for note in notes:
            print("census_diff: bounds: " + note)
    print("census_diff: %s: %d screen(s), %d text difference(s), %d bounds difference(s) (reported only)" % (
        "FAILED" if problems else "PASS", screens, len(problems), len(notes)))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
