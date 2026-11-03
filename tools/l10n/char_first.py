#!/usr/bin/env python3
"""G5, characterization tests first: tests a PR adds under tests/l10n/ in its first commit stay
untouched by every later commit.

For a template or pure-output change, the PR's first commit adds characterization tests under
tests/l10n/ that pass on the base. This script finds the first commit on head's first-parent line
after the merge base and checks:
  1. every tests/l10n/ file that commit adds or changes is identical at head (later commits left it alone);
  2. that commit changes nothing under src/ or res/, so the tests ran against base behaviour.
A PR whose first commit has no tests/l10n/ files passes with a note, unless --require is given.

  char_first.py --base origin/main [--head <sha>] [--require] [--print-first]

In CI on a pull request, pass the PR head commit (not the merge commit) as --head.
"""

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l10nlib  # noqa: E402

CHAR_DIR = "tests/l10n/"


def run(base, head, require=False, cwd=None):
    """(problems, notes, first_commit or None, characterization files)."""
    merge = l10nlib.merge_base(base, head, cwd)
    commits = l10nlib.first_parent_commits(merge, head, cwd)
    if not commits:
        return [], ["no commits after the merge base"], None, []
    first = commits[0]
    changed = l10nlib.changed_files(first + "^", first, ["."], cwd)
    characterization = [p for p in changed if p.startswith(CHAR_DIR)]
    problems, notes = [], []
    if not characterization:
        message = "the first commit %s adds no characterization tests under %s" % (l10nlib.short(first), CHAR_DIR)
        (problems if require else notes).append(message)
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
