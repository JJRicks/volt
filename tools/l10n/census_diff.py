#!/usr/bin/env python3
"""G7, the screen text census diff: HEAD's TextCensus dumps must hold the base runs' text once generated
identifiers and clock, duration and date values are masked. Bounds differences are reported but never fail.

Sweep the base APK twice and the head APK once (l10n/README.md, step 7). A record whose value differs
between the base runs only in its digits (drifting simulated telemetry) is compared by form, digit
runs masked; one that differs otherwise (a timing-dependent capture) is volatile: listed and counted,
never compared. Every other record must match HEAD, except that a HEAD value whose numbers alone differ
is reported ("numbers differ"), not failed: number values are G10's to check. With one base run nothing can
be told volatile, so run-to-run noise fails like a text change (a warning says so). Records are keyed
by screen file, field, locator with identifiers masked and the occurrence of that pair; a list that
reorders between the base runs is compared without its order (see compare_runs).

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
Records are in view-tree order (depth first), then dialogs, then notifications (only in the validations
whose checks are about notification text: TextCensus.writeWithNotifications).

  census_diff.py --base A --base A2 HEAD [--show-volatile] [--show-bounds]
                                                two or more base runs, then HEAD: census files or folders
  census_diff.py BASE HEAD                      one base run (volatile records cannot be told apart)
  census_diff.py --mask FILE                    print a census with the masks applied
"""

import argparse
import collections
import json
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


NUMERIC_DATE = PATTERNS[2]

# Generated identifiers, masked in locators and values before anything else. Each is random per run
# and is never text a batch formats: a UUID (profile, recording and scooter ids, also inside view
# tags such as scooter-edit-<uuid>), the hex value of a JSON id field ("report_id": "1458068b..."),
# and a free-standing hex run that is plainly an id: 16 or more hex digits, or 12 to 15 that mix
# digits and letters a-f ("Report 1458068b7d6e6467 · Volt"). A run must be all lower or all upper
# case and stand alone (no letter or digit beside it), so a decimal number shorter than 16 digits,
# a word, and a formatted number ("1,234,567") are never touched.
UUID = re.compile(r"(?<![0-9A-Za-z])[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
                  r"(?![0-9A-Za-z])")
JSON_ID = re.compile(r'("(?:id|[A-Za-z0-9_]+_id)"\s*:\s*")([0-9a-fA-F]{8,})(")')
HEX_ID = re.compile(r"(?<![0-9A-Za-z])(?:[0-9a-f]{16,}|[0-9A-F]{16,}"
                    r"|(?=[0-9a-f]*[a-f])(?=[a-f]*[0-9])[0-9a-f]{12,15}"
                    r"|(?=[0-9A-F]*[A-F])(?=[A-F]*[0-9])[0-9A-F]{12,15})(?![0-9A-Za-z])")
# Report previews show the JSON a report carries, whose millisecond fields ("wall_ms": 1793691898829,
# "first_ms": 51) are timestamps and timings of that run. Reports stay English and keep their own
# goldens (G9), so only the number of a JSON field whose name ends in _ms is masked, as <ms>.
JSON_MS = re.compile(r'("[A-Za-z0-9_]*_ms"\s*:\s*)-?\d+(?![\d.])')
UUID_MASK, HEX_MASK, MS_MASK = "<uuid>", "<hex-id>", "<ms>"


def mask_ids(text):
    """text with generated identifiers and JSON report timings replaced by <uuid>, <hex-id> and <ms>;
    nothing else changes."""
    text = UUID.sub(UUID_MASK, text)
    text = JSON_ID.sub(lambda m: m.group(1) + HEX_MASK + m.group(3), text)
    text = JSON_MS.sub(lambda m: m.group(1) + MS_MASK, text)
    return HEX_ID.sub(HEX_MASK, text)


def masked(value):
    """(comparison key, durations, digits, numeric dates): the key has identifiers and values masked
    and plural unit spellings merged; durations lists (number, unit as written) for the singular/plural
    check; digits lists every masked digit run, in order, for the zero-padding check; numeric dates
    lists the digit groups of each d/M or M/d/y date, for the order and year-width checks."""
    value = mask_ids(value)
    out, durations, digits, dates, at = [], [], [], [], 0
    spans = []
    for pattern in PATTERNS:
        for m in pattern.finditer(value):
            if not any(m.start() < e and s < m.end() for s, e, _ in spans):
                spans.append((m.start(), m.end(), pattern is NUMERIC_DATE))
    for s, e, numeric_date in sorted(spans):
        out.append(value[at:s])
        piece = value[s:e]
        digits.extend(re.findall(r"\d+", piece))
        if numeric_date:
            dates.append(tuple(piece.split("/")))
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
    return "".join(out), tuple(durations), tuple(digits), tuple(dates)


def mask(value):
    return masked(value)[0]


def _is_one(number):
    try:
        return float(number) == 1
    except ValueError:
        return False


def _padding(run):
    """"0" for a zero-padded run (05), "1" for a one-digit run (5), None when it cannot tell (12)."""
    return "0" if len(run) > 1 and run[0] == "0" else "1" if len(run) == 1 else None


def same_after_masking(base_value, head_value):
    """True when two texts differ only in clock, duration and date values.

    The values may differ, their spelling may not: a unit keeps its singular or plural word while
    its number stays 1 or stays not 1 ("2 seconds" to "3 second" fails), a zero-padded number cannot
    become unpadded ("05 min" to "5 min" fails), a numeric date keeps its year width, and a day above
    12 cannot move between the first two groups (d/M against M/d). A d/M and M/d swap with both
    groups 12 or under ("10/5" against "5/10") is two valid dates and cannot be told from the text.
    """
    a, a_durations, a_digits, a_dates = masked(base_value)
    b, b_durations, b_digits, b_dates = masked(head_value)
    if a != b:
        return False
    for (n1, u1), (n2, u2) in zip(a_durations, b_durations):
        if _is_one(n1) == _is_one(n2) and u1 != u2:
            return False
    for r1, r2 in zip(a_digits, b_digits):
        if {_padding(r1), _padding(r2)} == {"0", "1"}:
            return False
    for d1, d2 in zip(a_dates, b_dates):
        if len(d1) == 3 and len(d2) == 3 and len(d1[2]) != len(d2[2]):
            return False
        day1 = [i for i in (0, 1) if int(d1[i]) > 12]
        day2 = [i for i in (0, 1) if int(d2[i]) > 12]
        if day1 and day2 and day1 != day2:
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




def keyed(records):
    """{key: record} in record order. A record's key is (field, locator with identifiers masked,
    occurrence): the occurrence counts earlier records with the same field and masked locator, so the
    rows of a list whose views share a tag (charge-history-session:<uuid>) keep their order."""
    seen, out = collections.Counter(), collections.OrderedDict()
    for record in records:
        where = (record.field, mask_ids(record.locator))
        out[where + (seen[where],)] = record
        seen[where] += 1
    return out


def _where(key):
    return "%s %s%s" % (key[0], key[1], "" if key[2] == 0 else " #%d" % (key[2] + 1))


def _line(sign, key, value):
    return "%s %s: %s" % (sign, _where(key), l10nlib.json_string(value))


Result = collections.namedtuple("Result", "text bounds volatile reordered formed numbers")
# A digit run right after a digit and a decimal point or comma is a fraction (or a thousands group):
# its length is the precision, which formatting sets, so the form keeps it.
_DIGITS = re.compile(r"(?<=\d[.,])\d+|\d+")


def form(value):
    """value with identifiers masked and every digit run as <n>, a fraction's as <n:length>: what is
    left of a reading whose number drifts between runs ("137 W" and "145 W" are both "<n> W"; "18.7 W",
    "18,7 W" and "18.75 W" are three forms)."""
    value = mask_ids(value)
    return _DIGITS.sub(lambda m: "<n:%d>" % len(m.group(0)) if m.start() > 1 and value[m.start() - 1] in ".,"
                       and value[m.start() - 2].isdigit() else "<n>", value)


def same_form(base_value, head_value):
    """True when two texts differ at most in the values of their numbers: the same form (see form),
    and no digit run turns from zero-padded to unpadded or back ("08:00" against "8:00")."""
    if form(base_value) != form(head_value):
        return False
    pairs = zip(_DIGITS.findall(mask_ids(base_value)), _DIGITS.findall(mask_ids(head_value)))
    return not any({_padding(a), _padding(b)} == {"0", "1"} for a, b in pairs)


def _match(wanted, offered):
    """Greedy one-to-one match of wanted values to offered values (exact first, then same_after_masking);
    returns (the wanted values left unmatched, the offered values left over)."""
    pool, missing = list(offered), []
    for value in wanted:
        hit = next((i for i, v in enumerate(pool) if v == value), None)
        if hit is None:
            hit = next((i for i, v in enumerate(pool) if same_after_masking(value, v)), None)
        if hit is None:
            missing.append(value)
        else:
            del pool[hit]
    return missing, pool


_INDEX = re.compile(r"\[\d+\]")


def shape(key):
    """The list a key's row belongs to: field and masked locator with child indexes dropped, so the
    rows .../LinearLayout[0]/TextView[2] and .../LinearLayout[3]/TextView[2] share one."""
    return key[0], _INDEX.sub("[]", key[1])


def compare_runs(bases, head):
    """Compares HEAD's records with one or more BASE runs of the same steps; returns a Result.

    A key (see keyed) in every base run whose values agree across the runs (same_after_masking) is
    stable: HEAD must have it, with a value that matches under the masks. A key whose values differ
    between the base runs, or that only some base runs have, is volatile: reported, never compared.

    Fallback for a list that reorders between runs: of the keys of one shape (see shape) whose values
    are not identical in every base run, when there are two or more and every base run holds the same
    values there in some order, they are a reordered list. HEAD must hold those values at those keys,
    in any order (each matched under the masks), and they count as reordered, not volatile.

    A key whose values differ between the base runs only in their numbers (see same_form: drifting
    simulated telemetry) is compared by form: HEAD must have it with the same form, so a changed
    separator, precision, padding, unit, space, plural or capital still fails; the numbers' values go
    unchecked.

    A stable key whose HEAD value differs from the base value only in its numbers' values is reported
    as "numbers differ", not failed: two base runs cannot show that a drifting reading was equal by
    chance ("23 °C" both times), and number values are G10's to check (FmtLegacyTest), not G7's.

    HEAD keys that no base run has fail. text lists the failures, bounds the bounds notes (HEAD bounds
    unlike every base run's), volatile (key, [base values]) the records not compared, reordered the
    keys compared without their order, formed the keys compared by form and numbers the
    "<key>: <base> -> <head>" lines of the numbers that differ.
    """
    maps, now = [keyed(b) for b in bases], keyed(head)
    order = collections.OrderedDict()
    for m in maps:
        for key in m:
            order[key] = True
    text, bounds, volatile, reordered, formed, numbers = [], [], [], [], [], []
    lists = collections.OrderedDict()
    for key in order:
        if all(key in m for m in maps) and any(mask_ids(m[key].value) != mask_ids(maps[0][key].value) for m in maps[1:]):
            lists.setdefault(shape(key), []).append(key)
    for keys in lists.values():
        runs = [sorted(mask_ids(m[k].value) for k in keys) for m in maps]
        if len(keys) < 2 or any(run != runs[0] for run in runs[1:]):
            continue
        reordered.extend(keys)
        missing, left = _match([maps[0][k].value for k in keys], [now[k].value for k in keys if k in now])
        first = [mask_ids(maps[0][k].value) for k in keys]
        for value in missing:
            # Named by the key that held the value in the first base run.
            text.append(_line("-", keys[first.index(mask_ids(value))], value) + " (a reordered list's row)")
        for value in left if missing else ():
            text.append(_line("+", keys[0], value) + " (a reordered list's row)")
    shuffled = set(reordered)
    for key in order:
        if key in shuffled:
            continue
        if not all(key in m for m in maps):
            volatile.append((key, [m[key].value for m in maps if key in m]))
            continue
        first = maps[0][key]
        record = now.get(key)
        if not all(same_after_masking(first.value, m[key].value) for m in maps[1:]):
            if not all(same_form(first.value, m[key].value) for m in maps[1:]):
                volatile.append((key, [m[key].value for m in maps]))
                continue
            formed.append(key)
            if record is None:
                text.append(_line("-", key, first.value) + " (compared by form: its numbers drift)")
            elif not same_form(first.value, record.value):
                text.append(_line("-", key, first.value) + " (compared by form: its numbers drift)")
                text.append(_line("+", key, record.value) + " (compared by form: its numbers drift)")
            continue
        if record is None:
            text.append(_line("-", key, first.value))
        elif not same_after_masking(first.value, record.value):
            if same_form(first.value, record.value):
                numbers.append("%s: %s -> %s" % (_where(key), l10nlib.json_string(first.value), l10nlib.json_string(record.value)))
                continue
            text.append(_line("-", key, first.value))
            text.append(_line("+", key, record.value))
        elif all(m[key].bounds != record.bounds for m in maps):
            bounds.append("%s: %s -> %s" % (_where(key), first.bounds, record.bounds))
    for key, record in now.items():
        if key not in order:
            text.append(_line("+", key, record.value))
    return Result(text, bounds, volatile, reordered, formed, numbers)


def compare(base, head):
    """(text_differences, bounds_differences) between two record lists (one base run)."""
    result = compare_runs([base], head)
    return result.text, result.bounds


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


Summary = collections.namedtuple("Summary", "problems notes screens volatile reordered formed numbers records warnings")
SWEEP = "sweep.json"


def _sweep(path):
    """The sweep.json run-release-sweep.py --census writes into a census folder, or None."""
    name = os.path.join(path, SWEEP)
    if not os.path.isdir(path) or not os.path.isfile(name):
        return None
    try:
        data = json.loads(l10nlib.read_text(name))
    except ValueError:
        return None
    return data if isinstance(data, dict) else None


def check_runs(base_paths, head_path):
    """(problems, warnings) about the runs themselves. Base runs must be separate sweeps of one app APK:
    with two or more, HEAD given as a base, a base given twice, or base runs of different app APKs
    would mark every change volatile. Every run must use one test APK. A run without sweep.json
    cannot be checked, which is a warning."""
    problems, warnings = [], []
    real = [os.path.realpath(p) for p in base_paths]
    if len(real) > 1 and os.path.realpath(head_path) in real:
        problems.append("HEAD %s is also a base run" % head_path)
    if len(set(real)) < len(real):
        problems.append("a base run is given twice: each --base must be its own sweep")
    sweeps = [_sweep(p) for p in list(base_paths) + [head_path]]
    if any(s is None for s in sweeps):
        warnings.append("no %s in %s: cannot check which APKs the runs used" % (
            SWEEP, ", ".join(p for p, s in zip(list(base_paths) + [head_path], sweeps) if s is None)))
        return problems, warnings
    if len(base_paths) > 1 and len({s.get("app_sha256") for s in sweeps[:-1]}) > 1:
        problems.append("the base runs are of different app APKs (app_sha256 in %s): sweep the same base APK each time" % SWEEP)
    if len({s.get("runner_sha256") for s in sweeps}) > 1:
        problems.append("the runs used different test APKs (runner_sha256 in %s): use the head's test APK for every run" % SWEEP)
    return problems, warnings


def diff_runs(base_paths, head_path):
    """Summary of HEAD against the BASE runs. problems lists the failures; volatile holds
    "<screen>: <field> <locator>: <base values>" lines for the records and screens not compared
    (a screen only some base runs have counts each of its records); reordered counts the records
    compared without their order and formed those compared by form; numbers lists the stable records
    whose numbers alone differ in HEAD; records counts the records of the screens HEAD was compared on."""
    run_problems, warnings = check_runs(base_paths, head_path)
    base_files = [_collect(p) for p in base_paths]
    head_files = _collect(head_path)
    if not any(os.path.isdir(p) for p in list(base_paths) + [head_path]):
        pairs = [(os.path.basename(head_path), [p for p in base_paths], head_path)]
    else:
        names = set(head_files)
        for files in base_files:
            names |= set(files)
        pairs = [(name, [files.get(name) for files in base_files], head_files.get(name)) for name in sorted(names)]
    problems, notes, volatile, reordered, formed, numbers, records = list(run_problems), [], [], 0, 0, [], 0
    for name, bases, head in pairs:
        present = [b for b in bases if b is not None]
        if not present:
            problems.append("%s: only in head" % name)
            continue
        try:
            runs = [parse(l10nlib.read_text(b), b) for b in present]
            if len(present) < len(bases):
                records += len(keyed(runs[0]))
                volatile.extend("%s: %s (screen in %d of %d base runs)" % (name, _where(k), len(present), len(bases))
                                for k in keyed(runs[0]))
                continue
            if head is None:
                problems.append("%s: only in base" % name)
                continue
            result = compare_runs(runs, parse(l10nlib.read_text(head), head))
        except CensusError as error:
            problems.append(str(error))
            continue
        records += len(set().union(*[keyed(r) for r in runs]))
        problems.extend("%s: %s" % (name, t) for t in result.text)
        notes.extend("%s: %s" % (name, t) for t in result.bounds)
        volatile.extend("%s: %s: %s" % (name, _where(k), " | ".join(l10nlib.json_string(v) for v in values))
                        for k, values in result.volatile)
        reordered += len(result.reordered)
        formed += len(result.formed)
        numbers.extend("%s: %s" % (name, line) for line in result.numbers)
    return Summary(problems, notes, len(pairs), volatile, reordered, formed, numbers, records, warnings)


def diff_paths(base_path, head_path):
    """(text problems, bounds notes, screens compared) for one base run."""
    summary = diff_runs([base_path], head_path)
    return summary.problems, summary.notes, summary.screens


def _step(line):
    return line.split("/", 1)[0] if "/" in line.split(":", 1)[0] else line.split(":", 1)[0]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("paths", nargs="*", help="BASE HEAD, or HEAD with --base")
    parser.add_argument("--base", action="append", default=[], metavar="BASE",
                        help="a base run (census file or folder); give it twice or more to learn volatile records")
    parser.add_argument("--mask", metavar="FILE", help="print FILE with masks applied")
    parser.add_argument("--show-bounds", action="store_true", help="list each bounds difference")
    parser.add_argument("--show-volatile", action="store_true", help="list each volatile record with its base values")
    parser.add_argument("--show-numbers", action="store_true", help="list each stable record whose numbers alone differ in HEAD")
    args = parser.parse_args(argv)
    if args.mask:
        for record in parse(l10nlib.read_text(args.mask), args.mask):
            print("%s\t%s\t%s" % (record.field, mask_ids(record.locator), mask(record.value)))
        return 0
    if args.base:
        if len(args.paths) != 1:
            parser.error("give HEAD after the --base runs")
        bases, head = args.base, args.paths[0]
    else:
        if len(args.paths) != 2:
            parser.error("give BASE and HEAD, or --base BASE --base BASE2 HEAD")
        bases, head = args.paths[:1], args.paths[1]
    summary = diff_runs(bases, head)
    if len(bases) == 1:
        print("census_diff: warning: one base run, so run-to-run noise (timing-dependent captures, reordered "
              "lists) cannot be told apart from a text change; sweep the base twice and pass --base twice",
              file=sys.stderr)
    for warning in summary.warnings:
        print("census_diff: warning: " + warning, file=sys.stderr)
    for problem in summary.problems:
        print("census_diff: " + problem, file=sys.stderr)
    if args.show_bounds:
        for note in summary.notes:
            print("census_diff: bounds: " + note)
    if args.show_volatile:
        for line in summary.volatile:
            print("census_diff: volatile: " + line)
    if args.show_numbers:
        for line in summary.numbers:
            print("census_diff: numbers differ: " + line)
    if summary.volatile:
        steps = collections.Counter(_step(line) for line in summary.volatile)
        print("census_diff: volatile by step: " + ", ".join("%s %d" % (s, n) for s, n in sorted(steps.items())))
    print("census_diff: %s: %d screen(s), %d base run(s), %d text difference(s), %d of %d record(s) volatile "
          "(not compared), %d compared by form (numbers drift), %d with numbers that differ (reported only; G10 checks "
          "numbers), %d in reordered lists (compared without order), %d bounds difference(s) (reported only)" % (
              "FAILED" if summary.problems else "PASS", summary.screens, len(bases), len(summary.problems),
              len(summary.volatile), summary.records, summary.formed, len(summary.numbers), summary.reordered,
              len(summary.notes)))
    return 1 if summary.problems else 0


if __name__ == "__main__":
    sys.exit(main())
