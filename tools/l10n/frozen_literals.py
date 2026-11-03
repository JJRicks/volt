#!/usr/bin/env python3
"""G4, frozen test literals: every test statement that holds English stays exactly as it was; new
statements may be added.

The existing assertions are the reference for "English did not change", so an extraction PR must
not edit them or switch them to R.string or T lookups (a resource compared with itself proves
nothing). Freezing whole statements, not just literals, means an assertion cannot be weakened
(`|| true`), have its expected text changed while a decoy keeps the old literal elsewhere, or swap
two expectations.

A statement is the run of tokens between `;`, `{` and `}`, compared token for token (spacing and
comments do not matter; a class qualifier such as `TextFit.` in front of a call is ignored, so a
helper can move to a shared class). Statements are compared across all changed files in tests/ and
android-tests/ together, so moving a test helper between files is fine (`--per-file` forbids it).

A statement may disappear only when the test of deleted code is deleted: a new ledger row of kind
"removed" whose location is that test file and a line of the statement, holding its English
literals. The deletion must be a deletion: the changed region adds no English-looking literal, and
the removed English does not reappear in a new test statement.

English-looking: a literal with two letters in a row (or any non-ASCII letter) that is not a
lower-case key, path or id such as "app_settings" or "com.jjricks.volt". Java files only.

  frozen_literals.py --base origin/main [--head HEAD|WORKTREE] [--ledger FILE ...] [--per-file] [--report]
"""

import argparse
import collections
import difflib
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l10nlib  # noqa: E402
import ledger_check  # noqa: E402

TEST_DIRS = ("tests", "android-tests")
SEPARATORS = (";", "{", "}")

Statement = collections.namedtuple("Statement", "key first_line last_line english strings")


def _canonical(tokens):
    out, i = [], 0
    while i < len(tokens):
        t = tokens[i]
        # Drop class qualifiers (Upper.member and this.member) so a helper can move between classes.
        if t.kind == "ident" and (t.value[:1].isupper() or t.value == "this") and i + 2 < len(tokens) and \
                tokens[i + 1].value == "." and tokens[i + 2].kind == "ident" and \
                (i == 0 or tokens[i - 1].value != "."):
            i += 2
            continue
        out.append((t.kind, t.value) if t.kind in ("string", "char") else (t.kind, t.raw))
        i += 1
    return tuple(out)


def statements(text):
    """Statements holding at least one English-looking string literal."""
    if text is None:
        return []
    tokens = l10nlib.lex_java(text)
    result, current = [], []
    for tok in tokens + [None]:
        if tok is None or (tok.kind == "op" and tok.value in SEPARATORS):
            english = [t.value for t in current if t.kind == "string" and l10nlib.is_english_looking(t.value)]
            if english:
                last = current[-1]
                result.append(Statement(_canonical(current), current[0].line, last.line + last.raw.count("\n"),
                                        tuple(english), tuple(t.value for t in current if t.kind == "string")))
            current = []
        else:
            current.append(tok)
    return result


def english_literals(text):
    if text is None:
        return []
    return [(line, value) for line, value in l10nlib.string_literals(text) if l10nlib.is_english_looking(value)]


def _changed_regions(before, after):
    """[(base first line, base last line, head first line, head last line)] of the changed lines."""
    a = before.split("\n") if before is not None else []
    b = after.split("\n") if after is not None else []
    return [(i1 + 1, i2, j1 + 1, j2) for tag, i1, i2, j1, j2 in
            difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes() if tag != "equal"]


def run(base, head, explicit_ledgers=None, per_file=False, cwd=None):
    """(problems, summary)."""
    merge = l10nlib.merge_base(base, head, cwd)
    files = [p for p in l10nlib.changed_files(merge, head, list(TEST_DIRS), cwd) if p.endswith(".java")]
    before = l10nlib.read_files(merge, files, cwd)
    after = l10nlib.read_files(head, files, cwd)
    problems = []
    old, new = collections.Counter(), collections.Counter()
    old_where = collections.defaultdict(list)
    head_statements = {}
    for path in files:
        try:
            for s in statements(before[path]):
                key = (path, s.key) if per_file else s.key
                old[key] += 1
                old_where[key].append((path, s))
            head_statements[path] = statements(after[path])
        except l10nlib.LexError as error:
            problems.append("%s: %s" % (path, error))
            head_statements[path] = []
        for s in head_statements[path]:
            new[(path, s.key) if per_file else s.key] += 1
    added_english = collections.Counter()
    for (key, count) in (new - old).items():
        statement_key = key[1] if per_file else key
        for kind, value in statement_key:
            if kind == "string" and l10nlib.is_english_looking(value):
                added_english[value] += count

    rows, row_problems = ledger_check.range_rows(merge, head, explicit_ledgers, cwd)
    problems.extend(row_problems)
    removals = [r for r in rows if r.kind == "removed" and r.path.startswith(tuple(d + "/" for d in TEST_DIRS))]
    used_rows = set()
    removed = old - new
    recorded = 0
    for key, count in sorted(removed.items(), key=lambda kv: str(kv[0])):
        occurrences = old_where[key]
        matched = []
        for path, statement in occurrences:
            if len(matched) == count:
                break
            for r in removals:
                if id(r) not in used_rows and r.path == path and \
                        statement.first_line <= r.base_line <= statement.last_line and \
                        not (r.literals - collections.Counter(statement.strings)):
                    used_rows.add(id(r))
                    matched.append((path, statement, r))
                    break
        unmatched = [o for o in occurrences if not any(o[1] is m[1] for m in matched)][:count - len(matched)]
        for path, statement in unmatched:
            problems.append("%s:%d: a test statement with %s was changed or removed; test assertions are frozen "
                            "(a deleted test of deleted code needs a removed ledger row)" % (
                                path, statement.first_line, ", ".join(l10nlib.json_string(v) for v in statement.english)))
        for path, statement, row in matched:
            where = "%s:%d" % (row.source, row.line)
            region_english = []
            for b1, b2, h1, h2 in _changed_regions(before[path], after[path]):
                if b1 <= statement.last_line and statement.first_line <= b2 and after[path] is not None:
                    region_english.extend(v for line, v in english_literals(after[path]) if h1 <= line <= h2)
            back = [v for v in statement.english if added_english[v] > 0]
            if region_english:
                problems.append("%s: removed is for a deleted test, but the change at %s:%d puts %s in its place" % (
                    where, path, statement.first_line, l10nlib.json_string(region_english[0])))
            elif back:
                problems.append("%s: removed is for a deleted test, but %s comes back in a new test statement; "
                                "a changed assertion is not a removal" % (where, l10nlib.json_string(back[0])))
            else:
                recorded += 1
    for r in removals:
        if id(r) not in used_rows:
            problems.append("%s:%d: no frozen test statement on line %d of %s was removed; the row covers nothing" % (
                r.source, r.line, r.base_line, r.path))
    added = sum((new - old).values())
    summary = "%d changed test file(s); %d English statement(s) kept, %d added, %d removed (%d recorded in the ledger)" % (
        len(files), sum((old & new).values()), added, sum(removed.values()), recorded)
    return problems, summary


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--base", required=True, help="base revision, for example origin/main")
    parser.add_argument("--head", default="HEAD", help="head revision, or WORKTREE for uncommitted files")
    parser.add_argument("--ledger", nargs="*", help="ledger files (default: rows added since the merge base)")
    parser.add_argument("--per-file", action="store_true", help="also forbid moving a statement between files")
    parser.add_argument("--report", action="store_true",
                        help="list changes but never fail (an owner-approved English change, plan part D)")
    args = parser.parse_args(argv)
    problems, summary = run(args.base, l10nlib.resolve(args.head), args.ledger, args.per_file)
    for problem in problems:
        print("frozen_literals: %s%s" % ("report: " if args.report else "", problem), file=sys.stderr)
    status = "FAILED" if problems and not args.report else "PASS" if not problems else "REPORT"
    print("frozen_literals: %s: %s" % (status, summary))
    return 1 if problems and not args.report else 0


if __name__ == "__main__":
    sys.exit(main())
