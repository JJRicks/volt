#!/usr/bin/env python3
"""G5, characterization tests first: the tests that pin a batch's English output are committed
before the change, pass on the base, and stay untouched afterwards.

Characterization tests live under tests/l10n/characterization/ (other tests/l10n files, such as a
batch's .sources list or B0b's catalog tests, are not characterization tests). For the commits on
head's first-parent line after the merge base:
  1. every characterization file the range adds or changes must be added or changed by the first
     commit, and by no later commit;
  2. that first commit changes nothing under src/ or res/, so the tests ran against base behaviour;
  3. characterization is required when the range adds a template, plural, format or choice ledger row (or with
     --require): such a batch fails when its first commit holds no characterization tests.
A range with no characterization files and no template, plural, format or choice rows passes with a note.

  char_first.py --base origin/main [--head <sha>] [--require] [--print-first]
  char_first.py --base origin/main [--head <sha>] --listing-additions

In CI on a pull request, pass the PR head commit (not the merge commit) as --head.

--listing-additions (for prove-english.sh, owner-approved Nov 13 2026) answers one question and is not a
gate: does the range only ADD rows to existing listings? A listing is a .tsv data file under
tests/l10n/characterization/ that an unchanged characterization test compares with what the code
prints, line for line (dashboard-model-english.tsv, controls-english.tsv). A row for an entry that is new
in the same pull request cannot pass at the first commit, where the code does not have the entry yet.
Exit 0 and one line of detail when every characterization file the range changes is such a listing,
present at the merge base, at the first commit and at head, and every line it had at the merge base is
still there byte for byte and in order, with at least one line added, the same at the first commit and at
head, and an active entry of a tests/l10n/*.sources list (as tests/run.sh reads them) names a test that
reads it at head. Exit 1 and the reason otherwise: a .java test (code, which decides what is checked), a
new file, a changed, removed or reordered row, or a listing no listed test reads still gets the first-commit
run.
"""

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l10nlib  # noqa: E402
import ledger_check  # noqa: E402

CHAR_DIR = "tests/l10n/characterization/"


def run(base, head, require=False, cwd=None):
    """(problems, notes, first_commit or None, characterization files)."""
    merge = l10nlib.merge_base(base, head, cwd)
    commits = l10nlib.first_parent_commits(merge, head, cwd)
    if not commits:
        return [], ["no commits after the merge base"], None, []
    rows, row_problems = ledger_check.range_rows(merge, head, None, cwd)
    templated = [r for r in rows if r.kind in ("template", "plural", "format", "choice")]
    if templated:
        require = True
    in_range = l10nlib.changed_files(merge, head, [CHAR_DIR], cwd)
    first = commits[0]
    changed = l10nlib.changed_files(first + "^", first, ["."], cwd)
    characterization = [p for p in changed if p.startswith(CHAR_DIR)]
    problems, notes = list(row_problems), []
    if not in_range:
        message = "no characterization tests under %s%s" % (
            CHAR_DIR, " but the batch adds %d template, plural or format ledger row(s)" % len(templated) if templated else "")
        (problems if require else notes).append(message)
        return problems, notes, first, []
    for path in in_range:
        if path not in characterization:
            problems.append("%s is a characterization test but the first commit %s does not add it; "
                            "characterization tests come first" % (path, l10nlib.short(first)))
    if not characterization:
        return problems, notes, first, []
    behaviour = [p for p in changed if p.startswith(("src/", "res/"))]
    for path in behaviour:
        problems.append("the first commit %s also changes %s; characterization commits change tests only"
                        % (l10nlib.short(first), path))
    later = set(l10nlib.changed_files(first, head, characterization, cwd)) if len(commits) > 1 else set()
    for path in sorted(later):
        problems.append("%s was added by the first commit %s and changed later" % (path, l10nlib.short(first)))
    notes.append("%d characterization file(s) in %s, untouched by %d later commit(s)" % (
        len(characterization), l10nlib.short(first), len(commits) - 1))
    return problems, notes, first, characterization


LISTING_SUFFIX = ".tsv"
SOURCES_GLOB = "tests/l10n/*.sources"


def _lines(text):
    """Lines with their endings, so that a changed ending counts as a changed line."""
    return text.splitlines(True)


def _kept_in_order(old, new):
    """True when every line of old is in new, unchanged and in the same order (new may add lines anywhere)."""
    at = 0
    for line in new:
        if at < len(old) and line == old[at]:
            at += 1
    return at == len(old)


def _active_entries(rev, cwd=None):
    """The active entries of the tests/l10n/*.sources lists at rev, read as tests/run.sh reads them: from "#"
    on is a comment, the rest is trimmed, blank lines are skipped."""
    lists = [p for p in l10nlib.list_files(rev, ["tests/l10n"], cwd)
             if p.endswith(".sources") and p.count("/") == 2]
    entries = []
    for text in l10nlib.read_files(rev, lists, cwd).values():
        for line in (text or "").splitlines():
            entry = line.split("#", 1)[0].strip()
            if entry:
                entries.append(entry)
    return entries


def listing_additions(base, head, cwd=None):
    """(True, detail) when the range only adds rows to existing characterization listings, else (False, reason)."""
    merge = l10nlib.merge_base(base, head, cwd)
    commits = l10nlib.first_parent_commits(merge, head, cwd)
    if not commits:
        return False, "no commits after the merge base"
    first = commits[0]
    paths = l10nlib.changed_files(merge, head, [CHAR_DIR], cwd)
    if not paths:
        return False, "no characterization files changed"
    others = [p for p in paths if not p.endswith(LISTING_SUFFIX)]
    if others:
        return False, "not only listings (%s): %s" % (LISTING_SUFFIX, ", ".join(others))
    at_base = l10nlib.read_files(merge, paths, cwd)
    at_first = l10nlib.read_files(first, paths, cwd)
    at_head = l10nlib.read_files(head, paths, cwd)
    entries = _active_entries(head, cwd)
    readers = l10nlib.read_files(head, [e for e in entries if e.startswith(CHAR_DIR) and e.endswith(".java")], cwd)
    kept = added = 0
    for path in paths:
        if at_base[path] is None:
            return False, "%s is a new file, not an existing listing" % path
        if at_head[path] is None or at_first[path] is None:
            return False, "%s is removed" % path
        if at_first[path] != at_head[path]:
            return False, "%s differs between the first commit and head" % path
        old, new = _lines(at_base[path]), _lines(at_head[path])
        if len(new) <= len(old) or not _kept_in_order(old, new):
            return False, "%s changes, removes or reorders a line it had at the merge base" % path
        name = os.path.basename(path)
        if not any(text and name in text for text in readers.values()):
            return False, "no active characterization test in a tests/l10n/*.sources list reads %s" % path
        kept += len(old)
        added += len(new) - len(old)
    return True, ("the first commit %s only adds rows to %s; existing rows unchanged (%d lines), new rows (%d) "
                  "are proven at head (G6)" % (l10nlib.short(first), ", ".join(paths), kept, added))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--base", required=True, help="base revision, for example origin/main")
    parser.add_argument("--head", default="HEAD", help="head commit (a PR's head, not a merge commit)")
    parser.add_argument("--require", action="store_true", help="fail when the first commit has no characterization tests")
    parser.add_argument("--print-first", action="store_true", help="print only the first commit's sha when it has tests")
    parser.add_argument("--listing-additions", action="store_true",
                        help="exit 0 and print the detail when the range only adds rows to existing listings")
    args = parser.parse_args(argv)
    head = l10nlib.resolve(args.head)
    if head == l10nlib.WORKTREE:
        parser.error("char_first.py works on commits; commit first")
    if args.listing_additions:
        only_additions, detail = listing_additions(args.base, head)
        print(detail)
        return 0 if only_additions else 1
    problems, notes, first, files = run(args.base, head, args.require)
    if args.print_first:
        if first and files and not problems:
            print(first)
        return 1 if problems else 0
    for note in notes:
        print("char_first: note: " + note, file=sys.stderr)
    for problem in problems:
        print("char_first: " + problem, file=sys.stderr)
    print("char_first: %s: %s" % ("FAILED" if problems else "PASS",
                                  "%d characterization file(s)" % len(files) if files else "no characterization commit"))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
