"""Java expressions as ledger pieces, and a positional literal diff between two versions of a file.

Shared by extract.py (drafts), ledger_check.py (G1) and frozen_literals.py (G4). Standard library only.

Pieces: a + chain such as "Move " + title + " to slot " + (i + 1) becomes
  ["Move ", {"hole": "title", ...}, " to slot ", {"hole": "(i + 1)", ...}]
and a char literal in the chain is {"char": "x"}. Only + chains are followed: a ternary, String.format,
a StringBuilder.append chain or a numeric sum before the first string is not one expression.
"""

import collections
import difflib
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l10nlib  # noqa: E402

LITERALS = ("string", "char")
TERM_OPS = ("*", "/", "%")
OPENERS = {"(": ")", "[": "]"}
CLOSERS = {")": "(", "]": "["}
KEYWORDS = {"if", "while", "for", "switch", "catch", "synchronized", "return", "throw", "case", "else", "do",
            "try", "assert", "new", "instanceof", "yield"}

Expression = collections.namedtuple("Expression", "line first_line last_line pieces tokens")
"""line: the line of its first literal; first_line and last_line: the lines it spans; pieces: ledger
pieces; tokens: indexes (into the lexed tokens) of its top-level string and char literals."""


# ---------------------------------------------------------------------------
# + chains
# ---------------------------------------------------------------------------

def match_forward(tokens, k):
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
            end = match_forward(tokens, j)
            return None if end is None else _postfix_end(tokens, end)
        return None
    if t.kind in ("string", "char", "number", "ident"):
        return _postfix_end(tokens, k)
    if _is(t, "("):
        end = match_forward(tokens, k)
        return None if end is None else _postfix_end(tokens, end)
    return None


def _postfix_end(tokens, k):
    while k + 1 < len(tokens):
        nxt = tokens[k + 1]
        if _is(nxt, "(", "["):
            end = match_forward(tokens, k + 1)
            if end is None:
                return k
            k = end
        elif _is(nxt, ".") and k + 2 < len(tokens) and tokens[k + 2].kind == "ident":
            k += 2
        else:
            return k
    return k


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


def source_of(text, tokens, first, last):
    return text[tokens[first].pos:tokens[last].pos + len(tokens[last].raw)]


def pieces_for(text, tokens, spans):
    """(pieces, indexes of the top-level literal tokens)."""
    pieces, literal_tokens, arg = [], [], 0
    for first, last in spans:
        if first == last and tokens[first].kind == "string":
            pieces.append(tokens[first].value)
            literal_tokens.append(first)
        elif first == last and tokens[first].kind == "char":
            pieces.append({"char": tokens[first].value})
            literal_tokens.append(first)
        else:
            arg += 1
            pieces.append({"hole": " ".join(source_of(text, tokens, first, last).split()), "arg": arg, "type": "s"})
    return pieces, literal_tokens


class Index(object):
    """The tokens of one Java source and its literal expressions, each literal token in exactly one."""

    def __init__(self, source):
        self.text = l10nlib.translate_unicode_escapes(source)
        self.tokens = l10nlib.lex_java(source)
        self.expressions = []
        self.by_token = {}
        for i, tok in enumerate(self.tokens):
            if tok.kind not in LITERALS or i in self.by_token:
                continue
            spans = concatenation(self.tokens, i)
            pieces, literal_tokens = pieces_for(self.text, self.tokens, spans)
            literal_tokens = [k for k in literal_tokens if k not in self.by_token] or [i]
            if i not in literal_tokens:
                pieces, literal_tokens = [tok.value if tok.kind == "string" else {"char": tok.value}], [i]
                spans = [(i, i)]
            lines = [self.tokens[k].line for k in literal_tokens]
            expression = Expression(min(lines), self.tokens[spans[0][0]].line, self.tokens[spans[-1][1]].line,
                                    pieces, tuple(literal_tokens))
            for k in literal_tokens:
                self.by_token[k] = len(self.expressions)
            self.expressions.append(expression)

    def expression_of(self, token_index):
        return self.expressions[self.by_token[token_index]]


def literal_expressions(source):
    """[(line, value, pieces, span, token index)] for every string literal, as extract.py scan lists them."""
    index = Index(source)
    result = []
    for i, tok in enumerate(index.tokens):
        if tok.kind == "string":
            e = index.expression_of(i)
            result.append((tok.line, tok.value, e.pieces, (e.first_line, e.last_line), i))
    return result


# ---------------------------------------------------------------------------
# Comparing pieces and Java source
# ---------------------------------------------------------------------------

def source_key(source):
    """A Java expression's tokens, ignoring spacing and comments; one redundant outer ( ) is dropped."""
    try:
        tokens = [t.raw for t in l10nlib.lex_java(source)]
    except l10nlib.LexError:
        return (" ".join(source.split()),)
    while len(tokens) >= 2 and tokens[0] == "(" and tokens[-1] == ")":
        depth, closes_early = 0, False
        for k, raw in enumerate(tokens):
            depth += 1 if raw == "(" else -1 if raw == ")" else 0
            if depth == 0 and k < len(tokens) - 1:
                closes_early = True
                break
        if closes_early:
            break
        tokens = tokens[1:-1]
    return tuple(tokens)


def shape(sequence):
    """What a pieces sequence says about the Java source: literals, chars and hole sources, in order."""
    out = []
    for piece in sequence:
        if isinstance(piece, str):
            out.append(("string", piece))
        elif "char" in piece:
            out.append(("char", piece["char"]))
        else:
            out.append(("hole", source_key(piece["hole"])))
    return tuple(out)


def literal_items(sequence):
    """[(kind, value)] of the string and char pieces of one pieces sequence, in order."""
    return [("string", p) if isinstance(p, str) else ("char", p["char"])
            for p in sequence if isinstance(p, str) or "char" in p]


# ---------------------------------------------------------------------------
# Positional literal diff
# ---------------------------------------------------------------------------

class FileDiff(object):
    """Which literal tokens of one file left (gone) and arrived (added) between two versions.

    Lines are aligned first (unchanged lines keep their literals). Within each changed region the
    literal sequences are aligned again, so a literal counts as unchanged only when it stays in the
    same region in the same order: swapping two literals between distant lines removes both.
    """

    def __init__(self, before, after):
        self.base = Index(before) if before is not None else None
        self.head = Index(after) if after is not None else None
        base_tokens = self.base.tokens if self.base else []
        head_tokens = self.head.tokens if self.head else []
        a = before.split("\n") if before is not None else []
        b = after.split("\n") if after is not None else []
        opcodes = difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes()
        self.gone, self.added = {}, {}
        self.hunks = []  # (base first line, base last line, head first line, head last line)
        self.hunk_added = collections.defaultdict(list)
        base_hunk, head_hunk = {}, {}
        for tag, i1, i2, j1, j2 in opcodes:
            if tag == "equal":
                continue
            number = len(self.hunks)
            self.hunks.append((i1 + 1, i2, j1 + 1, j2))
            for line in range(i1 + 1, i2 + 1):
                base_hunk[line] = number
            for line in range(j1 + 1, j2 + 1):
                head_hunk[line] = number
        in_base = self._bucket(base_tokens, base_hunk)
        in_head = self._bucket(head_tokens, head_hunk)
        for number in range(len(self.hunks)):
            left, right = in_base.get(number, []), in_head.get(number, [])
            matcher = difflib.SequenceMatcher(None, [self._key(base_tokens[k]) for k in left],
                                              [self._key(head_tokens[k]) for k in right], autojunk=False)
            kept_left, kept_right = set(), set()
            for block in matcher.get_matching_blocks():
                kept_left.update(range(block.a, block.a + block.size))
                kept_right.update(range(block.b, block.b + block.size))
            for n, k in enumerate(left):
                if n not in kept_left:
                    self.gone[k] = number
            for n, k in enumerate(right):
                if n not in kept_right:
                    self.added[k] = number
                    self.hunk_added[number].append(k)

    @staticmethod
    def _key(tok):
        return (tok.kind, tok.value)

    @staticmethod
    def _bucket(tokens, hunk_of_line):
        """{hunk: [literal token indexes]}; a token spanning lines joins the first changed line it touches."""
        result = collections.defaultdict(list)
        for k, tok in enumerate(tokens):
            if tok.kind not in LITERALS:
                continue
            for line in range(tok.line, tok.line + tok.raw.count("\n") + 1):
                if line in hunk_of_line:
                    result[hunk_of_line[line]].append(k)
                    break
        return result

    def base_token(self, k):
        return self.base.tokens[k]

    def head_token(self, k):
        return self.head.tokens[k]
