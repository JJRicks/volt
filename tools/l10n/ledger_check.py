#!/usr/bin/env python3
"""G1, the ledger of moved literals: no string or char literal may leave src/ without a ledger row.

For every src/ Java file that changed between the merge base and head, the literals are compared
by position (expressions.FileDiff): a literal is gone when it no longer sits in the same changed
region in the same order, so swapping two literals, or changing one, removes it. Every gone literal
must be covered by exactly one new row in the batch's ledger, l10n/ledger/<batch>.tsv:

  exact, template, plural  the row's pieces must equal the base expression on its line (as
                           extract.py draft writes them), every literal of it must be gone, and a
                           T.<key>(...) or M.<key>(...) call (or Msg.of / T.render with T.<KEY>)
                           must pass the row's holes in placeholder order in the head lines that
                           replaced it; each call serves one row, in the base order
  format                   a literal that mixes numbers and unit text: a String.format format string
                           ("%.1f mph") or a + chain (n + " km/h"). The pieces are the split: text
                           the template keeps, {"spec"} items for the specifiers (each with the base
                           argument it formats and the Fmt call FMT_SPECS pairs with it), {"text",
                           "key"} items for text a catalog key now holds, and the chain's holes. The
                           split must spell the base expression exactly; the head call is
                           T.<template>(...) with the Fmt calls, holes and T.<key>() calls in
                           placeholder order, replacing the whole String.format(...) call; each key
                           must hold its text, and the template, filled with sentinels and the keys'
                           English, must print the original. A plain %s passes its value through,
                           with string literals turned into T.<key>() calls that have exact rows.
                           A compiled test under tests/l10n/ must call each Fmt method it passes
                           (G10's parity); every format row is listed, with the tests calling the
                           method that held it, or a note that none does
  removed                  the code was deleted: the changed lines holding it only lose tokens, and
                           the whole statement holding it is gone (not just the literal and its +);
                           when its enclosing block (an if, a method) went too, every other
                           statement in that block needs a removed row of its own, or the row's note
                           starts "whole block:"; every removed row is listed for the reviewer, with
                           the whole deleted block when there is one
  stays-english, id-kept   the literal moved within src/: the same value is added elsewhere (outside
                           the changed lines it left, so a reorder or swap is not a move), and the
                           code it left either only lost tokens or names the constant it moved to:
                           NAME = "..." (a static final field) or an enum constant NAME(..., "..."),
                           which several sites may share; each region must then name, in the base
                           order of its literals, the constant each one moved to, and a shared
                           constant is listed with every site. The constant may already be in src
                           (a later batch comparing through UnitCode); each such site is listed

A row whose pieces cannot match a + chain (a ternary, String.format, a StringBuilder chain) has a
note starting "manual:"; its literals must then appear in order from its line, and it is listed
for the reviewer. When the line parses as an expression with the row's literals, a manual row must
still match it. A hole's "as" (the expression head passes) must be Fmt.<method>(..., <hole>, ...)
unless the row is manual; every "as" is listed for the reviewer. Every ledger at head (not only this batch's) is also checked against the catalog:
an exact row's English must still equal its literal and a template must still render the original,
unless l10n/english-changes.tsv records an owner-approved change of that key (its keys must exist).
Ledger files are append-only. An extraction batch adds no literal to src/ (other than ""): text goes
to the catalog, and a literal that moved needs a stays-english or id-kept row. The one exception is a
new lower-case key or id (a preference name, a requireNonNull message; l10nlib.is_identifier_like):
it is not text, but a person could read one, so every such literal is listed for the reviewer with
the statement that holds it, and it does not by itself make its changed region count as text. A new
format-only literal ("%.1f", "%,.0f": number specifiers, digits and punctuation; is_format_only) is allowed
and listed the same way.

Nothing else may change around the text either. In a changed region of a src/ file that holds or held
a literal, the only differences from the base allowed are a row's expression replaced by the call
that serves it (with a manual row, anywhere in the statement holding its literals) and a removed
row's deleted statement: wrapping a kept literal or a new T call in .toUpperCase(), or dropping code
next to it, fails. Whole new members (a method or field added next to converted text), regions a
stays-english or id-kept move touched, and every other src change are listed for the reviewer.

Ledger format: a TSV with the header

  location<TAB>pieces<TAB>key<TAB>kind<TAB>note

  location  base file:line of the expression's first literal, e.g. src/com/jjricks/volt/Ui.java:812
  pieces    JSON: the original expression as literal pieces and holes, in order, e.g.
              ["Move ", {"hole": "title", "arg": 1, "type": "s"}, " to slot ", {"hole": "n", "arg": 2, "type": "d"}]
            a hole is {"hole": <Java source at base>, "arg": <placeholder index>, "type": "s"|"d"|"f"},
            plus "quantity": true for a plural's quantity and "as": <Java source> when head passes a
            different expression for it; a char literal is {"char": "x"}. For kind plural, pieces
            is an object of English plural forms: {"one": [...], "other": [...]}. For kind format,
            the split, e.g. for String.format(Locale.US, "%.1f mph", kmh):
              [{"spec": "%.1f", "arg": 1, "value": "kmh", "as": "Fmt.decimal(Locale.US, kmh, 1)"}, " ",
               {"text": "mph", "key": "units_mph", "arg": 2}]
  key       the resource key (exact, template, plural, id-kept; format: the template), or -
            (stays-english, removed)
  kind      exact | template | plural | format | id-kept | stays-english | removed
  note      free text, no tabs; "manual: <why>" for a hand-written row

A row whose location is under tests/ or android-tests/ must be kind removed; frozen_literals.py
checks those.

Usage: ledger_check.py --base <rev> [--head <rev>|WORKTREE] [--ledger FILE ...] [--catalog-only]
By default the new rows are those added to l10n/ledger/*.tsv since the merge base.
"""

import argparse
import collections
import difflib
import json
import os
import re
import shutil
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l10nlib  # noqa: E402
import gen_catalog  # noqa: E402
import expressions  # noqa: E402

HEADER = ("location", "pieces", "key", "kind", "note")
KINDS = ("exact", "template", "plural", "format", "id-kept", "stays-english", "removed")
CATALOG_KINDS = ("exact", "template", "plural", "format")
MOVING = CATALOG_KINDS + ("removed",)
STAYING = ("id-kept", "stays-english")
NEEDS_KEY = ("exact", "template", "plural", "format", "id-kept")
LOCATION = re.compile(r"^((?:src|tests|android-tests)/[^\t:]+\.java):([1-9][0-9]*)$")
LEDGER_DIR = "l10n/ledger"
CHANGES_PATH = "l10n/english-changes.tsv"
CHANGES_HEADER = ("key", "quantity", "old", "new", "note")
MANUAL_WINDOW = 30
TEST_DIRS = ("tests/", "android-tests/")

Row = collections.namedtuple("Row", "source line text path base_line pieces key kind note literals split")
Row.__new__.__defaults__ = (None,)
"""pieces: the base expression's pieces (for a format row, derived from its split); split: a format
row's split, else None."""


class LedgerError(ValueError):
    pass


def _check_sequence(sequence, where):
    if not isinstance(sequence, list):
        raise LedgerError("%s: pieces must be a JSON array" % where)
    for piece in sequence:
        if isinstance(piece, str):
            continue
        if not isinstance(piece, dict):
            raise LedgerError("%s: each piece is a string or an object" % where)
        if "char" in piece:
            if set(piece) != {"char"} or not isinstance(piece["char"], str) or len(piece["char"]) != 1:
                raise LedgerError("%s: a char piece is {\"char\": \"x\"}" % where)
            continue
        if not isinstance(piece.get("hole"), str) or not piece["hole"].strip():
            raise LedgerError("%s: a hole needs \"hole\": the Java source of the value" % where)
        if not isinstance(piece.get("arg"), int) or piece["arg"] < 1:
            raise LedgerError("%s: a hole needs \"arg\": its placeholder index, from 1" % where)
        if piece.get("type") not in ("s", "d", "f"):
            raise LedgerError("%s: a hole needs \"type\": s, d or f" % where)
        if "as" in piece and (not isinstance(piece["as"], str) or not piece["as"].strip()):
            raise LedgerError("%s: a hole's \"as\" is the Java source head passes" % where)
        extra = set(piece) - {"hole", "arg", "type", "quantity", "as"}
        if extra:
            raise LedgerError("%s: unknown hole field(s) %s" % (where, ", ".join(sorted(extra))))


# A java.util.Formatter specifier: %[index$][flags][width][.precision]conversion. %% and %n take no argument.
FORMAT_SPEC = re.compile(r"%(\d+\$)?([-#+ 0,(]*)(\d+)?(\.\d+)?([tT][A-Za-z]|[A-Za-z%])")
# Conversions that print a number and never a word (%s, %b, %c, %S and the %t dates can).
NUMBER_CONVERSIONS = frozenset("dofeEgGxX")


def is_format_only(value):
    """True for a literal made of number specifiers, digits and punctuation only ("%.1f", "%d",
    "%,.0f", "%.1f–%.1f"): no letter outside a specifier, every specifier a number conversion
    (NUMBER_CONVERSIONS, or %% and %n), and at least one of them. It holds no words, so an extraction
    batch may add one; every one is listed."""
    rest, found, at = [], False, 0
    for m in FORMAT_SPEC.finditer(value):
        rest.append(value[at:m.start()])
        at = m.end()
        if m.group(5) in ("%", "n"):
            continue
        if m.group(5) not in NUMBER_CONVERSIONS:
            return False
        found = True
    rest.append(value[at:])
    leftover = "".join(rest)
    return found and "%" not in leftover and not any(c.isalpha() for c in leftover)


def format_text(raw):
    """What a piece of a String.format format string prints: %% is %, %n a line break."""
    return re.sub(r"%[%n]", lambda m: "%" if m.group(0) == "%%" else "\n", raw)


def _check_split(split, where):
    """The base pieces a format row's split reassembles to (strings joined, holes kept), checking each
    item. Raises LedgerError."""
    if not isinstance(split, list) or not split:
        raise LedgerError("%s: a format row's pieces are its split: a JSON array of text, {\"spec\": ...}, "
                          "{\"text\": ..., \"key\": ...} and hole items" % where)
    derived, buffer, args = [], [], {}
    kinds = set()
    for item in split:
        if isinstance(item, str):
            if not item:
                raise LedgerError("%s: an empty text piece" % where)
            buffer.append(item)
            continue
        if not isinstance(item, dict):
            raise LedgerError("%s: each split item is a string or an object" % where)
        if "spec" in item:
            if set(item) != {"spec", "arg", "value", "as"}:
                raise LedgerError("%s: a spec item is {\"spec\": \"%%.1f\", \"arg\": n, \"value\": <base argument>, "
                                  "\"as\": <the Fmt call head passes>}" % where)
            m = FORMAT_SPEC.fullmatch(item["spec"]) if isinstance(item["spec"], str) else None
            if m is None or m.group(5) in ("%", "n"):
                raise LedgerError("%s: %r is not a format specifier that takes an argument (%%%% and %%n are text)" % (
                    where, item["spec"]))
            for field in ("value", "as"):
                if not isinstance(item[field], str) or not item[field].strip():
                    raise LedgerError("%s: a spec item's %r is Java source" % (where, field))
            buffer.append(item["spec"])
            kinds.add("spec")
        elif "key" in item:
            if set(item) != {"text", "key", "arg"}:
                raise LedgerError("%s: a text item is {\"text\": <the literal's text>, \"key\": <catalog key>, \"arg\": n}"
                                  % where)
            if not isinstance(item["key"], str) or not gen_catalog.KEY_PATTERN.match(item["key"]):
                raise LedgerError("%s: a text item needs a resource key" % where)
            if not isinstance(item["text"], str) or not item["text"]:
                raise LedgerError("%s: a text item's text is the part of the literal its key holds" % where)
            buffer.append(item["text"])
            kinds.add("key")
        elif "hole" in item:
            _check_sequence([item], where)
            if item["type"] != "s":
                raise LedgerError("%s: a format row's holes are text (%%n$s): type s" % where)
            if "quantity" in item:
                raise LedgerError("%s: a format row has no plural quantity" % where)
            if "as" in item and not fmt_wrapper(item):
                raise LedgerError("%s: hole %s is passed as %s; a format row's \"as\" must be Fmt.<method>(..., %s, ...)"
                                  % (where, item["hole"], item["as"], item["hole"]))
            if buffer:
                derived.append("".join(buffer))
                buffer = []
            derived.append(dict(item))
            kinds.add("hole")
        else:
            raise LedgerError("%s: unknown split item %s" % (where, json.dumps(item, ensure_ascii=False)))
        if not isinstance(item.get("arg"), int) or isinstance(item.get("arg"), bool) or item["arg"] < 1:
            raise LedgerError("%s: each spec, text and hole item needs \"arg\": its placeholder index, from 1" % where)
        if item["arg"] in args:
            raise LedgerError("%s: two split items claim arg %d" % (where, item["arg"]))
        args[item["arg"]] = item
    if buffer:
        derived.append("".join(buffer))
    if sorted(args) != list(range(1, len(args) + 1)):
        raise LedgerError("%s: the split's args must be 1 to %d with no gaps" % (where, len(args)))
    if not kinds & {"spec", "key"}:
        raise LedgerError("%s: a format row splits a specifier out to Fmt or text out to a key; without either "
                          "it is a template row" % where)
    if "spec" in kinds:
        if "hole" in kinds:
            raise LedgerError("%s: spec items split a String.format literal, which has no holes" % where)
        for item in split:
            text = item if isinstance(item, str) else item.get("text")
            if text is not None and "%" in re.sub(r"%[%n]", "", text):
                raise LedgerError("%s: the text %r holds a format specifier; split it out as a spec item" % (where, text))
    return derived


def sequences(pieces):
    return list(pieces.values()) if isinstance(pieces, dict) else [pieces]


def literal_counts(pieces):
    """The string literals an expression consumed, as a Counter.

    For plural forms a literal shared by several forms counts once (the per-value maximum).
    """
    total = collections.Counter()
    for sequence in sequences(pieces):
        for value, count in collections.Counter(p for p in sequence if isinstance(p, str)).items():
            total[value] = max(total[value], count)
    return total


def holes(pieces):
    return [p for s in sequences(pieces) for p in s if isinstance(p, dict) and "hole" in p]


def parse_ledger(text, source="ledger"):
    """Rows of one ledger file. Raises LedgerError with every problem."""
    try:
        _, rows = l10nlib.read_tsv(text, HEADER)
    except ValueError as error:
        raise LedgerError("%s: %s" % (source, error))
    raw_lines = text.split("\n")
    result, problems = [], []
    for line, (location, pieces_text, key, kind, note) in rows:
        where = "%s:%d" % (source, line)
        try:
            m = LOCATION.match(location)
            if not m:
                raise LedgerError("%s: location must be <src|tests|android-tests path>.java:<line>" % where)
            if kind == "TODO" or key == "TODO":
                raise LedgerError("%s: unfinished draft row (key or kind is TODO)" % where)
            if kind not in KINDS:
                raise LedgerError("%s: kind must be one of %s" % (where, ", ".join(KINDS)))
            if kind in NEEDS_KEY and not gen_catalog.KEY_PATTERN.match(key):
                raise LedgerError("%s: kind %s needs a resource key" % (where, kind))
            if kind not in NEEDS_KEY and key not in ("-", ""):
                raise LedgerError("%s: kind %s takes no key; write -" % (where, kind))
            if m.group(1).startswith(TEST_DIRS) and kind != "removed":
                raise LedgerError("%s: a row for a test file must be kind removed (a test of deleted code)" % where)
            try:
                pieces = json.loads(pieces_text)
            except ValueError as error:
                raise LedgerError("%s: pieces is not valid JSON: %s" % (where, error))
            split = None
            if kind == "plural":
                if not isinstance(pieces, dict) or "other" not in pieces or \
                        any(q not in gen_catalog.QUANTITIES for q in pieces):
                    raise LedgerError("%s: plural pieces are {\"one\": [...], \"other\": [...]}" % where)
                for quantity, sequence in pieces.items():
                    _check_sequence(sequence, "%s[%s]" % (where, quantity))
            elif kind == "format":
                if note.startswith("manual:"):
                    raise LedgerError("%s: a format row is checked whole; it takes no \"manual:\" note" % where)
                split, pieces = pieces, _check_split(pieces, where)
            else:
                _check_sequence(pieces, where)
            if kind == "exact" and (len(pieces) != 1 or not isinstance(pieces[0], str)):
                raise LedgerError("%s: an exact row has exactly one string piece" % where)
            args = {}
            for hole in holes(pieces):
                if hole.get("quantity") and "arg" not in hole:
                    continue
                previous = args.setdefault(hole["arg"], hole)
                if expressions.source_key(previous.get("as", previous["hole"])) != \
                        expressions.source_key(hole.get("as", hole["hole"])) or previous["type"] != hole["type"]:
                    raise LedgerError("%s: two holes claim arg %d with different sources or types" % (where, hole["arg"]))
            for hole in holes(pieces):
                if "as" in hole and not fmt_wrapper(hole) and not note.startswith("manual:"):
                    raise LedgerError("%s: hole %s is passed as %s; only Fmt.<method>(..., %s, ...) is checked, so "
                                      "any other \"as\" needs a note starting \"manual:\"" % (
                                          where, hole["hole"], hole["as"], hole["hole"]))
            result.append(Row(source, line, raw_lines[line - 1].rstrip("\r"), m.group(1), int(m.group(2)), pieces,
                              key, kind, note, literal_counts(pieces), split))
        except LedgerError as error:
            problems.append(str(error))
    if problems:
        raise LedgerError("\n".join(problems))
    return result


def load_rows(paths_and_texts):
    rows, problems = [], []
    for source, text in paths_and_texts:
        try:
            rows.extend(parse_ledger(text, source))
        except LedgerError as error:
            problems.extend(str(error).split("\n"))
    return rows, problems


def _ledger_names(rev, cwd=None):
    return [p for p in l10nlib.list_files(rev, [LEDGER_DIR], cwd) if p.endswith(".tsv")]


def range_rows(merge, head, explicit=None, cwd=None):
    """(new rows, problems): rows added to l10n/ledger/*.tsv since merge (append-only), or every row of
    the explicit files."""
    if explicit:
        return load_rows([(p, l10nlib.read_text(p)) for p in explicit])
    names = [p for p in l10nlib.changed_files(merge, head, [LEDGER_DIR], cwd) if p.endswith(".tsv")]
    after = l10nlib.read_files(head, names, cwd)
    before = l10nlib.read_files(merge, names, cwd)
    rows, problems = [], []
    for name in names:
        if after[name] is None:
            problems.append("%s: deleted; ledger files are append-only" % name)
            continue
        try:
            head_rows = parse_ledger(after[name], name)
        except LedgerError as error:
            problems.extend(str(error).split("\n"))
            continue
        old = collections.Counter()
        if before[name]:
            try:
                old.update(r.text for r in parse_ledger(before[name], name))
            except LedgerError:
                pass
        for row in head_rows:
            if old[row.text] > 0:
                old[row.text] -= 1
            else:
                rows.append(row)
        for text, count in old.items():
            if count > 0:
                problems.append("%s: row %r was removed or edited; ledger files are append-only" % (name, text[:80]))
    return rows, problems


# ---------------------------------------------------------------------------
# The catalog side: every ledger at head, with recorded English changes
# ---------------------------------------------------------------------------

def _catalog_at(head, cwd=None):
    """Catalog entries at head, by name, or {} when head has no strings files."""
    names = [p for p in l10nlib.list_files(head, ["res/values"], cwd)
             if re.match(r"^res/values/strings.*\.xml$", p)]
    if not names:
        return {}
    texts = l10nlib.read_files(head, names, cwd)
    work = tempfile.mkdtemp(prefix="l10n-ledger-")
    try:
        for name in names:
            l10nlib.write_text(os.path.join(work, name), texts[name])
        entries, _ = gen_catalog.load_catalog(os.path.join(work, "res", "values"), work)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    return {e.name: e for e in entries}


def parse_changes(text, source=CHANGES_PATH):
    """{(key, quantity): [(old, new, where)]} in file order. quantity is "other" for a string."""
    result = collections.defaultdict(list)
    if not text:
        return result
    _, rows = l10nlib.read_tsv(text, CHANGES_HEADER)
    for line, (key, quantity, old, new, note) in rows:
        where = "%s:%d" % (source, line)
        if not gen_catalog.KEY_PATTERN.match(key):
            raise LedgerError("%s: bad key %r" % (where, key))
        quantity = "other" if quantity in ("", "-") else quantity
        if quantity not in gen_catalog.QUANTITIES:
            raise LedgerError("%s: quantity must be - or a plural category" % where)
        if not note.strip():
            raise LedgerError("%s: note the owner approval (PR or decision)" % where)
        result[(key, quantity)].append((l10nlib.unescape_field(old), l10nlib.unescape_field(new), where))
    return result


def extraction_text(entry, quantity, changes, problems, where):
    """The English a key had when it was extracted: today's catalog text with recorded changes undone."""
    text = entry.items[quantity].text
    for old, new, change_where in reversed(changes.get((entry.name, quantity), [])):
        if text != new:
            problems.append("%s: %s says %s[%s] became %r, but the next text is %r" % (
                change_where, CHANGES_PATH, entry.name, quantity, new, text))
            return None
        text = old
    return text


SIMPLE_PLACEHOLDER = re.compile(r"%(\d+)\$s|%%")


def render_simple(template, pieces):
    """(template rendered, original rendered) with sentinel strings, when every hole is a plain %n$s.

    None when the template uses other conversions (tests/l10n/LedgerTest covers those with Java's formatter).
    """
    found = [p for p in pieces if isinstance(p, dict) and "hole" in p]
    if any(h["type"] != "s" for h in found) or re.search(r"%(?!(\d+\$s|%))", template):
        return None
    sentinel = {h["arg"]: chr(1) + chr(64 + h["arg"]) for h in found}

    def substitute(m):
        if m.group(0) == "%%":
            return "%"
        return sentinel.get(int(m.group(1)), m.group(0))
    original = "".join(p if isinstance(p, str) else p["char"] if "char" in p else sentinel[p["arg"]] for p in pieces)
    return SIMPLE_PLACEHOLDER.sub(substitute, template), original


def split_is_format_literal(split):
    return any(isinstance(item, dict) and "spec" in item for item in split)


def check_format_catalog(row, entry, catalog, changes, where):
    """A format row against the catalog: every text item's key holds exactly that text, and the template,
    filled with sentinels for the numbers and holes and with the keys' English for the text items,
    prints the original literal (or + chain) with the same sentinels."""
    problems = []
    template = extraction_text(entry, "other", changes, problems, where)
    if template is None:
        return problems
    if not re.fullmatch(r"(?:[^%]|%%|%[1-9][0-9]*\$s)*", template):
        return problems + ["%s: format row: template %s is %r; a format row's template takes only %%n$s placeholders" % (
            where, row.key, template)]
    literal = split_is_format_literal(row.split)
    shown = format_text if literal else (lambda raw: raw)
    values, original = {}, []
    for item in row.split:
        if isinstance(item, str):
            original.append(shown(item))
            continue
        sentinel = chr(1) + chr(64 + item["arg"])
        if "key" not in item:
            values[item["arg"]] = sentinel
            original.append(sentinel)
            continue
        text = shown(item["text"])
        values[item["arg"]] = text
        original.append(text)
        unit = catalog.get(item["key"])
        if unit is None:
            problems.append("%s: format row: text key %s is not in res/values/strings*.xml" % (where, item["key"]))
            continue
        if unit.kind != "string":
            problems.append("%s: format row: text key %s is a %s, not a string" % (where, item["key"], unit.kind))
            continue
        english = extraction_text(unit, "other", changes, problems, where)
        if english is not None and english != text:
            problems.append("%s: format row: text key %s is %r but that part of the literal was %r" % (
                where, item["key"], english, text))

    def substitute(m):
        if m.group(0) == "%%":
            return "%"
        return values.get(int(m.group(1)), m.group(0))
    rendered, original = SIMPLE_PLACEHOLDER.sub(substitute, template), "".join(original)
    if rendered != original:
        problems.append("%s: format row: template %s renders %r with its text keys and sentinels, the original %r" % (
            where, row.key, rendered, original))
    return problems


def check_against_catalog(rows, catalog, changes=None):
    problems = []
    changes = changes or {}
    for row in rows:
        if row.kind not in CATALOG_KINDS + ("id-kept",):
            continue
        where = "%s:%d" % (row.source, row.line)
        entry = catalog.get(row.key)
        if entry is None:
            problems.append("%s: key %s is not in res/values/strings*.xml" % (where, row.key))
            continue
        if row.kind == "id-kept":
            continue
        want = "plural" if row.kind == "plural" else "string"
        if entry.kind != want:
            problems.append("%s: key %s is a %s, but the row's kind is %s" % (where, row.key, entry.kind, row.kind))
            continue
        if row.kind == "format":
            problems.extend(check_format_catalog(row, entry, catalog, changes, where))
        elif row.kind == "exact":
            text = extraction_text(entry, "other", changes, problems, where)
            if text is not None and text != row.pieces[0]:
                problems.append("%s: exact row: %s is %r but the literal was %r" % (where, row.key, text, row.pieces[0]))
        elif row.kind == "template":
            text = extraction_text(entry, "other", changes, problems, where)
            rendered = render_simple(text, row.pieces) if text is not None else None
            if rendered is not None and rendered[0] != rendered[1]:
                problems.append("%s: template %s renders %r with sentinels, the original %r" % (
                    where, row.key, rendered[0], rendered[1]))
        else:
            for quantity, sequence in row.pieces.items():
                if quantity not in entry.items:
                    problems.append("%s: plural %s has no %s item" % (where, row.key, quantity))
                    continue
                text = extraction_text(entry, quantity, changes, problems, where)
                rendered = render_simple(text, sequence) if text is not None else None
                if rendered is not None and rendered[0] != rendered[1]:
                    problems.append("%s: plural %s[%s] renders %r with sentinels, the original %r" % (
                        where, row.key, quantity, rendered[0], rendered[1]))
    return problems


def catalog_consistency(head, cwd=None):
    """(problems, rows checked): every ledger row at head against the catalog at head."""
    names = _ledger_names(head, cwd)
    texts = l10nlib.read_files(head, names + [CHANGES_PATH], cwd)
    rows, problems = load_rows([(n, texts[n]) for n in names if texts[n] is not None])
    try:
        changes = parse_changes(texts.get(CHANGES_PATH))
    except (LedgerError, ValueError) as error:
        return problems + [str(error)], len(rows)
    if rows or changes:
        try:
            catalog = _catalog_at(head, cwd)
        except gen_catalog.CatalogError as error:
            return problems + ["catalog: " + p for p in error.problems], len(rows)
        problems.extend(check_against_catalog(rows, catalog, changes))
        checked = set(r.key for r in rows if r.kind in CATALOG_KINDS)
        checked.update(item["key"] for r in rows if r.kind == "format" for item in r.split
                       if isinstance(item, dict) and "key" in item)
        problems.extend(check_changes(changes, catalog, checked))
    return problems, len(rows)


def check_changes(changes, catalog, ledger_keys):
    """Every recorded English change names a catalog key and quantity, and (for keys no ledger row
    checks) its last new text is today's text."""
    problems = []
    for (key, quantity), entries in sorted(changes.items()):
        where = entries[0][2]
        entry = catalog.get(key)
        if entry is None:
            problems.append("%s: %s names key %s, which is not in res/values/strings*.xml" % (where, CHANGES_PATH, key))
        elif quantity not in entry.items:
            problems.append("%s: %s names %s[%s], which the catalog does not have" % (where, CHANGES_PATH, key, quantity))
        elif key not in ledger_keys and entries[-1][1] != entry.items[quantity].text:
            problems.append("%s: %s says %s[%s] became %r, but it is %r" % (
                entries[-1][2], CHANGES_PATH, key, quantity, entries[-1][1], entry.items[quantity].text))
    return problems


# ---------------------------------------------------------------------------
# The source side: where each new row's literals were, and where they went
# ---------------------------------------------------------------------------

def _first_literal_on(index, line):
    for k, tok in enumerate(index.tokens):
        if tok.kind in expressions.LITERALS and tok.line >= line:
            return k if tok.line == line else None
    return None


def locate(row, diff, claimed):
    """(token indexes, manual, problem) for the base literals one src row describes."""
    index = diff.base
    shapes = set(expressions.shape(s) for s in sequences(row.pieces))
    if len(shapes) == 1:
        wanted = shapes.pop()
        candidates = [e for e in index.expressions if e.first_line <= row.base_line <= e.last_line and
                      expressions.shape(e.pieces) == wanted]
        if candidates:
            def score(e):
                free = not any((row.path, k) in claimed for k in e.tokens)
                gone = all(k in diff.gone for k in e.tokens)
                return (free, gone == (row.kind in MOVING))
            best = max(candidates, key=score)
            return list(best.tokens), False, None
    if row.kind == "format":
        on_line = [json.dumps(e.pieces, ensure_ascii=False) for e in index.expressions
                   if e.first_line <= row.base_line <= e.last_line]
        return None, False, ("format row: its split reassembles to %s, which is not the expression on line %d of %s "
                             "at the merge base%s; the split must spell the original exactly, in order" % (
                                 json.dumps(row.pieces, ensure_ascii=False), row.base_line, row.path,
                                 " (found: %s)" % "; ".join(on_line) if on_line else " (no literal there)"))
    if not row.note.startswith("manual:"):
        on_line = [json.dumps(e.pieces, ensure_ascii=False) for e in index.expressions
                   if e.first_line <= row.base_line <= e.last_line]
        return None, False, ("the pieces do not match the expression on line %d of %s at the merge base%s; a "
                             "hand-written row (ternary, String.format, append chain) needs a note starting "
                             "\"manual:\"" % (row.base_line, row.path,
                                              " (found: %s)" % "; ".join(on_line) if on_line else " (no literal there)"))
    if not isinstance(row.pieces, dict):
        # The note excuses a shape the + chain parser cannot follow, not a mismatch with one it did parse.
        items = expressions.literal_items(row.pieces)
        parsed = [json.dumps(e.pieces, ensure_ascii=False) for e in index.expressions
                  if e.first_line <= row.base_line <= e.last_line and expressions.literal_items(e.pieces) == items]
        if parsed:
            return None, True, ("manual row, but line %d of %s parses as an expression with the same literals, and "
                                "the pieces do not match it (found: %s)" % (row.base_line, row.path, "; ".join(parsed)))
    start = _first_literal_on(index, row.base_line)
    if start is None:
        return None, True, "no literal on line %d of %s at the merge base" % (row.base_line, row.path)
    window = [k for k in range(start, len(index.tokens)) if index.tokens[k].kind in expressions.LITERALS and
              index.tokens[k].line < row.base_line + MANUAL_WINDOW]
    found = set()
    for sequence in sequences(row.pieces):
        at = 0
        for item in expressions.literal_items(sequence):
            while at < len(window) and (index.tokens[window[at]].kind, index.tokens[window[at]].value) != item:
                at += 1
            if at == len(window):
                return None, True, "manual row: %s is not on or after line %d (in order, within %d lines)" % (
                    l10nlib.json_string(item[1]), row.base_line, MANUAL_WINDOW)
            found.add(window[at])
            at += 1
    if found and index.tokens[min(found)].line != row.base_line:
        return None, True, "manual row: its first literal is on line %d, not %d" % (
            index.tokens[min(found)].line, row.base_line)
    return sorted(found), True, None


def call_index(head_texts):
    """{"accessor": {name: [Call]}, "id": {CONST: [Call]}, "bare": {CONST: [Call]}, "refs": set of names}.

    A Call is (path, line, token index, [arg source keys]); a bare T.CONST reference has args None.
    """
    accessors, ids, bare, refs = (collections.defaultdict(list), collections.defaultdict(list),
                                  collections.defaultdict(list), set())
    for path, text in head_texts.items():
        if text is None:
            continue
        try:
            index = _Lexed(text)
        except l10nlib.LexError:
            continue
        tokens = index.tokens
        for i in range(len(tokens) - 2):
            if tokens[i].kind != "ident" or tokens[i].value not in ("T", "M") or tokens[i + 1].value != "." or \
                    tokens[i + 2].kind != "ident" or (i > 0 and tokens[i - 1].value == "."):
                continue
            name = tokens[i + 2].value
            refs.add(name)
            if i + 3 < len(tokens) and tokens[i + 3].value == "(" and tokens[i + 3].kind == "op":
                accessors[name].append((path, tokens[i].line, i, _arguments(index, i + 3)))
                continue
            if tokens[i].value == "T" and i > 0 and tokens[i - 1].value == "(":
                args = _arguments(index, i - 1)
                if args and args[0] == ("T", ".", name):
                    ids[name].append((path, tokens[i].line, i, args[1:]))
                    continue
            bare[name].append((path, tokens[i].line, i, None))
    return {"accessor": accessors, "id": ids, "bare": bare, "refs": refs}


class _Lexed(object):
    def __init__(self, source):
        self.text = l10nlib.translate_unicode_escapes(source)
        self.tokens = l10nlib.lex_java(source)


def _arguments(index, opener):
    tokens = index.tokens
    close = expressions.match_forward(tokens, opener)
    if close is None or close == opener + 1:
        return []
    args, start, depth = [], opener + 1, 0
    for k in range(opener + 1, close):
        value = tokens[k].value if tokens[k].kind == "op" else None
        if value in ("(", "[", "{"):
            depth += 1
        elif value in (")", "]", "}"):
            depth -= 1
        elif value == "," and depth == 0:
            args.append(expressions.source_key(expressions.source_of(index.text, tokens, start, k - 1)))
            start = k + 1
    args.append(expressions.source_key(expressions.source_of(index.text, tokens, start, close - 1)))
    return args


def expected_arguments(row):
    """(quantity source key or None, [source keys by arg index]) the head call must pass."""
    if row.kind == "format":
        by_arg = {}
        for item in row.split:
            if isinstance(item, str):
                continue
            if "spec" in item:
                source = item["as"]
            elif "key" in item:
                source = "T.%s()" % gen_catalog.camel(item["key"])
            else:
                source = item.get("as", item["hole"])
            by_arg[item["arg"]] = expressions.source_key(source)
        return None, [by_arg[i] for i in sorted(by_arg)]
    by_arg, quantity = {}, None
    for hole in holes(row.pieces):
        source = expressions.source_key(hole.get("as", hole["hole"]))
        if hole.get("quantity"):
            quantity = source
        by_arg.setdefault(hole["arg"], source)
    return quantity, [by_arg[i] for i in sorted(by_arg)]


def _passes_holes(row, actual):
    quantity, args = expected_arguments(row)
    if not args and quantity is None:
        return actual in ([], None)
    if actual is None:
        return False
    if row.kind == "plural":
        if not actual or (quantity is not None and actual[0] != quantity):
            return False
        actual = actual[1:]
    return actual == args


def check_call_sites(row, calls, path=None, ranges=None, used=None, after=-1):
    """(problem or None, the call used): a call of the row's key passes its holes in placeholder order.

    With path and ranges, the call must sit in those head lines of path (the code that replaced the
    row's literals), must not be in used, and must come after token index after (calls follow the
    base order of their rows), so two keys swapped between converted sites fail.
    """
    accessor, constant = gen_catalog.camel(row.key), gen_catalog.constant(row.key)
    if accessor not in calls["refs"] and constant not in calls["refs"]:
        return "key %s is not used in src at head (no T.%s, M.%s or T.%s)" % (
            row.key, accessor, accessor, constant), None
    candidates = sorted(calls["accessor"].get(accessor, []) + calls["id"].get(constant, []) +
                        calls["bare"].get(constant, []), key=lambda c: (c[0], c[2]))
    passing = [c for c in candidates if _passes_holes(row, c[3])]
    quantity, args = expected_arguments(row)
    want = ([" ".join(quantity) if quantity else "<quantity>"] if row.kind == "plural" else []) + \
        [" ".join(a) for a in args]
    if not passing:
        found = [c for c in candidates if c[3] is not None]
        seen = "; ".join("%s:%d (%s)" % (p, l, ", ".join(" ".join(a) for a in act)) for p, l, _, act in found[:3])
        return "no call of %s passes the holes in placeholder order (%s)%s" % (
            accessor, ", ".join(want), "; found " + seen if seen else "; only T.%s is referenced" % constant), None
    if ranges is None:
        return None, passing[0]
    here = [c for c in passing if c[0] == path and any(h1 <= c[1] <= h2 for h1, h2 in ranges)]
    free = [c for c in here if (c[0], c[2]) not in (used or ()) and c[2] > after]
    if free:
        return None, free[0]
    lines = ", ".join("%d-%d" % r if r[0] <= r[1] else "(none)" for r in ranges)
    if here:
        return ("the call of %s in head lines %s of %s is already used by another row, or comes before the call of "
                "an earlier row; each converted site needs its own call, in the base order" % (
                    accessor, lines, path)), None
    elsewhere = "; ".join("%s:%d" % (c[0], c[1]) for c in passing[:3])
    return ("%s is called at %s, not in the head lines that replaced the literal (%s, lines %s); the call must "
            "sit where the literal was" % (accessor, elsewhere, path, lines)), None


def fmt_wrapper(hole):
    """True when a hole's "as" is Fmt.<method>(...) with the hole's own source as one whole argument."""
    try:
        lexed = _Lexed(hole["as"])
    except l10nlib.LexError:
        return False
    t = lexed.tokens
    if len(t) < 5 or t[0].value != "Fmt" or t[1].value != "." or t[2].kind != "ident" or \
            t[3].kind != "op" or t[3].value != "(" or expressions.match_forward(t, 3) != len(t) - 1:
        return False
    return expressions.source_key(hole["hole"]) in _arguments(lexed, 3)


# How a String.format specifier moves into Fmt, for format rows: the specifier, the Fmt method, and
# the arguments that call must pass ("locale": the String.format call's locale, "value": the spec's
# base argument, a digit: that group of the specifier). Fmt's own parity with the specifier is G10's
# (tests/l10n/FmtLegacyTest); a pair not listed here needs a tooling pull request that adds it.
FMT_SPECS = (
    (re.compile(r"%\.([0-6])f"), "decimal", ("locale", "value", "1")),
    (re.compile(r"%,\.([0-6])f"), "grouped", ("locale", "value", "1")),
    (re.compile(r"%d"), "integer", ("value",)),
)
# %d prints the locale's digits; Fmt.integer always prints Latin digits, which these locales do too.
LATIN_DIGIT_LOCALES = (("Locale", ".", "US"), ("Locale", ".", "ROOT"), ("Locale", ".", "ENGLISH"))
# String.format without a locale uses the default format locale.
DEFAULT_LOCALES = (("Locale", ".", "getDefault", "(", ")"),
                   ("Locale", ".", "getDefault", "(", "Locale", ".", "Category", ".", "FORMAT", ")"))


PLAIN_STRING_SPEC = re.compile(r"%(\d+\$)?s")


def _token_keys(source):
    return [(t.kind, t.value if t.kind in expressions.LITERALS else t.raw) for t in _Lexed(source).tokens]


def passthrough_problem(item):
    """None when a plain %s spec item's "as" is its value with some string literals replaced by a
    no-argument T.<key>() or M.<key>() call each, and nothing else changed. A template's %n$s prints
    a CharSequence as String.format's %s did; each literal replaced needs its own exact row, which
    checks the key's English and the call's place. Else the problem."""
    try:
        value, given = _token_keys(item["value"]), _token_keys(item["as"])
    except l10nlib.LexError:
        return "the specifier %s's value or \"as\" is not Java source" % item["spec"]
    i = j = 0
    while i < len(value) and j < len(given):
        if value[i] == given[j]:
            i, j = i + 1, j + 1
            continue
        call = given[j:j + 5]
        if value[i][0] == "string" and len(call) == 5 and call[0] in (("ident", "T"), ("ident", "M")) and \
                call[1] == ("op", ".") and call[2][0] == "ident" and call[3:] == [("op", "("), ("op", ")")] and \
                not (j > 0 and given[j - 1] == ("op", ".")):
            i, j = i + 1, j + 5
            continue
        break
    if i == len(value) and j == len(given):
        return None
    return ("the specifier %s prints %s as it is, so head must pass that same expression, with only its string "
            "literals turned into T.<key>() calls (each with an exact row), but the row passes %s" % (
                item["spec"], item["value"], item["as"]))


def spec_problem(item, locale):
    """None when a spec item's "as" is the Fmt call FMT_SPECS pairs with its specifier, with the
    String.format call's locale (a source key, or None for the default) and the spec's value. A plain
    %s passes its value through (passthrough_problem)."""
    if PLAIN_STRING_SPEC.fullmatch(item["spec"]):
        return passthrough_problem(item)
    try:
        lexed = _Lexed(item["as"])
    except l10nlib.LexError:
        lexed = None
    t = lexed.tokens if lexed else []
    if len(t) < 4 or t[0].value != "Fmt" or t[1].value != "." or t[2].kind != "ident" or \
            t[3].kind != "op" or t[3].value != "(" or expressions.match_forward(t, 3) != len(t) - 1:
        return "the specifier %s is passed as %s, which is not one Fmt.<method>(...) call" % (item["spec"], item["as"])
    method, actual = t[2].value, _arguments(lexed, 3)
    for pattern, name, shape in FMT_SPECS:
        m = pattern.fullmatch(item["spec"])
        if not m:
            continue
        if name == "integer" and locale not in LATIN_DIGIT_LOCALES:
            return ("%s prints %s's digits, and Fmt.integer always prints Latin ones; only a String.format with "
                    "Locale.US, Locale.ROOT or Locale.ENGLISH moves to Fmt.integer" % (
                        item["spec"], " ".join(locale) if locale else "the default locale"))
        wanted = []
        for locale_key in ([locale] if locale else DEFAULT_LOCALES):
            want = []
            for part in shape:
                want.append(locale_key if part == "locale" else expressions.source_key(item["value"]) if part == "value"
                            else expressions.source_key(m.group(int(part))))
            wanted.append(want)
        if method == name and actual in wanted:
            return None
        return "the specifier %s of %s prints as Fmt.%s(%s), but the row passes %s" % (
            item["spec"], item["value"], name, ", ".join(" ".join(a).replace(" . ", ".") for a in wanted[0]), item["as"])
    return ("G1 does not know which Fmt call prints like %s (FMT_SPECS in tools/l10n/ledger_check.py lists the "
            "pairs it checks); add the pair, with a G10 test, in a tooling pull request" % item["spec"])


def _argument_spans(tokens, opener):
    """(closing index, [(first, last)] of each top-level argument) of the call whose ( is at opener."""
    close = expressions.match_forward(tokens, opener)
    if close is None or close == opener + 1:
        return close, []
    spans, start, depth = [], opener + 1, 0
    for k in range(opener + 1, close):
        value = tokens[k].value if tokens[k].kind == "op" else None
        if value in ("(", "[", "{"):
            depth += 1
        elif value in (")", "]", "}"):
            depth -= 1
        elif value == "," and depth == 0:
            spans.append((start, k - 1))
            start = k + 1
    spans.append((start, close - 1))
    return close, spans


def string_format_site(index, k):
    """(first, last, locale source key or None, [argument source keys after the format string]) when the
    literal at token k is the whole format-string argument of String.format(...), else None."""
    tokens = index.tokens
    depth, j = 0, k - 1
    while j >= 0:
        t = tokens[j]
        if t.kind == "op" and t.value in (")", "]", "}"):
            depth += 1
        elif t.kind == "op" and t.value in ("(", "[", "{"):
            if depth == 0:
                break
            depth -= 1
        j -= 1
    if j < 3 or tokens[j].value != "(" or [x.value for x in tokens[j - 3:j]] != ["String", ".", "format"]:
        return None
    close, spans = _argument_spans(tokens, j)
    if close is None or (k, k) not in spans or spans.index((k, k)) > 1:
        return None
    at = spans.index((k, k))
    keys = [expressions.source_key(expressions.source_of(index.text, tokens, a, b)) for a, b in spans]
    return j - 3, close, keys[0] if at == 1 else None, keys[at + 1:]


def format_site(row, diff, tokens):
    """(problem, (first, last) base token span the head call replaces, locale or None) for a format row."""
    index = diff.base
    literal = split_is_format_literal(row.split)
    sites = [(k, string_format_site(index, k)) for k in tokens]
    if not literal:
        for k, site in sites:
            if site is not None:
                return ("format row: %s on line %d is String.format's format string; split its specifiers out as "
                        "spec items" % (l10nlib.json_string(index.tokens[k].value), index.tokens[k].line)), None, None
        return None, diff.base.expression_of(tokens[0]).span, None
    k, site = sites[0]
    if site is None:
        return ("format row: %s on line %d is not the format string of a String.format(...) call, so it has no "
                "specifiers to split out" % (l10nlib.json_string(index.tokens[k].value), index.tokens[k].line)), None, None
    first, last, locale, after = site
    used, ordinary = set(), 0
    for item in row.split:
        if not isinstance(item, dict) or "spec" not in item:
            continue
        explicit = FORMAT_SPEC.fullmatch(item["spec"]).group(1)
        if explicit:
            position = int(explicit[:-1]) - 1
        else:
            position, ordinary = ordinary, ordinary + 1
        if position >= len(after):
            return ("format row: the specifier %s on line %d has no argument in the String.format call" % (
                item["spec"], index.tokens[k].line)), None, None
        if expressions.source_key(item["value"]) != after[position]:
            return ("format row: the specifier %s formats %s at the merge base, not %s" % (
                item["spec"], " ".join(after[position]), item["value"])), None, None
        used.add(position)
        problem = spec_problem(item, locale)
        if problem:
            return "format row: %s" % problem, None, None
    if len(used) != len(after):
        return ("format row: the String.format call on line %d passes %d argument(s) after its format string, and "
                "the specifiers use %d" % (index.tokens[k].line, len(after), len(used))), None, None
    return None, (first, last), locale


NOT_METHOD_HEADERS = {"if", "for", "while", "switch", "catch", "synchronized", "do", "try", "else", "finally"}


def enclosing_method(tokens, k):
    """The name of the method (or constructor) whose body holds token k, or None."""
    j = k
    while True:
        depth, opener = 0, None
        for i in range(j - 1, -1, -1):
            if tokens[i].kind == "op" and tokens[i].value == "}":
                depth += 1
            elif tokens[i].kind == "op" and tokens[i].value == "{":
                if depth == 0:
                    opener = i
                    break
                depth -= 1
        if opener is None:
            return None
        first = opener
        while first > 0 and not (tokens[first - 1].kind == "op" and tokens[first - 1].value in ";{}"):
            first -= 1
        header = tokens[first:opener]
        values = [t.value for t in header]
        if any(v in values for v in ("class", "enum", "interface", "record")):
            return None
        if header and values[0] not in NOT_METHOD_HEADERS and not any(v in values for v in ("=", "->", "new")):
            p = 0
            while p < len(header):
                if header[p].kind == "op" and header[p].value == "(":
                    if p > 0 and header[p - 1].kind == "ident" and not (p > 1 and header[p - 2].value == "@"):
                        return header[p - 1].value
                    close = expressions.match_forward(header, p)
                    p = close if close is not None else p
                p += 1
        j = opener


def compiled_tests(head, cwd=None):
    """The tests/l10n/ Java files that tests/run.sh compiles at head: those a tests/l10n/*.sources list
    names (one repository path per line, "#" starts a comment)."""
    lists = [p for p in l10nlib.list_files(head, ["tests/l10n"], cwd)
             if os.path.dirname(p) == "tests/l10n" and p.endswith(".sources")]
    listed = set()
    for text in l10nlib.read_files(head, lists, cwd).values():
        for line in (text or "").splitlines():
            line = line.split("#", 1)[0].strip()
            if line.startswith("tests/l10n/") and line.endswith(".java"):
                listed.add(line)
    return sorted(listed)


def test_callers(head, cwd=None):
    """{(Class, method): [test files]} for every Class.method( call in a test under tests/l10n/ that
    tests/run.sh compiles at head (compiled_tests). Methods match by simple name, so an overload counts:
    the result only lists tests for the reviewer, except Fmt.<method>, which G1 requires (G10)."""
    names = compiled_tests(head, cwd)
    found = collections.defaultdict(list)
    for path, text in sorted(l10nlib.read_files(head, names, cwd).items()):
        try:
            tokens = l10nlib.lex_java(text or "")
        except l10nlib.LexError:
            continue
        for i in range(len(tokens) - 3):
            if tokens[i].kind == "ident" and tokens[i + 1].value == "." and tokens[i + 2].kind == "ident" and \
                    tokens[i + 3].value == "(" and path not in found[(tokens[i].value, tokens[i + 2].value)]:
                found[(tokens[i].value, tokens[i + 2].value)].append(path)
    return found


def constant_name(tokens, k):
    """NAME when the literal at token k is all of a constant's value: NAME = "..." (a field such as
    static final String NAME = "V";) or the whole argument of an enum constant NAME(..., "V", ...)."""
    def op(j, *values):
        return 0 <= j < len(tokens) and tokens[j].kind == "op" and tokens[j].value in values
    if k >= 2 and op(k - 1, "=") and tokens[k - 2].kind == "ident" and op(k + 1, ";", ","):
        return tokens[k - 2].value
    if not (op(k - 1, "(", ",") and op(k + 1, ")", ",")):
        return None
    depth, j = 0, k - 1
    while j >= 0:
        if op(j, ")", "]", "}"):
            depth += 1
        elif op(j, "(", "[", "{"):
            if depth == 0:
                break
            depth -= 1
        j -= 1
    if j < 2 or not op(j, "(") or tokens[j - 1].kind != "ident" or not op(j - 2, "{", ","):
        return None
    # NAME( is an enum constant when it sits in an enum body before the body's first ;
    depth, i = 0, j - 2
    while i >= 0:
        if op(i, ")", "]", "}"):
            depth += 1
        elif op(i, "(", "[", "{"):
            if depth == 0:
                break
            depth -= 1
        elif depth == 0 and op(i, ";"):
            return None
        i -= 1
    if i < 0 or not op(i, "{"):
        return None
    first = i
    while first > 0 and not op(first - 1, ";", "{", "}"):
        first -= 1
    if not any(t.kind == "ident" and t.value == "enum" for t in tokens[first:i]):
        return None
    return tokens[j - 1].value


def _region_tokens(index, first, last):
    return [t for t in index.tokens if first <= t.line <= last] if index is not None else []


def added_in_hunk(diff, hunk):
    """The first head token of a changed region that is not a base token kept in order (None when the
    region only lost tokens, which is what deleting code looks like)."""
    b1, b2, h1, h2 = diff.hunks[hunk]
    base = iter([(t.kind, t.value if t.kind in expressions.LITERALS else t.raw)
                 for t in _region_tokens(diff.base, b1, b2)])
    for tok in _region_tokens(diff.head, h1, h2):
        key = (tok.kind, tok.value if tok.kind in expressions.LITERALS else tok.raw)
        if not any(key == b for b in base):
            return tok
    return None


def _excerpt(tokens, limit=16):
    if limit is None:
        return " ".join(t.raw for t in tokens)
    text = " ".join(t.raw for t in tokens[:limit])
    return text + (" ..." if len(tokens) > limit else "")


WHOLE_BLOCK = "whole block:"


def statement_of(diff, k):
    """(first, last, terminator) of the base statement holding base token k."""
    return expressions.statement_span(diff.base.tokens, k)


def deleted_whole(diff, k):
    """True when the head lost every token of the base statement holding token k."""
    deleted, _ = diff.changed_tokens()
    first, last, terminator = statement_of(diff, k)
    if any(j not in deleted for j in range(first, last + 1)):
        return False
    if terminator is not None and terminator > last and terminator not in deleted:
        # The aligner may keep this ; and drop the one before the statement instead.
        return first > 0 and (first - 1) in deleted
    return True


def call_span(tokens, k):
    """(first, last) head token indexes of the catalog call whose T or M token is at k, with a
    trailing .render() (a deferred Msg rendered on the spot)."""
    start, end = k, k + 2
    if k + 3 < len(tokens) and tokens[k + 3].kind == "op" and tokens[k + 3].value == "(":
        close = expressions.match_forward(tokens, k + 3)
        end = close if close is not None else k + 3
    elif k > 0 and tokens[k - 1].kind == "op" and tokens[k - 1].value == "(":
        # Msg.of(T.NAME, ...) or T.render(T.NAME, ...): the whole enclosing call.
        start = k - 1
        if start > 0 and tokens[start - 1].kind == "ident":
            start -= 1
            while start >= 2 and tokens[start - 1].value == "." and tokens[start - 2].kind == "ident":
                start -= 2
        close = expressions.match_forward(tokens, k - 1)
        end = close if close is not None else k + 2
    if [t.raw for t in tokens[end + 1:end + 5]] == [".", "render", "(", ")"]:
        end += 4
    return start, end


def deleted_scope(diff, first, last):
    """(first, last, frame) widened from a deleted statement to each enclosing block (a method, an if)
    whose header and body were deleted with it. frame is the set of base tokens of those blocks'
    headers and braces; any other token in the widened span is another statement deleted with them."""
    tokens = diff.base.tokens
    deleted, _ = diff.changed_tokens()
    frame = set()
    while True:
        depth, opener = 0, None
        for j in range(first - 1, -1, -1):
            if tokens[j].kind == "op" and tokens[j].value == "}":
                depth += 1
            elif tokens[j].kind == "op" and tokens[j].value == "{":
                if depth == 0:
                    opener = j
                    break
                depth -= 1
        if opener is None:
            return first, last, frame
        head_first = opener
        while head_first > 0 and not (tokens[head_first - 1].kind == "op" and tokens[head_first - 1].value in ";{}"):
            head_first -= 1
        close = expressions.match_brace(tokens, opener)
        if any(j not in deleted for j in range(head_first, close + 1)):
            return first, last, frame
        frame.update(range(head_first, opener + 1))
        frame.add(close)
        first, last = head_first, close


def _class_name(path):
    return os.path.basename(path)[:-len(".java")]


def _references(diff, hunk, row_path, arrival_path, name):
    """Positions, in the head lines of one changed region, that name the constant NAME of arrival_path:
    NAME in the same file, or Class.NAME."""
    owner = _class_name(arrival_path)
    _, _, h1, h2 = diff.hunks[hunk]
    region = _region_tokens(diff.head, h1, h2)
    found = []
    for k, tok in enumerate(region):
        if tok.kind != "ident" or tok.value != name:
            continue
        qualified = k >= 2 and region[k - 1].value == "." and region[k - 2].value == owner
        unqualified = (k == 0 or region[k - 1].value != ".") and arrival_path == row_path
        if qualified or unqualified:
            found.append(k)
    return found


def _points_to(diff, hunk, row_path, arrival_path, arrival_index, arrival_diff):
    """The constant's name when the code a staying literal left names the constant it arrived in: the
    arrival is NAME = "..." or an enum constant NAME(..., "...", ...), and the region's head lines say
    NAME (same file) or Class.NAME. Else None."""
    name = constant_name(arrival_diff.head.tokens, arrival_index)
    if name is None or not _references(diff, hunk, row_path, arrival_path, name):
        return None
    return name


def _whole_members(tokens, first, last):
    """True when tokens first..last are whole method or field declarations in a class body."""
    def op(j, *values):
        return tokens[j].kind == "op" and tokens[j].value in values
    if first == 0 or not op(first - 1, ";", "{", "}"):
        return False
    depth, opener = 0, None
    for i in range(first - 1, -1, -1):
        if op(i, "}"):
            depth += 1
        elif op(i, "{"):
            if depth == 0:
                opener = i
                break
            depth -= 1
    if opener is None:
        return False
    start = opener
    while start > 0 and not op(start - 1, ";", "{", "}"):
        start -= 1
    if not any(t.kind == "ident" and t.value in ("class", "enum", "interface", "record") for t in tokens[start:opener]):
        return False
    j = first
    while j <= last:
        k, header = j, []
        while k <= last and not op(k, ";", "{"):
            if op(k, "(", "["):
                close = expressions.match_forward(tokens, k)
                if close is None or close > last:
                    return False
                header.extend(tokens[k:close + 1])
                k = close + 1
                continue
            header.append(tokens[k])
            k += 1
        if k > last or not header or any(t.kind == "ident" and t.value in ("class", "enum", "interface", "record")
                                         for t in header):
            return False
        if op(k, "{"):
            # A method: a name and parameters before its body; an initializer block has none.
            if not any(op(m, "(") for m in range(j, k)) or any(op(m, "=") for m in range(j, k)):
                return False
            k = expressions.match_brace(tokens, k)
            if not op(k, "}") or k > last:
                return False
        j = k + 1
    return j == last + 1


def inserted_members(tokens, first, last):
    """(first, last) of whole member declarations that head tokens first..last are, possibly once slid
    over equal neighbouring tokens (a diff may show "} void f() { ... ;" for "void f() { ... ; }"),
    or None."""
    def key(tok):
        return (tok.kind, tok.value if tok.kind in expressions.LITERALS else tok.raw)
    candidates, f, l = [(first, last)], first, last
    while l + 1 < len(tokens) and key(tokens[f]) == key(tokens[l + 1]):
        f, l = f + 1, l + 1
        candidates.append((f, l))
    f, l = first, last
    while f > 0 and key(tokens[f - 1]) == key(tokens[l]):
        f, l = f - 1, l - 1
        candidates.append((f, l))
    return next(((f, l) for f, l in candidates if _whole_members(tokens, f, l)), None)


def other_changes(path, diff, allowed_base, allowed_head, moved_hunks, new_ids=()):
    """(problems, warnings) for code a batch changed besides converting or deleting text.

    allowed_base and allowed_head map token indexes to a span id: a row's base expression (or a
    manual row's statement) and the head call that serves it share one id, and a removed row's
    deleted code has an id with no head side. Each span counts as one item when the changed regions
    are aligned, so only tokens outside them can differ. new_ids are head tokens of new key or id
    literals (listed elsewhere); they alone do not make a region hold text.
    """
    if diff.base is None or diff.head is None:
        return [], ["%s was %s; check it by hand" % (path, "added" if diff.base is None else "deleted")]
    problems, warnings = [], []
    for hunk, (b1, b2, h1, h2) in enumerate(diff.hunks):
        sides = []
        for index, allowed, first, last in ((diff.base, allowed_base, b1, b2), (diff.head, allowed_head, h1, h2)):
            keys, where = [], []
            for k, tok in enumerate(index.tokens):
                if not first <= tok.line <= last:
                    continue
                if k in allowed:
                    if keys and keys[-1] == ("span", allowed[k]):
                        continue
                    keys.append(("span", allowed[k]))
                    where.append(None)
                else:
                    keys.append((tok.kind, tok.value if tok.kind in expressions.LITERALS else tok.raw))
                    where.append(k)
            sides.append((keys, where))
        (a, a_at), (b, b_at) = sides
        text = any(t.kind in expressions.LITERALS for t in _region_tokens(diff.base, b1, b2)) or \
            any(t.kind in expressions.LITERALS and k not in new_ids
                for k, t in enumerate(diff.head.tokens) if h1 <= t.line <= h2)
        lost, gained = [], []
        for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes():
            if text and tag == "insert" and None not in b_at[j1:j2] and b_at[j2 - 1] - b_at[j1] == j2 - j1 - 1:
                members = inserted_members(diff.head.tokens, b_at[j1], b_at[j2 - 1])
                if members is not None:
                    # A new method or field next to text does not run where the text is; it is listed.
                    warnings.append("%s:%d: this change adds the whole member(s) `%s`; check by hand that nothing "
                                    "in them changes English" % (
                                        path, diff.head_token(members[0]).line,
                                        _excerpt(diff.head.tokens[members[0]:members[1] + 1], 60)))
                    continue
            if tag != "equal":
                lost.extend(k for k in a_at[i1:i2] if k is not None)
                gained.extend(k for k in b_at[j1:j2] if k is not None)
        if not lost and not gained:
            continue
        parts = []
        # Beside a move the reviewer is the check, so the whole change is printed.
        limit = None if (path, hunk) in moved_hunks else 16
        if gained:
            parts.append("adds `%s`" % _excerpt([diff.head_token(k) for k in gained], limit))
        if lost:
            parts.append("drops `%s`" % _excerpt([diff.base_token(k) for k in lost], limit))
        line = diff.head_token(gained[0]).line if gained else diff.base_token(lost[0]).line
        what = "%s:%d: this change %s" % (path, line, " and ".join(parts))
        if (path, hunk) in moved_hunks:
            warnings.append("%s beside a stays-english or id-kept move; check by hand that the English is the same"
                            % what)
        elif text:
            problems.append("%s%s; around text, an extraction batch only replaces a ledger row's expression with "
                            "the call that serves it (or deletes a removed row's statement)" % (
                                what, " (base line %d)" % diff.base_token(lost[0]).line if lost and not gained
                                else ""))
        else:
            warnings.append("%s, away from any text; check by hand that it changes no English" % what)
    return problems, warnings


def existing_constants(sources, value):
    """[(path, token)] of constants already in src at head, outside the batch's arrivals, whose whole
    value is the string literal value: NAME = "..." or an enum constant NAME(..., "..."). sources is
    [(path, tokens, tokens to skip)]."""
    return [(path, k) for path, tokens, skip in sources for k, tok in enumerate(tokens)
            if tok.kind == "string" and tok.value == value and k not in skip and constant_name(tokens, k)]


def check_named_moves(named, head_tokens, diffs, existing=()):
    """(problems, warnings) for staying literals that moved into named constants.

    named maps (path, head token) of a constant's literal to the sites [(row, path, base token)] that
    name it; head_tokens(path) gives that file's head tokens; existing holds the constants that were
    already in src (a later batch moving its literals into B1's UnitCode). In each changed region, the
    constants the head lines name must include one reference per literal that left it, in the base
    order of those literals: unit.equals("V") ... unit.equals("W") cannot become WATT ... VOLT, nor
    two "V" sites one VOLT. Every constant several sites share, and every existing constant a site
    names, is listed with each site."""
    problems, warnings = [], []
    by_hunk = collections.defaultdict(list)  # (path, hunk) -> [(base token, (owner, name), row)]
    for (a_path, a_k), uses in sorted(named.items()):
        a_tokens = head_tokens(a_path)
        name = constant_name(a_tokens, a_k)
        for row, path, k in uses:
            by_hunk[(path, diffs[path].gone[k])].append((k, (a_path, name), row))
        if len(uses) > 1 or (a_path, a_k) in existing:
            first = max(i for i in range(a_k) if a_tokens[i].kind == "ident" and a_tokens[i].value == name)
            sites = ", ".join("%s:%d (%s:%d)" % (path, diffs[path].base_token(k).line, row.source, row.line)
                              for row, path, k in uses)
            warnings.append("%s:%d: %d occurrence(s) of %s moved into the %sconstant %s.%s (`%s`): %s; check by hand "
                            "that every site means this same value" % (
                                a_path, a_tokens[a_k].line, len(uses), l10nlib.json_string(a_tokens[a_k].value),
                                "existing " if (a_path, a_k) in existing else "one ",
                                _class_name(a_path), name, _excerpt(a_tokens[first:a_k + 2], None),
                                sites))
    for (path, hunk), moved in sorted(by_hunk.items()):
        diff = diffs[path]
        moved.sort(key=lambda m: m[0])
        want = [m[1] for m in moved]
        refs = []
        for constant in set(want):
            refs.extend((k, constant) for k in _references(diff, hunk, path, constant[0], constant[1]))
        named_order = [constant for _, constant in sorted(refs)]
        at = 0
        for constant in named_order:
            if at < len(want) and constant == want[at]:
                at += 1
        if at < len(want):
            _, _, h1, h2 = diff.hunks[hunk]
            problems.append("%s:%d: the literals that left these lines for constants are, in order, %s, but the head "
                            "lines name %s; each site must name the constant its own literal moved to (head lines "
                            "%d-%d: `%s`)" % (
                                path, diff.base_token(moved[0][0]).line,
                                ", ".join("%s (%s.%s)" % (l10nlib.json_string(diff.base_token(m[0]).value),
                                                          _class_name(m[1][0]), m[1][1]) for m in moved),
                                ", ".join("%s.%s" % (_class_name(c[0]), c[1]) for c in named_order) or "none",
                                h1, h2, _excerpt(_region_tokens(diff.head, h1, h2), 60)))
    return problems, warnings


def fmt_methods(row):
    """The Fmt methods a format row's spec and hole items pass, in order, once each."""
    found = []
    for item in row.split:
        if isinstance(item, dict) and "as" in item:
            try:
                t = _Lexed(item["as"]).tokens
            except l10nlib.LexError:
                continue
            if len(t) > 3 and t[0].value == "Fmt" and t[1].value == "." and t[3].value == "(" and t[2].value not in found:
                found.append(t[2].value)
    return found


def format_review(row, where, diff, base_span, head_span, callers):
    """(problems, warning) for a format row whose call was found. Each Fmt method the row passes must be
    called by a compiled test under tests/l10n/ (G10 FmtLegacyTest proves it prints like the specifier
    FMT_SPECS pairs it with). The reviewer sees the base code, the call that replaced it, each
    specifier's replacement and the tests that call the method that held the literal; a method no test
    can call (private, an instance method of a screen) is named in the warning instead."""
    base_tokens, head_tokens = diff.base.tokens, diff.head.tokens
    method = enclosing_method(base_tokens, base_span[0])
    owner = _class_name(row.path)
    pairs = ["%s -> %s" % (item["spec"], item["as"]) for item in row.split if isinstance(item, dict) and "spec" in item]
    pairs += ["%s -> %s" % (item["hole"], item["as"]) for item in row.split
              if isinstance(item, dict) and "hole" in item and "as" in item]
    keys = ["%s -> %s" % (l10nlib.json_string(item["text"]), item["key"]) for item in row.split
            if isinstance(item, dict) and "key" in item]
    problems = ["%s: format row at %s:%d passes Fmt.%s, and no test that tests/run.sh compiles under tests/l10n/ "
                "calls it; G10 (FmtLegacyTest) must show it prints like the specifier it replaces" % (
                    where, row.path, row.base_line, name) for name in fmt_methods(row) if not callers.get(("Fmt", name))]
    tests = callers.get((owner, method), []) if method else []
    if tests:
        site = "; a method named %s.%s is called by %s" % (owner, method, ", ".join(tests))
    elif fmt_methods(row):
        site = ("; no compiled test under tests/l10n/ calls %s.%s(...), so check by hand that its values are ones "
                "G10 covers" % (owner, method or "<the enclosing method>"))
    else:
        site = ""
    warning = "%s: format row at %s:%d: `%s` becomes `%s`; text %s%s%s; check by hand that each replacement prints " \
              "what its specifier did" % (
                  where, row.path, row.base_line, _excerpt(base_tokens[base_span[0]:base_span[1] + 1], None),
                  _excerpt(head_tokens[head_span[0]:head_span[1] + 1], None), ", ".join(keys) or "none",
                  "; specifiers " + ", ".join(pairs) if pairs else "", site)
    return problems, warning


def run(base, head, explicit_ledgers=None, cwd=None):
    """(problems, warnings, summary)."""
    merge = l10nlib.merge_base(base, head, cwd)
    files = [p for p in l10nlib.changed_files(merge, head, ["src"], cwd) if p.endswith(".java")]
    before = l10nlib.read_files(merge, files, cwd)
    after = l10nlib.read_files(head, files, cwd)
    diffs = {}
    problems, warnings = [], []
    for path in files:
        try:
            diffs[path] = expressions.FileDiff(before[path], after[path])
        except l10nlib.LexError as error:
            problems.append("%s: %s" % (path, error))
    rows, row_problems = range_rows(merge, head, explicit_ledgers, cwd)
    problems.extend(row_problems)

    head_sources = [p for p in l10nlib.list_files(head, ["src"], cwd) if p.endswith(".java")]
    calls = call_index(l10nlib.read_files(head, head_sources, cwd)) if any(r.kind in CATALOG_KINDS for r in rows) else None

    claimed = {}  # (path, token) -> row
    staying = []  # (row, path, token) gone literals a stays-english or id-kept row says moved
    converted = []  # (path, first base token, row, where, head line ranges) for the call-site check
    manual = 0
    allowed_base = collections.defaultdict(dict)  # path -> {base token: span id} a row may replace or delete
    allowed_head = collections.defaultdict(dict)  # path -> {head token: span id} of the calls that serve rows
    manual_rows = set()
    removed_own = collections.defaultdict(set)  # path -> base tokens of removed rows' own statements
    removed_frames = collections.defaultdict(set)  # path -> headers and braces of blocks they deleted
    widened = []  # (row, where, path, own statement span, widened span) for removed rows past their statement
    format_rows = {}  # id(row) -> the base span a format row's call replaces
    for row in rows:
        if not row.path.startswith("src/"):
            continue
        where = "%s:%d" % (row.source, row.line)
        diff = diffs.get(row.path)
        if diff is None or diff.base is None:
            if row.kind in MOVING:
                problems.append("%s: %s did not change since the merge base, or did not exist there" % (where, row.path))
            continue
        tokens, is_manual, problem = locate(row, diff, claimed)
        if problem:
            problems.append("%s: %s" % (where, problem))
            continue
        format_span = None
        if row.kind == "format":
            problem, format_span, _ = format_site(row, diff, tokens)
            if problem:
                problems.append("%s: %s" % (where, problem))
                continue
            format_rows[id(row)] = format_span
        manual += 1 if is_manual else 0
        if is_manual:
            manual_rows.add(id(row))
        for k in tokens:
            if row.kind == "removed":
                first, last, terminator = statement_of(diff, k)
                if terminator is not None and diff.base_token(terminator).value == ";":
                    last = terminator
                own = range(first, last + 1)
                removed_own[row.path].update(own)
                first, last, frame = deleted_scope(diff, first, last)
                removed_frames[row.path].update(frame)
                span = range(first, last + 1)
                if len(span) > len(own) and not any(w[0] is row and w[4] == span for w in widened):
                    widened.append((row, where, row.path, own, span))
            elif row.kind == "format":
                # A String.format literal's row replaces the whole String.format(...) call.
                first, last = format_span
                span = range(first, last + 1)
            elif row.kind in CATALOG_KINDS and is_manual:
                first, last, _ = statement_of(diff, k)
                span = range(first, last + 1)
            elif row.kind in CATALOG_KINDS:
                first, last = diff.base.expression_of(k).span
                span = range(first, last + 1)
            else:
                continue
            for j in span:
                allowed_base[row.path].setdefault(j, id(row))
        if row.kind == "removed" and tokens:
            # Each statement the row deletes is listed, unless a whole-block warning already prints it;
            # a row may cover literals in several statements, only some of them inside a deleted block.
            shown = set()
            for k in sorted(tokens):
                first, last, _ = statement_of(diff, k)
                if (first, last) in shown or any(
                        w[2] == row.path and w[4][0] <= first and last <= w[4][-1] for w in widened):
                    continue
                shown.add((first, last))
                warnings.append("%s: removed row at %s:%d deletes `%s`; check by hand that no screen showed this "
                                "text" % (where, row.path, row.base_line, _excerpt(diff.base.tokens[first:last + 1], None)))
        if is_manual:
            warnings.append("%s: manual row at %s:%d; check its pieces, and the statement around its call, by hand: %s" % (
                where, row.path, row.base_line, json.dumps(row.pieces, ensure_ascii=False)))
        for hole in holes(row.pieces) if row.kind != "format" else ():
            if "as" in hole:
                warnings.append("%s: at %s:%d the hole %s is passed as %s; check by hand that it renders the same" % (
                    where, row.path, row.base_line, hole["hole"], hole["as"]))
        for k in tokens:
            tok = diff.base_token(k)
            if (row.path, k) in claimed:
                other = claimed[(row.path, k)]
                problems.append("%s: %s on line %d is already covered by %s:%d" % (
                    where, l10nlib.json_string(tok.value), tok.line, other.source, other.line))
                continue
            claimed[(row.path, k)] = row
            if row.kind in MOVING and k not in diff.gone:
                problems.append("%s: the row says %s (line %d) left, but it is still there at head" % (
                    where, l10nlib.json_string(tok.value), tok.line))
            elif row.kind == "removed" and added_in_hunk(diff, diff.gone[k]) is not None:
                replacement = added_in_hunk(diff, diff.gone[k])
                problems.append("%s: removed is for deleted code, but the change that removes %s puts %s in its "
                                "place (head line %d); deleting code only loses tokens, so record where the text "
                                "went instead" % (where, l10nlib.json_string(tok.value), replacement.raw,
                                                  replacement.line))
            elif row.kind == "removed" and not deleted_whole(diff, k):
                first, last, _ = statement_of(diff, k)
                deleted, _ = diff.changed_tokens()
                kept = [diff.base_token(j) for j in range(first, last + 1) if j not in deleted]
                problems.append("%s: removed is for deleted code, but only part of the statement holding %s (line "
                                "%d) was deleted; %s still runs at head. Delete the whole statement, or record "
                                "where the text went" % (where, l10nlib.json_string(tok.value), tok.line,
                                                        _excerpt(kept)))
            elif row.kind in STAYING and k in diff.gone:
                staying.append((row, row.path, k))
        if row.kind in CATALOG_KINDS and calls is not None:
            hunks = sorted(set(diff.gone[k] for k in tokens if k in diff.gone))
            if hunks:
                converted.append((row.path, min(tokens), row, where, [diff.hunks[h][2:] for h in hunks]))

    # A removed row whose statement went with its enclosing block (an if, a method) frees that whole
    # block. Every other statement deleted with it must hold a removed row's literal itself, unless
    # the row's note asks for the whole block; either way the reviewer sees the entire block.
    asked = [(path, span) for row, _, path, _, span in widened if row.note.startswith(WHOLE_BLOCK)]
    listed = set()
    for row, where, path, own, span in widened:
        tokens = diffs[path].base.tokens
        others = [j for j in span if j not in removed_own[path] and j not in removed_frames[path]]
        whole = any(p == path and s[0] <= span[0] and span[-1] <= s[-1] for p, s in asked)
        if others and not whole:
            first, last, _ = statement_of(diffs[path], others[0])
            problems.append("%s: removed row at %s:%d deletes the whole block around its statement (base lines "
                            "%d-%d), which also deletes `%s` (base line %d), code with no removed row of its own; "
                            "record that code, or start a note of a removed row in the block with \"%s\" to ask "
                            "for the whole block" % (
                                where, path, row.base_line, tokens[span[0]].line, tokens[span[-1]].line,
                                _excerpt(tokens[first:last + 1], None), tokens[others[0]].line, WHOLE_BLOCK))
        if (path, span[0], span[-1]) in listed:
            continue
        listed.add((path, span[0], span[-1]))
        warnings.append("%s: removed row at %s:%d deletes the whole block (base lines %d-%d)%s: `%s`; check by "
                        "hand that no screen showed this text and nothing in it set what a screen shows" % (
                            where, path, row.base_line, tokens[span[0]].line, tokens[span[-1]].line,
                            ", with other code, as a whole block: note asks" if others and whole else "",
                            _excerpt(tokens[span[0]:span[-1] + 1], None)))

    used, last, callers = set(), {}, None
    for path, _, row, where, ranges in sorted(converted, key=lambda c: (c[0], c[1])):
        problem, call = check_call_sites(row, calls, path, ranges, used, last.get(path, -1))
        if problem:
            problems.append("%s: %s" % (where, problem))
        else:
            used.add((call[0], call[2]))
            last[path] = call[2]
            if path in diffs and diffs[path].head is not None:
                head_tokens = diffs[path].head.tokens
                if id(row) in manual_rows:
                    # A manual row's base side is its whole statement, so the head side is too.
                    first, end, _ = expressions.statement_span(head_tokens, call[2])
                else:
                    first, end = call_span(head_tokens, call[2])
                for j in range(first, end + 1):
                    allowed_head[path].setdefault(j, id(row))
                if row.kind == "format":
                    if callers is None:
                        callers = test_callers(head, cwd)
                    found, warning = format_review(row, where, diffs[path], format_rows[id(row)], (first, end),
                                                   callers)
                    problems.extend(found)
                    warnings.append(warning)
                    # The text keys' calls it passes serve this row, not an exact row of the same key; the T
                    # calls inside a %s argument serve the exact rows of the literals they replaced.
                    if head_tokens[call[2] + 3].value == "(":
                        _, spans = _argument_spans(head_tokens, call[2] + 3)
                    else:  # Msg.of(T.KEY, ...) or T.render(T.KEY, ...): the template's args follow the id
                        _, spans = _argument_spans(head_tokens, call[2] - 1)
                        spans = spans[1:]
                    texts = sorted(item["arg"] for item in row.split if isinstance(item, dict) and "key" in item)
                    for arg in texts:
                        if arg <= len(spans):
                            used.add((path, spans[arg - 1][0]))

    arrivals = []  # [path, hunk, token, used] for every literal added to a changed src file
    for path in sorted(diffs):
        for k in sorted(diffs[path].added):
            arrivals.append([path, diffs[path].added[k], k, False])
    named = collections.defaultdict(list)  # (path, head token) of a constant -> [(row, path, base token)] naming it
    existing = set()  # constants named above that were already in src (not arrivals)
    head_lexed = {}

    def head_tokens(p):
        if p in diffs and diffs[p].head is not None:
            return diffs[p].head.tokens
        if p not in head_lexed:
            head_lexed[p] = []
            try:
                head_lexed[p] = l10nlib.lex_java(l10nlib.read_files(head, [p], cwd).get(p) or "")
            except l10nlib.LexError:
                pass
        return head_lexed[p]

    def sources():
        unchanged = [p for p in head_sources if p not in diffs and p not in head_lexed]
        if unchanged:
            for p, text in l10nlib.read_files(head, unchanged, cwd).items():
                head_lexed[p] = []
                try:
                    head_lexed[p] = l10nlib.lex_java(text or "")
                except l10nlib.LexError:
                    pass
        for p in head_sources:
            skip = set(diffs[p].added) if p in diffs and diffs[p].head is not None else set()
            yield p, head_tokens(p), skip
    for row, path, k in staying:
        tok = diffs[path].base_token(k)
        hunk = diffs[path].gone[k]
        here = (path, hunk)
        moved_whole = added_in_hunk(diffs[path], hunk) is None
        # A move lands outside the changed region it left (within one region it is a reorder, a swap).
        # The code it left names the constant it went to (several sites may share one constant), or
        # was moved out whole (one site per arrival).
        same = [n for n, a in enumerate(arrivals) if (a[0], a[1]) != here and
                (diffs[a[0]].head_token(a[2]).kind, diffs[a[0]].head_token(a[2]).value) == (tok.kind, tok.value)]
        chosen = next((n for n in same if arrivals[n][3] in (False, "named") and
                       _points_to(diffs[path], hunk, path, arrivals[n][0], arrivals[n][2], diffs[arrivals[n][0]])), None)
        if chosen is not None:
            arrivals[chosen][3] = "named"
            named[(arrivals[chosen][0], arrivals[chosen][2])].append((row, path, k))
            continue
        chosen = next((n for n in same if arrivals[n][3] is False), None) if moved_whole else None
        if chosen is not None:
            arrivals[chosen][3] = "whole"
            continue
        # A constant already in src that holds the same value (a later batch moving its data units to
        # B1's UnitCode), named by the code the literal left.
        if tok.kind == "string":
            found = next(((p, j) for p, j in existing_constants(sources(), tok.value)
                          if _references(diffs[path], hunk, path, p, constant_name(head_tokens(p), j))), None)
            if found is not None:
                existing.add(found)
                named[found].append((row, path, k))
                continue
        problems.append("%s:%d: %s row: %s left %s:%d and is not added anywhere else in src where the code it "
                        "left points (a constant those lines name, or code moved out whole), nor held by an "
                        "existing constant those lines name; text that leaves src needs exact, template, plural, "
                        "format or removed" % (
                            row.source, row.line, row.kind, l10nlib.json_string(tok.value), path, tok.line))
    problems_named, warnings_named = check_named_moves(named, head_tokens, diffs, existing)
    problems.extend(problems_named)
    warnings.extend(warnings_named)

    # A new lower-case key, path or id is not text (a preference name, a requireNonNull message); it is
    # allowed, and listed with its statement, since a person could still read one.
    # A new format-only literal ("%.1f", "%d") holds no words either; it is allowed and listed the same way.
    new_ids, new_formats = collections.defaultdict(set), collections.defaultdict(set)
    for path, _, k, used_by_move in arrivals:
        tok = diffs[path].head_token(k)
        if not used_by_move and tok.kind == "string" and l10nlib.is_identifier_like(tok.value):
            new_ids[path].add(k)
        elif not used_by_move and tok.kind == "string" and is_format_only(tok.value):
            new_formats[path].add(k)

    moved_hunks = set((path, diffs[path].gone[k]) for _, path, k in staying)
    moved_hunks.update((a[0], a[1]) for a in arrivals if a[3])
    for path in sorted(diffs):
        problem_list, warning_list = other_changes(path, diffs[path], allowed_base[path], allowed_head[path],
                                                   moved_hunks, new_ids[path] | new_formats[path])
        problems.extend(problem_list)
        warnings.extend(warning_list)

    gone_total = 0
    gone_files = set()
    for path in sorted(diffs):
        diff = diffs[path]
        for k in sorted(diff.gone):
            gone_total += 1
            gone_files.add(path)
            if (path, k) not in claimed:
                tok = diff.base_token(k)
                problems.append("%s:%d: %s %s left with no ledger row" % (
                    path, tok.line, "char" if tok.kind == "char" else "literal", l10nlib.json_string(tok.value)))

    new_literals = 0
    for found, what, check in ((new_ids, "key or id", "that no screen shows it"),
                               (new_formats, "format-only", "that the number it formats prints as before")):
        for path in sorted(found):
            head_tokens = diffs[path].head.tokens
            for k in sorted(found[path]):
                first, last, _ = expressions.statement_span(head_tokens, k)
                # A window centred on the literal, so a long statement never hides the code that uses it.
                start, end = max(first, k - 20), min(last, k + 20)
                warnings.append("%s:%d: new %s literal %s in `%s%s%s`; check by hand %s" % (
                    path, head_tokens[k].line, what, l10nlib.json_string(head_tokens[k].value),
                    "... " if start > first else "", _excerpt(head_tokens[start:end + 1], None),
                    " ..." if end < last else "", check))
    for path, _, k, used_by_move in arrivals:
        tok = diffs[path].head_token(k)
        if k in new_ids[path] or k in new_formats[path]:
            continue
        if not used_by_move and not (tok.kind == "string" and tok.value == ""):
            new_literals += 1
            problems.append("%s:%d: new %s %s; an extraction batch adds no text to src (put it in the catalog), and "
                            "a literal that moved needs a stays-english or id-kept row" % (
                                path, tok.line, "char" if tok.kind == "char" else "literal",
                                l10nlib.json_string(tok.value)))

    catalog_problems, catalog_rows = catalog_consistency(head, cwd)
    problems.extend(catalog_problems)
    summary = "%d literal(s) left %d src file(s); %d new ledger row(s)%s; %d new literal(s); " \
              "%d new key or id literal(s), listed; %s%d ledger row(s) agree with the catalog" % (
                  gone_total, len(gone_files), len(rows), " (%d manual)" % manual if manual else "",
                  new_literals, sum(len(v) for v in new_ids.values()),
                  "%d new format-only literal(s), listed; " % sum(len(v) for v in new_formats.values())
                  if any(new_formats.values()) else "", catalog_rows)
    return problems, warnings, summary


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--base", help="base revision, for example origin/main")
    parser.add_argument("--head", default="HEAD", help="head revision, or WORKTREE for uncommitted files")
    parser.add_argument("--ledger", nargs="*", help="ledger files (default: rows added since the merge base)")
    parser.add_argument("--catalog-only", action="store_true",
                        help="only check every ledger at head against the catalog (any pull request)")
    args = parser.parse_args(argv)
    head = l10nlib.resolve(args.head)
    if args.catalog_only:
        problems, count = catalog_consistency(head)
        warnings, summary = [], "%d ledger row(s) agree with the catalog" % count
    else:
        if not args.base:
            parser.error("--base is required")
        problems, warnings, summary = run(args.base, head, args.ledger)
    for warning in warnings:
        print("ledger_check: warning: " + warning, file=sys.stderr)
    for problem in problems:
        print("ledger_check: " + problem, file=sys.stderr)
    print("ledger_check: %s: %s" % ("FAILED" if problems else "PASS", summary))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
