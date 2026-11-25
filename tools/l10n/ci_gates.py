#!/usr/bin/env python3
"""The localization gates CI runs on every push and pull request, scoped by what the change is.

The English-unchanged gates (G1, G4, G5, the golden diff, shrink-only baselines) apply to
localization batches, not to every pull request: an ordinary feature or fix may change English
and add UI text. The kind comes from the paths changed since the merge base:

  english-change  l10n/english-changes.tsv changed: an owner-approved English change (plan part D).
                  G1 runs with the recorded changes; G4 and the golden diff only report. It may
                  not also add ledger rows (an extraction goes in its own pull request). It updates
                  the tests that assert the old English, so an edit to an existing top-level
                  tests/l10n/<Name>Test.java is listed in a REPORT line for the reviewer, not failed,
                  unless it is a gate test (GATE_TESTS). The rest of the proof tooling stays strict:
                  gate tests, deleted tests, other tests/l10n files and everything else is_tooling
                  names. G5 applies when CI passes --pr-head: characterization changes go in the
                  first commit, which changes nothing under src/ or res/.
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
  feature-text    a batch by the rule above that only adds new English alongside a feature (owner-approved
                  Nov 24 2026; tightened after the council's round 1 on #646). It qualifies when: l10n/ledger/,
                  l10n/lang/, l10n/english-changes.tsv and every res/values-*/ file are unchanged; the only
                  English catalog files changed are res/values/strings_*.xml (strings.xml and any other file
                  gen_catalog reads must not change), where every existing key is exactly as it was (English,
                  attributes and file) and every new key has volt:screen and volt:purpose; src/ gains only T.
                  and M. references; no src/ line holding a catalog reference is removed or changed (compared
                  without whitespace, so re-indenting one is fine); and no src/ literal left or changed
                  (expressions.FileDiff's "gone", as G1 sees it). The batch's gates hold (G1, G5, G8 shrink-only,
                  goldens frozen), with these differences: the feature tests tools/l10n/test-kinds.tsv lists at
                  the merge base, tests/*.java, android-tests/, tests/run.sh and android-tests/build.sh may
                  change, and G4 lists those test files' problems instead of failing (--relax); G4 stays strict
                  for characterization tests, and its ALWAYS_STRICT problems (a type-shadowing new test file, an
                  added exit or halt) fail everywhere. Every other tests/l10n file stays frozen as proof tooling,
                  a test the list does not name included, and no characterization test may be deleted. Every
                  proof test an active tests/l10n/*.sources entry ran at the merge base must still be one at head,
                  and tests/run.sh keeps its lines that name l10n; prove-english.sh (english-proof) then checks by
                  name that every proof suite ran and passed (--print-proof-suites).
  ordinary        anything else. The lint baselines must list every finding (they may grow; run
                  hardcoded_lint.py --write-baselines), and every ledger must still agree with the
                  catalog.

Every kind also runs, in the workflow: the tools' unit tests, gen_catalog.py --check and
tests/l10n/golden.sh (goldens equal head's output).

  ci_gates.py --base origin/main [--head HEAD] [--pr-head <sha>] [--mode auto|batch|feature-text|english-change|ordinary]
  ci_gates.py --base origin/main --print-mode
  ci_gates.py --base origin/main --print-tooling   the changed proof tooling files the tooling gate counts
                                                   for the change's kind (an English change's and a feature-text
                                                   change's reported edits are left out)
  ci_gates.py --base origin/main --print-english-tests   an English change's reported test edits
  ci_gates.py --base origin/main --print-feature-edits   a feature-text change's reported test and runner edits
  ci_gates.py --base origin/main --check-feature-text    the feature-text checks alone (for prove-english.sh)
"""

import argparse
import collections
import difflib
import os
import re
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l10nlib  # noqa: E402
import char_first  # noqa: E402
import expressions  # noqa: E402
import gen_catalog  # noqa: E402

TOOLS = os.path.dirname(os.path.abspath(__file__))
CHANGES_PATH = "l10n/english-changes.tsv"
CATALOG_FILE = re.compile(r"^res/values[^/]*/strings_[^/]+\.xml$")
TOOLING = ("tools/l10n/", ".github/workflows/", "tests/l10n/")
TOOLING_FILES = ("tests/run.sh", "build.sh", "android-tests/build.sh")
NOT_TOOLING = ("tests/l10n/characterization/", "tests/l10n/golden/")
GOLDEN_DIR = "tests/l10n/golden"
LEDGER_DIR = "l10n/ledger/"
# The tests/l10n tests that implement gates (l10n/README.md, "The gates"): G2, G9, G10, glyph coverage and the
# catalog checks. They stay strict in every kind, an English change included.
GATE_TESTS = frozenset(["LedgerTest.java", "FmtLegacyTest.java", "ReportsStayEnglishTest.java",
                        "GlyphCoverageTest.java", "CatalogCheckTest.java", "CatalogUsageTest.java"])
FEATURE_TEXT = "feature-text"
# The reviewed list of which top-level tests/l10n/<Name>.java files are proof tests and which are feature tests that
# live there. It is read at the merge base, so a pull request's own edit to it (a tooling change) never applies to it.
TEST_KINDS_PATH = "tools/l10n/test-kinds.tsv"
# Runners a feature-text change may edit as an ordinary change may (owner-approved Nov 24 2026). build.sh is not one:
# it holds G3's round-trip and the release guard.
FEATURE_TEXT_RUNNERS = ("tests/run.sh", "android-tests/build.sh")
RUN_SH = "tests/run.sh"
ENGLISH_CATALOG = re.compile(r"^res/values/strings_[^/]+\.xml$")
TRANSLATIONS = re.compile(r"^res/values-[^/]+/")
OTHER_ENGLISH = re.compile(r"^res/values/strings[^/]*\.xml$")  # every file gen_catalog reads, strings.xml included
LANG_DIR = "l10n/lang/"
CHAR_DIR = char_first.CHAR_DIR
NEW_KEY_ATTRIBUTES = ("screen", "purpose")


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


def parse_test_kinds(text):
    """{path: "proof" or "feature"} from tools/l10n/test-kinds.tsv (# comments, a header, then path, kind, reason)."""
    kinds = {}
    for line in (text or "").splitlines():
        fields = line.split("\t")
        if not line.strip() or line.startswith("#") or fields[0] == "test" or len(fields) < 2:
            continue
        kinds[fields[0]] = fields[1].strip()
    return kinds


def feature_tests(merge, cwd=None):
    """The tests/l10n tests a feature-text change may edit: the list's feature rows at the merge base. A gate test is
    never one, and anything the list does not name is a proof test."""
    kinds = parse_test_kinds(l10nlib.read_files(merge, [TEST_KINDS_PATH], cwd)[TEST_KINDS_PATH])
    return set(p for p, kind in kinds.items() if kind == "feature" and new_test_file(p) and
               p[len("tests/l10n/"):] not in GATE_TESTS)


def _catalog_entries(texts):
    """({key: entry}, problems) for {path: text or None} of res/values/strings_*.xml files."""
    entries, problems = {}, []
    work = tempfile.mkdtemp(prefix="l10n-ci-gates-")
    try:
        for path, text in sorted(texts.items()):
            if text is None:
                continue
            full = os.path.join(work, os.path.basename(path))
            l10nlib.write_text(full, text)
            found, file_problems = gen_catalog.read_strings_file(full, path)
            problems.extend(file_problems)
            for entry in found:
                entries.setdefault(entry.name, entry)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    return entries, problems


def _entry_shape(entry):
    """What an existing key must keep in a feature-text change: its kind, every item as written, its attributes and
    its file. A translator's comment (the entry's note) is not English and may change."""
    return (entry.kind, tuple((q, item.raw) for q, item in entry.items.items()), tuple(sorted(entry.meta.items())),
            entry.translatable, entry.formatted, entry.file)


def feature_text_problems(merge, head, changed, cwd=None, gained=()):
    """(reasons the change is not feature text, the new keys). No reasons: it only adds English. gained is the catalog
    references src/ gained (gained_catalog_references)."""
    reasons = []
    for path in changed:
        if path.startswith(LEDGER_DIR):
            reasons.append("%s changed (moving old text records ledger rows)" % path)
        elif TRANSLATIONS.match(path):
            reasons.append("%s changed (translations)" % path)
        elif path == CHANGES_PATH:
            reasons.append("%s changed (an English change)" % path)
        elif path.startswith(LANG_DIR):
            reasons.append("%s changed (the language table: names and statuses)" % path)
        elif OTHER_ENGLISH.match(path) and not ENGLISH_CATALOG.match(path):
            reasons.append("%s changed (English text the catalog reads; only new keys in res/values/strings_*.xml "
                           "qualify)" % path)
    for name in gained:
        if not name.startswith(("T.", "M.")):
            reasons.append("src gains %s (only T. and M. calls qualify)" % name)
    catalog = [p for p in changed if ENGLISH_CATALOG.match(p)]
    before, before_problems = _catalog_entries(l10nlib.read_files(merge, catalog, cwd))
    after, after_problems = _catalog_entries(l10nlib.read_files(head, catalog, cwd))
    reasons += ["the catalog does not read at head: %s" % p for p in after_problems[:3]]
    reasons += ["the catalog does not read at the merge base: %s" % p for p in before_problems[:3]]
    for name in sorted(before):
        if name not in after:
            reasons.append("%s: %s is removed or renamed" % (before[name].file, name))
        elif _entry_shape(before[name]) != _entry_shape(after[name]):
            reasons.append("%s: %s changes (its English or its attributes)" % (before[name].file, name))
    new_keys = sorted(name for name in after if name not in before)
    for name in new_keys:
        for attribute in NEW_KEY_ATTRIBUTES:
            if attribute not in after[name].meta:
                reasons.append("%s: new key %s has no volt:%s" % (after[name].file, name, attribute))
    sources = [p for p in changed if p.startswith("src/") and p.endswith(".java")]
    old, new = l10nlib.read_files(merge, sources, cwd), l10nlib.read_files(head, sources, cwd)
    for path in sources:
        for line, names in changed_reference_lines(old[path], new[path]):
            reasons.append("%s:%d: a line holding %s changed or went (pointing existing code at other text is a "
                           "batch; feature text only adds lines that call the catalog)" % (path, line, ", ".join(names)))
        try:
            diff = expressions.FileDiff(old[path], new[path])
        except l10nlib.LexError as error:
            reasons.append("%s: %s" % (path, error))
            continue
        for k in sorted(diff.gone):
            token = diff.base_token(k)
            reasons.append("%s:%d: %s %s left or changed (moving old text)" % (
                path, token.line, "char" if token.kind == "char" else "literal", l10nlib.json_string(token.value)))
    return reasons, new_keys


def feature_text_edits(merge, changed, added=(), cwd=None):
    """The proof tooling paths a feature-text change may change: listed feature tests and the two runners."""
    allowed = feature_tests(merge, cwd).union(FEATURE_TEXT_RUNNERS)
    return [p for p in changed if is_tooling(p) and p in allowed and not (p in added and new_test_file(p))]


def localization_lines(text):
    """The lines of tests/run.sh that name l10n (any case): the part that builds the catalog and runs the lists."""
    return [line for line in (text or "").splitlines() if "l10n" in line.lower()]


def proof_run_problems(merge, head, cwd=None):
    """Ways a feature-text change would stop a proof test from running, or [] when none."""
    problems = []
    texts = (l10nlib.read_files(merge, [RUN_SH], cwd)[RUN_SH], l10nlib.read_files(head, [RUN_SH], cwd)[RUN_SH])
    if texts[0] is not None:
        at, lines = 0, (texts[1] or "").splitlines()
        for wanted in localization_lines(texts[0]):
            while at < len(lines) and lines[at] != wanted:
                at += 1
            if at == len(lines):
                problems.append("%s: a line of its localization part changed or went (%s); the part that runs the "
                                "tests/l10n/*.sources lists stays as it is" % (RUN_SH, wanted.strip()))
                break
            at += 1
    features = feature_tests(merge, cwd)
    ran = set(e for e in char_first._active_entries(merge, cwd) if char_first.runs_in_run_sh(e) and e not in features)
    still = set(char_first._active_entries(head, cwd))
    present = l10nlib.read_files(head, sorted(ran - still), cwd)
    for entry in sorted(ran - still):
        if present[entry] is not None:  # a deleted proof test is the tooling gate's
            problems.append("%s: no tests/l10n/*.sources list runs it at head; a proof test keeps running" % entry)
    return problems


def feature_text_summary(new_keys):
    names = ", ".join(new_keys[:5]) + (" and %d more" % (len(new_keys) - 5) if len(new_keys) > 5 else "")
    count = "no new English key" if not new_keys else "%d new English key%s (%s)" % (
        len(new_keys), "" if len(new_keys) == 1 else "s", names)
    return "%s, no existing English changed" % count


def choose_kind(merge, head, changed, gained, cwd=None):
    """(kind, the reasons a batch is not feature text, new keys)."""
    kind = kind_of(changed, bool(gained))
    if kind != "batch":
        return kind, [], []
    reasons, new_keys = feature_text_problems(merge, head, changed, cwd, gained)
    return (FEATURE_TEXT if not reasons else kind), reasons, new_keys


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


def _catalog_reference_sites(tokens):
    """(name, line) for each catalog reference in lexed Java (see catalog_references)."""
    for i in range(len(tokens) - 1):
        if tokens[i].value == "import" and tokens[i + 1].value == "static":
            name = []
            for t in tokens[i + 2:]:
                if t.value == ";":
                    break
                name.append(t.value)
            name = "".join(name)
            if re.match(r"(%s\.)?(T|M|R)\.(\*|[A-Za-z_$])" % re.escape(CATALOG_PACKAGE), name):
                yield "import static " + name, tokens[i].line
    for i in range(len(tokens) - 2):
        t = tokens[i]
        if t.kind != "ident" or tokens[i + 1].value != ".":
            continue
        if i > 0 and tokens[i - 1].value == "." and _qualifier(tokens, i) != CATALOG_PACKAGE:
            continue
        if t.value in ("T", "M") and tokens[i + 2].kind == "ident":
            yield t.value + "." + tokens[i + 2].value, t.line
        elif t.value == "R" and tokens[i + 2].value in ("string", "plurals") and i + 4 < len(tokens) and \
                tokens[i + 3].value == "." and tokens[i + 4].kind == "ident":
            yield "R.%s.%s" % (tokens[i + 2].value, tokens[i + 4].value), t.line


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
    for name, _ in _catalog_reference_sites(tokens):
        found[name] += 1
    return found


def changed_reference_lines(before, after):
    """[(base line, references)] for each line of before that holds a catalog reference and is removed or changed in
    after. Lines compare with their whitespace removed, so re-indenting a line (wrapping it in a new block) keeps it."""
    if before is None:
        return []
    try:
        sites = list(_catalog_reference_sites(l10nlib.lex_java(before)))
    except l10nlib.LexError:
        return [(0, ["(the file does not lex at the merge base)"])]
    by_line = collections.defaultdict(list)
    for name, line in sites:
        by_line[line].append(name)
    if not by_line:
        return []
    squash = lambda text: ["".join(line.split()) for line in text.split("\n")]  # noqa: E731
    old, new = squash(before), squash(after) if after is not None else []
    gone = set()
    for tag, i1, i2, _, _ in difflib.SequenceMatcher(None, old, new, autojunk=False).get_opcodes():
        if tag != "equal":
            gone.update(range(i1 + 1, i2 + 1))
    return [(line, by_line[line]) for line in sorted(by_line) if line in gone]
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


def english_test_file(path):
    """A top-level tests/l10n/<Name>Test.java that is not a gate test: an English change may edit one."""
    name = path[len("tests/l10n/"):]
    return new_test_file(path) and name.endswith("Test.java") and name not in GATE_TESTS


def tooling_changes(changed, added=(), reported=()):
    """Changed proof tooling; added is the paths that did not exist at the merge base, reported the English
    change's test edits (english_test_edits) that are listed for the reviewer instead."""
    return [p for p in changed if is_tooling(p) and not (p in added and new_test_file(p)) and p not in reported]


def english_test_edits(merge, head, changed, cwd=None):
    """The english_test_file paths that exist both at the merge base and at head and differ: edits, not
    additions or deletions."""
    candidates = [p for p in changed if english_test_file(p)]
    before = l10nlib.read_files(merge, candidates, cwd)
    after = l10nlib.read_files(head, candidates, cwd)
    return [p for p in candidates if before[p] is not None and after[p] is not None]


def added_paths(merge, head, changed, cwd=None):
    candidates = [p for p in changed if new_test_file(p)]
    before = l10nlib.read_files(merge, candidates, cwd)
    after = l10nlib.read_files(head, candidates, cwd)
    return set(p for p in candidates if before[p] is None and after[p] is not None)


def g4_relax_paths(merge, changed, cwd=None):
    """The changed test files whose G4 problems a feature-text change lists instead of failing: tests/*.java,
    android-tests/ and the feature tests the list names. Characterization and proof tests stay strict."""
    features = feature_tests(merge, cwd)
    return [p for p in changed if p.endswith(".java") and p.startswith(("tests/", "android-tests/")) and
            (not p.startswith("tests/l10n/") or p in features)]


def g4_arguments(kind, merge, changed, cwd=None):
    """frozen_literals.py's extra arguments for a kind that lists some test edits (ALWAYS_STRICT problems fail in
    every kind): an English change lists every path's, a feature-text change only g4_relax_paths'."""
    if kind == "english-change":
        return ["--relax-all"]
    if kind == FEATURE_TEXT:
        return [arg for p in g4_relax_paths(merge, changed, cwd) for arg in ("--relax", p)]
    return []


def proof_suites(merge, head, cwd=None):
    """The class names of the proof suites tests/run.sh must run at head: every active tests/l10n/*.sources entry
    it runs (char_first.RUN_PATTERN), at head or at the merge base and still present, that the merge base's list
    does not name a feature test. Characterization suites included."""
    features = feature_tests(merge, cwd)
    entries = set(char_first._active_entries(head, cwd)) | set(char_first._active_entries(merge, cwd))
    entries = sorted(e for e in entries if char_first.runs_in_run_sh(e) and e not in features)
    present = l10nlib.read_files(head, entries, cwd)
    return sorted(set(os.path.basename(e)[:-len(".java")] for e in entries if present[e] is not None))


def plan(kind, base, head, pr_head, root, g4_args=None):
    """[(label, argv or None, strict)] for one kind; argv None means a check done here. g4_args: frozen_literals.py's
    extra arguments (g4_arguments); by default an English change lists every path and other kinds list none."""
    tool = lambda name, *args: [sys.executable, os.path.join(TOOLS, name)] + list(args)  # noqa: E731
    steps = [("ledgers agree with the catalog", tool("ledger_check.py", "--catalog-only", "--head", head), True),
             ("no static imports of the catalog", None, True)]
    if kind == "ordinary":
        steps.append(("G8 baselines list every finding", tool("hardcoded_lint.py", "--check", "--root", root), True))
        return steps
    strict = kind == "batch"
    # A feature-text change keeps the goldens frozen, as a batch does; its test edits are reported (G4), as an
    # English change's are, because its feature tests may change with the feature.
    steps += [
        ("G1 ledger", tool("ledger_check.py", "--base", base, "--head", head), True),
        ("G4 frozen test statements", tool("frozen_literals.py", "--base", base, "--head", head) +
         (g4_args if g4_args is not None else ["--relax-all"] if kind == "english-change" else []), True),
        ("G8 shrink-only baselines", tool("hardcoded_lint.py", "--check", "--base", base, "--tight", "--root", root),
         True),
        ("G9 goldens unchanged since the merge base", None, kind in ("batch", FEATURE_TEXT)),
        ("proof tooling unchanged", None, True),
    ]
    if kind == "english-change":
        steps.append(("no ledger rows in an English change", None, True))
        steps.append(("tests/l10n edits follow l10n/english-changes.tsv", None, False))
    if kind == FEATURE_TEXT:
        steps.append(("feature text adds only new English", None, True))
        steps.append(("proof tests still run", None, True))
        steps.append(("feature tests and runners edited", None, False))
    if pr_head:
        steps.insert(3, ("G5 characterization first", tool("char_first.py", "--base", base, "--head", pr_head), True))
    return steps


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--base", required=True, help="base revision, for example origin/main")
    parser.add_argument("--head", default="HEAD", help="the checked-out commit (a pull request's merge commit)")
    parser.add_argument("--pr-head", default="", help="the pull request's head commit, for G5")
    parser.add_argument("--mode", default="auto", choices=("auto", "batch", FEATURE_TEXT, "english-change", "ordinary"))
    parser.add_argument("--print-mode", action="store_true", help="print the kind and exit")
    parser.add_argument("--print-tooling", action="store_true",
                        help="print the changed proof tooling files the tooling gate counts for this kind and exit")
    parser.add_argument("--print-english-tests", action="store_true",
                        help="print an English change's test edits, which are reported, not failed, and exit")
    parser.add_argument("--print-feature-edits", action="store_true",
                        help="print a feature-text change's test and runner edits, which are reported, and exit")
    parser.add_argument("--print-g4-args", action="store_true",
                        help="print frozen_literals.py's extra arguments for this kind, one per line, and exit")
    parser.add_argument("--print-proof-suites", action="store_true",
                        help="print the proof suites tests/run.sh must run and pass at head, one per line, and exit")
    parser.add_argument("--check-feature-text", action="store_true",
                        help="run only the feature-text checks (only new English; proof tests still run) and exit")
    args = parser.parse_args(argv)
    head = l10nlib.resolve(args.head)
    merge = l10nlib.merge_base(args.base, head)
    changed = l10nlib.changed_files(merge, head, ["."])
    added = added_paths(merge, head, changed)
    gained = gained_catalog_references(merge, head, changed)
    kind, not_feature_text, new_keys = choose_kind(merge, head, changed, gained)
    if args.mode != "auto":
        kind = args.mode
        if kind == FEATURE_TEXT:
            not_feature_text, new_keys = feature_text_problems(merge, head, changed, gained=gained)
    if kind == "english-change":
        reported = english_test_edits(merge, head, changed)
    elif kind == FEATURE_TEXT:
        reported = feature_text_edits(merge, changed, added)
    else:
        reported = []
    frozen_gone = []
    if kind == FEATURE_TEXT:
        # Characterization tests are outside is_tooling (G5 orders them), but a feature-text change may not delete one.
        chars = [p for p in changed if p.startswith(CHAR_DIR)]
        at_head = l10nlib.read_files(head, chars)
        frozen_gone = [p for p in chars if at_head[p] is None]
    g4_args = g4_arguments(kind, merge, changed)
    if args.print_g4_args or args.print_proof_suites:
        for line in g4_args if args.print_g4_args else proof_suites(merge, head):
            print(line)
        return 0
    if args.print_tooling or args.print_english_tests or args.print_feature_edits:
        for path in tooling_changes(changed, added, reported) + frozen_gone if args.print_tooling else reported:
            print(path)
        return 0
    if args.print_mode:
        print(kind)
        return 0
    if args.check_feature_text:
        problems = not_feature_text + proof_run_problems(merge, head)
        for problem in problems:
            print("ci_gates: %s" % problem, file=sys.stderr)
        print("ci_gates: %s: %s" % ("FAILED" if problems else "PASS", "; ".join(problems[:3]) if problems else
                                    "%s; proof tests still run" % feature_text_summary(new_keys)))
        return 1 if problems else 0
    why = ""
    if kind == FEATURE_TEXT:
        why = "; %s" % feature_text_summary(new_keys)
    elif kind == "batch" and not_feature_text:
        why = "; not feature-text: %s%s" % ("; ".join(not_feature_text[:3]), " (and %d more)" % (
            len(not_feature_text) - 3) if len(not_feature_text) > 3 else "")
    print("ci_gates: %s change (%d path(s) since merge base %s)%s%s" % (
        kind, len(changed), l10nlib.short(merge), why,
        "; src gained catalog references (%s)" % ", ".join(gained[:5]) if gained else ""))
    failed = []
    for label, command, strict in plan(kind, args.base, head, args.pr_head, l10nlib.repo_root(), g4_args):
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
        elif label.startswith("tests/l10n edits"):
            ok = not reported
            detail = "none" if ok else "%s; check that each edit only follows a row of %s" % (
                ", ".join(reported), CHANGES_PATH)
        elif label.startswith("feature text adds"):
            ok = not not_feature_text
            detail = feature_text_summary(new_keys) if ok else "%d reason(s)" % len(not_feature_text)
            for reason in not_feature_text:
                print("ci_gates: %s: %s; a change that touches existing English or moves old text is a batch" % (
                    label, reason), file=sys.stderr)
        elif label.startswith("proof tests still run"):
            problems = proof_run_problems(merge, head)
            ok, detail = not problems, "%d problem(s)" % len(problems) if problems else (
                "every proof test listed at the merge base is listed at head; tests/run.sh keeps its l10n lines")
            for problem in problems:
                print("ci_gates: %s: %s" % (label, problem), file=sys.stderr)
        elif label.startswith("feature tests and runners"):
            ok = not reported
            detail = "none" if ok else "%s; check that each edit follows the feature (the proof tests stay frozen)" % (
                ", ".join(reported))
        elif label.startswith("G9"):
            changes = golden_changes(merge, head)
            ok, detail = not changes, ", ".join(changes) or "unchanged"
            if changes:
                print("ci_gates: %s: %s changed since %s%s" % (
                    label, ", ".join(changes), l10nlib.short(merge),
                    "; an extraction batch must not change report output" if strict else " (reported only)"),
                    file=sys.stderr)
        else:
            changes = tooling_changes(changed, added, reported) + frozen_gone
            ok, detail = not changes, ", ".join(changes) or "unchanged"
            if changes and kind == FEATURE_TEXT:
                print("ci_gates: %s: %s changed; a feature-text pull request may edit only the feature tests %s "
                      "lists, tests/run.sh and android-tests/build.sh: the proof tests (every other tests/l10n file, "
                      "a test the list doesn't name included) and the proof tools change in their own pull request"
                      % (label, ", ".join(changes), TEST_KINDS_PATH), file=sys.stderr)
            elif changes:
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
