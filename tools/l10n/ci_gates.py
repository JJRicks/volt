#!/usr/bin/env python3
"""The localization gates CI runs on every push and pull request, scoped by what the change is.

The English-unchanged gates (G1, G4, G5, the golden diff, shrink-only baselines) apply to
localization batches, not to every pull request: an ordinary feature or fix may change English
and add UI text. The kind comes from the paths changed since the merge base:

  english-change  l10n/english-changes.tsv changed: an owner-approved English change (plan part D).
                  G1 runs with the recorded changes; G4 and the golden diff only report. It may
                  not also add ledger rows (an extraction goes in its own pull request).
  batch           l10n/ledger/ or a res/values*/strings_*.xml changed, or a src/ file gained a
                  T.<name>, M.<name>, R.string or R.plurals reference (a conversion that forgot its
                  ledger): an extraction batch. Every gate is strict, and the proof tooling
                  (is_tooling: tools/l10n/, .github/workflows/, tests/l10n/ except
                  characterization/, golden/ and *.sources, tests/run.sh, build.sh and
                  android-tests/build.sh) must not change in the same pull request. A new
                  tests/l10n/<Name>.java (B0b's catalog tests, B1's FmtLegacyTest) only adds
                  checks, so a batch may add one; editing or deleting one is a tooling change.
                  The run.sh hook and moving test helpers (TextFit) go in a pull request of
                  their own before the batch (l10n/README.md, "Order of the first pull requests").
  ordinary        anything else. The lint baselines must list every finding (they may grow; run
                  hardcoded_lint.py --write-baselines), and every ledger must still agree with the
                  catalog.

Every kind also runs, in the workflow: the tools' unit tests, gen_catalog.py --check and
tests/l10n/golden.sh (goldens equal head's output).

  ci_gates.py --base origin/main [--head HEAD] [--pr-head <sha>] [--mode auto|batch|english-change|ordinary]
  ci_gates.py --base origin/main --print-mode
  ci_gates.py --base origin/main --print-tooling   the proof tooling files changed since the merge base
"""

import argparse
import collections
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l10nlib  # noqa: E402

TOOLS = os.path.dirname(os.path.abspath(__file__))
CHANGES_PATH = "l10n/english-changes.tsv"
CATALOG_FILE = re.compile(r"^res/values[^/]*/strings_[^/]+\.xml$")
TOOLING = ("tools/l10n/", ".github/workflows/", "tests/l10n/")
TOOLING_FILES = ("tests/run.sh", "build.sh", "android-tests/build.sh")
NOT_TOOLING = ("tests/l10n/characterization/", "tests/l10n/golden/")
GOLDEN_DIR = "tests/l10n/golden"
LEDGER_DIR = "l10n/ledger/"


def is_tooling(path):
    """The proof tools and what runs them: a batch must not change these (golden/ is G9's job)."""
    if path in TOOLING_FILES:
        return True
    if not path.startswith(TOOLING) or path.startswith(NOT_TOOLING):
        return False
    return not (path.startswith("tests/l10n/") and path.endswith(".sources") and path.count("/") == 2)


def kind_of(changed, gains_catalog_references=False):
    if CHANGES_PATH in changed:
        return "english-change"
    if gains_catalog_references or any(p.startswith(LEDGER_DIR) or CATALOG_FILE.match(p) for p in changed):
        return "batch"
    return "ordinary"


CATALOG_PACKAGE = "com.jjricks.volt"


def _qualifier(tokens, i):
    """The dotted name in front of tokens[i] ("com.jjricks.volt" for com.jjricks.volt.T), or "" for none."""
    parts, j = [], i - 1
    while j >= 1 and tokens[j].value == "." and tokens[j - 1].kind == "ident":
        parts.insert(0, tokens[j - 1].value)
        j -= 2
    return ".".join(parts) if j < 0 or tokens[j].value != "." else None


def catalog_static_imports(tokens):
    """The T, M or R members a file imports statically ("com.jjricks.volt.T.commonSave", "...M.*")."""
    names = []
    for i in range(len(tokens) - 1):
        if tokens[i].value == "import" and tokens[i + 1].value == "static":
            name = []
            for t in tokens[i + 2:]:
                if t.value == ";":
                    break
                name.append(t.value)
            name = "".join(name)
            if re.match(r"(%s\.)?(T|M|R)\.(\*|[A-Za-z_$])" % re.escape(CATALOG_PACKAGE), name):
                names.append(name)
    return names


def static_import_problems(head, cwd=None):
    """["path: import static ..."] for every src/ file at head that imports catalog members statically. A bare
    call through such an import can't be told from any other method, so every PR refuses them: call T.x()."""
    files = [p for p in l10nlib.list_files(head, ["src"], cwd) if p.endswith(".java")]
    problems = []
    for path, text in sorted(l10nlib.read_files(head, files, cwd).items()):
        try:
            tokens = l10nlib.lex_java(text or "")
        except l10nlib.LexError:
            continue
        problems += ["%s: import static %s" % (path, name) for name in catalog_static_imports(tokens)]
    return problems


def catalog_references(text):
    """Counter of T.<name>, M.<name>, R.string.<name> and R.plurals.<name> references in Java source,
    bare or qualified with the app's package, plus static imports of T, M or R members (after which a
    bare name is a catalog call). Another object's member (x.T.y) or the framework's android.R is not."""
    found = collections.Counter()
    if text is None:
        return found
    try:
        tokens = l10nlib.lex_java(text)
    except l10nlib.LexError:
        return found
    for name in catalog_static_imports(tokens):
        found["import static " + name] += 1
    for i in range(len(tokens) - 2):
        t = tokens[i]
        if t.kind != "ident" or tokens[i + 1].value != ".":
            continue
        if i > 0 and tokens[i - 1].value == "." and _qualifier(tokens, i) != CATALOG_PACKAGE:
            continue
        if t.value in ("T", "M") and tokens[i + 2].kind == "ident":
            found[t.value + "." + tokens[i + 2].value] += 1
        elif t.value == "R" and tokens[i + 2].value in ("string", "plurals") and i + 4 < len(tokens) and \
                tokens[i + 3].value == "." and tokens[i + 4].kind == "ident":
            found["R.%s.%s" % (tokens[i + 2].value, tokens[i + 4].value)] += 1
    return found


def gained_catalog_references(merge, head, changed, cwd=None):
    """References to the catalog that src/ has at head but not at the merge base."""
    files = [p for p in changed if p.startswith("src/") and p.endswith(".java")]
    before, after = l10nlib.read_files(merge, files, cwd), l10nlib.read_files(head, files, cwd)
    gained = collections.Counter()
    for path in files:
        gained.update(catalog_references(after[path]) - catalog_references(before[path]))
    return sorted(gained)


def golden_changes(merge, head, cwd=None):
    """Golden files that differ from the merge base, or [] when the base has none yet."""
    if not l10nlib.list_files(merge, [GOLDEN_DIR], cwd):
        return []
    return l10nlib.changed_files(merge, head, [GOLDEN_DIR], cwd)


def new_test_file(path):
    """A path that, when it did not exist at the merge base, is a new test and not a tooling change."""
    return path.startswith("tests/l10n/") and path.count("/") == 2 and path.endswith(".java")


def tooling_changes(changed, added=()):
    """Changed proof tooling; added is the paths that did not exist at the merge base."""
    return [p for p in changed if is_tooling(p) and not (p in added and new_test_file(p))]


def added_paths(merge, head, changed, cwd=None):
    candidates = [p for p in changed if new_test_file(p)]
    before = l10nlib.read_files(merge, candidates, cwd)
    after = l10nlib.read_files(head, candidates, cwd)
    return set(p for p in candidates if before[p] is None and after[p] is not None)


def plan(kind, base, head, pr_head, root):
    """[(label, argv or None, strict)] for one kind; argv None means a check done here."""
    tool = lambda name, *args: [sys.executable, os.path.join(TOOLS, name)] + list(args)  # noqa: E731
    steps = [("ledgers agree with the catalog", tool("ledger_check.py", "--catalog-only", "--head", head), True),
             ("no static imports of the catalog", None, True)]
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
    if kind == "english-change":
        steps.append(("no ledger rows in an English change", None, True))
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
    parser.add_argument("--print-tooling", action="store_true", help="print the changed proof tooling files and exit")
    args = parser.parse_args(argv)
    head = l10nlib.resolve(args.head)
    merge = l10nlib.merge_base(args.base, head)
    changed = l10nlib.changed_files(merge, head, ["."])
    added = added_paths(merge, head, changed)
    if args.print_tooling:
        for path in tooling_changes(changed, added):
            print(path)
        return 0
    gained = gained_catalog_references(merge, head, changed)
    kind = kind_of(changed, bool(gained)) if args.mode == "auto" else args.mode
    if args.print_mode:
        print(kind)
        return 0
    print("ci_gates: %s change (%d path(s) since merge base %s)%s" % (
        kind, len(changed), l10nlib.short(merge),
        "; src gained catalog references (%s)" % ", ".join(gained[:5]) if gained else ""))
    failed = []
    for label, command, strict in plan(kind, args.base, head, args.pr_head, l10nlib.repo_root()):
        if command is not None:
            print("ci_gates: --- %s" % label)
            result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            sys.stdout.write(result.stdout.decode("utf-8", "replace"))
            ok, detail = result.returncode == 0, "exit %d" % result.returncode
        elif label.startswith("no static imports"):
            problems = static_import_problems(head)
            ok, detail = not problems, "%d found" % len(problems) if problems else "none"
            for problem in problems:
                print("ci_gates: %s; call T.x(), M.x() or R.string.x instead, so CI can see the catalog use" % problem,
                      file=sys.stderr)
        elif label.startswith("no ledger rows"):
            ledgers = [p for p in changed if p.startswith(LEDGER_DIR)]
            ok, detail = not ledgers, ", ".join(ledgers) or "none"
            if ledgers:
                print("ci_gates: %s: %s changed; an owner-approved English change must not also extract text, so "
                      "put the extraction in its own pull request" % (label, ", ".join(ledgers)), file=sys.stderr)
        elif label.startswith("G9"):
            changes = golden_changes(merge, head)
            ok, detail = not changes, ", ".join(changes) or "unchanged"
            if changes:
                print("ci_gates: %s: %s changed since %s%s" % (
                    label, ", ".join(changes), l10nlib.short(merge),
                    "; an extraction batch must not change report output" if strict else " (reported only)"),
                    file=sys.stderr)
        else:
            changes = tooling_changes(changed, added)
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
