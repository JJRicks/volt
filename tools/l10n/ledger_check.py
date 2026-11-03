#!/usr/bin/env python3
"""G1, the ledger of moved literals: no string literal may leave src/ without a ledger row.

Lexes the Java string literals of every src/ file that changed between the merge base and head,
at both ends. Every literal that disappears must be covered by a row in the batch's ledger,
l10n/ledger/<batch>.tsv (format below and in l10n/README.md). Rows are also checked against the
catalog: exact and template keys must exist, and exact text must equal the literal.

Ledger format: a TSV with the header

  location<TAB>pieces<TAB>key<TAB>kind<TAB>note

  location  base file:line of the original expression, e.g. src/com/jjricks/volt/Ui.java:812
  pieces    JSON: the original expression as literal pieces and holes, in order, e.g.
              ["Move ", {"hole": "title", "arg": 1, "type": "s"}, " to slot ", {"hole": "n", "arg": 2, "type": "d"}]
            a hole is {"hole": <Java source>, "arg": <placeholder index>, "type": "s"|"d"|"f"}, plus
            "quantity": true for a plural's quantity; a char literal is {"char": "x"}. For kind plural,
            pieces is an object of English plural forms: {"one": [...], "other": [...]}.
  key       the resource key (exact, template, plural, id-kept), or - (stays-english, removed)
  kind      exact | template | plural | id-kept | stays-english | removed
  note      free text, no tabs

Usage: ledger_check.py --base <rev> [--head <rev>|WORKTREE] [--ledger FILE ...]
By default the ledger files are those added or changed between the merge base and head.
"""

import argparse
import collections
import json
import os
import re
import shutil
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l10nlib  # noqa: E402
import gen_catalog  # noqa: E402

HEADER = ("location", "pieces", "key", "kind", "note")
KINDS = ("exact", "template", "plural", "id-kept", "stays-english", "removed")
MOVING = ("exact", "template", "plural", "removed")
NEEDS_KEY = ("exact", "template", "plural", "id-kept")
LOCATION = re.compile(r"^((?:src|tests|android-tests)/[^\t:]+\.java):([1-9][0-9]*)$")

Row = collections.namedtuple("Row", "source line path base_line pieces key kind note literals")


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
        extra = set(piece) - {"hole", "arg", "type", "quantity"}
        if extra:
            raise LedgerError("%s: unknown hole field(s) %s" % (where, ", ".join(sorted(extra))))


def literal_counts(pieces):
    """The string literals an expression consumed, as a Counter.

    For plural forms a literal shared by several forms counts once (the per-value maximum).
    """
    if isinstance(pieces, dict):
        total = collections.Counter()
        for sequence in pieces.values():
            for value, count in collections.Counter(p for p in sequence if isinstance(p, str)).items():
                total[value] = max(total[value], count)
        return total
    return collections.Counter(p for p in pieces if isinstance(p, str))


def parse_ledger(text, source="ledger"):
    """Rows of one ledger file. Raises LedgerError with every problem."""
    try:
        _, rows = l10nlib.read_tsv(text, HEADER)
    except ValueError as error:
        raise LedgerError("%s: %s" % (source, error))
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
            result.append(Row(source, line, m.group(1), int(m.group(2)), pieces, key, kind, note,
                              literal_counts(pieces)))
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


def ledger_texts(base, head, explicit, cwd=None):
    if explicit:
        return [(p, l10nlib.read_text(p)) for p in explicit]
    names = [p for p in l10nlib.changed_files(base, head, ["l10n/ledger"], cwd) if p.endswith(".tsv")]
    texts = l10nlib.read_files(head, names, cwd)
    return [(p, texts[p]) for p in names if texts[p] is not None]


def literal_counter(text):
    if text is None:
        return collections.Counter()
    return collections.Counter(value for _, value in l10nlib.string_literals(text))


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


def check_against_catalog(rows, catalog):
    problems = []
    for row in rows:
        if row.kind not in ("exact", "template", "plural"):
            continue
        where = "%s:%d" % (row.source, row.line)
        entry = catalog.get(row.key)
        want = "plural" if row.kind == "plural" else "string"
        if entry is None:
            problems.append("%s: key %s is not in res/values/strings*.xml" % (where, row.key))
            continue
        if entry.kind != want:
            problems.append("%s: key %s is a %s, but the row's kind is %s" % (where, row.key, entry.kind, row.kind))
            continue
        if row.kind == "exact" and entry.items["other"].text != row.pieces[0]:
            problems.append("%s: exact row: %s is %r but the literal was %r" % (
                where, row.key, entry.items["other"].text, row.pieces[0]))
        if row.kind == "template":
            rendered = render_simple(entry.items["other"].text, row.pieces)
            if rendered is not None and rendered[0] != rendered[1]:
                problems.append("%s: template %s renders %r with sentinels, the original %r" % (
                    where, row.key, rendered[0], rendered[1]))
    return problems


SIMPLE_PLACEHOLDER = re.compile(r"%(\d+)\$s|%%")


def render_simple(template, pieces):
    """(template rendered, original rendered) with sentinel strings, when every hole is a plain %n$s.

    None when the template uses other conversions (tests/l10n/LedgerTest covers those with Java's formatter).
    """
    holes = [p for p in pieces if isinstance(p, dict) and "hole" in p]
    if any(h["type"] != "s" for h in holes) or re.search(r"%(?!(\d+\$s|%))", template):
        return None
    sentinel = {h["arg"]: chr(1) + chr(64 + h["arg"]) for h in holes}

    def substitute(m):
        if m.group(0) == "%%":
            return "%"
        return sentinel.get(int(m.group(1)), m.group(0))
    original = "".join(p if isinstance(p, str) else p["char"] if "char" in p else sentinel[p["arg"]] for p in pieces)
    return SIMPLE_PLACEHOLDER.sub(substitute, template), original


def run(base, head, explicit_ledgers=None, cwd=None):
    """(problems, warnings, summary)."""
    merge = l10nlib.merge_base(base, head, cwd)
    files = [p for p in l10nlib.changed_files(merge, head, ["src"], cwd) if p.endswith(".java")]
    before = l10nlib.read_files(merge, files, cwd)
    after = l10nlib.read_files(head, files, cwd)
    removed = {}
    for path in files:
        gone = literal_counter(before[path]) - literal_counter(after[path])
        if gone:
            removed[path] = gone
    rows, problems = load_rows(ledger_texts(merge, head, explicit_ledgers, cwd))
    warnings = []
    covered = collections.defaultdict(collections.Counter)
    for row in rows:
        if not row.path.startswith("src/"):
            continue
        where = "%s:%d" % (row.source, row.line)
        text = before.get(row.path)
        if text is None:
            if row.path not in files:
                warnings.append("%s: %s did not change in this range" % (where, row.path))
            continue
        on_line = set(v for line, v in l10nlib.string_literals(text) if line == row.base_line)
        if row.literals and not on_line & set(row.literals):
            problems.append("%s: none of the row's literals is on line %d of %s at the merge base" % (
                where, row.base_line, row.path))
        if row.kind in MOVING:
            covered[row.path].update(row.literals)
    for path in sorted(removed):
        for value, count in sorted(removed[path].items()):
            missing = count - covered[path][value]
            if missing > 0:
                lines = [str(line) for line, v in l10nlib.string_literals(before[path]) if v == value]
                problems.append("%s: %d occurrence(s) of %s left with no ledger row (base line %s)" % (
                    path, missing, l10nlib.json_string(value), ", ".join(lines)))
    for path in sorted(covered):
        for value, count in sorted(covered[path].items()):
            extra = count - removed.get(path, collections.Counter())[value]
            if extra > 0:
                warnings.append("%s: the ledger moves %s %d more time(s) than it left the file" % (
                    path, l10nlib.json_string(value), extra))
    if rows:
        try:
            problems.extend(check_against_catalog(rows, _catalog_at(head, cwd)))
        except gen_catalog.CatalogError as error:
            problems.extend("catalog: " + p for p in error.problems)
    total = sum(sum(c.values()) for c in removed.values())
    summary = "%d literal(s) left %d src file(s); %d ledger row(s)" % (total, len(removed), len(rows))
    return problems, warnings, summary


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--base", required=True, help="base revision, for example origin/main")
    parser.add_argument("--head", default="HEAD", help="head revision, or WORKTREE for uncommitted files")
    parser.add_argument("--ledger", nargs="*", help="ledger files (default: those changed since the merge base)")
    args = parser.parse_args(argv)
    head = l10nlib.resolve(args.head)
    problems, warnings, summary = run(args.base, head, args.ledger)
    for warning in warnings:
        print("ledger_check: warning: " + warning, file=sys.stderr)
    for problem in problems:
        print("ledger_check: " + problem, file=sys.stderr)
    print("ledger_check: %s: %s" % ("FAILED" if problems else "PASS", summary))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
