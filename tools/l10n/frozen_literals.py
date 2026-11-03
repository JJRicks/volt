#!/usr/bin/env python3
"""G4, frozen test literals: English-looking literals in tests/ and android-tests/ may be added, never
changed or removed, between the merge base and head.

The existing assertions are the reference for "English did not change", so an extraction PR must not
edit them or switch them to R.string or T lookups (a resource compared with itself proves nothing).
Literals are compared across all changed test files together, so moving a helper from one test file
to another is fine. A removal is allowed only for a test of deleted code, recorded in the batch
ledger as a row of kind "removed" whose location is the test file (see ledger_check.py).

English-looking: a literal with two letters in a row (or any non-ASCII letter) that is not a
lower-case key, path or id such as "app_settings" or "com.jjricks.volt". Java files only.

  frozen_literals.py --base origin/main [--head HEAD|WORKTREE] [--ledger FILE ...] [--per-file]
"""

import argparse
import collections
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l10nlib  # noqa: E402
import ledger_check  # noqa: E402

TEST_DIRS = ("tests", "android-tests")


def english_literals(text):
    if text is None:
        return []
    return [(line, value) for line, value in l10nlib.string_literals(text) if l10nlib.is_english_looking(value)]


def run(base, head, explicit_ledgers=None, per_file=False, cwd=None):
    """(problems, summary)."""
    merge = l10nlib.merge_base(base, head, cwd)
    files = [p for p in l10nlib.changed_files(merge, head, list(TEST_DIRS), cwd) if p.endswith(".java")]
    before = l10nlib.read_files(merge, files, cwd)
    after = l10nlib.read_files(head, files, cwd)
    where = collections.defaultdict(list)
    old, new = collections.Counter(), collections.Counter()
    for path in files:
        for line, value in english_literals(before[path]):
            key = (path, value) if per_file else value
            old[key] += 1
            where[key].append("%s:%d" % (path, line))
        for _, value in english_literals(after[path]):
            new[(path, value) if per_file else value] += 1
    rows, problems = ledger_check.load_rows(ledger_check.ledger_texts(merge, head, explicit_ledgers, cwd))
    allowed = collections.Counter()
    for row in rows:
        if row.kind == "removed" and row.path.startswith(TEST_DIRS):
            for value, count in row.literals.items():
                allowed[(row.path, value) if per_file else value] += count
    removed = old - new
    for key, count in sorted(removed.items(), key=lambda kv: str(kv[0])):
        missing = count - allowed[key]
        if missing > 0:
            value = key[1] if per_file else key
            problems.append("%s removed or changed %d time(s) (base: %s); test literals are frozen" % (
                l10nlib.json_string(value), missing, ", ".join(where[key][:4]) + (" ..." if len(where[key]) > 4 else "")))
    added = sum((new - old).values())
    summary = "%d changed test file(s); %d English literal(s) added, %d removed (%d recorded in the ledger)" % (
        len(files), added, sum(removed.values()), sum(min(c, allowed[k]) for k, c in removed.items()))
    return problems, summary


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--base", required=True, help="base revision, for example origin/main")
    parser.add_argument("--head", default="HEAD", help="head revision, or WORKTREE for uncommitted files")
    parser.add_argument("--ledger", nargs="*", help="ledger files (default: those changed since the merge base)")
    parser.add_argument("--per-file", action="store_true", help="also forbid moving a literal between files")
    args = parser.parse_args(argv)
    problems, summary = run(args.base, l10nlib.resolve(args.head), args.ledger, args.per_file)
    for problem in problems:
        print("frozen_literals: " + problem, file=sys.stderr)
    print("frozen_literals: %s: %s" % ("FAILED" if problems else "PASS", summary))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
