#!/usr/bin/env python3
"""G8, the hard-coded text lint (Python, run in CI; the plan's Java HardcodedTextLint is replaced by this).

Flags two kinds of string literal in src/**/*.java:
  sink       any literal with letters inside a UI sink call: setText, setContentDescription, setHint,
             setTooltipText, setStateDescription, setError, setTitle, setMessage, set*Button, toast,
             Toast.makeText, notice, notification titles, texts and actions, announceForAccessibility,
             setAccessibilityPaneTitle, setChartTitle and Paint drawText
  multiword  any other literal with two or more words of letters, for example "Turn off"

Every file that has findings today has a baseline, l10n/baseline/<File>.txt, listing them (no line
numbers, so rebases do not churn it). The check fails when a file has a finding its baseline does not
list, and, with --base, when any baseline grew compared with the merge base: baselines may only
shrink. A batch deletes the baselines of the files it converts by regenerating them.

  hardcoded_lint.py --check [--base origin/main] [--tight]
  hardcoded_lint.py --write-baselines        regenerate every baseline from the working tree
  hardcoded_lint.py --report                 counts per file
"""

import argparse
import collections
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l10nlib  # noqa: E402

SINKS = {"setText", "setContentDescription", "setHint", "setTooltipText", "setStateDescription", "setError",
         "setTitle", "setMessage", "toast", "makeText", "notice", "setContentTitle", "setContentText",
         "setSubText", "setTicker", "addAction", "bigText", "setBigContentTitle", "setSummaryText",
         "announceForAccessibility", "setAccessibilityPaneTitle", "setChartTitle", "drawText", "setDescription"}
BUTTON_SINK = re.compile(r"^set[A-Z]?\w*Button$")
BASELINE_DIR = os.path.join("l10n", "baseline")
SOURCE_DIR = "src"
HEADER = ("# Hard-coded English in %s when this baseline was generated. It may only shrink.\n"
          "# Regenerate with: python3 tools/l10n/hardcoded_lint.py --write-baselines\n")

Finding = collections.namedtuple("Finding", "rule value line")


def _is_sink(tokens, open_index):
    """True when the ( at open_index starts the arguments of a UI sink call."""
    if open_index == 0 or tokens[open_index - 1].kind != "ident":
        return False
    name = tokens[open_index - 1].value
    if name in SINKS or BUTTON_SINK.match(name):
        return True
    # new Notification.Action.Builder(icon, "Title", intent)
    return name == "Builder" and open_index >= 3 and tokens[open_index - 3].value == "Action"


def _contexts(tokens):
    """{id(token): "sink" or "annotation"} for string literals inside sink calls or annotation arguments."""
    stack, result = [], {}
    for i, tok in enumerate(tokens):
        if tok.kind == "op" and tok.value == "(":
            if i >= 2 and tokens[i - 1].kind == "ident" and tokens[i - 2].kind == "op" and tokens[i - 2].value == "@":
                stack.append("annotation")
            else:
                stack.append("sink" if _is_sink(tokens, i) else None)
        elif tok.kind == "op" and tok.value == ")":
            if stack:
                stack.pop()
        elif tok.kind == "string":
            if "annotation" in stack:
                result[id(tok)] = "annotation"
            elif "sink" in stack:
                result[id(tok)] = "sink"
    return result


def sink_literals(tokens):
    """String literal tokens that sit (at any depth) inside a sink call's arguments."""
    contexts = _contexts(tokens)
    return [t for t in tokens if contexts.get(id(t)) == "sink"]


def findings(source):
    """Hard-coded English in one Java source. Annotation arguments are never UI text."""
    tokens = l10nlib.lex_java(source)
    contexts = _contexts(tokens)
    result = []
    for tok in tokens:
        if tok.kind != "string" or contexts.get(id(tok)) == "annotation":
            continue
        if contexts.get(id(tok)) == "sink" and l10nlib.has_letters(tok.value):
            result.append(Finding("sink", tok.value, tok.line))
        elif l10nlib.is_multiword(tok.value):
            result.append(Finding("multiword", tok.value, tok.line))
    return result


def entry(finding):
    return "%s\t%s" % (finding.rule, l10nlib.json_string(finding.value))


def baseline_name(path):
    return os.path.splitext(os.path.basename(path))[0] + ".txt"


def render_baseline(path, found):
    return HEADER % path + "".join(line + "\n" for line in sorted(entry(f) for f in found))


def parse_baseline(text):
    counter = collections.Counter()
    for line in text.split("\n"):
        if not line.strip() or line.startswith("#"):
            continue
        rule, _, value = line.partition("\t")
        if rule not in ("sink", "multiword"):
            raise ValueError("bad baseline line: %r" % line)
        json.loads(value)
        counter[line] += 1
    return counter


def source_files(root):
    result = []
    for directory, _, names in os.walk(os.path.join(root, SOURCE_DIR)):
        for name in names:
            if name.endswith(".java"):
                result.append(os.path.relpath(os.path.join(directory, name), root).replace(os.sep, "/"))
    return sorted(result)


def scan(root):
    """{relative path: [Finding]} for every source file, plus a name-collision check."""
    result, names = {}, {}
    for path in source_files(root):
        name = baseline_name(path)
        if name in names:
            raise SystemExit("hardcoded_lint: %s and %s share the baseline name %s" % (names[name], path, name))
        names[name] = path
        result[path] = findings(l10nlib.read_text(os.path.join(root, path)))
    return result


def read_baselines(root):
    directory = os.path.join(root, BASELINE_DIR)
    result = {}
    if os.path.isdir(directory):
        for name in sorted(os.listdir(directory)):
            if name.endswith(".txt"):
                result[name] = parse_baseline(l10nlib.read_text(os.path.join(directory, name)))
    return result


def baselines_at(rev, cwd=None):
    names = [p for p in l10nlib.list_files(rev, [BASELINE_DIR], cwd) if p.endswith(".txt")]
    texts = l10nlib.read_files(rev, names, cwd)
    return {os.path.basename(p): parse_baseline(texts[p]) for p in names if texts[p] is not None}


def write_baselines(root, scanned):
    directory = os.path.join(root, BASELINE_DIR)
    os.makedirs(directory, exist_ok=True)
    wanted = {}
    for path, found in scanned.items():
        if found:
            wanted[baseline_name(path)] = render_baseline(path, found)
    removed = 0
    for name in os.listdir(directory):
        if name.endswith(".txt") and name not in wanted:
            os.remove(os.path.join(directory, name))
            removed += 1
    for name, text in wanted.items():
        l10nlib.write_text(os.path.join(directory, name), text)
    return len(wanted), removed


def check(root, scanned, base=None, tight_paths=None, cwd=None):
    """(problems, notes)."""
    problems, notes = [], []
    baselines = read_baselines(root)
    by_name = {baseline_name(p): p for p in scanned}
    for name in sorted(set(baselines) - set(by_name)):
        problems.append("%s/%s has no source file; delete it (--write-baselines)" % (BASELINE_DIR, name))
    for path, found in sorted(scanned.items()):
        name = baseline_name(path)
        allowed = baselines.get(name, collections.Counter())
        current = collections.Counter(entry(f) for f in found)
        new = current - allowed
        for line in sorted(new):
            rule, _, value = line.partition("\t")
            where = sorted(f.line for f in found if entry(f) == line)
            problems.append("%s:%s: new hard-coded English (%s) %s: put it in res/values/strings_<area>.xml and use T"
                            % (path, ",".join(map(str, where)), rule, value))
        stale = allowed - current
        if stale:
            message = "%s/%s can shrink by %d entr%s; run --write-baselines" % (
                BASELINE_DIR, name, sum(stale.values()), "y" if sum(stale.values()) == 1 else "ies")
            if tight_paths is not None and path in tight_paths:
                problems.append(message)
            else:
                notes.append(message)
    if base:
        merge = l10nlib.merge_base(base, "HEAD", cwd)
        before = baselines_at(merge, cwd)
        if before:
            for name, counter in sorted(baselines.items()):
                grown = counter - before.get(name, collections.Counter())
                if grown:
                    problems.append("%s/%s grew by %d entr%s since %s; baselines may only shrink" % (
                        BASELINE_DIR, name, sum(grown.values()), "y" if sum(grown.values()) == 1 else "ies",
                        l10nlib.short(merge)))
        else:
            notes.append("no baselines at %s: first run, the shrink-only check starts with the next change"
                         % l10nlib.short(merge))
    return problems, notes


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="fail on findings missing from the baselines (default)")
    mode.add_argument("--write-baselines", action="store_true", help="regenerate l10n/baseline/*.txt")
    mode.add_argument("--report", action="store_true", help="print findings per file")
    parser.add_argument("--base", help="also fail when a baseline grew since the merge base with this revision")
    parser.add_argument("--tight", action="store_true",
                        help="with --base: a baseline of a file changed since the merge base must list exactly its findings")
    parser.add_argument("--root", default=None, help="repository root (default: this checkout)")
    args = parser.parse_args(argv)
    root = args.root or l10nlib.repo_root(os.path.dirname(os.path.abspath(__file__)))
    scanned = scan(root)
    total = sum(len(f) for f in scanned.values())
    files_with = sum(1 for f in scanned.values() if f)
    if args.write_baselines:
        written, removed = write_baselines(root, scanned)
        print("hardcoded_lint: wrote %d baseline(s) (%d findings), removed %d" % (written, total, removed))
        return 0
    if args.report:
        for path, found in sorted(scanned.items(), key=lambda kv: -len(kv[1])):
            if found:
                rules = collections.Counter(f.rule for f in found)
                print("%5d  %s  (sink %d, multiword %d)" % (len(found), path, rules["sink"], rules["multiword"]))
        print("hardcoded_lint: %d findings in %d of %d files" % (total, files_with, len(scanned)))
        return 0
    tight = None
    if args.tight:
        if not args.base:
            parser.error("--tight needs --base")
        merge = l10nlib.merge_base(args.base, "HEAD", root)
        tight = set(l10nlib.changed_files(merge, l10nlib.WORKTREE, [SOURCE_DIR], root))
    problems, notes = check(root, scanned, args.base, tight, root)
    for note in notes:
        print("hardcoded_lint: note: " + note, file=sys.stderr)
    for problem in problems:
        print("hardcoded_lint: " + problem, file=sys.stderr)
    print("hardcoded_lint: %s: %d findings in %d of %d source files, %d baseline file(s)" % (
        "FAILED" if problems else "PASS", total, files_with, len(scanned), len(read_baselines(root))))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
