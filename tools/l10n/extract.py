#!/usr/bin/env python3
"""G1 helper: list a batch's literals, and draft ledger rows for the literals a batch moved.

  extract.py scan src/com/jjricks/volt/Ui.java ...
      Every string literal with its location, whether it looks like English text, whether it sits
      in a UI sink call, and the concatenation it belongs to (as ledger pieces). Use it to plan a batch.

  extract.py draft --base origin/main --batch b2-ui [--head HEAD|WORKTREE]
      After moving text into res/values/strings_<area>.xml and T calls, finds every literal that left
      src/ and has no ledger row yet, and appends one draft row per original expression to
      l10n/ledger/<batch>.tsv with key and kind set to TODO. Fill in key, kind, each hole's arg and
      type (the draft guesses "s"), and fix the pieces where the guess is wrong; ledger_check.py
      refuses TODO rows. The guess follows + chains only: a ternary, String.format or a numeric
      sum before the first string (a + b + "x") must be written by hand.
"""

import argparse
import collections
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l10nlib  # noqa: E402
import ledger_check  # noqa: E402
import hardcoded_lint  # noqa: E402

TERM_OPS = ("*", "/", "%")
OPENERS = {"(": ")", "[": "]"}
CLOSERS = {")": "(", "]": "["}


def _match_forward(tokens, k):
    want, depth = OPENERS[tokens[k].value], 0
    for j in range(k, len(tokens)):
        if tokens[j].kind == "op" and tokens[j].value == tokens[k].value:
            depth += 1
        elif tokens[j].kind == "op" and tokens[j].value == want:
            depth -= 1
            if depth == 0:
                return j
    return None


def _match_backward(tokens, k):
    want, depth = CLOSERS[tokens[k].value], 0
    for j in range(k, -1, -1):
        if tokens[j].kind == "op" and tokens[j].value == tokens[k].value:
            depth += 1
        elif tokens[j].kind == "op" and tokens[j].value == want:
            depth -= 1
            if depth == 0:
                return j
    return None


def _is(tok, *values):
    return tok is not None and tok.kind == "op" and tok.value in values


def _primary_end(tokens, k):
    """Last index of the primary expression starting at k, or None."""
    if k >= len(tokens):
        return None
    t = tokens[k]
    if _is(t, "-", "!", "~"):
        return _primary_end(tokens, k + 1)
    if t.kind == "ident" and t.value == "new":
        j = k + 1
        while j < len(tokens) and (tokens[j].kind == "ident" or _is(tokens[j], ".")):
            j += 1
        if j < len(tokens) and _is(tokens[j], "(", "["):
            end = _match_forward(tokens, j)
            return None if end is None else _postfix_end(tokens, end)
        return None
    if t.kind in ("string", "char", "number", "ident"):
        return _postfix_end(tokens, k)
    if _is(t, "("):
        end = _match_forward(tokens, k)
        return None if end is None else _postfix_end(tokens, end)
    return None


def _postfix_end(tokens, k):
    while k + 1 < len(tokens):
        nxt = tokens[k + 1]
        if _is(nxt, "(", "["):
            end = _match_forward(tokens, k + 1)
            if end is None:
                return k
            k = end
        elif _is(nxt, ".") and k + 2 < len(tokens) and tokens[k + 2].kind == "ident":
            k += 2
        else:
            return k
    return k


KEYWORDS = {"if", "while", "for", "switch", "catch", "synchronized", "return", "throw", "case", "else", "do",
            "try", "assert", "new", "instanceof", "yield"}


def _callee(tok):
    """True when tok can be called or indexed: a name, or the end of another call, index or group."""
    return (tok.kind == "ident" and tok.value not in KEYWORDS) or _is(tok, ")", "]")


def _primary_start(tokens, k):
    """First index of the primary expression ending at k (a name, literal, call or index chain), or None."""
    if k < 0:
        return None
    j = k
    while True:
        t = tokens[j]
        if _is(t, ")", "]"):
            opener = _match_backward(tokens, j)
            if opener is None:
                return None
            if opener > 0 and _callee(tokens[opener - 1]):
                j = opener - 1
                continue
            start = opener
        elif t.kind in ("string", "char", "number", "ident"):
            start = j
            if start > 0 and tokens[start - 1].kind == "ident" and tokens[start - 1].value == "new":
                start -= 1
        else:
            return None
        if start >= 2 and _is(tokens[start - 1], ".") and (tokens[start - 2].kind in ("ident", "string") or
                                                           _is(tokens[start - 2], ")", "]")):
            j = start - 2
            continue
        break
    if start > 0 and _is(tokens[start - 1], "-", "!", "~") and (start == 1 or tokens[start - 2].kind == "op"
                                                               and tokens[start - 2].value not in (")", "]")):
        start -= 1
    return start


def _term_end(tokens, k):
    end = _primary_end(tokens, k)
    while end is not None and end + 2 < len(tokens) and _is(tokens[end + 1], *TERM_OPS):
        nxt = _primary_end(tokens, end + 2)
        if nxt is None:
            break
        end = nxt
    return end


def _term_start(tokens, k):
    start = _primary_start(tokens, k)
    while start is not None and start >= 2 and _is(tokens[start - 1], *TERM_OPS):
        prev = _primary_start(tokens, start - 2)
        if prev is None:
            break
        start = prev
    return start


def concatenation(tokens, i):
    """[(first, last), ...] token spans of the + operands around the literal at index i."""
    spans = [(i, i)]
    k = i
    while k >= 2 and _is(tokens[k - 1], "+"):
        start = _term_start(tokens, k - 2)
        if start is None:
            break
        spans.insert(0, (start, k - 2))
        k = start
    k = i
    while k + 2 < len(tokens) and _is(tokens[k + 1], "+"):
        end = _term_end(tokens, k + 2)
        if end is None:
            break
        spans.append((k + 2, end))
        k = end
    return spans


def pieces_for(text, tokens, spans):
    pieces, arg = [], 0
    for first, last in spans:
        if first == last and tokens[first].kind == "string":
            pieces.append(tokens[first].value)
        elif first == last and tokens[first].kind == "char":
            pieces.append({"char": tokens[first].value})
        else:
            arg += 1
            source = text[tokens[first].pos:tokens[last].pos + len(tokens[last].raw)]
            pieces.append({"hole": " ".join(source.split()), "arg": arg, "type": "s"})
    return pieces


def literal_expressions(source):
    """[(line, value, pieces, span)] for every string literal, grouping each + chain once."""
    text = l10nlib.translate_unicode_escapes(source)
    tokens = l10nlib.lex_java(source)
    result = []
    for i, tok in enumerate(tokens):
        if tok.kind != "string":
            continue
        spans = concatenation(tokens, i)
        result.append((tok.line, tok.value, pieces_for(text, tokens, spans), (spans[0][0], spans[-1][1]), i))
    return result


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


def draft_rows(base, head, existing_rows, cwd=None):
    """Draft ledger lines for literals that left src/ and are not yet covered."""
    merge = l10nlib.merge_base(base, head, cwd)
    files = [p for p in l10nlib.changed_files(merge, head, ["src"], cwd) if p.endswith(".java")]
    before = l10nlib.read_files(merge, files, cwd)
    after = l10nlib.read_files(head, files, cwd)
    covered = collections.defaultdict(collections.Counter)
    for row in existing_rows:
        if row.kind in ledger_check.MOVING:
            covered[row.path].update(row.literals)
    lines = []
    for path in files:
        gone = ledger_check.literal_counter(before[path]) - ledger_check.literal_counter(after[path])
        gone = gone - covered[path]
        if not gone:
            continue
        seen_spans = set()
        for line, value, pieces, span, _ in literal_expressions(before[path]):
            if gone[value] <= 0 or span in seen_spans:
                continue
            seen_spans.add(span)
            for literal, count in ledger_check.literal_counts(pieces).items():
                gone[literal] -= count
            lines.append("%s:%d\t%s\tTODO\tTODO\t" % (path, line, json.dumps(pieces, ensure_ascii=False)))
    return lines


def cmd_draft(args):
    head = l10nlib.resolve(args.head)
    root = l10nlib.repo_root()
    path = os.path.join(root, "l10n", "ledger", args.batch + ".tsv")
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


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command")
    scan = sub.add_parser("scan", help="list literals in Java files")
    scan.add_argument("paths", nargs="+")
    draft = sub.add_parser("draft", help="append draft ledger rows for literals that left src/")
    draft.add_argument("--base", required=True)
    draft.add_argument("--head", default=l10nlib.WORKTREE, help="head revision (default: the working tree)")
    draft.add_argument("--batch", required=True, help="ledger name, l10n/ledger/<batch>.tsv")
    args = parser.parse_args(argv)
    if args.command == "scan":
        return cmd_scan(args.paths)
    if args.command == "draft":
        return cmd_draft(args)
    parser.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
