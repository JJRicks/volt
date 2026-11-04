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
A range with no characterization files and no template, plural or format rows passes with a note.

  char_first.py --base origin/main [--head <sha>] [--require] [--print-first]

In CI on a pull request, pass the PR head commit (not the merge commit) as --head.
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


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--base", required=True, help="base revision, for example origin/main")
    parser.add_argument("--head", default="HEAD", help="head commit (a PR's head, not a merge commit)")
    parser.add_argument("--require", action="store_true", help="fail when the first commit has no characterization tests")
    parser.add_argument("--print-first", action="store_true", help="print only the first commit's sha when it has tests")
    args = parser.parse_args(argv)
    head = l10nlib.resolve(args.head)
    if head == l10nlib.WORKTREE:
        parser.error("char_first.py works on commits; commit first")
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
