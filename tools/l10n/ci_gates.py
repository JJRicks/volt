#!/usr/bin/env python3
"""The localization gates CI runs on every push and pull request, scoped by what the change is.

The English-unchanged gates (G1, G4, G5, the golden diff, shrink-only baselines) apply to
localization batches, not to every pull request: an ordinary feature or fix may change English
and add UI text. The kind comes from the paths changed since the merge base:

  english-change  l10n/english-changes.tsv changed: an owner-approved English change (plan part D).
                  G1 runs with the recorded changes; G4 and the golden diff only report.
  batch           l10n/ledger/ or a res/values*/strings_*.xml changed: an extraction batch. Every
                  gate is strict, and the proof tooling (tools/l10n/, .github/workflows/,
                  tests/l10n/golden.sh) must not change in the same pull request.
  ordinary        anything else. The lint baselines must list every finding (they may grow; run
                  hardcoded_lint.py --write-baselines), and every ledger must still agree with the
                  catalog.

Every kind also runs, in the workflow: the tools' unit tests, gen_catalog.py --check and
tests/l10n/golden.sh (goldens equal head's output).

  ci_gates.py --base origin/main [--head HEAD] [--pr-head <sha>] [--mode auto|batch|english-change|ordinary]
  ci_gates.py --base origin/main --print-mode
"""

import argparse
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l10nlib  # noqa: E402

TOOLS = os.path.dirname(os.path.abspath(__file__))
CHANGES_PATH = "l10n/english-changes.tsv"
CATALOG_FILE = re.compile(r"^res/values[^/]*/strings_[^/]+\.xml$")
TOOLING = ("tools/l10n/", ".github/workflows/", "tests/l10n/golden.sh")
GOLDEN_DIR = "tests/l10n/golden"


def kind_of(changed):
    if CHANGES_PATH in changed:
        return "english-change"
    if any(p.startswith("l10n/ledger/") or CATALOG_FILE.match(p) for p in changed):
        return "batch"
    return "ordinary"


def golden_changes(merge, head, cwd=None):
    """Golden files that differ from the merge base, or [] when the base has none yet."""
    if not l10nlib.list_files(merge, [GOLDEN_DIR], cwd):
        return []
    return l10nlib.changed_files(merge, head, [GOLDEN_DIR], cwd)


def tooling_changes(changed):
    return [p for p in changed if p.startswith(TOOLING)]


def plan(kind, base, head, pr_head, root):
    """[(label, argv or None, strict)] for one kind; argv None means a check done here."""
    tool = lambda name, *args: [sys.executable, os.path.join(TOOLS, name)] + list(args)  # noqa: E731
    steps = [("ledgers agree with the catalog", tool("ledger_check.py", "--catalog-only", "--head", head), True)]
    if kind == "ordinary":
        steps.append(("G8 baselines list every finding", tool("hardcoded_lint.py", "--check", "--root", root), True))
        return steps
    strict = kind == "batch"
    steps += [
        ("G1 ledger", tool("ledger_check.py", "--base", base, "--head", head), True),
        ("G4 frozen test statements", tool("frozen_literals.py", "--base", base, "--head", head) +
         ([] if strict else ["--report"]), True),
        ("G8 shrink-only baselines", tool("hardcoded_lint.py", "--check", "--base", base, "--tight", "--root", root),
         True),
        ("G9 goldens unchanged since the merge base", None, strict),
        ("proof tooling unchanged", None, True),
    ]
    if pr_head:
        steps.insert(3, ("G5 characterization first", tool("char_first.py", "--base", base, "--head", pr_head), True))
    return steps


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--base", required=True, help="base revision, for example origin/main")
    parser.add_argument("--head", default="HEAD", help="the checked-out commit (a pull request's merge commit)")
    parser.add_argument("--pr-head", default="", help="the pull request's head commit, for G5")
    parser.add_argument("--mode", default="auto", choices=("auto", "batch", "english-change", "ordinary"))
    parser.add_argument("--print-mode", action="store_true", help="print the kind and exit")
    args = parser.parse_args(argv)
    head = l10nlib.resolve(args.head)
    merge = l10nlib.merge_base(args.base, head)
    changed = l10nlib.changed_files(merge, head, ["."])
    kind = kind_of(changed) if args.mode == "auto" else args.mode
    if args.print_mode:
        print(kind)
        return 0
    print("ci_gates: %s change (%d path(s) since merge base %s)" % (kind, len(changed), l10nlib.short(merge)))
    failed = []
    for label, command, strict in plan(kind, args.base, head, args.pr_head, l10nlib.repo_root()):
        if command is not None:
            print("ci_gates: --- %s" % label)
            result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            sys.stdout.write(result.stdout.decode("utf-8", "replace"))
            ok, detail = result.returncode == 0, "exit %d" % result.returncode
        elif label.startswith("G9"):
            changes = golden_changes(merge, head)
            ok, detail = not changes, ", ".join(changes) or "unchanged"
            if changes:
                print("ci_gates: %s: %s changed since %s%s" % (
                    label, ", ".join(changes), l10nlib.short(merge),
                    "; an extraction batch must not change report output" if strict else " (reported only)"),
                    file=sys.stderr)
        else:
            changes = tooling_changes(changed)
            ok, detail = not changes, ", ".join(changes) or "unchanged"
            if changes:
                print("ci_gates: %s: %s changed; change the proof tools in their own pull request, not in a "
                      "localization batch" % (label, ", ".join(changes)), file=sys.stderr)
        if not ok and strict:
            failed.append(label)
        print("ci_gates: %s %s (%s)" % ("PASS" if ok else "FAILED" if strict else "REPORT", label, detail))
        sys.stdout.flush()
    print("ci_gates: %s: %s change, %d gate(s) failed%s" % (
        "FAILED" if failed else "PASS", kind, len(failed), ": " + ", ".join(failed) if failed else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
