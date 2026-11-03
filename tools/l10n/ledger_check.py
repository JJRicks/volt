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
  removed                  the code was deleted: the changed lines holding it only lose tokens, and
                           the whole statement holding it is gone (not just the literal and its +);
                           when its enclosing block (an if, a method) went too, every other
                           statement in that block needs a removed row of its own, or the row's note
                           starts "whole block:"; every removed row is listed for the reviewer, with
                           the whole deleted block when there is one
  stays-english, id-kept   the literal moved within src/: the same value is added elsewhere (outside
                           the changed lines it left, so a reorder or swap is not a move), and the
                           code it left either only lost tokens or names the constant it moved to

A row whose pieces cannot match a + chain (a ternary, String.format, a StringBuilder chain) has a
note starting "manual:"; its literals must then appear in order from its line, and it is listed
for the reviewer. When the line parses as an expression with the row's literals, a manual row must
still match it. A hole's "as" (the expression head passes) must be Fmt.<method>(..., <hole>, ...)
unless the row is manual; every "as" is listed for the reviewer. Every ledger at head (not only this batch's) is also checked against the catalog:
an exact row's English must still equal its literal and a template must still render the original,
unless l10n/english-changes.tsv records an owner-approved change of that key (its keys must exist).
Ledger files are append-only. An extraction batch adds no literal to src/ (other than ""): text goes
to the catalog, and a literal that moved needs a stays-english or id-kept row.

Nothing else may change around the text either. In a changed region of a src/ file that holds or held
a literal, the only differences from the base allowed are a row's expression replaced by the call
that serves it (with a manual row, anywhere in the statement holding its literals) and a removed
row's deleted statement: wrapping a kept literal or a new T call in .toUpperCase(), or dropping code
next to it, fails. Regions a stays-english or id-kept move touched, and every other src change, are
listed for the reviewer.

Ledger format: a TSV with the header

  location<TAB>pieces<TAB>key<TAB>kind<TAB>note

  location  base file:line of the expression's first literal, e.g. src/com/jjricks/volt/Ui.java:812
  pieces    JSON: the original expression as literal pieces and holes, in order, e.g.
              ["Move ", {"hole": "title", "arg": 1, "type": "s"}, " to slot ", {"hole": "n", "arg": 2, "type": "d"}]
            a hole is {"hole": <Java source at base>, "arg": <placeholder index>, "type": "s"|"d"|"f"},
            plus "quantity": true for a plural's quantity and "as": <Java source> when head passes a
            different expression for it; a char literal is {"char": "x"}. For kind plural, pieces
            is an object of English plural forms: {"one": [...], "other": [...]}.
  key       the resource key (exact, template, plural, id-kept), or - (stays-english, removed)
  kind      exact | template | plural | id-kept | stays-english | removed
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
KINDS = ("exact", "template", "plural", "id-kept", "stays-english", "removed")
CATALOG_KINDS = ("exact", "template", "plural")
MOVING = CATALOG_KINDS + ("removed",)
STAYING = ("id-kept", "stays-english")
NEEDS_KEY = ("exact", "template", "plural", "id-kept")
LOCATION = re.compile(r"^((?:src|tests|android-tests)/[^\t:]+\.java):([1-9][0-9]*)$")
LEDGER_DIR = "l10n/ledger"
CHANGES_PATH = "l10n/english-changes.tsv"
CHANGES_HEADER = ("key", "quantity", "old", "new", "note")
MANUAL_WINDOW = 30
TEST_DIRS = ("tests/", "android-tests/")

Row = collections.namedtuple("Row", "source line text path base_line pieces key kind note literals")


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
            if kind == "plural":
                if not isinstance(pieces, dict) or "other" not in pieces or \
                        any(q not in gen_catalog.QUANTITIES for q in pieces):
                    raise LedgerError("%s: plural pieces are {\"one\": [...], \"other\": [...]}" % where)
                for quantity, sequence in pieces.items():
                    _check_sequence(sequence, "%s[%s]" % (where, quantity))
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
                              key, kind, note, literal_counts(pieces)))
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
        if row.kind == "exact":
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
        problems.extend(check_changes(changes, catalog, set(r.key for r in rows if r.kind in CATALOG_KINDS)))
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
                free = not any(k in claimed for k in e.tokens)
                gone = all(k in diff.gone for k in e.tokens)
                return (free, gone == (row.kind in MOVING))
            best = max(candidates, key=score)
            return list(best.tokens), False, None
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


def _points_to(diff, hunk, row_path, arrival_path, arrival_index, arrival_diff):
    """True when the code a staying literal left names the constant it arrived in: the arrival is
    NAME = "..." and the region's head lines say NAME (same file) or Class.NAME."""
    tokens = arrival_diff.head.tokens
    if arrival_index < 2 or tokens[arrival_index - 1].value != "=" or tokens[arrival_index - 2].kind != "ident":
        return False
    name, owner = tokens[arrival_index - 2].value, _class_name(arrival_path)
    _, _, h1, h2 = diff.hunks[hunk]
    region = _region_tokens(diff.head, h1, h2)
    for k, tok in enumerate(region):
        if tok.kind != "ident" or tok.value != name:
            continue
        qualified = k >= 2 and region[k - 1].value == "." and region[k - 2].value == owner
        unqualified = (k == 0 or region[k - 1].value != ".") and arrival_path == row_path
        if qualified or unqualified:
            return True
    return False


def other_changes(path, diff, allowed_base, allowed_head, moved_hunks):
    """(problems, warnings) for code a batch changed besides converting or deleting text.

    allowed_base and allowed_head map token indexes to a span id: a row's base expression (or a
    manual row's statement) and the head call that serves it share one id, and a removed row's
    deleted code has an id with no head side. Each span counts as one item when the changed regions
    are aligned, so only tokens outside them can differ.
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
        lost, gained = [], []
        for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes():
            if tag != "equal":
                lost.extend(k for k in a_at[i1:i2] if k is not None)
                gained.extend(k for k in b_at[j1:j2] if k is not None)
        if not lost and not gained:
            continue
        parts = []
        if gained:
            parts.append("adds `%s`" % _excerpt([diff.head_token(k) for k in gained]))
        if lost:
            parts.append("drops `%s`" % _excerpt([diff.base_token(k) for k in lost]))
        line = diff.head_token(gained[0]).line if gained else diff.base_token(lost[0]).line
        what = "%s:%d: this change %s" % (path, line, " and ".join(parts))
        text = any(t.kind in expressions.LITERALS for t in _region_tokens(diff.base, b1, b2)) or \
            any(t.kind in expressions.LITERALS for t in _region_tokens(diff.head, h1, h2))
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
        if row.kind == "removed" and tokens and not any(w[0] is row for w in widened):
            first, last, _ = statement_of(diff, min(tokens))
            warnings.append("%s: removed row at %s:%d deletes `%s`; check by hand that no screen showed this text" % (
                where, row.path, row.base_line, _excerpt(diff.base.tokens[first:last + 1], None)))
        if is_manual:
            warnings.append("%s: manual row at %s:%d; check its pieces, and the statement around its call, by hand: %s" % (
                where, row.path, row.base_line, json.dumps(row.pieces, ensure_ascii=False)))
        for hole in holes(row.pieces):
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

    used, last = set(), {}
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

    arrivals = []  # [path, hunk, token, used] for every literal added to a changed src file
    for path in sorted(diffs):
        for k in sorted(diffs[path].added):
            arrivals.append([path, diffs[path].added[k], k, False])
    for row, path, k in staying:
        tok = diffs[path].base_token(k)
        hunk = diffs[path].gone[k]
        here = (path, hunk)
        moved_whole = added_in_hunk(diffs[path], hunk) is None
        for arrival in arrivals:
            other = diffs[arrival[0]].head_token(arrival[2])
            # A move lands outside the changed region it left (within one region it is a reorder, a
            # swap), and the code it left was moved out whole or names the constant it went to.
            if not arrival[3] and (other.kind, other.value) == (tok.kind, tok.value) and \
                    (arrival[0], arrival[1]) != here and \
                    (moved_whole or _points_to(diffs[path], hunk, path, arrival[0], arrival[2], diffs[arrival[0]])):
                arrival[3] = True
                break
        else:
            problems.append("%s:%d: %s row: %s left %s:%d and is not added anywhere else in src where the code it "
                            "left points (a constant those lines name, or code moved out whole); text that leaves "
                            "src needs exact, template, plural or removed" % (
                                row.source, row.line, row.kind, l10nlib.json_string(tok.value), path, tok.line))

    moved_hunks = set((path, diffs[path].gone[k]) for _, path, k in staying)
    moved_hunks.update((a[0], a[1]) for a in arrivals if a[3])
    for path in sorted(diffs):
        problem_list, warning_list = other_changes(path, diffs[path], allowed_base[path], allowed_head[path],
                                                   moved_hunks)
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
    for path, _, k, used_by_move in arrivals:
        tok = diffs[path].head_token(k)
        if not used_by_move and not (tok.kind == "string" and tok.value == ""):
            new_literals += 1
            problems.append("%s:%d: new %s %s; an extraction batch adds no text to src (put it in the catalog), and "
                            "a literal that moved needs a stays-english or id-kept row" % (
                                path, tok.line, "char" if tok.kind == "char" else "literal",
                                l10nlib.json_string(tok.value)))

    catalog_problems, catalog_rows = catalog_consistency(head, cwd)
    problems.extend(catalog_problems)
    summary = "%d literal(s) left %d src file(s); %d new ledger row(s)%s; %d new literal(s); " \
              "%d ledger row(s) agree with the catalog" % (
                  gone_total, len(gone_files), len(rows), " (%d manual)" % manual if manual else "",
                  new_literals, catalog_rows)
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
