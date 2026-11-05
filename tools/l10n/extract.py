#!/usr/bin/env python3
"""G1 helper: list a batch's literals, draft ledger rows for the literals a batch moved, and move
ledger rows to new merge-base lines after a rebase.

  extract.py scan src/com/jjricks/volt/Ui.java ...
      Every string literal with its location, whether it looks like English text, whether it sits
      in a UI sink call, and the concatenation it belongs to (as ledger pieces). Use it to plan a batch.

  extract.py draft --base origin/main --batch b2-ui [--head HEAD|WORKTREE]
      After moving text into res/values/strings_<area>.xml and T calls, finds every literal that left
      src/ (by position, as ledger_check.py counts them) and has no ledger row yet, and appends one draft
      row per original expression to l10n/ledger/<batch>.tsv with key and kind set to TODO. Fill in key,
      kind, each hole's arg and type (the draft guesses "s"), and fix the pieces where the guess is wrong;
      ledger_check.py refuses TODO rows. The guess follows + chains only: for a ternary, String.format,
      an append chain or a numeric sum before the first string, write the row by hand with a note
      starting "manual:".

  extract.py relocate --base origin/main --batch b2-ui
      After a rebase moved lines, rewrites each of the batch's rows whose line no longer holds its
      expression at the new merge base, when exactly one expression in that file has the same pieces.
      Rows it cannot place unambiguously are listed for a hand fix.
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l10nlib  # noqa: E402
import expressions  # noqa: E402
import ledger_check  # noqa: E402
import hardcoded_lint  # noqa: E402

literal_expressions = expressions.literal_expressions


def cmd_scan(paths):
    print("location\tliteral\tenglish\tsink\tpieces")
    root = l10nlib.repo_root()
    for path in paths:
        rel = os.path.relpath(os.path.abspath(path), root)
        source = l10nlib.read_text(path)
        sink_lines = set((t.line, t.value) for t in hardcoded_lint.sink_literals(l10nlib.lex_java(source)))
        for line, value, pieces, _, _ in literal_expressions(source):
            print("%s:%d\t%s\t%s\t%s\t%s" % (
                rel, line, l10nlib.json_string(value),
                "yes" if l10nlib.is_english_looking(value) else "no",
                "yes" if (line, value) in sink_lines else "no",
                json.dumps(pieces, ensure_ascii=False)))
    return 0


def _covered(rows, diffs):
    """(path, token) pairs the existing rows already cover (rows that fail to locate cover nothing)."""
    claimed = {}
    for row in rows:
        diff = diffs.get(row.path)
        if diff is None or diff.base is None or not row.path.startswith("src/"):
            continue
        if row.kind == ledger_check.CHOICE:
            candidates, _ = ledger_check.locate_choice(row, diff)
            tokens = list(candidates[0][0]) if candidates else []
            # A local's strings the chosen sentence reads are covered with it.
            context = ledger_check.choose_choice(row, diff, candidates)[4]
            for shared in (context.shared if context else ()):
                tokens.extend(shared[3])
        else:
            tokens, _, problem = ledger_check.locate(row, diff, claimed)
        for k in tokens or []:
            claimed[(row.path, k)] = row
    return claimed


def draft_rows(base, head, existing_rows, cwd=None):
    """Draft ledger lines for literals that left src/ and are not yet covered, one per base expression."""
    merge = l10nlib.merge_base(base, head, cwd)
    files = [p for p in l10nlib.changed_files(merge, head, ["src"], cwd) if p.endswith(".java")]
    before = l10nlib.read_files(merge, files, cwd)
    after = l10nlib.read_files(head, files, cwd)
    diffs = {p: expressions.FileDiff(before[p], after[p]) for p in files if before[p] is not None}
    claimed = _covered(existing_rows, diffs)
    lines = []
    for path in files:
        diff = diffs.get(path)
        if diff is None:
            continue
        seen = set()
        for k in sorted(diff.gone):
            if (path, k) in claimed:
                continue
            number = diff.base.by_token[k]
            if number in seen:
                continue
            seen.add(number)
            e = diff.base.expressions[number]
            lines.append("%s:%d\t%s\tTODO\tTODO\t" % (path, e.line, json.dumps(e.pieces, ensure_ascii=False)))
    return lines


def _ledger_path(batch):
    return os.path.join(l10nlib.repo_root(), "l10n", "ledger", batch + ".tsv")


def cmd_draft(args):
    head = l10nlib.resolve(args.head)
    root = l10nlib.repo_root()
    path = _ledger_path(args.batch)
    existing_text = l10nlib.read_text(path) if os.path.exists(path) else ""
    rows = []
    if existing_text.strip():
        try:
            # Earlier drafts still marked TODO count as covered, so a second draft adds only new rows.
            rows = ledger_check.parse_ledger(existing_text.replace("\tTODO\tTODO\t", "\t-\tremoved\t"), path)
        except ledger_check.LedgerError as error:
            print("extract: fix %s first:\n%s" % (path, error), file=sys.stderr)
            return 1
    lines = draft_rows(args.base, head, rows)
    if not lines:
        print("extract: every literal that left src/ already has a row in %s" % os.path.relpath(path, root))
        return 0
    text = existing_text
    if not text.strip():
        text = ("# Ledger for %s: one row per original Java expression whose literals left src/.\n"
                "# Format: tools/l10n/ledger_check.py and l10n/README.md.\n" % args.batch)
        text += "\t".join(ledger_check.HEADER) + "\n"
    elif not text.endswith("\n"):
        text += "\n"
    text += "\n".join(lines) + "\n"
    l10nlib.write_text(path, text)
    print("extract: appended %d draft row(s) to %s; replace every TODO." % (len(lines), os.path.relpath(path, root)))
    return 0


def relocate(text, merge, cwd=None, source="ledger"):
    """(new ledger text, moved count, problems): rows placed on their expression's line at merge."""
    rows = ledger_check.parse_ledger(text, source)
    paths = sorted(set(r.path for r in rows))
    bases = l10nlib.read_files(merge, paths, cwd)
    indexes = {p: expressions.Index(t) for p, t in bases.items() if t is not None}
    lines = text.split("\n")
    moved, problems = 0, []
    for row in rows:
        index = indexes.get(row.path)
        where = "%s:%d" % (source, row.line)
        if index is None:
            problems.append("%s: %s does not exist at %s" % (where, row.path, l10nlib.short(merge)))
            continue
        wanted = set(expressions.shape(s) for s in ledger_check.sequences(row.pieces))
        if len(wanted) != 1:
            problems.append("%s: forms differ; place this row by hand" % where)
            continue
        wanted = wanted.pop()
        matches = [e for e in index.expressions if expressions.shape(e.pieces) == wanted]
        if any(e.first_line <= row.base_line <= e.last_line for e in matches):
            continue
        if len(matches) != 1:
            problems.append("%s: %d expressions in %s have these pieces; place this row by hand" % (
                where, len(matches), row.path))
            continue
        fields = lines[row.line - 1].split("\t")
        fields[0] = "%s:%d" % (row.path, matches[0].line)
        lines[row.line - 1] = "\t".join(fields)
        moved += 1
    return "\n".join(lines), moved, problems


def cmd_relocate(args):
    path = _ledger_path(args.batch)
    merge = l10nlib.merge_base(args.base, "HEAD")
    try:
        text, moved, problems = relocate(l10nlib.read_text(path), merge, source=os.path.basename(path))
    except ledger_check.LedgerError as error:
        print("extract: fix %s first:\n%s" % (path, error), file=sys.stderr)
        return 1
    if moved:
        l10nlib.write_text(path, text)
    for problem in problems:
        print("extract: " + problem, file=sys.stderr)
    print("extract: moved %d row(s) to their lines at %s; %d need a hand fix" % (
        moved, l10nlib.short(merge), len(problems)))
    return 1 if problems else 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command")
    scan = sub.add_parser("scan", help="list literals in Java files")
    scan.add_argument("paths", nargs="+")
    draft = sub.add_parser("draft", help="append draft ledger rows for literals that left src/")
    draft.add_argument("--base", required=True)
    draft.add_argument("--head", default=l10nlib.WORKTREE, help="head revision (default: the working tree)")
    draft.add_argument("--batch", required=True, help="ledger name, l10n/ledger/<batch>.tsv")
    move = sub.add_parser("relocate", help="move ledger rows to their lines at a new merge base")
    move.add_argument("--base", required=True)
    move.add_argument("--batch", required=True)
    args = parser.parse_args(argv)
    if args.command == "scan":
        return cmd_scan(args.paths)
    if args.command == "draft":
        return cmd_draft(args)
    if args.command == "relocate":
        return cmd_relocate(args)
    parser.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
