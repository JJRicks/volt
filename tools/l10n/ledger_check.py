#!/usr/bin/env python3
"""G1, the ledger of moved literals: no string or char literal may leave src/ without a ledger row.

For every src/ Java file that changed between the merge base and head, the literals are compared
by position (expressions.FileDiff): a literal is gone when it no longer sits in the same changed
region in the same order, so swapping two literals, or changing one, removes it. Every gone literal
must be covered by exactly one new row in the batch's ledger, l10n/ledger/<batch>.tsv:

  exact, template, plural  the row's pieces must equal the base expression on its line (as
                           extract.py draft writes them), every literal of it must be gone, and a
                           T.<key>(...) or M.<key>(...) call (or Msg.of / T.render with T.<KEY>)
                           must pass the row's holes in placeholder order in the head lines that
                           replaced it; each call serves one row, in the base order
  format                   a literal that mixes numbers and unit text: a String.format format string
                           ("%.1f mph") or a + chain (n + " km/h"). The pieces are the split: text
                           the template keeps, {"spec"} items for the specifiers (each with the base
                           argument it formats and the Fmt call FMT_SPECS pairs with it), {"text",
                           "key"} items for text a catalog key now holds, and the chain's holes. The
                           split must spell the base expression exactly; the head call is
                           T.<template>(...) with the Fmt calls, holes and T.<key>() calls in
                           placeholder order, replacing the whole String.format(...) call; each key
                           must hold its text, and the template, filled with sentinels and the keys'
                           English, must print the original. A plain %s passes its value through,
                           with string literals turned into T.<key>() calls that have exact rows.
                           A compiled test under tests/l10n/ must call each Fmt method it passes
                           (G10's parity); every format row is listed, with the tests calling the
                           method that held it, or a note that none does
  choice                   a + chain holding a parenthesized ternary (or chosen on by an id test,
                           "On".equals(value)) becomes a ternary between whole templates: the pieces
                           are {"if", "then", "else"} with one key per branch (the key column, then
                           before else, joined by commas); each branch must be the base expression
                           with its conditions forced, and head must hold the same conditions with
                           each branch one call of its key; every choice row is listed
  removed                  the code was deleted: the changed lines holding it only lose tokens, and
                           the whole statement holding it is gone (not just the literal and its +);
                           when its enclosing block (an if, a method) went too, every other
                           statement in that block needs a removed row of its own, or the row's note
                           starts "whole block:"; every removed row is listed for the reviewer, with
                           the whole deleted block when there is one
  stays-english, id-kept   the literal moved within src/: the same value is added elsewhere (outside
                           the changed lines it left, so a reorder or swap is not a move), and the
                           code it left either only lost tokens or names the constant it moved to:
                           NAME = "..." (a static final field) or an enum constant NAME(..., "..."),
                           which several sites may share; each region must then name, in the base
                           order of its literals, the constant each one moved to, and a shared
                           constant is listed with every site. The constant may already be in src
                           (a later batch comparing through UnitCode); each such site is listed

A row whose pieces cannot match a + chain (a ternary, String.format, a StringBuilder chain) has a
note starting "manual:"; its literals must then appear in order from its line, and it is listed
for the reviewer. When the line parses as an expression with the row's literals, a manual row must
still match it. A hole's "as" (the expression head passes) must be Fmt.<method>(..., <hole>, ...)
unless the row is manual; every "as" is listed for the reviewer. Every ledger at head (not only this batch's) is also checked against the catalog:
an exact row's English must still equal its literal and a template must still render the original,
unless l10n/english-changes.tsv records an owner-approved change of that key (its keys must exist).
Ledger files are append-only. An extraction batch adds no literal to src/ (other than ""): text goes
to the catalog, and a literal that moved needs a stays-english or id-kept row. The one exception is a
new lower-case key or id (a preference name, a requireNonNull message; l10nlib.is_identifier_like):
it is not text, but a person could read one, so every such literal is listed for the reviewer with
the statement that holds it, and it does not by itself make its changed region count as text. A new
format-only literal ("%.1f", "%,.0f": number specifiers, digits and punctuation; is_format_only) is allowed
and listed the same way.

Nothing else may change around the text either. In a changed region of a src/ file that holds or held
a literal, the only differences from the base allowed are a row's expression replaced by the call
that serves it (with a manual row, anywhere in the statement holding its literals) and a removed
row's deleted statement: wrapping a kept literal or a new T call in .toUpperCase(), or dropping code
next to it, fails. Whole new members (a method or field added next to converted text), regions a
stays-english or id-kept move touched, and every other src change are listed for the reviewer. Two
more changes are allowed and listed: a String declaration (a private field, a local, or a field
declared in a private type itself or in a local type) whose initializer becomes the row's call
turning CharSequence (for a deferred M call), with the code around every use of the variable in
its file; and a legacy locale wrapped for Fmt (Locale.US becoming Fmt.locale(Locale.US)) outside
CatalogUsageTest.ENGLISH_ONLY files and the plan's data-tier files (DATA_TIER), which needs a
compiled tests/l10n/ test calling Fmt.locale (G10). Three more are allowed and listed (gates round 3b):
a throw switching to a src subclass that keeps getMessage() English (exception_retype), a private
method's String parameter becoming Msg or CharSequence when every caller passes a catalog call
(parameter_retypes), and Locale.US becoming Locale.ROOT in a String.format whose pattern prints the
same in both (locale_swaps). A plural row's pieces may model a helper call (HELPER_MODELS).

Ledger format: a TSV with the header

  location<TAB>pieces<TAB>key<TAB>kind<TAB>note

  location  base file:line of the expression's first literal, e.g. src/com/jjricks/volt/Ui.java:812
  pieces    JSON: the original expression as literal pieces and holes, in order, e.g.
              ["Move ", {"hole": "title", "arg": 1, "type": "s"}, " to slot ", {"hole": "n", "arg": 2, "type": "d"}]
            a hole is {"hole": <Java source at base>, "arg": <placeholder index>, "type": "s"|"d"|"f"},
            plus "quantity": true for a plural's quantity and "as": <Java source> when head passes a
            different expression for it; a char literal is {"char": "x"}. For kind plural, pieces
            is an object of English plural forms: {"one": [...], "other": [...]}. For kind format,
            the split, e.g. for String.format(Locale.US, "%.1f mph", kmh):
              [{"spec": "%.1f", "arg": 1, "value": "kmh", "as": "Fmt.decimal(Locale.US, kmh, 1)"}, " ",
               {"text": "mph", "key": "units_mph", "arg": 2}]
  key       the resource key (exact, template, plural, id-kept; format: the template), or -
            (stays-english, removed)
  kind      exact | template | plural | format | id-kept | stays-english | removed
  note      free text, no tabs; "manual: <why>" for a hand-written row

A row whose location is under tests/ or android-tests/ must be kind removed; frozen_literals.py
checks those.

Usage: ledger_check.py --base <rev> [--head <rev>|WORKTREE] [--ledger FILE ...] [--catalog-only]
By default the new rows are those added to l10n/ledger/*.tsv since the merge base.
"""

import argparse
import collections
import difflib
import json
import os
import re
import shutil
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l10nlib  # noqa: E402
import gen_catalog  # noqa: E402
import expressions  # noqa: E402

HEADER = ("location", "pieces", "key", "kind", "note")
KINDS = ("exact", "template", "plural", "format", "choice", "id-kept", "stays-english", "removed")
CATALOG_KINDS = ("exact", "template", "plural", "format")
CHOICE = "choice"
MOVING = CATALOG_KINDS + ("removed", CHOICE)
STAYING = ("id-kept", "stays-english")
NEEDS_KEY = ("exact", "template", "plural", "format", "id-kept")
LOCATION = re.compile(r"^((?:src|tests|android-tests)/[^\t:]+\.java):([1-9][0-9]*)$")
LEDGER_DIR = "l10n/ledger"
CHANGES_PATH = "l10n/english-changes.tsv"
CHANGES_HEADER = ("key", "quantity", "old", "new", "note")
MANUAL_WINDOW = 30
TEST_DIRS = ("tests/", "android-tests/")

Row = collections.namedtuple("Row", "source line text path base_line pieces key kind note literals split")
Row.__new__.__defaults__ = (None,)
"""pieces: the base expression's pieces (for a format row, derived from its split); split: a format
row's split, else None."""


class LedgerError(ValueError):
    pass


def _check_sequence(sequence, where):
    if not isinstance(sequence, list):
        raise LedgerError("%s: pieces must be a JSON array" % where)
    for piece in sequence:
        if isinstance(piece, str):
            continue
        if not isinstance(piece, dict):
            raise LedgerError("%s: each piece is a string or an object" % where)
        if "char" in piece:
            if set(piece) != {"char"} or not isinstance(piece["char"], str) or len(piece["char"]) != 1:
                raise LedgerError("%s: a char piece is {\"char\": \"x\"}" % where)
            continue
        if "helper" in piece:
            _check_helper(piece, where)
            continue
        if not isinstance(piece.get("hole"), str) or not piece["hole"].strip():
            raise LedgerError("%s: a hole needs \"hole\": the Java source of the value" % where)
        if not isinstance(piece.get("arg"), int) or piece["arg"] < 1:
            raise LedgerError("%s: a hole needs \"arg\": its placeholder index, from 1" % where)
        if piece.get("type") not in ("s", "d", "f"):
            raise LedgerError("%s: a hole needs \"type\": s, d or f" % where)
        if "as" in piece and (not isinstance(piece["as"], str) or not piece["as"].strip()):
            raise LedgerError("%s: a hole's \"as\" is the Java source head passes" % where)
        extra = set(piece) - {"hole", "arg", "type", "quantity", "as"}
        if extra:
            raise LedgerError("%s: unknown hole field(s) %s" % (where, ", ".join(sorted(extra))))


# Helpers whose output a plural row may model (B10's BackupArchive.plural): the parameter types, the exact
# body tokens with {0}, {1} for the parameter names, and what each argument of the row's call must be
# ("count": a whole-number hole, "text": a string literal or a text hole). G1 requires the helper's
# declaration in the row's file at the merge base to be exactly this; tests/l10n/LedgerTest evaluates the
# same model (LedgerTest.helper) with the sentinel quantities.
HELPER_MODELS = {
    "count_noun": ((("int", "long"), ("String",)),
                   'return {0} + " " + {1} + ( {0} == 1 ? "" : "s" ) ;',
                   ("count", "text")),
}


def _check_helper(piece, where):
    if set(piece) != {"helper", "model", "args"} or not isinstance(piece["helper"], str) or \
            not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", piece["helper"]) or not isinstance(piece["args"], list):
        raise LedgerError("%s: a helper piece is {\"helper\": <method name>, \"model\": <one of %s>, \"args\": [...]}" % (
            where, ", ".join(sorted(HELPER_MODELS))))
    model = HELPER_MODELS.get(piece["model"])
    if model is None:
        raise LedgerError("%s: unknown helper model %r (known: %s)" % (where, piece["model"], ", ".join(sorted(HELPER_MODELS))))
    if len(piece["args"]) != len(model[2]):
        raise LedgerError("%s: helper model %s takes %d argument(s)" % (where, piece["model"], len(model[2])))
    for arg, want in zip(piece["args"], model[2]):
        if isinstance(arg, str):
            if want != "text":
                raise LedgerError("%s: helper model %s takes a whole-number hole there, not the text %r" % (
                    where, piece["model"], arg))
            continue
        _check_sequence([arg], where)
        if "hole" not in arg or (want == "count") != (arg["type"] == "d"):
            raise LedgerError("%s: helper model %s's %s argument is a %s hole" % (
                where, piece["model"], want, "whole-number (type d)" if want == "count" else "text (type s)"))
        if (want == "count") != bool(arg.get("quantity")):
            raise LedgerError("%s: helper model %s's %s argument %s the plural's quantity (\"quantity\": true), so "
                              "G2 checks each form at the quantities that pick it" % (
                                  where, piece["model"], want, "must be" if want == "count" else "cannot be"))


def _check_helper_row(pieces, kind, where):
    """A helper piece only in a plural row whose forms are all the one base expression, with one and other,
    and a single quantity source: the helper pluralizes, the catalog's forms must print what it prints."""
    if not helper_pieces(pieces):
        return
    if kind != "plural":
        raise LedgerError("%s: a helper piece belongs only in a plural row (its count is the plural's quantity)" % where)
    forms = list(pieces.values())
    if "one" not in pieces or any(f != forms[0] for f in forms):
        raise LedgerError("%s: a plural row with a helper piece has one and other forms, each the same base expression "
                          "(the helper chooses the words)" % where)
    sources = set(expressions.source_key(h.get("as", h["hole"])) for h in holes(pieces) if h.get("quantity"))
    if len(sources) != 1:
        raise LedgerError("%s: a plural row with a helper piece has exactly one quantity source" % where)


# What each model prints, for G1's own check of a helper plural row (LedgerTest.helper is the Java twin).
HELPER_OUTPUT = {
    "count_noun": lambda count, noun: "%d %s%s" % (count, noun, "" if count == 1 else "s"),
}
# The quantities G1 and G2 check each English plural form at (LedgerTest.compare).
FORM_QUANTITIES = {"one": (1,), "other": (0, 2, 21)}
HELPER_PLACEHOLDER = re.compile(r"%(\d+)\$([sd])|%%")


def _sentinel(hole, quantity):
    if hole.get("quantity"):
        return quantity
    return 1234567 if hole["type"] == "d" else chr(1) + chr(64 + hole["arg"])


def evaluate_helper_form(sequence, quantity):
    """What the base expression of a helper plural row prints at quantity, with sentinel holes."""
    out = []
    for p in sequence:
        if isinstance(p, str):
            out.append(p)
        elif "char" in p:
            out.append(p["char"])
        elif "helper" in p:
            out.append(HELPER_OUTPUT[p["model"]](*[a if isinstance(a, str) else _sentinel(a, quantity)
                                                   for a in p["args"]]))
        else:
            out.append(str(_sentinel(p, quantity)))
    return "".join(out)


def render_helper_form(template, sequence, quantity):
    """The catalog form filled with the same sentinels, or None when it uses other conversions (G2 checks it)."""
    if re.search(r"%(?!(\d+\$[sd]|%))", template):
        return None
    by_arg = {h["arg"]: h for h in holes(sequence) if "arg" in h}

    def substitute(m):
        if m.group(0) == "%%":
            return "%"
        hole = by_arg.get(int(m.group(1)))
        if hole is None or (hole["type"] == "d") != (m.group(2) == "d"):
            return m.group(0)
        return str(_sentinel(hole, quantity))
    return HELPER_PLACEHOLDER.sub(substitute, template)


def helper_source(piece):
    """The Java call a helper piece stands for: plural(skipped, "scooter")."""
    return "%s(%s)" % (piece["helper"], ", ".join(
        l10nlib.json_string(a) if isinstance(a, str) else a["hole"] for a in piece["args"]))


def helper_pieces(pieces):
    """The helper pieces of a row, once each (plural forms repeat them)."""
    found = []
    for p in (p for s in sequences(pieces) for p in s if isinstance(p, dict) and "helper" in p):
        if p not in found:
            found.append(p)
    return found


def helper_problem(piece, index):
    """None when the file at the merge base declares the helper exactly as its model says, else why."""
    model = HELPER_MODELS[piece["model"]]
    tokens = index.tokens
    found = []
    for k, opener, close, first in method_declarations(tokens):
        params = _parameter_type_tokens(tokens, opener, close)
        if tokens[k].value == piece["helper"] and len(params) == len(model[0]):
            found.append((k, opener, close, params))
    if len(found) != 1:
        return "helper %s(...) with %d parameter(s) is declared %d time(s) in the file at the merge base, not once" % (
            piece["helper"], len(model[0]), len(found))
    k, opener, close, params = found[0]
    for (type_token, _), allowed in zip(params, model[0]):
        if type_token is None or tokens[type_token].value not in allowed:
            return "helper %s's parameters are not (%s)" % (piece["helper"], ", ".join("|".join(a) for a in model[0]))
    body = close + 1
    while body < len(tokens) and tokens[body].value != "{":
        body += 1
    end = expressions.match_brace(tokens, body)
    want = [t.raw for t in l10nlib.lex_java(model[1].format(*[name for _, name in params]))]
    got = [t.raw for t in tokens[body + 1:end]]
    if got != want:
        return "helper %s's body is `%s`, not model %s's `%s`" % (piece["helper"], " ".join(got), piece["model"],
                                                                  " ".join(want))
    return None


# A java.util.Formatter specifier: %[index$][flags][width][.precision]conversion. %% and %n take no argument.
FORMAT_SPEC = re.compile(r"%(\d+\$)?([-#+ 0,(]*)(\d+)?(\.\d+)?([tT][A-Za-z]|[A-Za-z%])")
# Conversions that print a number and never a word (%s, %b, %c, %S and the %t dates can).
NUMBER_CONVERSIONS = frozenset("dofeEgGxX")


def is_format_only(value):
    """True for a literal made of number specifiers, digits and punctuation only ("%.1f", "%d",
    "%,.0f", "%.1f–%.1f"): no letter outside a specifier, every specifier a number conversion
    (NUMBER_CONVERSIONS, or %% and %n), and at least one of them. It holds no words, so an extraction
    batch may add one; every one is listed."""
    rest, found, at = [], False, 0
    for m in FORMAT_SPEC.finditer(value):
        rest.append(value[at:m.start()])
        at = m.end()
        if m.group(5) in ("%", "n"):
            continue
        if m.group(5) not in NUMBER_CONVERSIONS:
            return False
        found = True
    rest.append(value[at:])
    leftover = "".join(rest)
    return found and "%" not in leftover and not any(c.isalpha() for c in leftover)


def format_text(raw):
    """What a piece of a String.format format string prints: %% is %, %n a line break."""
    return re.sub(r"%[%n]", lambda m: "%" if m.group(0) == "%%" else "\n", raw)


def _check_split(split, where):
    """The base pieces a format row's split reassembles to (strings joined, holes kept), checking each
    item. Raises LedgerError."""
    if not isinstance(split, list) or not split:
        raise LedgerError("%s: a format row's pieces are its split: a JSON array of text, {\"spec\": ...}, "
                          "{\"text\": ..., \"key\": ...} and hole items" % where)
    derived, buffer, args = [], [], {}
    kinds = set()
    for item in split:
        if isinstance(item, str):
            if not item:
                raise LedgerError("%s: an empty text piece" % where)
            buffer.append(item)
            continue
        if not isinstance(item, dict):
            raise LedgerError("%s: each split item is a string or an object" % where)
        if "spec" in item:
            if set(item) != {"spec", "arg", "value", "as"}:
                raise LedgerError("%s: a spec item is {\"spec\": \"%%.1f\", \"arg\": n, \"value\": <base argument>, "
                                  "\"as\": <the Fmt call head passes>}" % where)
            m = FORMAT_SPEC.fullmatch(item["spec"]) if isinstance(item["spec"], str) else None
            if m is None or m.group(5) in ("%", "n"):
                raise LedgerError("%s: %r is not a format specifier that takes an argument (%%%% and %%n are text)" % (
                    where, item["spec"]))
            for field in ("value", "as"):
                if not isinstance(item[field], str) or not item[field].strip():
                    raise LedgerError("%s: a spec item's %r is Java source" % (where, field))
            buffer.append(item["spec"])
            kinds.add("spec")
        elif "key" in item:
            if set(item) != {"text", "key", "arg"}:
                raise LedgerError("%s: a text item is {\"text\": <the literal's text>, \"key\": <catalog key>, \"arg\": n}"
                                  % where)
            if not isinstance(item["key"], str) or not gen_catalog.KEY_PATTERN.match(item["key"]):
                raise LedgerError("%s: a text item needs a resource key" % where)
            if not isinstance(item["text"], str) or not item["text"]:
                raise LedgerError("%s: a text item's text is the part of the literal its key holds" % where)
            buffer.append(item["text"])
            kinds.add("key")
        elif "hole" in item:
            _check_sequence([item], where)
            if item["type"] != "s":
                raise LedgerError("%s: a format row's holes are text (%%n$s): type s" % where)
            if "quantity" in item:
                raise LedgerError("%s: a format row has no plural quantity" % where)
            if "as" in item and not fmt_wrapper(item):
                raise LedgerError("%s: hole %s is passed as %s; a format row's \"as\" must be Fmt.<method>(..., %s, ...)"
                                  % (where, item["hole"], item["as"], item["hole"]))
            if buffer:
                derived.append("".join(buffer))
                buffer = []
            derived.append(dict(item))
            kinds.add("hole")
        else:
            raise LedgerError("%s: unknown split item %s" % (where, json.dumps(item, ensure_ascii=False)))
        if not isinstance(item.get("arg"), int) or isinstance(item.get("arg"), bool) or item["arg"] < 1:
            raise LedgerError("%s: each spec, text and hole item needs \"arg\": its placeholder index, from 1" % where)
        if item["arg"] in args:
            raise LedgerError("%s: two split items claim arg %d" % (where, item["arg"]))
        args[item["arg"]] = item
    if buffer:
        derived.append("".join(buffer))
    if sorted(args) != list(range(1, len(args) + 1)):
        raise LedgerError("%s: the split's args must be 1 to %d with no gaps" % (where, len(args)))
    if not kinds & {"spec", "key"}:
        raise LedgerError("%s: a format row splits a specifier out to Fmt or text out to a key; without either "
                          "it is a template row" % where)
    if "spec" in kinds:
        if "hole" in kinds:
            raise LedgerError("%s: spec items split a String.format literal, which has no holes" % where)
        for item in split:
            text = item if isinstance(item, str) else item.get("text")
            if text is not None and "%" in re.sub(r"%[%n]", "", text):
                raise LedgerError("%s: the text %r holds a format specifier; split it out as a spec item" % (where, text))
    return derived


def choice_leaves(node):
    """The pieces sequences of a choice row's branches, then before else, depth first."""
    if isinstance(node, dict) and "if" in node:
        return choice_leaves(node["then"]) + choice_leaves(node["else"])
    return [node]


def choice_conditions(node):
    """[condition source] of a choice row, in the order its branches nest."""
    if isinstance(node, dict) and "if" in node:
        return [node["if"]] + choice_conditions(node["then"]) + choice_conditions(node["else"])
    return []


def _check_choice(node, where, depth=0):
    if isinstance(node, list):
        _check_sequence(node, where)
        return
    if not isinstance(node, dict) or set(node) != {"if", "then", "else"} or not isinstance(node["if"], str) or \
            not node["if"].strip():
        raise LedgerError("%s: a choice row's pieces are {\"if\": <condition Java>, \"then\": <branch>, \"else\": "
                          "<branch>}, each branch a pieces array or another such object" % where)
    if depth > 8:
        raise LedgerError("%s: a choice row nests at most 8 conditions" % where)
    try:
        l10nlib.lex_java(node["if"])
    except l10nlib.LexError as error:
        raise LedgerError("%s: the condition %r is not Java: %s" % (where, node["if"], error))
    _check_choice(node["then"], where, depth + 1)
    _check_choice(node["else"], where, depth + 1)


def sequences(pieces):
    if isinstance(pieces, dict) and "if" in pieces:
        return choice_leaves(pieces)
    return list(pieces.values()) if isinstance(pieces, dict) else [pieces]


def literal_counts(pieces):
    """The string literals an expression consumed, as a Counter (a helper call's literal arguments too).

    For plural forms a literal shared by several forms counts once (the per-value maximum).
    """
    total = collections.Counter()
    for sequence in sequences(pieces):
        flat = [p for p in sequence if isinstance(p, str)] + [
            a for p in sequence if isinstance(p, dict) and "helper" in p for a in p["args"] if isinstance(a, str)]
        for value, count in collections.Counter(flat).items():
            total[value] = max(total[value], count)
    return total


def holes(pieces):
    """Every hole of the pieces, with those inside a helper call's arguments."""
    found = []
    for s in sequences(pieces):
        for p in s:
            if isinstance(p, dict) and "hole" in p:
                found.append(p)
            elif isinstance(p, dict) and "helper" in p:
                found.extend(a for a in p["args"] if isinstance(a, dict) and "hole" in a)
    return found


def parse_ledger(text, source="ledger"):
    """Rows of one ledger file. Raises LedgerError with every problem."""
    try:
        _, rows = l10nlib.read_tsv(text, HEADER)
    except ValueError as error:
        raise LedgerError("%s: %s" % (source, error))
    raw_lines = text.split("\n")
    result, problems = [], []
    for line, (location, pieces_text, key, kind, note) in rows:
        where = "%s:%d" % (source, line)
        try:
            m = LOCATION.match(location)
            if not m:
                raise LedgerError("%s: location must be <src|tests|android-tests path>.java:<line>" % where)
            if kind == "TODO" or key == "TODO":
                raise LedgerError("%s: unfinished draft row (key or kind is TODO)" % where)
            if kind not in KINDS:
                raise LedgerError("%s: kind must be one of %s" % (where, ", ".join(KINDS)))
            if kind in NEEDS_KEY and not gen_catalog.KEY_PATTERN.match(key):
                raise LedgerError("%s: kind %s needs a resource key" % (where, kind))
            if kind == CHOICE and not all(gen_catalog.KEY_PATTERN.match(k) for k in key.split(",")):
                raise LedgerError("%s: a choice row's key is its branches' resource keys, then before else, joined "
                                  "by commas" % where)
            if kind not in NEEDS_KEY and kind != CHOICE and key not in ("-", ""):
                raise LedgerError("%s: kind %s takes no key; write -" % (where, kind))
            if m.group(1).startswith(TEST_DIRS) and kind != "removed":
                raise LedgerError("%s: a row for a test file must be kind removed (a test of deleted code)" % where)
            try:
                pieces = json.loads(pieces_text)
            except ValueError as error:
                raise LedgerError("%s: pieces is not valid JSON: %s" % (where, error))
            split = None
            if kind == "plural":
                if not isinstance(pieces, dict) or "other" not in pieces or \
                        any(q not in gen_catalog.QUANTITIES for q in pieces):
                    raise LedgerError("%s: plural pieces are {\"one\": [...], \"other\": [...]}" % where)
                for quantity, sequence in pieces.items():
                    _check_sequence(sequence, "%s[%s]" % (where, quantity))
                _check_helper_row(pieces, kind, where)
            elif kind == CHOICE:
                if note.startswith("manual:"):
                    raise LedgerError("%s: a choice row is checked whole; it takes no \"manual:\" note" % where)
                _check_choice(pieces, where)
                _check_helper_row(pieces, kind, where)
                if len(choice_leaves(pieces)) != len(key.split(",")):
                    raise LedgerError("%s: a choice row has %d branch(es) and %d key(s)" % (
                        where, len(choice_leaves(pieces)), len(key.split(","))))
            elif kind == "format":
                if note.startswith("manual:"):
                    raise LedgerError("%s: a format row is checked whole; it takes no \"manual:\" note" % where)
                split, pieces = pieces, _check_split(pieces, where)
                _check_helper_row(pieces, kind, where)
            else:
                _check_sequence(pieces, where)
                _check_helper_row(pieces, kind, where)
            if kind == "exact" and (len(pieces) != 1 or not isinstance(pieces[0], str)):
                raise LedgerError("%s: an exact row has exactly one string piece" % where)
            # A choice row's branches are templates of their own; plural forms share their args.
            for group in (choice_leaves(pieces) if kind == CHOICE else [pieces]):
                args = {}
                for hole in holes(group):
                    if hole.get("quantity") and "arg" not in hole:
                        continue
                    previous = args.setdefault(hole["arg"], hole)
                    if expressions.source_key(previous.get("as", previous["hole"])) != \
                            expressions.source_key(hole.get("as", hole["hole"])) or previous["type"] != hole["type"]:
                        raise LedgerError("%s: two holes claim arg %d with different sources or types" % (
                            where, hole["arg"]))
            for hole in holes(pieces):
                if "as" in hole and not fmt_wrapper(hole) and not note.startswith("manual:"):
                    raise LedgerError("%s: hole %s is passed as %s; only Fmt.<method>(..., %s, ...) is checked, so "
                                      "any other \"as\" needs a note starting \"manual:\"" % (
                                          where, hole["hole"], hole["as"], hole["hole"]))
            result.append(Row(source, line, raw_lines[line - 1].rstrip("\r"), m.group(1), int(m.group(2)), pieces,
                              key, kind, note, literal_counts(pieces), split))
        except LedgerError as error:
            problems.append(str(error))
    if problems:
        raise LedgerError("\n".join(problems))
    return result


def load_rows(paths_and_texts):
    rows, problems = [], []
    for source, text in paths_and_texts:
        try:
            rows.extend(parse_ledger(text, source))
        except LedgerError as error:
            problems.extend(str(error).split("\n"))
    return rows, problems


def _ledger_names(rev, cwd=None):
    return [p for p in l10nlib.list_files(rev, [LEDGER_DIR], cwd) if p.endswith(".tsv")]


def range_rows(merge, head, explicit=None, cwd=None):
    """(new rows, problems): rows added to l10n/ledger/*.tsv since merge (append-only), or every row of
    the explicit files."""
    if explicit:
        return load_rows([(p, l10nlib.read_text(p)) for p in explicit])
    names = [p for p in l10nlib.changed_files(merge, head, [LEDGER_DIR], cwd) if p.endswith(".tsv")]
    after = l10nlib.read_files(head, names, cwd)
    before = l10nlib.read_files(merge, names, cwd)
    rows, problems = [], []
    for name in names:
        if after[name] is None:
            problems.append("%s: deleted; ledger files are append-only" % name)
            continue
        try:
            head_rows = parse_ledger(after[name], name)
        except LedgerError as error:
            problems.extend(str(error).split("\n"))
            continue
        old = collections.Counter()
        if before[name]:
            try:
                old.update(r.text for r in parse_ledger(before[name], name))
            except LedgerError:
                pass
        for row in head_rows:
            if old[row.text] > 0:
                old[row.text] -= 1
            else:
                rows.append(row)
        for text, count in old.items():
            if count > 0:
                problems.append("%s: row %r was removed or edited; ledger files are append-only" % (name, text[:80]))
    return rows, problems


# ---------------------------------------------------------------------------
# The catalog side: every ledger at head, with recorded English changes
# ---------------------------------------------------------------------------

def _catalog_at(head, cwd=None):
    """Catalog entries at head, by name, or {} when head has no strings files."""
    names = [p for p in l10nlib.list_files(head, ["res/values"], cwd)
             if re.match(r"^res/values/strings.*\.xml$", p)]
    if not names:
        return {}
    texts = l10nlib.read_files(head, names, cwd)
    work = tempfile.mkdtemp(prefix="l10n-ledger-")
    try:
        for name in names:
            l10nlib.write_text(os.path.join(work, name), texts[name])
        entries, _ = gen_catalog.load_catalog(os.path.join(work, "res", "values"), work)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    return {e.name: e for e in entries}


def parse_changes(text, source=CHANGES_PATH):
    """{(key, quantity): [(old, new, where)]} in file order. quantity is "other" for a string."""
    result = collections.defaultdict(list)
    if not text:
        return result
    _, rows = l10nlib.read_tsv(text, CHANGES_HEADER)
    for line, (key, quantity, old, new, note) in rows:
        where = "%s:%d" % (source, line)
        if not gen_catalog.KEY_PATTERN.match(key):
            raise LedgerError("%s: bad key %r" % (where, key))
        quantity = "other" if quantity in ("", "-") else quantity
        if quantity not in gen_catalog.QUANTITIES:
            raise LedgerError("%s: quantity must be - or a plural category" % where)
        if not note.strip():
            raise LedgerError("%s: note the owner approval (PR or decision)" % where)
        result[(key, quantity)].append((l10nlib.unescape_field(old), l10nlib.unescape_field(new), where))
    return result


def extraction_text(entry, quantity, changes, problems, where):
    """The English a key had when it was extracted: today's catalog text with recorded changes undone."""
    text = entry.items[quantity].text
    for old, new, change_where in reversed(changes.get((entry.name, quantity), [])):
        if text != new:
            problems.append("%s: %s says %s[%s] became %r, but the next text is %r" % (
                change_where, CHANGES_PATH, entry.name, quantity, new, text))
            return None
        text = old
    return text


SIMPLE_PLACEHOLDER = re.compile(r"%(\d+)\$s|%%")


def render_simple(template, pieces):
    """(template rendered, original rendered) with sentinel strings, when every hole is a plain %n$s.

    None when the template uses other conversions (tests/l10n/LedgerTest covers those with Java's formatter).
    """
    found = [p for p in pieces if isinstance(p, dict) and "hole" in p]
    if any(h["type"] != "s" for h in found) or re.search(r"%(?!(\d+\$s|%))", template) or \
            any(isinstance(p, dict) and "helper" in p for p in pieces):
        return None
    sentinel = {h["arg"]: chr(1) + chr(64 + h["arg"]) for h in found}

    def substitute(m):
        if m.group(0) == "%%":
            return "%"
        return sentinel.get(int(m.group(1)), m.group(0))
    original = "".join(p if isinstance(p, str) else p["char"] if "char" in p else sentinel[p["arg"]] for p in pieces)
    return SIMPLE_PLACEHOLDER.sub(substitute, template), original


def split_is_format_literal(split):
    return any(isinstance(item, dict) and "spec" in item for item in split)


def check_format_catalog(row, entry, catalog, changes, where):
    """A format row against the catalog: every text item's key holds exactly that text, and the template,
    filled with sentinels for the numbers and holes and with the keys' English for the text items,
    prints the original literal (or + chain) with the same sentinels."""
    problems = []
    template = extraction_text(entry, "other", changes, problems, where)
    if template is None:
        return problems
    if not re.fullmatch(r"(?:[^%]|%%|%[1-9][0-9]*\$s)*", template):
        return problems + ["%s: format row: template %s is %r; a format row's template takes only %%n$s placeholders" % (
            where, row.key, template)]
    literal = split_is_format_literal(row.split)
    shown = format_text if literal else (lambda raw: raw)
    values, original = {}, []
    for item in row.split:
        if isinstance(item, str):
            original.append(shown(item))
            continue
        sentinel = chr(1) + chr(64 + item["arg"])
        if "key" not in item:
            values[item["arg"]] = sentinel
            original.append(sentinel)
            continue
        text = shown(item["text"])
        values[item["arg"]] = text
        original.append(text)
        unit = catalog.get(item["key"])
        if unit is None:
            problems.append("%s: format row: text key %s is not in res/values/strings*.xml" % (where, item["key"]))
            continue
        if unit.kind != "string":
            problems.append("%s: format row: text key %s is a %s, not a string" % (where, item["key"], unit.kind))
            continue
        english = extraction_text(unit, "other", changes, problems, where)
        if english is not None and english != text:
            problems.append("%s: format row: text key %s is %r but that part of the literal was %r" % (
                where, item["key"], english, text))

    def substitute(m):
        if m.group(0) == "%%":
            return "%"
        return values.get(int(m.group(1)), m.group(0))
    rendered, original = SIMPLE_PLACEHOLDER.sub(substitute, template), "".join(original)
    if rendered != original:
        problems.append("%s: format row: template %s renders %r with its text keys and sentinels, the original %r" % (
            where, row.key, rendered, original))
    return problems


def check_against_catalog(rows, catalog, changes=None):
    problems = []
    changes = changes or {}
    for row in rows:
        if row.kind == CHOICE:
            where = "%s:%d" % (row.source, row.line)
            for key, sequence in zip(row.key.split(","), choice_leaves(row.pieces)):
                entry = catalog.get(key)
                if entry is None:
                    problems.append("%s: key %s is not in res/values/strings*.xml" % (where, key))
                    continue
                if entry.kind != "string":
                    problems.append("%s: choice key %s is a %s, but each branch is one string" % (where, key, entry.kind))
                    continue
                text = extraction_text(entry, "other", changes, problems, where)
                rendered = render_simple(text, sequence) if text is not None else None
                if rendered is not None and rendered[0] != rendered[1]:
                    problems.append("%s: choice branch %s renders %r with sentinels, the original %r" % (
                        where, key, rendered[0], rendered[1]))
            continue
        if row.kind not in CATALOG_KINDS + ("id-kept",):
            continue
        where = "%s:%d" % (row.source, row.line)
        entry = catalog.get(row.key)
        if entry is None:
            problems.append("%s: key %s is not in res/values/strings*.xml" % (where, row.key))
            continue
        if row.kind == "id-kept":
            continue
        want = "plural" if row.kind == "plural" else "string"
        if entry.kind != want:
            problems.append("%s: key %s is a %s, but the row's kind is %s" % (where, row.key, entry.kind, row.kind))
            continue
        if row.kind == "format":
            problems.extend(check_format_catalog(row, entry, catalog, changes, where))
        elif row.kind == "exact":
            text = extraction_text(entry, "other", changes, problems, where)
            if text is not None and text != row.pieces[0]:
                problems.append("%s: exact row: %s is %r but the literal was %r" % (where, row.key, text, row.pieces[0]))
        elif row.kind == "template":
            text = extraction_text(entry, "other", changes, problems, where)
            rendered = render_simple(text, row.pieces) if text is not None else None
            if rendered is not None and rendered[0] != rendered[1]:
                problems.append("%s: template %s renders %r with sentinels, the original %r" % (
                    where, row.key, rendered[0], rendered[1]))
        elif helper_pieces(row.pieces):
            for quantity in sorted(entry.items):
                if quantity not in row.pieces:
                    problems.append("%s: plural %s has a %s item, but the helper row models no %s form" % (
                        where, row.key, quantity, quantity))
                    continue
                text = extraction_text(entry, quantity, changes, problems, where)
                for q in FORM_QUANTITIES.get(quantity, ()) if text is not None else ():
                    rendered = render_helper_form(text, row.pieces[quantity], q)
                    expected = evaluate_helper_form(row.pieces[quantity], q)
                    if rendered is not None and rendered != expected:
                        problems.append("%s: plural %s[%s] at quantity %d renders %r, but the base expression prints "
                                        "%r" % (where, row.key, quantity, q, rendered, expected))
        else:
            for quantity, sequence in row.pieces.items():
                if quantity not in entry.items:
                    problems.append("%s: plural %s has no %s item" % (where, row.key, quantity))
                    continue
                text = extraction_text(entry, quantity, changes, problems, where)
                rendered = render_simple(text, sequence) if text is not None else None
                if rendered is not None and rendered[0] != rendered[1]:
                    problems.append("%s: plural %s[%s] renders %r with sentinels, the original %r" % (
                        where, row.key, quantity, rendered[0], rendered[1]))
    return problems


def catalog_consistency(head, cwd=None):
    """(problems, rows checked): every ledger row at head against the catalog at head."""
    names = _ledger_names(head, cwd)
    texts = l10nlib.read_files(head, names + [CHANGES_PATH], cwd)
    rows, problems = load_rows([(n, texts[n]) for n in names if texts[n] is not None])
    try:
        changes = parse_changes(texts.get(CHANGES_PATH))
    except (LedgerError, ValueError) as error:
        return problems + [str(error)], len(rows)
    if rows or changes:
        try:
            catalog = _catalog_at(head, cwd)
        except gen_catalog.CatalogError as error:
            return problems + ["catalog: " + p for p in error.problems], len(rows)
        problems.extend(check_against_catalog(rows, catalog, changes))
        checked = set(r.key for r in rows if r.kind in CATALOG_KINDS)
        checked.update(k for r in rows if r.kind == CHOICE for k in r.key.split(","))
        checked.update(item["key"] for r in rows if r.kind == "format" for item in r.split
                       if isinstance(item, dict) and "key" in item)
        problems.extend(check_changes(changes, catalog, checked))
    return problems, len(rows)


def check_changes(changes, catalog, ledger_keys):
    """Every recorded English change names a catalog key and quantity, and (for keys no ledger row
    checks) its last new text is today's text."""
    problems = []
    for (key, quantity), entries in sorted(changes.items()):
        where = entries[0][2]
        entry = catalog.get(key)
        if entry is None:
            problems.append("%s: %s names key %s, which is not in res/values/strings*.xml" % (where, CHANGES_PATH, key))
        elif quantity not in entry.items:
            problems.append("%s: %s names %s[%s], which the catalog does not have" % (where, CHANGES_PATH, key, quantity))
        elif key not in ledger_keys and entries[-1][1] != entry.items[quantity].text:
            problems.append("%s: %s says %s[%s] became %r, but it is %r" % (
                entries[-1][2], CHANGES_PATH, key, quantity, entries[-1][1], entry.items[quantity].text))
    return problems


# ---------------------------------------------------------------------------
# The source side: where each new row's literals were, and where they went
# ---------------------------------------------------------------------------

def _first_literal_on(index, line):
    for k, tok in enumerate(index.tokens):
        if tok.kind in expressions.LITERALS and tok.line >= line:
            return k if tok.line == line else None
    return None


def locate(row, diff, claimed):
    """(token indexes, manual, problem) for the base literals one src row describes."""
    index = diff.base
    shapes = set(expressions.shape(s) for s in sequences(row.pieces))
    if len(shapes) == 1:
        wanted = shapes.pop()
        candidates = [e for e in index.expressions if e.first_line <= row.base_line <= e.last_line and
                      expressions.shape(e.pieces) == wanted]
        if candidates:
            def score(e):
                free = not any((row.path, k) in claimed for k in e.tokens)
                gone = all(k in diff.gone for k in e.tokens)
                return (free, gone == (row.kind in MOVING))
            best = max(candidates, key=score)
            found = list(best.tokens)
            names = set(p["helper"] for p in helper_pieces(row.pieces))
            if names:
                # A helper call's literal arguments (plural(n, "scooter")) leave with the expression.
                tokens = index.tokens
                for j in range(best.span[0], best.span[1] + 1):
                    if tokens[j].kind == "string" and j not in found and _op(tokens, j - 1, "(", ",") and \
                            _op(tokens, j + 1, ")", ","):
                        depth, o = 0, j - 1
                        while o >= 0:
                            if _op(tokens, o, ")"):
                                depth += 1
                            elif _op(tokens, o, "("):
                                if depth == 0:
                                    break
                                depth -= 1
                            o -= 1
                        if o > 0 and tokens[o - 1].kind == "ident" and tokens[o - 1].value in names and \
                                not _op(tokens, o - 2, "."):
                            found.append(j)
            return sorted(found), False, None
    if row.kind == "format":
        on_line = [json.dumps(e.pieces, ensure_ascii=False) for e in index.expressions
                   if e.first_line <= row.base_line <= e.last_line]
        return None, False, ("format row: its split reassembles to %s, which is not the expression on line %d of %s "
                             "at the merge base%s; the split must spell the original exactly, in order" % (
                                 json.dumps(row.pieces, ensure_ascii=False), row.base_line, row.path,
                                 " (found: %s)" % "; ".join(on_line) if on_line else " (no literal there)"))
    if not row.note.startswith("manual:"):
        on_line = [json.dumps(e.pieces, ensure_ascii=False) for e in index.expressions
                   if e.first_line <= row.base_line <= e.last_line]
        return None, False, ("the pieces do not match the expression on line %d of %s at the merge base%s; a "
                             "hand-written row (ternary, String.format, append chain) needs a note starting "
                             "\"manual:\"" % (row.base_line, row.path,
                                              " (found: %s)" % "; ".join(on_line) if on_line else " (no literal there)"))
    if not isinstance(row.pieces, dict):
        # The note excuses a shape the + chain parser cannot follow, not a mismatch with one it did parse.
        items = expressions.literal_items(row.pieces)
        parsed = [json.dumps(e.pieces, ensure_ascii=False) for e in index.expressions
                  if e.first_line <= row.base_line <= e.last_line and expressions.literal_items(e.pieces) == items]
        if parsed:
            return None, True, ("manual row, but line %d of %s parses as an expression with the same literals, and "
                                "the pieces do not match it (found: %s)" % (row.base_line, row.path, "; ".join(parsed)))
    start = _first_literal_on(index, row.base_line)
    if start is None:
        return None, True, "no literal on line %d of %s at the merge base" % (row.base_line, row.path)
    window = [k for k in range(start, len(index.tokens)) if index.tokens[k].kind in expressions.LITERALS and
              index.tokens[k].line < row.base_line + MANUAL_WINDOW]
    found = set()
    for sequence in sequences(row.pieces):
        at = 0
        for item in expressions.literal_items(sequence):
            while at < len(window) and (index.tokens[window[at]].kind, index.tokens[window[at]].value) != item:
                at += 1
            if at == len(window):
                return None, True, "manual row: %s is not on or after line %d (in order, within %d lines)" % (
                    l10nlib.json_string(item[1]), row.base_line, MANUAL_WINDOW)
            found.add(window[at])
            at += 1
    if found and index.tokens[min(found)].line != row.base_line:
        return None, True, "manual row: its first literal is on line %d, not %d" % (
            index.tokens[min(found)].line, row.base_line)
    return sorted(found), True, None


def call_index(head_texts):
    """{"accessor": {name: [Call]}, "id": {CONST: [Call]}, "bare": {CONST: [Call]}, "refs": set of names}.

    A Call is (path, line, token index, [arg source keys]); a bare T.CONST reference has args None.
    """
    accessors, ids, bare, refs = (collections.defaultdict(list), collections.defaultdict(list),
                                  collections.defaultdict(list), set())
    for path, text in head_texts.items():
        if text is None:
            continue
        try:
            index = _Lexed(text)
        except l10nlib.LexError:
            continue
        tokens = index.tokens
        for i in range(len(tokens) - 2):
            if tokens[i].kind != "ident" or tokens[i].value not in ("T", "M") or tokens[i + 1].value != "." or \
                    tokens[i + 2].kind != "ident" or (i > 0 and tokens[i - 1].value == "."):
                continue
            name = tokens[i + 2].value
            refs.add(name)
            if i + 3 < len(tokens) and tokens[i + 3].value == "(" and tokens[i + 3].kind == "op":
                accessors[name].append((path, tokens[i].line, i, _arguments(index, i + 3)))
                continue
            if tokens[i].value == "T" and i > 0 and tokens[i - 1].value == "(":
                args = _arguments(index, i - 1)
                if args and args[0] == ("T", ".", name):
                    ids[name].append((path, tokens[i].line, i, args[1:]))
                    continue
            bare[name].append((path, tokens[i].line, i, None))
    return {"accessor": accessors, "id": ids, "bare": bare, "refs": refs}


class _Lexed(object):
    def __init__(self, source):
        self.text = l10nlib.translate_unicode_escapes(source)
        self.tokens = l10nlib.lex_java(source)


def _arguments(index, opener):
    tokens = index.tokens
    close = expressions.match_forward(tokens, opener)
    if close is None or close == opener + 1:
        return []
    args, start, depth = [], opener + 1, 0
    for k in range(opener + 1, close):
        value = tokens[k].value if tokens[k].kind == "op" else None
        if value in ("(", "[", "{"):
            depth += 1
        elif value in (")", "]", "}"):
            depth -= 1
        elif value == "," and depth == 0:
            args.append(expressions.source_key(expressions.source_of(index.text, tokens, start, k - 1)))
            start = k + 1
    args.append(expressions.source_key(expressions.source_of(index.text, tokens, start, close - 1)))
    return args


def expected_arguments(row):
    """(quantity source key or None, [source keys by arg index]) the head call must pass."""
    if row.kind == "format":
        by_arg = {}
        for item in row.split:
            if isinstance(item, str):
                continue
            if "spec" in item:
                source = item["as"]
            elif "key" in item:
                source = "T.%s()" % gen_catalog.camel(item["key"])
            else:
                source = item.get("as", item["hole"])
            by_arg[item["arg"]] = expressions.source_key(source)
        return None, [by_arg[i] for i in sorted(by_arg)]
    by_arg, quantity = {}, None
    for hole in holes(row.pieces):
        source = expressions.source_key(hole.get("as", hole["hole"]))
        if hole.get("quantity"):
            quantity = source
        by_arg.setdefault(hole["arg"], source)
    return quantity, [by_arg[i] for i in sorted(by_arg)]


def _passes_holes(row, actual):
    quantity, args = expected_arguments(row)
    if not args and quantity is None:
        return actual in ([], None)
    if actual is None:
        return False
    if row.kind == "plural":
        if not actual or (quantity is not None and actual[0] != quantity):
            return False
        actual = actual[1:]
    return actual == args


def check_call_sites(row, calls, path=None, ranges=None, used=None, after=-1):
    """(problem or None, the call used): a call of the row's key passes its holes in placeholder order.

    With path and ranges, the call must sit in those head lines of path (the code that replaced the
    row's literals), must not be in used, and must come after token index after (calls follow the
    base order of their rows), so two keys swapped between converted sites fail.
    """
    accessor, constant = gen_catalog.camel(row.key), gen_catalog.constant(row.key)
    if accessor not in calls["refs"] and constant not in calls["refs"]:
        return "key %s is not used in src at head (no T.%s, M.%s or T.%s)" % (
            row.key, accessor, accessor, constant), None
    candidates = sorted(calls["accessor"].get(accessor, []) + calls["id"].get(constant, []) +
                        calls["bare"].get(constant, []), key=lambda c: (c[0], c[2]))
    passing = [c for c in candidates if _passes_holes(row, c[3])]
    quantity, args = expected_arguments(row)
    want = ([" ".join(quantity) if quantity else "<quantity>"] if row.kind == "plural" else []) + \
        [" ".join(a) for a in args]
    if not passing:
        found = [c for c in candidates if c[3] is not None]
        seen = "; ".join("%s:%d (%s)" % (p, l, ", ".join(" ".join(a) for a in act)) for p, l, _, act in found[:3])
        return "no call of %s passes the holes in placeholder order (%s)%s" % (
            accessor, ", ".join(want), "; found " + seen if seen else "; only T.%s is referenced" % constant), None
    if ranges is None:
        return None, passing[0]
    here = [c for c in passing if c[0] == path and any(h1 <= c[1] <= h2 for h1, h2 in ranges)]
    free = [c for c in here if (c[0], c[2]) not in (used or ()) and c[2] > after]
    if free:
        return None, free[0]
    lines = ", ".join("%d-%d" % r if r[0] <= r[1] else "(none)" for r in ranges)
    if here:
        return ("the call of %s in head lines %s of %s is already used by another row, or comes before the call of "
                "an earlier row; each converted site needs its own call, in the base order" % (
                    accessor, lines, path)), None
    elsewhere = "; ".join("%s:%d" % (c[0], c[1]) for c in passing[:3])
    return ("%s is called at %s, not in the head lines that replaced the literal (%s, lines %s); the call must "
            "sit where the literal was" % (accessor, elsewhere, path, lines)), None


def fmt_wrapper(hole):
    """True when a hole's "as" is Fmt.<method>(...) with the hole's own source as one whole argument."""
    try:
        lexed = _Lexed(hole["as"])
    except l10nlib.LexError:
        return False
    t = lexed.tokens
    if len(t) < 5 or t[0].value != "Fmt" or t[1].value != "." or t[2].kind != "ident" or \
            t[3].kind != "op" or t[3].value != "(" or expressions.match_forward(t, 3) != len(t) - 1:
        return False
    return expressions.source_key(hole["hole"]) in _arguments(lexed, 3)


# How a String.format specifier moves into Fmt, for format rows: the specifier, the Fmt method, and
# the arguments that call must pass ("locale": the String.format call's locale, "value": the spec's
# base argument, a digit: that group of the specifier). Fmt's own parity with the specifier is G10's
# (tests/l10n/FmtLegacyTest); a pair not listed here needs a tooling pull request that adds it.
FMT_SPECS = (
    (re.compile(r"%\.([0-6])f"), "decimal", ("locale", "value", "1")),
    (re.compile(r"%,\.([0-6])f"), "grouped", ("locale", "value", "1")),
    (re.compile(r"%d"), "integer", ("value",)),
)
# %d prints the locale's digits; Fmt.integer always prints Latin digits, which these locales do too.
LATIN_DIGIT_LOCALES = (("Locale", ".", "US"), ("Locale", ".", "ROOT"), ("Locale", ".", "ENGLISH"))
# String.format without a locale uses the default format locale.
DEFAULT_LOCALES = (("Locale", ".", "getDefault", "(", ")"),
                   ("Locale", ".", "getDefault", "(", "Locale", ".", "Category", ".", "FORMAT", ")"))


PLAIN_STRING_SPEC = re.compile(r"%(\d+\$)?s")


def _token_keys(source):
    return [(t.kind, t.value if t.kind in expressions.LITERALS else t.raw) for t in _Lexed(source).tokens]


def passthrough_problem(item):
    """None when a plain %s spec item's "as" is its value with some string literals replaced by a
    no-argument T.<key>() or M.<key>() call each, and nothing else changed. A template's %n$s prints
    a CharSequence as String.format's %s did; each literal replaced needs its own exact row, which
    checks the key's English and the call's place. Else the problem."""
    try:
        value, given = _token_keys(item["value"]), _token_keys(item["as"])
    except l10nlib.LexError:
        return "the specifier %s's value or \"as\" is not Java source" % item["spec"]
    i = j = 0
    while i < len(value) and j < len(given):
        if value[i] == given[j]:
            i, j = i + 1, j + 1
            continue
        call = given[j:j + 5]
        if value[i][0] == "string" and len(call) == 5 and call[0] in (("ident", "T"), ("ident", "M")) and \
                call[1] == ("op", ".") and call[2][0] == "ident" and call[3:] == [("op", "("), ("op", ")")] and \
                not (j > 0 and given[j - 1] == ("op", ".")):
            i, j = i + 1, j + 5
            continue
        break
    if i == len(value) and j == len(given):
        return None
    return ("the specifier %s prints %s as it is, so head must pass that same expression, with only its string "
            "literals turned into T.<key>() calls (each with an exact row), but the row passes %s" % (
                item["spec"], item["value"], item["as"]))


def spec_problem(item, locale):
    """None when a spec item's "as" is the Fmt call FMT_SPECS pairs with its specifier, with the
    String.format call's locale (a source key, or None for the default) and the spec's value. A plain
    %s passes its value through (passthrough_problem)."""
    if PLAIN_STRING_SPEC.fullmatch(item["spec"]):
        return passthrough_problem(item)
    try:
        lexed = _Lexed(item["as"])
    except l10nlib.LexError:
        lexed = None
    t = lexed.tokens if lexed else []
    if len(t) < 4 or t[0].value != "Fmt" or t[1].value != "." or t[2].kind != "ident" or \
            t[3].kind != "op" or t[3].value != "(" or expressions.match_forward(t, 3) != len(t) - 1:
        return "the specifier %s is passed as %s, which is not one Fmt.<method>(...) call" % (item["spec"], item["as"])
    method, actual = t[2].value, _arguments(lexed, 3)
    for pattern, name, shape in FMT_SPECS:
        m = pattern.fullmatch(item["spec"])
        if not m:
            continue
        if name == "integer" and locale not in LATIN_DIGIT_LOCALES:
            return ("%s prints %s's digits, and Fmt.integer always prints Latin ones; only a String.format with "
                    "Locale.US, Locale.ROOT or Locale.ENGLISH moves to Fmt.integer" % (
                        item["spec"], " ".join(locale) if locale else "the default locale"))
        wanted = []
        for locale_key in ([locale] if locale else DEFAULT_LOCALES):
            want = []
            for part in shape:
                want.append(locale_key if part == "locale" else expressions.source_key(item["value"]) if part == "value"
                            else expressions.source_key(m.group(int(part))))
            wanted.append(want)
        if method == name and actual in wanted:
            return None
        return "the specifier %s of %s prints as Fmt.%s(%s), but the row passes %s" % (
            item["spec"], item["value"], name, ", ".join(" ".join(a).replace(" . ", ".") for a in wanted[0]), item["as"])
    return ("G1 does not know which Fmt call prints like %s (FMT_SPECS in tools/l10n/ledger_check.py lists the "
            "pairs it checks); add the pair, with a G10 test, in a tooling pull request" % item["spec"])


def _argument_spans(tokens, opener):
    """(closing index, [(first, last)] of each top-level argument) of the call whose ( is at opener."""
    close = expressions.match_forward(tokens, opener)
    if close is None or close == opener + 1:
        return close, []
    spans, start, depth = [], opener + 1, 0
    for k in range(opener + 1, close):
        value = tokens[k].value if tokens[k].kind == "op" else None
        if value in ("(", "[", "{"):
            depth += 1
        elif value in (")", "]", "}"):
            depth -= 1
        elif value == "," and depth == 0:
            spans.append((start, k - 1))
            start = k + 1
    spans.append((start, close - 1))
    return close, spans


def string_format_site(index, k):
    """(first, last, locale source key or None, [argument source keys after the format string]) when the
    literal at token k is the whole format-string argument of String.format(...), else None."""
    tokens = index.tokens
    depth, j = 0, k - 1
    while j >= 0:
        t = tokens[j]
        if t.kind == "op" and t.value in (")", "]", "}"):
            depth += 1
        elif t.kind == "op" and t.value in ("(", "[", "{"):
            if depth == 0:
                break
            depth -= 1
        j -= 1
    if j < 3 or tokens[j].value != "(" or [x.value for x in tokens[j - 3:j]] != ["String", ".", "format"]:
        return None
    close, spans = _argument_spans(tokens, j)
    if close is None or (k, k) not in spans or spans.index((k, k)) > 1:
        return None
    at = spans.index((k, k))
    keys = [expressions.source_key(expressions.source_of(index.text, tokens, a, b)) for a, b in spans]
    return j - 3, close, keys[0] if at == 1 else None, keys[at + 1:]


def format_site(row, diff, tokens):
    """(problem, (first, last) base token span the head call replaces, locale or None) for a format row."""
    index = diff.base
    literal = split_is_format_literal(row.split)
    sites = [(k, string_format_site(index, k)) for k in tokens]
    if not literal:
        for k, site in sites:
            if site is not None:
                return ("format row: %s on line %d is String.format's format string; split its specifiers out as "
                        "spec items" % (l10nlib.json_string(index.tokens[k].value), index.tokens[k].line)), None, None
        return None, diff.base.expression_of(tokens[0]).span, None
    k, site = sites[0]
    if site is None:
        return ("format row: %s on line %d is not the format string of a String.format(...) call, so it has no "
                "specifiers to split out" % (l10nlib.json_string(index.tokens[k].value), index.tokens[k].line)), None, None
    first, last, locale, after = site
    used, ordinary = set(), 0
    for item in row.split:
        if not isinstance(item, dict) or "spec" not in item:
            continue
        explicit = FORMAT_SPEC.fullmatch(item["spec"]).group(1)
        if explicit:
            position = int(explicit[:-1]) - 1
        else:
            position, ordinary = ordinary, ordinary + 1
        if position >= len(after):
            return ("format row: the specifier %s on line %d has no argument in the String.format call" % (
                item["spec"], index.tokens[k].line)), None, None
        if expressions.source_key(item["value"]) != after[position]:
            return ("format row: the specifier %s formats %s at the merge base, not %s" % (
                item["spec"], " ".join(after[position]), item["value"])), None, None
        used.add(position)
        problem = spec_problem(item, locale)
        if problem:
            return "format row: %s" % problem, None, None
    if len(used) != len(after):
        return ("format row: the String.format call on line %d passes %d argument(s) after its format string, and "
                "the specifiers use %d" % (index.tokens[k].line, len(after), len(used))), None, None
    return None, (first, last), locale


NOT_METHOD_HEADERS = {"if", "for", "while", "switch", "catch", "synchronized", "do", "try", "else", "finally"}


def enclosing_method(tokens, k):
    """The name of the method (or constructor) whose body holds token k, or None."""
    j = k
    while True:
        depth, opener = 0, None
        for i in range(j - 1, -1, -1):
            if tokens[i].kind == "op" and tokens[i].value == "}":
                depth += 1
            elif tokens[i].kind == "op" and tokens[i].value == "{":
                if depth == 0:
                    opener = i
                    break
                depth -= 1
        if opener is None:
            return None
        first = opener
        while first > 0 and not (tokens[first - 1].kind == "op" and tokens[first - 1].value in ";{}"):
            first -= 1
        header = tokens[first:opener]
        values = [t.value for t in header]
        if header_type_keywords(header):
            return None
        if header and values[0] not in NOT_METHOD_HEADERS and not any(v in values for v in ("=", "->", "new")):
            p = 0
            while p < len(header):
                if header[p].kind == "op" and header[p].value == "(":
                    if p > 0 and header[p - 1].kind == "ident" and not (p > 1 and header[p - 2].value == "@"):
                        return header[p - 1].value
                    close = expressions.match_forward(header, p)
                    p = close if close is not None else p
                p += 1
        j = opener


def compiled_tests(head, cwd=None):
    """The tests/l10n/ Java files that tests/run.sh compiles at head: those a tests/l10n/*.sources list
    names (one repository path per line, "#" starts a comment)."""
    lists = [p for p in l10nlib.list_files(head, ["tests/l10n"], cwd)
             if os.path.dirname(p) == "tests/l10n" and p.endswith(".sources")]
    listed = set()
    for text in l10nlib.read_files(head, lists, cwd).values():
        for line in (text or "").splitlines():
            line = line.split("#", 1)[0].strip()
            if line.startswith("tests/l10n/") and line.endswith(".java"):
                listed.add(line)
    return sorted(listed)


def test_callers(head, cwd=None):
    """{(Class, method): [test files]} for every Class.method( call in a test under tests/l10n/ that
    tests/run.sh compiles at head (compiled_tests). Methods match by simple name, so an overload counts:
    the result only lists tests for the reviewer, except Fmt.<method>, which G1 requires (G10)."""
    names = compiled_tests(head, cwd)
    found = collections.defaultdict(list)
    for path, text in sorted(l10nlib.read_files(head, names, cwd).items()):
        try:
            tokens = l10nlib.lex_java(text or "")
        except l10nlib.LexError:
            continue
        for i in range(len(tokens) - 3):
            if tokens[i].kind == "ident" and tokens[i + 1].value == "." and tokens[i + 2].kind == "ident" and \
                    tokens[i + 3].value == "(" and path not in found[(tokens[i].value, tokens[i + 2].value)]:
                found[(tokens[i].value, tokens[i + 2].value)].append(path)
    return found


_TYPE_KEYWORDS = ("class", "enum", "interface", "record")


def header_type_keywords(header):
    """The type keywords (class, enum, interface, record) that declare a type in the tokens of one
    block header, in order. A keyword inside parentheses (a parameter, an annotation's arguments) or
    after a . (C.class) declares nothing, and record is a contextual keyword: it declares a record
    only when a name follows it (record Point(int x), not void save(Ride record) or Ride record :)."""
    found, depth = [], 0
    for i, t in enumerate(header):
        if t.kind == "op" and t.value in ("(", "["):
            depth += 1
        elif t.kind == "op" and t.value in (")", "]"):
            depth -= 1
        elif depth == 0 and t.kind == "ident" and t.value in _TYPE_KEYWORDS and not (
                i > 0 and header[i - 1].kind == "op" and header[i - 1].value == "."):
            if t.value == "record" and not (i + 1 < len(header) and header[i + 1].kind == "ident"):
                continue
            found.append(t.value)
    return found


def _final_field(tokens, k):
    """True when NAME at token k (in NAME = ...) is declared by a final field of a class, enum or record
    body, or by a field of an interface body (implicitly static final): a local variable, an assignment
    statement or a non-final field is not a constant."""
    def op(j, *values):
        return 0 <= j < len(tokens) and tokens[j].kind == "op" and tokens[j].value in values
    depth, start = 0, k - 1
    while start >= 0:  # back to the start of the statement
        if op(start, ")", "]"):
            depth += 1
        elif op(start, "(", "["):
            if depth == 0:
                return False  # inside a call or an index: f(x = "..."), not a declaration
            depth -= 1
        elif depth == 0 and op(start, ";", "{", "}"):
            break
        start -= 1
    end, depth = start + 1, 0
    while end <= k and not (depth == 0 and op(end, "=")):  # the first declarator's =
        if op(end, "(", "["):
            depth += 1
        elif op(end, ")", "]"):
            depth -= 1
        end += 1
    if end != k + 1 and not op(k - 1, ","):
        return False
    modifiers = tokens[start + 1:end]
    if len(modifiers) < 2 or modifiers[-1].kind != "ident" or not (
            modifiers[-2].kind == "ident" or op(start + len(modifiers) - 1, ">", "]")):
        return False  # an assignment (NAME = ..., this.NAME = ...), not a declaration (Type NAME = ...)
    kind = body_kind(tokens, start)
    if kind is None:
        return False  # a method, constructor, initializer or lambda body
    final = any(t.kind == "ident" and t.value == "final" for t in modifiers)
    return final or kind == "interface"


def body_kind(tokens, j):
    """The keyword (class, enum, interface, record) of the type body whose { holds token j directly, or
    None when the nearest enclosing { opens a method, constructor, initializer, lambda, anonymous class
    or other block (or there is none)."""
    def op(i, *values):
        return 0 <= i < len(tokens) and tokens[i].kind == "op" and tokens[i].value in values
    depth = 0
    while j >= 0:  # the block that holds token j
        if op(j, "}"):
            depth += 1
        elif op(j, "{"):
            if depth == 0:
                break
            depth -= 1
        j -= 1
    if j < 0:
        return None
    header = j
    while header > 0 and not op(header - 1, ";", "{", "}"):
        header -= 1
    kinds = header_type_keywords(tokens[header:j])
    return kinds[-1] if kinds else None


def constant_name(tokens, k):
    """NAME when the literal at token k is all of a constant's value: NAME = "..." declared by a final
    field (static final String NAME = "V";, not a local variable or an assignment) or the whole argument
    of an enum constant NAME(..., "V", ...)."""
    def op(j, *values):
        return 0 <= j < len(tokens) and tokens[j].kind == "op" and tokens[j].value in values
    if k >= 2 and op(k - 1, "=") and tokens[k - 2].kind == "ident" and op(k + 1, ";", ","):
        return tokens[k - 2].value if _final_field(tokens, k - 2) else None
    if not (op(k - 1, "(", ",") and op(k + 1, ")", ",")):
        return None
    depth, j = 0, k - 1
    while j >= 0:
        if op(j, ")", "]", "}"):
            depth += 1
        elif op(j, "(", "[", "{"):
            if depth == 0:
                break
            depth -= 1
        j -= 1
    if j < 2 or not op(j, "(") or tokens[j - 1].kind != "ident" or not op(j - 2, "{", ","):
        return None
    # NAME( is an enum constant when it sits in an enum body before the body's first ;
    depth, i = 0, j - 2
    while i >= 0:
        if op(i, ")", "]", "}"):
            depth += 1
        elif op(i, "(", "[", "{"):
            if depth == 0:
                break
            depth -= 1
        elif depth == 0 and op(i, ";"):
            return None
        i -= 1
    if i < 0 or not op(i, "{"):
        return None
    first = i
    while first > 0 and not op(first - 1, ";", "{", "}"):
        first -= 1
    if not any(t.kind == "ident" and t.value == "enum" for t in tokens[first:i]):
        return None
    return tokens[j - 1].value


def _region_tokens(index, first, last):
    return [t for t in index.tokens if first <= t.line <= last] if index is not None else []


def added_in_hunk(diff, hunk):
    """The first head token of a changed region that is not a base token kept in order (None when the
    region only lost tokens, which is what deleting code looks like)."""
    b1, b2, h1, h2 = diff.hunks[hunk]
    base = iter([(t.kind, t.value if t.kind in expressions.LITERALS else t.raw)
                 for t in _region_tokens(diff.base, b1, b2)])
    for tok in _region_tokens(diff.head, h1, h2):
        key = (tok.kind, tok.value if tok.kind in expressions.LITERALS else tok.raw)
        if not any(key == b for b in base):
            return tok
    return None


def _excerpt(tokens, limit=16):
    if limit is None:
        return " ".join(t.raw for t in tokens)
    text = " ".join(t.raw for t in tokens[:limit])
    return text + (" ..." if len(tokens) > limit else "")


WHOLE_BLOCK = "whole block:"


def statement_of(diff, k):
    """(first, last, terminator) of the base statement holding base token k."""
    return expressions.statement_span(diff.base.tokens, k)


def deleted_whole(diff, k):
    """True when the head lost every token of the base statement holding token k."""
    deleted, _ = diff.changed_tokens()
    first, last, terminator = statement_of(diff, k)
    if any(j not in deleted for j in range(first, last + 1)):
        return False
    if terminator is not None and terminator > last and terminator not in deleted:
        # The aligner may keep this ; and drop the one before the statement instead.
        return first > 0 and (first - 1) in deleted
    return True


def call_span(tokens, k):
    """(first, last) head token indexes of the catalog call whose T or M token is at k, with a
    trailing .render() (a deferred Msg rendered on the spot)."""
    start, end = k, k + 2
    if k + 3 < len(tokens) and tokens[k + 3].kind == "op" and tokens[k + 3].value == "(":
        close = expressions.match_forward(tokens, k + 3)
        end = close if close is not None else k + 3
    elif k > 0 and tokens[k - 1].kind == "op" and tokens[k - 1].value == "(":
        # Msg.of(T.NAME, ...) or T.render(T.NAME, ...): the whole enclosing call.
        start = k - 1
        if start > 0 and tokens[start - 1].kind == "ident":
            start -= 1
            while start >= 2 and tokens[start - 1].value == "." and tokens[start - 2].kind == "ident":
                start -= 2
        close = expressions.match_forward(tokens, k - 1)
        end = close if close is not None else k + 2
    if [t.raw for t in tokens[end + 1:end + 5]] == [".", "render", "(", ")"]:
        end += 4
    return start, end


def deleted_scope(diff, first, last):
    """(first, last, frame) widened from a deleted statement to each enclosing block (a method, an if)
    whose header and body were deleted with it. frame is the set of base tokens of those blocks'
    headers and braces; any other token in the widened span is another statement deleted with them."""
    tokens = diff.base.tokens
    deleted, _ = diff.changed_tokens()
    frame = set()
    while True:
        depth, opener = 0, None
        for j in range(first - 1, -1, -1):
            if tokens[j].kind == "op" and tokens[j].value == "}":
                depth += 1
            elif tokens[j].kind == "op" and tokens[j].value == "{":
                if depth == 0:
                    opener = j
                    break
                depth -= 1
        if opener is None:
            return first, last, frame
        head_first = opener
        while head_first > 0 and not (tokens[head_first - 1].kind == "op" and tokens[head_first - 1].value in ";{}"):
            head_first -= 1
        close = expressions.match_brace(tokens, opener)
        if any(j not in deleted for j in range(head_first, close + 1)):
            return first, last, frame
        frame.update(range(head_first, opener + 1))
        frame.add(close)
        first, last = head_first, close


DECLARATION_MODIFIERS = frozenset(("private", "public", "protected", "static", "final", "transient", "volatile"))


def declaration_type(tokens, first, last):
    """(type token index, declarator name) when tokens first..last are the whole initializer of one
    declarator of a declaration statement, [modifiers] Type a = ..., name = <first..last>, ...; (a
    field or a local), with a simple, unqualified Type. Else None."""
    def op(j, *values):
        return 0 <= j < len(tokens) and tokens[j].kind == "op" and tokens[j].value in values
    if first < 2 or not op(first - 1, "=") or tokens[first - 2].kind != "ident" or not op(last + 1, ";", ","):
        return None
    start = first
    while start > 0 and not op(start - 1, ";", "{", "}"):
        start -= 1
    k = start
    while k < len(tokens) and tokens[k].kind == "ident" and tokens[k].value in DECLARATION_MODIFIERS:
        k += 1
    if k + 1 >= first or tokens[k].kind != "ident" or tokens[k + 1].kind != "ident" or op(k + 2, "."):
        return None
    # Every declarator before the one at first: name = <expression>, at the statement's top level.
    j, depth = k + 1, 0
    while j < first - 2:
        if op(j, "(", "[", "{"):
            depth += 1
        elif op(j, ")", "]", "}"):
            depth -= 1
        elif op(j, ";"):
            return None
        j += 1
    if depth != 0 or not (first - 2 == k + 1 or op(first - 3, ",")):
        return None
    return k, tokens[first - 2].value


def statement_bounds(tokens, k):
    """(first, index of the ; or { or } that ends it) of the statement holding token k."""
    start = k
    while start > 0 and not (tokens[start - 1].kind == "op" and tokens[start - 1].value in (";", "{", "}")):
        start -= 1
    end = k
    while end < len(tokens) and not (tokens[end].kind == "op" and tokens[end].value in (";", "{", "}")):
        end += 1
    return start, end


def retyped_declaration(diff, base_span, head_span):
    """(base type token, head type token, name) when a row's base expression was a declarator's whole
    initializer in a String declaration, and the call that serves it is that declarator's whole
    initializer in the same declaration with the type CharSequence (a deferred M call needs it). Else
    None. Only this one type change is allowed around text."""
    base = declaration_type(diff.base.tokens, base_span[0], base_span[-1])
    head = declaration_type(diff.head.tokens, head_span[0], head_span[-1])
    if base is None or head is None or base[1] != head[1]:
        return None
    b, h = base[0], head[0]
    if diff.base.tokens[b].value != "String" or diff.head.tokens[h].value != "CharSequence":
        return None
    def modifiers(tokens, k):
        return [t.value for t in tokens[statement_bounds(tokens, k)[0]:k]]
    if modifiers(diff.base.tokens, b) != modifiers(diff.head.tokens, h):
        return None
    return b, h, base[1]


def _declarators(tokens, k, end):
    """[(first, last)] of the declarators of a declaration whose type token is k and whose ; is end."""
    parts, start, depth = [], k + 1, 0
    for j in range(k + 1, end):
        if tokens[j].kind == "op" and tokens[j].value in ("(", "[", "{"):
            depth += 1
        elif tokens[j].kind == "op" and tokens[j].value in (")", "]", "}"):
            depth -= 1
        elif tokens[j].kind == "op" and tokens[j].value == "," and depth == 0:
            parts.append((start, j - 1))
            start = j + 1
    parts.append((start, end - 1))
    return parts


def split_declaration(diff, base_span, head_span):
    """(base tokens, head tokens, head type token, name) when a row's literal was one declarator of a
    String declaration with several (String a = "", b = "x", c = "";) and head moved that declarator
    out, unchanged otherwise, into a CharSequence declaration of its own right beside the rest
    (String a = "", c = ""; CharSequence b = M.x();), with the same modifiers. Else None."""
    base_tokens, head_tokens = diff.base.tokens, diff.head.tokens
    base = declaration_type(base_tokens, base_span[0], base_span[-1])
    head = declaration_type(head_tokens, head_span[0], head_span[-1])
    if base is None or head is None or base[1] != head[1]:
        return None
    b, h = base[0], head[0]
    if base_tokens[b].value != "String" or head_tokens[h].value != "CharSequence":
        return None

    def key(tok):
        return (tok.kind, tok.value if tok.kind in expressions.LITERALS else tok.raw)

    def statement(tokens, k):
        start, end = statement_bounds(tokens, k)
        return start, end if end < len(tokens) and tokens[end].value == ";" else None

    b_start, b_end = statement(base_tokens, b)
    h_start, h_end = statement(head_tokens, h)
    if b_end is None or h_end is None or head_span[-1] != h_end - 1 or head_span[0] - 2 != h + 1:
        return None  # head: [modifiers] CharSequence name = <call>; and nothing more
    modifiers = [key(t) for t in base_tokens[b_start:b]]
    if [key(t) for t in head_tokens[h_start:h]] != modifiers:
        return None
    parts = _declarators(base_tokens, b, b_end)
    mine = [n for n, (f, l) in enumerate(parts) if f <= base_span[0] and base_span[-1] <= l]
    if len(parts) < 2 or len(mine) != 1:
        return None
    rest = []
    for n, (f, l) in enumerate(parts):
        if n != mine[0]:
            rest += ([("op", ",")] if rest else []) + [key(t) for t in base_tokens[f:l + 1]]
    wanted = modifiers + [key(base_tokens[b])] + rest + [("op", ";")]
    # The rest of the declaration sits right before or right after the new one.
    neighbours = []
    if h_start >= 2 and head_tokens[h_start - 1].value == ";":
        neighbours.append((statement(head_tokens, h_start - 2)[0], h_start - 1))
    if h_end + 1 < len(head_tokens):
        after = statement(head_tokens, h_end + 1)
        if after[0] == h_end + 1 and after[1] is not None:
            neighbours.append(after)
    for first, last in neighbours:
        if [key(t) for t in head_tokens[first:last + 1]] == wanted:
            return range(b_start, b_end + 1), range(min(first, h_start), max(last, h_end) + 1), h, base[1]
    return None


def _opener(tokens, j):
    """The index of the { whose block holds token j directly, or None at file level."""
    depth = 0
    while j >= 0:
        if tokens[j].kind == "op" and tokens[j].value == "}":
            depth += 1
        elif tokens[j].kind == "op" and tokens[j].value == "{":
            if depth == 0:
                return j
            depth -= 1
        j -= 1
    return None


def _supertype_use(tokens, names):
    """(line, excerpt) of the first extends or implements clause in the file that names one of NAMES
    (class Ring extends Base, class Shown implements Labels, <T extends Base>), or None. The clause runs to
    the next { or ;. A subclass inherits a package-private field, and an implementing class an
    interface constant, so Ui.Ring r; r.subtitle names a private Base's field from another file."""
    for i, t in enumerate(tokens):
        if t.kind != "ident" or t.value not in ("extends", "implements"):
            continue
        j = i + 1
        while j < len(tokens) and not (tokens[j].kind == "op" and tokens[j].value in ("{", ";")):
            if tokens[j].kind == "ident" and tokens[j].value in names:
                return tokens[j].line, _excerpt(tokens[max(0, i - 3):j + 1], None)
            j += 1
    return None


def file_reach(tokens, start, end):
    """None when the declaration whose modifiers and type are tokens start..end-1 can be named only in
    its own file: a local (a method, lambda, initializer or anonymous class body), a private field of
    a class, enum or record, a field declared directly in a private type (a package-private field of a
    private nested class), or a field of a type declared in a method or other block, at any depth.
    The private type counts only when it is the field's own enclosing type and no extends or
    implements clause in the file names it: a subclass or an implementing class that other files can
    name inherits the field. javac checks only the access flags of the field's own class, so a
    package-private field of a non-private class nested in a private one is reachable through any
    field or method of that type (Ui.IN.subtitle with static final Base.In IN). Else a short
    reason: "not private", or what hands the field on."""
    kind = body_kind(tokens, start)
    if kind is None:
        return None
    if kind != "interface" and any(t.kind == "ident" and t.value == "private" for t in tokens[start:end]):
        return None  # a private member is never inherited
    opener = _opener(tokens, start - 1)
    direct, hidden_in = True, None
    while opener is not None:  # the type body holding the declaration, then each type around it
        header = opener
        while header > 0 and not (tokens[header - 1].kind == "op" and tokens[header - 1].value in ";{}"):
            header -= 1
        keyword = max(i for i in range(header, opener) if tokens[i].kind == "ident" and tokens[i].value in _TYPE_KEYWORDS)
        name = tokens[keyword + 1].value if keyword + 1 < opener and tokens[keyword + 1].kind == "ident" else None
        if any(t.kind == "ident" and t.value == "private" for t in tokens[header:keyword]):
            if direct:
                inherited = _supertype_use(tokens, {name} if name else set())
                if inherited is None:
                    return None
                return ("in a private type that line %d hands on (`%s`: a subclass or implementing class "
                        "inherits it)" % inherited)
            if hidden_in is None:
                hidden_in = (tokens[keyword].line, name)
        outer = _opener(tokens, header - 1)
        if outer is None:
            break  # a top-level type
        if body_kind(tokens, header) is None:
            return None  # a class declared in a method or other block
        direct = False
        opener = outer
    if hidden_in is not None:
        return ("in a non-private type nested in the private %s (line %d): javac checks only the field's own "
                "class, so any field or method whose type is that nested class (static final %s.In IN) hands "
                "it to other files (Ui.IN.subtitle)" % (hidden_in[1], hidden_in[0], hidden_in[1]))
    return "not private"


def retype_reach_problem(where, path, diff, h_type):
    """A problem when the declaration whose type token is head token h_type is a field other files can
    name (not private, or any interface field): G1 lists the uses of a retyped variable from its own
    file only, and r.subtitle.equals("Not reported") in another file compiles on and turns false. A
    private field, a local, a field of a type declared in a block, or a field declared directly in a
    private type that no extends or implements clause hands on is named only in its own file (file_reach), which retype_warning lists. Else None."""
    tokens = diff.head.tokens
    start, _ = statement_bounds(tokens, h_type)
    reach = file_reach(tokens, start, h_type)
    if reach is None:
        return None
    first, last, _ = expressions.statement_span(tokens, h_type)
    return ("%s: %s:%d: `%s` changes from String to CharSequence on a field that is %s, so code in "
            "other files can name it and G1 cannot list those uses (an equals, an overload chosen by type); an "
            "extraction batch retypes only a field other files cannot name (a private field, a field declared "
            "directly in a private class that nothing in the file extends or implements, or any field of a class "
            "declared in a method) or a local: make the field "
            "private in an earlier commit of its own, or keep the String and convert where it is shown" % (
                where, path, tokens[h_type].line, _excerpt(tokens[first:last + 1], None), reach))


def retype_warning(where, path, diff, h_type, name, split_from=None):
    """The reviewer's listing of a String declaration that became CharSequence for its catalog call,
    with the code around every use of the variable in the file, a window of up to 12 tokens each side
    for each use not already inside a printed window (only this file can name it; retype_reach_problem
    refuses the rest): a use that picks an overload by type
    (a String and a CharSequence overload), compares with equals or casts reads it differently now."""
    tokens = diff.head.tokens
    first, last, _ = expressions.statement_span(tokens, h_type)
    uses, printed = [], -1
    for k, t in enumerate(tokens):
        if t.kind != "ident" or t.value != name or first <= k <= last or k <= printed:
            continue  # not a use, the declaration itself, or inside a window already printed
        s_first, s_last, _ = expressions.statement_span(tokens, k)
        # A window centred on the use, so a long statement never hides it; a later use of the same
        # statement outside this window gets a window of its own. Every use is printed, none capped.
        start, end = max(s_first, k - 12), min(s_last, k + 12)
        printed = end
        uses.append("line %d `%s%s%s`" % (t.line, "... " if start > s_first else "",
                                          _excerpt(tokens[start:end + 1], None), " ..." if end < s_last else ""))
    shown = "; ".join(uses)
    return ("%s: %s:%d: the declaration `%s`%s changes from String to CharSequence for its catalog call; check by "
            "hand that every use of %s behaves the same with a CharSequence (an overload chosen by type, equals, a "
            "cast)%s" % (
                where, path, tokens[h_type].line, _excerpt(tokens[first:last + 1], None),
                " (moved out of `%s`, the rest kept as it was)" % split_from if split_from else "", name,
                "; only this file can name it (private or local), at: %s" % shown if uses else
                "; only this file can name it (private or local), and it names it nowhere else"))


def _class_name(path):
    return os.path.basename(path)[:-len(".java")]


def _references(diff, hunk, row_path, arrival_path, name):
    """Positions, in the head lines of one changed region, that name the constant NAME of arrival_path:
    NAME in the same file, or Class.NAME."""
    owner = _class_name(arrival_path)
    _, _, h1, h2 = diff.hunks[hunk]
    region = _region_tokens(diff.head, h1, h2)
    found = []
    for k, tok in enumerate(region):
        if tok.kind != "ident" or tok.value != name:
            continue
        qualified = k >= 2 and region[k - 1].value == "." and region[k - 2].value == owner
        unqualified = (k == 0 or region[k - 1].value != ".") and arrival_path == row_path
        if qualified or unqualified:
            found.append(k)
    return found


def _points_to(diff, hunk, row_path, arrival_path, arrival_index, arrival_diff):
    """The constant's name when the code a staying literal left names the constant it arrived in: the
    arrival is a final field NAME = "..." or an enum constant NAME(..., "...", ...), and the region's head lines say
    NAME (same file) or Class.NAME. Else None."""
    name = constant_name(arrival_diff.head.tokens, arrival_index)
    if name is None or not _references(diff, hunk, row_path, arrival_path, name):
        return None
    return name


def _whole_members(tokens, first, last):
    """True when tokens first..last are whole method or field declarations in a class body."""
    def op(j, *values):
        return tokens[j].kind == "op" and tokens[j].value in values
    if first == 0 or not op(first - 1, ";", "{", "}"):
        return False
    depth, opener = 0, None
    for i in range(first - 1, -1, -1):
        if op(i, "}"):
            depth += 1
        elif op(i, "{"):
            if depth == 0:
                opener = i
                break
            depth -= 1
    if opener is None:
        return False
    start = opener
    while start > 0 and not op(start - 1, ";", "{", "}"):
        start -= 1
    if not header_type_keywords(tokens[start:opener]):
        return False
    j = first
    while j <= last:
        k, header = j, []
        while k <= last and not op(k, ";", "{"):
            if op(k, "(", "["):
                close = expressions.match_forward(tokens, k)
                if close is None or close > last:
                    return False
                header.extend(tokens[k:close + 1])
                k = close + 1
                continue
            header.append(tokens[k])
            k += 1
        if k > last or not header or header_type_keywords(header):
            return False
        if op(k, "{"):
            # A method: a name and parameters before its body; an initializer block has none.
            if not any(op(m, "(") for m in range(j, k)) or any(op(m, "=") for m in range(j, k)):
                return False
            k = expressions.match_brace(tokens, k)
            if not op(k, "}") or k > last:
                return False
        j = k + 1
    return j == last + 1


def inserted_members(tokens, first, last):
    """(first, last) of whole member declarations that head tokens first..last are, possibly once slid
    over equal neighbouring tokens (a diff may show "} void f() { ... ;" for "void f() { ... ; }"),
    or None."""
    def key(tok):
        return (tok.kind, tok.value if tok.kind in expressions.LITERALS else tok.raw)
    candidates, f, l = [(first, last)], first, last
    while l + 1 < len(tokens) and key(tokens[f]) == key(tokens[l + 1]):
        f, l = f + 1, l + 1
        candidates.append((f, l))
    f, l = first, last
    while f > 0 and key(tokens[f - 1]) == key(tokens[l]):
        f, l = f - 1, l - 1
        candidates.append((f, l))
    return next(((f, l) for f, l in candidates if _whole_members(tokens, f, l)), None)


# A call site's legacy locale wrapped for Fmt (plan section 4.1): Fmt.locale(Locale.US) is Locale.US
# while Volt is English (G10's FmtLegacyTest), so this one change is allowed around text and listed.
LOCALE_ARGUMENTS = (("Locale", ".", None), ("java", ".", "util", ".", "Locale", ".", None),
                    ("Locale", ".", "getDefault", "(", ")"))


def locale_wraps(tokens):
    """{Fmt token index: (wrapper token indexes, inner token indexes)} for every Fmt.locale(<legacy>)
    in tokens, <legacy> being Locale.X, java.util.Locale.X or Locale.getDefault()."""
    found = {}
    for k in range(len(tokens) - 6):
        if tokens[k].kind != "ident" or tokens[k].value != "Fmt" or (k > 0 and tokens[k - 1].value == ".") or \
                [t.raw for t in tokens[k + 1:k + 4]] != [".", "locale", "("]:
            continue
        close = expressions.match_forward(tokens, k + 3)
        if close is None:
            continue
        inner = tokens[k + 4:close]
        for shape in LOCALE_ARGUMENTS:
            if len(inner) == len(shape) and all(t.kind == "ident" and t.value != "getDefault" if want is None
                                                else t.raw == want for t, want in zip(inner, shape)):
                found[k] = ([k, k + 1, k + 2, k + 3, close], list(range(k + 4, close)))
                break
    return found


def to_string_uses(tokens, names):
    """{name token index: (wrapper token indexes, [name token index])} for every name.toString() (or
    this.name.toString()) of a variable in names."""
    found = {}
    for k in range(len(tokens) - 4):
        if tokens[k].kind != "ident" or tokens[k].value not in names or \
                (k > 0 and tokens[k - 1].value == "." and not (k > 1 and tokens[k - 2].value == "this")) or \
                [t.raw for t in tokens[k + 1:k + 5]] != [".", "toString", "(", ")"]:
            continue
        found[k] = ([k + 1, k + 2, k + 3, k + 4], [k])
    return found


def other_changes(path, diff, allowed_base, allowed_head, moved_hunks, new_ids=(), wraps=None, retyped=()):
    """(problems, warnings) for code a batch changed besides converting or deleting text.

    allowed_base and allowed_head map token indexes to a span id: a row's base expression (or a
    manual row's statement) and the head call that serves it share one id, and a removed row's
    deleted code has an id with no head side. Each span counts as one item when the changed regions
    are aligned, so only tokens outside them can differ. new_ids are head tokens of new key or id
    literals (listed elsewhere); they alone do not make a region hold text.

    A legacy locale wrapped into Fmt.locale(...) (Locale.US becoming Fmt.locale(Locale.US)) is not a
    change here: ("locale", head Fmt token) of each such wrap is added to wraps, which run() lists and
    holds to G10. Nor is a .toString() added to a use of a variable in retyped (a String declaration
    that became CharSequence for its catalog call), which gives the String the use had: ("string",
    head name token) is added. Unwrapping either (Fmt.locale(Locale.US) becoming Locale.US, or
    name.toString() becoming name) is a change.
    """
    if diff.base is None or diff.head is None:
        return [], ["%s was %s; check it by hand" % (path, "added" if diff.base is None else "deleted")]
    problems, warnings = [], []
    groups = []
    for tokens in (diff.base.tokens, diff.head.tokens):
        found = dict((at, ("locale",) + group) for at, group in locale_wraps(tokens).items())
        found.update((at, ("string",) + group) for at, group in to_string_uses(tokens, set(retyped)).items())
        groups.append(found)
    for hunk, (b1, b2, h1, h2) in enumerate(diff.hunks):
        sides = []
        for side, (index, allowed, first, last) in enumerate(((diff.base, allowed_base, b1, b2),
                                                              (diff.head, allowed_head, h1, h2))):
            wrapper, inner = {}, {}
            for at, (_, outer, inside) in groups[side].items():
                if not any(j in allowed for j in outer + inside):
                    wrapper.update((j, at) for j in outer)
                    inner.update((j, at) for j in inside)
            keys, where, wrapped = [], [], []
            for k, tok in enumerate(index.tokens):
                if not first <= tok.line <= last or k in wrapper:
                    continue
                if k in allowed:
                    if keys and keys[-1] == ("span", allowed[k]):
                        continue
                    keys.append(("span", allowed[k]))
                    where.append(None)
                else:
                    keys.append((tok.kind, tok.value if tok.kind in expressions.LITERALS else tok.raw))
                    where.append(k)
                wrapped.append(inner.get(k))
            sides.append((keys, where, wrapped, wrapper))
        (a, a_at, a_wrap, a_wrapper), (b, b_at, b_wrap, b_wrapper) = sides
        text = any(t.kind in expressions.LITERALS for t in _region_tokens(diff.base, b1, b2)) or \
            any(t.kind in expressions.LITERALS and k not in new_ids
                for k, t in enumerate(diff.head.tokens) if h1 <= t.line <= h2)
        lost, gained = [], []
        for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes():
            if tag == "equal":
                for o in range(i2 - i1):
                    was, now = a_wrap[i1 + o], b_wrap[j1 + o]
                    if now is not None and was is None and wraps is not None:
                        wraps.add((groups[1][now][0], now))
                    elif was is not None and now is None:
                        lost.extend(j for j, at in a_wrapper.items() if at == was and j not in lost)
                continue
            if text and tag == "insert" and None not in b_at[j1:j2] and \
                    set(range(b_at[j1], b_at[j2 - 1] + 1)) <= set(b_at[j1:j2]) | set(b_wrapper):
                members = inserted_members(diff.head.tokens, b_at[j1], b_at[j2 - 1])
                if members is not None:
                    # A new method or field next to text does not run where the text is; it is listed.
                    warnings.append("%s:%d: this change adds the whole member(s) `%s`; check by hand that nothing "
                                    "in them changes English" % (
                                        path, diff.head_token(members[0]).line,
                                        _excerpt(diff.head.tokens[members[0]:members[1] + 1], 60)))
                    continue
            lost.extend(k for k in a_at[i1:i2] if k is not None)
            gained.extend(k for k in b_at[j1:j2] if k is not None)
            # Show a changed argument with its Fmt.locale( ) or .toString(), so the excerpt reads as the code.
            for wrap, wrapper_map, out in ((a_wrap[i1:i2], a_wrapper, lost), (b_wrap[j1:j2], b_wrapper, gained)):
                for at in set(w for w in wrap if w is not None):
                    out.extend(j for j, w in wrapper_map.items() if w == at and j not in out)
        if not lost and not gained:
            continue
        lost.sort()
        gained.sort()
        parts = []
        # Beside a move the reviewer is the check, so the whole change is printed.
        limit = None if (path, hunk) in moved_hunks else 16
        if gained:
            parts.append("adds `%s`" % _excerpt([diff.head_token(k) for k in gained], limit))
        if lost:
            parts.append("drops `%s`" % _excerpt([diff.base_token(k) for k in lost], limit))
        line = diff.head_token(gained[0]).line if gained else diff.base_token(lost[0]).line
        what = "%s:%d: this change %s" % (path, line, " and ".join(parts))
        if (path, hunk) in moved_hunks:
            warnings.append("%s beside a stays-english or id-kept move; check by hand that the English is the same"
                            % what)
        elif text:
            problems.append("%s%s; around text, an extraction batch only replaces a ledger row's expression with "
                            "the call that serves it (or deletes a removed row's statement)" % (
                                what, " (base line %d)" % diff.base_token(lost[0]).line if lost and not gained
                                else ""))
        else:
            warnings.append("%s, away from any text; check by hand that it changes no English" % what)
    return problems, warnings


def existing_constants(sources, value):
    """[(path, token)] of constants already in src at head, outside the batch's arrivals, whose whole
    value is the string literal value: a final field NAME = "..." or an enum constant NAME(..., "..."). sources is
    [(path, tokens, tokens to skip)]."""
    return [(path, k) for path, tokens, skip in sources for k, tok in enumerate(tokens)
            if tok.kind == "string" and tok.value == value and k not in skip and constant_name(tokens, k)]


def check_named_moves(named, head_tokens, diffs, existing=()):
    """(problems, warnings) for staying literals that moved into named constants.

    named maps (path, head token) of a constant's literal to the sites [(row, path, base token)] that
    name it; head_tokens(path) gives that file's head tokens; existing holds the constants that were
    already in src (a later batch moving its literals into B1's UnitCode). In each changed region, the
    constants the head lines name must include one reference per literal that left it, in the base
    order of those literals: unit.equals("V") ... unit.equals("W") cannot become WATT ... VOLT, nor
    two "V" sites one VOLT. Every constant several sites share, and every existing constant a site
    names, is listed with each site."""
    problems, warnings = [], []
    by_hunk = collections.defaultdict(list)  # (path, hunk) -> [(base token, (owner, name), row)]
    for (a_path, a_k), uses in sorted(named.items()):
        a_tokens = head_tokens(a_path)
        name = constant_name(a_tokens, a_k)
        for row, path, k in uses:
            by_hunk[(path, diffs[path].gone[k])].append((k, (a_path, name), row))
        if len(uses) > 1 or (a_path, a_k) in existing:
            first = max(i for i in range(a_k) if a_tokens[i].kind == "ident" and a_tokens[i].value == name)
            sites = ", ".join("%s:%d (%s:%d)" % (path, diffs[path].base_token(k).line, row.source, row.line)
                              for row, path, k in uses)
            warnings.append("%s:%d: %d occurrence(s) of %s moved into the %sconstant %s.%s (`%s`): %s; check by hand "
                            "that every site means this same value" % (
                                a_path, a_tokens[a_k].line, len(uses), l10nlib.json_string(a_tokens[a_k].value),
                                "existing " if (a_path, a_k) in existing else "one ",
                                _class_name(a_path), name, _excerpt(a_tokens[first:a_k + 2], None),
                                sites))
    for (path, hunk), moved in sorted(by_hunk.items()):
        diff = diffs[path]
        moved.sort(key=lambda m: m[0])
        want = [m[1] for m in moved]
        refs = []
        for constant in set(want):
            refs.extend((k, constant) for k in _references(diff, hunk, path, constant[0], constant[1]))
        named_order = [constant for _, constant in sorted(refs)]
        at = 0
        for constant in named_order:
            if at < len(want) and constant == want[at]:
                at += 1
        if at < len(want):
            _, _, h1, h2 = diff.hunks[hunk]
            problems.append("%s:%d: the literals that left these lines for constants are, in order, %s, but the head "
                            "lines name %s; each site must name the constant its own literal moved to (head lines "
                            "%d-%d: `%s`)" % (
                                path, diff.base_token(moved[0][0]).line,
                                ", ".join("%s (%s.%s)" % (l10nlib.json_string(diff.base_token(m[0]).value),
                                                          _class_name(m[1][0]), m[1][1]) for m in moved),
                                ", ".join("%s.%s" % (_class_name(c[0]), c[1]) for c in named_order) or "none",
                                h1, h2, _excerpt(_region_tokens(diff.head, h1, h2), 60)))
    return problems, warnings


def fmt_methods(row):
    """The Fmt methods a format row's spec and hole items pass, in order, once each."""
    found = []
    for item in row.split:
        if isinstance(item, dict) and "as" in item:
            try:
                t = _Lexed(item["as"]).tokens
            except l10nlib.LexError:
                continue
            if len(t) > 3 and t[0].value == "Fmt" and t[1].value == "." and t[3].value == "(" and t[2].value not in found:
                found.append(t[2].value)
    return found


def format_review(row, where, diff, base_span, head_span, callers):
    """(problems, warning) for a format row whose call was found. Each Fmt method the row passes must be
    called by a compiled test under tests/l10n/ (G10 FmtLegacyTest proves it prints like the specifier
    FMT_SPECS pairs it with). The reviewer sees the base code, the call that replaced it, each
    specifier's replacement and the tests that call the method that held the literal; a method no test
    can call (private, an instance method of a screen) is named in the warning instead."""
    base_tokens, head_tokens = diff.base.tokens, diff.head.tokens
    method = enclosing_method(base_tokens, base_span[0])
    owner = _class_name(row.path)
    pairs = ["%s -> %s" % (item["spec"], item["as"]) for item in row.split if isinstance(item, dict) and "spec" in item]
    pairs += ["%s -> %s" % (item["hole"], item["as"]) for item in row.split
              if isinstance(item, dict) and "hole" in item and "as" in item]
    keys = ["%s -> %s" % (l10nlib.json_string(item["text"]), item["key"]) for item in row.split
            if isinstance(item, dict) and "key" in item]
    problems = ["%s: format row at %s:%d passes Fmt.%s, and no test that tests/run.sh compiles under tests/l10n/ "
                "calls it; G10 (FmtLegacyTest) must show it prints like the specifier it replaces" % (
                    where, row.path, row.base_line, name) for name in fmt_methods(row) if not callers.get(("Fmt", name))]
    tests = callers.get((owner, method), []) if method else []
    if tests:
        site = "; a method named %s.%s is called by %s" % (owner, method, ", ".join(tests))
    elif fmt_methods(row):
        site = ("; no compiled test under tests/l10n/ calls %s.%s(...), so check by hand that its values are ones "
                "G10 covers" % (owner, method or "<the enclosing method>"))
    else:
        site = ""
    warning = "%s: format row at %s:%d: `%s` becomes `%s`; text %s%s%s; check by hand that each replacement prints " \
              "what its specifier did" % (
                  where, row.path, row.base_line, _excerpt(base_tokens[base_span[0]:base_span[1] + 1], None),
                  _excerpt(head_tokens[head_span[0]:head_span[1] + 1], None), ", ".join(keys) or "none",
                  "; specifiers " + ", ".join(pairs) if pairs else "", site)
    return problems, warning


def arrival_match(row, diff, k, hunk, arrival, diffs):
    """How surely an added literal is the moved copy of the staying literal at base token k: 2 when it
    arrived in an expression with the same literals as the one k left (several literals moved
    together), plus 1 when the head lines of the region k left name the arrival's class (Class.) or
    the method that holds it (method(...))."""
    a_path, _, a_k, _ = arrival
    a_diff = diffs[a_path]
    score = 0
    left = expressions.literal_items(diff.base.expression_of(k).pieces)
    came = expressions.literal_items(a_diff.head.expression_of(a_k).pieces)
    if len(left) > 1 and left == came:
        score += 2
    _, _, h1, h2 = diff.hunks[hunk]
    region = _region_tokens(diff.head, h1, h2)
    method = enclosing_method(a_diff.head.tokens, a_k)
    owner = _class_name(a_path)
    for j, tok in enumerate(region[:-1]):
        if tok.kind != "ident":
            continue
        if (tok.value == owner and a_path != row.path and region[j + 1].value == ".") or \
                (method and tok.value == method and region[j + 1].value == "("):
            score += 1
            break
    return score


CATALOG_USAGE_TEST = "tests/l10n/CatalogUsageTest.java"
_ENGLISH_ONLY = re.compile(r"\bENGLISH_ONLY\s*=\s*Arrays\s*\.\s*asList\s*\(([^;]*)\)\s*;")


def english_only_files(head, cwd=None):
    """(file names, problem or None): CatalogUsageTest.ENGLISH_ONLY at head, the files that stay English
    in every language (reports, logs, the shell process). No such test at head gives no files; a test
    whose list G1 cannot read is a problem, so a renamed list never lets a wrap through unseen."""
    text = l10nlib.read_files(head, [CATALOG_USAGE_TEST], cwd).get(CATALOG_USAGE_TEST)
    if text is None:
        return frozenset(), None
    found = _ENGLISH_ONLY.search(text)
    if not found:
        return frozenset(), ("%s: G1 cannot read its ENGLISH_ONLY = Arrays.asList(...) list of English-only files, "
                             "which a Fmt.locale wrap must stay out of" % CATALOG_USAGE_TEST)
    return frozenset(re.findall(r'"([^"\\]+)"', found.group(1))), None


# Plan section 4.1, the data tier: these files write journals, CSV, GPX, JSON, backups, hex and the
# Telemetry labels and values, which never localize (a decimal comma or a localized date would be
# stored or sent), and plan section 4.3 lists no display call site in them. A Fmt.locale wrap there
# fails like one in an ENGLISH_ONLY file; a display site found in one moves out in a commit of its own.
DATA_TIER = frozenset(name + ".java" for name in (
    "Telemetry", "RideStore", "RideArchive", "PhoneRideStore", "ChargeSessionStore", "BackupArchive", "BackupJson",
    "AppDataBackup", "TestJournal", "ChargeScheduleController"))


# Names whose presence in a wrap's statement means it builds a Telemetry or TelemetryHistory reading
# (Telemetry.putSimulationSample, new TelemetryHistory.Reading): plan section 4.1 puts Telemetry
# labels and values in the data tier, and plan section 4.3 keeps ScooterService's synthetic
# remaining_range_km reading canonical, so such a wrap in a display file gets the data-tier note.
TELEMETRY_WRITERS = frozenset(("Telemetry", "TelemetryHistory", "putSimulationSample"))


def locale_wrap_review(path, diff, k, callers, english_only=frozenset()):
    """(problems, warning) for a legacy locale wrapped into Fmt.locale(...) at head token k. G10
    (FmtLegacyTest) shows Fmt.locale returns the legacy locale while Volt is English, so a compiled
    test under tests/l10n/ must call Fmt.locale; the reviewer sees the statement and the tests that
    call the method holding it (the characterization of that site), or a note that none does."""
    tokens = diff.head.tokens
    close = expressions.match_forward(tokens, k + 3)
    first, last, _ = expressions.statement_span(tokens, k)
    start, end = max(first, k - 20), min(last, close + 20)
    problems = []
    if not callers.get(("Fmt", "locale")):
        problems.append("%s: legacy locales are wrapped into Fmt.locale(...), and no test that tests/run.sh compiles "
                        "under tests/l10n/ calls Fmt.locale; G10 (FmtLegacyTest) must show it keeps the legacy "
                        "locale while Volt is English" % path)
    if os.path.basename(path) in english_only:
        problems.append("%s:%d: `%s` is wrapped into Fmt.locale(...) in an English-only file (%s ENGLISH_ONLY): "
                        "its text and data stay English in every language, so it keeps its legacy locale" % (
                            path, tokens[k].line, _excerpt(tokens[k + 4:close], None), CATALOG_USAGE_TEST))
    if os.path.basename(path) in DATA_TIER:
        problems.append("%s:%d: `%s` is wrapped into Fmt.locale(...) in a data-tier file (plan section 4.1; "
                        "ledger_check.DATA_TIER): what it formats is stored, sent or logged and never localizes, so "
                        "it keeps its legacy locale" % (path, tokens[k].line, _excerpt(tokens[k + 4:close], None)))
    method = enclosing_method(tokens, k)
    owner = _class_name(path)
    tests = callers.get((owner, method), []) if method else []
    if tests:
        site = "a method named %s.%s is called by %s" % (owner, method, ", ".join(tests))
    else:
        site = "no compiled test under tests/l10n/ calls %s.%s(...), so check by hand that it formats the same" % (
            owner, method or "<the enclosing method>")
    root = [t.value for t in tokens[k + 4:close]][-1:] == ["ROOT"]
    telemetry = sorted({t.value for t in tokens[first:last + 1] if t.kind == "ident" and t.value in TELEMETRY_WRITERS})
    warning = ("%s:%d: `%s` becomes `%s` in `%s%s%s`; Fmt.locale keeps that locale while Volt is English (G10), and "
               "a shipped language formats in its own; %s%s%s" % (
                   path, tokens[k].line, _excerpt(tokens[k + 4:close], None), _excerpt(tokens[k:close + 1], None),
                   "... " if start > first else "", _excerpt(tokens[start:end + 1], None),
                   " ..." if end < last else "", site,
                   "; Locale.ROOT is the data tier (plan section 4: ids, enum tokens, stored values and logs never "
                   "localize), so check that this is text a screen shows, such as a decorative upper-casing, and "
                   "not an id or a value that is stored, sent or compared (Turkish upper-cases i to a dotted capital I)"
                   if root else "",
                   "; the statement names %s, and a Telemetry or TelemetryHistory label or value is the data tier (plan "
                   "section 4.1; the ScooterService remaining_range_km reading stays canonical, plan section 4.3), so "
                   "check that the wrapped text is only shown and never becomes a reading's stored, sent or logged "
                   "value, which keeps its legacy locale" % ", ".join(telemetry) if telemetry else ""))
    return problems, warning


# ---------------------------------------------------------------------------
# Types declared in src at head: an exception that a converted throw switches to
# ---------------------------------------------------------------------------

THROWABLE_SUFFIXES = ("Exception", "Error", "Throwable")
# Methods that change what a Throwable prints; a class between the new and the old type may not declare them.
MESSAGE_METHODS = frozenset(("getMessage", "getLocalizedMessage", "toString"))

TypeDecl = collections.namedtuple("TypeDecl", "path tokens name keyword open close extends")
"""A class declared in a src file at head: its tokens, the class keyword's index, the { and } of its body,
and the simple name its extends clause names (None without one)."""


def _simple_type(tokens, j, end):
    """The simple name of the type that starts at token j (a.b.C<X> gives C) before index end."""
    name = None
    while j < end:
        t = tokens[j]
        if t.kind == "ident":
            name = t.value
            if j + 1 < end and tokens[j + 1].kind == "op" and tokens[j + 1].value == ".":
                j += 2
                continue
        break
    return name


class SourceTypes(object):
    """The classes declared in src at head, by simple name, read lazily from {path: text}."""

    def __init__(self, texts):
        self._texts, self._by_name = texts, None

    def named(self, name):
        if self._by_name is None:
            self._by_name = collections.defaultdict(list)
            for path, text in sorted(self._texts.items()):
                if text is None:
                    continue
                try:
                    tokens = l10nlib.lex_java(text)
                except l10nlib.LexError:
                    continue
                for k in range(len(tokens) - 1):
                    if tokens[k].kind != "ident" or tokens[k].value != "class" or tokens[k + 1].kind != "ident" or \
                            (k > 0 and tokens[k - 1].kind == "op" and tokens[k - 1].value == "."):
                        continue
                    open_ = k + 2
                    while open_ < len(tokens) and not (tokens[open_].kind == "op" and tokens[open_].value in "{;"):
                        open_ += 1
                    if open_ >= len(tokens) or tokens[open_].value != "{":
                        continue
                    extends = None
                    for j in range(k + 2, open_):
                        if tokens[j].kind == "ident" and tokens[j].value == "extends":
                            extends = _simple_type(tokens, j + 1, open_)
                            break
                    self._by_name[tokens[k + 1].value].append(TypeDecl(
                        path, tokens, tokens[k + 1].value, k, open_, expressions.match_brace(tokens, open_), extends))
        return self._by_name.get(name, [])


def _members(decl):
    """[(name token index, ( index, ) index, body { index or None)] of the methods and constructors
    declared directly in a class body (nested types, initializers and bodies are skipped)."""
    tokens, found, j = decl.tokens, [], decl.open + 1
    while j < decl.close:
        t = tokens[j]
        if t.kind == "op" and t.value == "{":
            j = expressions.match_brace(tokens, j) + 1
            continue
        if t.kind == "ident" and j + 1 < decl.close and tokens[j + 1].kind == "op" and tokens[j + 1].value == "(" and \
                not (tokens[j - 1].kind == "ident" and tokens[j - 1].value == "new") and \
                not (tokens[j - 1].kind == "op" and tokens[j - 1].value == "."):
            close = expressions.match_forward(tokens, j + 1)
            if close is None:
                break
            k = close + 1
            while k < decl.close and not (tokens[k].kind == "op" and tokens[k].value in ("{", ";", "=", ",")):
                k += 1
            if k < decl.close and tokens[k].value in ("{", ";"):
                found.append((j, j + 1, close, k if tokens[k].value == "{" else None))
                j = (expressions.match_brace(tokens, k) + 1) if tokens[k].value == "{" else k + 1
                continue
            j = close + 1
            continue
        j += 1
    return found


def _parameters(tokens, opener, close):
    """[(simple type name, parameter name)] of a parameter list ( ... )."""
    params, start, depth = [], opener + 1, 0
    if close == opener + 1:
        return params
    for k in range(opener + 1, close + 1):
        t = tokens[k]
        if k == close or (depth == 0 and t.kind == "op" and t.value == ","):
            part = [x for x in tokens[start:k] if not (x.kind == "ident" and x.value == "final")]
            while part and part[0].kind == "op" and part[0].value == "@":
                part = part[2:]
            params.append((_simple_type(part, 0, len(part) - 1) if len(part) > 1 else None,
                           part[-1].value if part else None))
            start = k + 1
        elif t.kind == "op" and t.value in ("(", "<", "["):
            depth += 1
        elif t.kind == "op" and t.value in (")", ">", "]"):
            depth -= 1
    return params


def _raw(tokens, first, last):
    return [t.raw for t in tokens[first:last + 1]]


def _english_helper(decl, name):
    """True when the class declares a method NAME(Msg p) whose body is `return p.english();`, optionally
    after `if (p == null) throw new NullPointerException(...);` (UserFacingException's english)."""
    tokens = decl.tokens
    for at, opener, close, body in _members(decl):
        if tokens[at].value != name or body is None:
            continue
        params = _parameters(tokens, opener, close)
        if len(params) != 1 or params[0][0] != "Msg":
            continue
        p = params[0][1]
        inner = _raw(tokens, body + 1, expressions.match_brace(tokens, body) - 1)
        guard = ["if", "(", p, "==", "null", ")", "throw", "new", "NullPointerException", "("]
        if inner[:len(guard)] == guard:
            end = inner.index(";") if ";" in inner else len(inner)
            if end != len(inner) and inner[end - 1] == ")" and all(x.startswith('"') or x == ")" for x in inner[len(guard):end]):
                inner = inner[end + 1:]
        if inner == ["return", p, ".", "english", "(", ")", ";"]:
            return True
    return False


def english_constructors(types, decl, arity, msg_at, seen=()):
    """(problem or None, [constructor excerpts]) for a new <decl>(...) call with arity arguments whose
    argument msg_at is a Msg: every constructor that call can pick must keep getMessage() the English
    text, by passing the Msg's english() (or a method english(msg) that returns it) as the message of a
    JDK superclass, or the Msg itself to such a constructor of a src superclass or of this class."""
    tokens = decl.tokens
    chosen = []
    for at, opener, close, body in _members(decl):
        if tokens[at].value != decl.name or body is None:
            continue
        params = _parameters(tokens, opener, close)
        if len(params) == arity and params[msg_at][0] == "Msg":
            chosen.append((at, opener, close, body, params))
    where = "%s:%d" % (decl.path, tokens[decl.keyword].line)
    if not chosen:
        return ("%s (%s) has no constructor with %d parameter(s) whose parameter %d is a Msg, so the catalog call "
                "cannot keep getMessage() the English text" % (decl.name, where, arity, msg_at + 1)), []
    shown = []
    for at, opener, close, body, params in chosen:
        end = expressions.match_brace(tokens, body)
        excerpt = _excerpt(tokens[at:end + 1], None)
        shown.append("`%s` (%s:%d)" % (excerpt, decl.path, tokens[at].line))
        msg = params[msg_at][1]
        first = body + 1
        semicolon = first
        while semicolon < end and not (tokens[semicolon].kind == "op" and tokens[semicolon].value == ";"):
            semicolon += 1
        call = _raw(tokens, first, semicolon - 1)
        if len(call) < 3 or call[0] not in ("super", "this") or call[1] != "(" or \
                expressions.match_forward(tokens, first + 1) != semicolon - 1:
            return ("the constructor `%s` (%s:%d) does not start with super(...) or this(...) passing its Msg %s, so "
                    "getMessage() is not the English text" % (excerpt, decl.path, tokens[at].line, msg)), shown
        _, spans = _argument_spans(tokens, first + 1)
        args = [_raw(tokens, a, b) for a, b in spans]
        key = (decl.path, at)
        if key in seen:
            return "the constructor `%s` calls itself" % excerpt, shown
        if call[0] == "this" or (decl.extends and len(types.named(decl.extends)) == 1):
            target = decl if call[0] == "this" else types.named(decl.extends)[0]
            if [msg] not in args:
                return ("the constructor `%s` (%s:%d) passes its Msg %s on to %s(...) other than as a whole argument, "
                        "so G1 cannot follow it to getMessage()" % (excerpt, decl.path, tokens[at].line, msg,
                                                                   call[0])), shown
            problem, more = english_constructors(types, target, len(args), args.index([msg]), seen + (key,))
            shown.extend(more)
            if problem:
                return problem, shown
            continue
        if decl.extends and len(types.named(decl.extends)) > 1:
            return "%s extends %s, which src declares more than once" % (decl.name, decl.extends), shown
        message = args[0] if args else []
        if message != [msg, ".", "english", "(", ")"] and not (
                len(message) == 4 and message[1:] == ["(", msg, ")"] and _english_helper(decl, message[0])):
            return ("the constructor `%s` (%s:%d) passes `%s` as the message of %s; getMessage() stays the English "
                    "text only when it passes %s.english() (or a method english(Msg) that returns it)" % (
                        excerpt, decl.path, tokens[at].line, " ".join(message) or "nothing",
                        decl.extends or "Object", msg)), shown
    return None, shown


def _new_site(tokens, first, last):
    """(new index, type first, type last, opener, [(first, last)] argument spans, argument index) when
    tokens first..last are one whole argument of a new Type(...) expression, else None."""
    depth, j = 0, first - 1
    while j >= 0:
        t = tokens[j]
        if t.kind == "op" and t.value in (")", "]", "}"):
            depth += 1
        elif t.kind == "op" and t.value in ("(", "[", "{"):
            if depth == 0:
                break
            depth -= 1
        j -= 1
    if j < 2 or tokens[j].value != "(":
        return None
    close, spans = _argument_spans(tokens, j)
    if close is None or (first, last) not in spans:
        return None
    name_last, name_first = j - 1, j - 1
    if tokens[name_last].kind != "ident":
        return None
    while name_first >= 2 and tokens[name_first - 1].value == "." and tokens[name_first - 2].kind == "ident":
        name_first -= 2
    if name_first < 1 or tokens[name_first - 1].kind != "ident" or tokens[name_first - 1].value != "new":
        return None
    return name_first - 1, name_first, name_last, j, spans, spans.index((first, last))


def _id_argument(types, decl, tokens, first, last):
    """True when tokens first..last name an enum constant of an enum nested in decl (Code.NO_SPACE or
    BackupException.Code.NO_SPACE) or a static final field of decl (BackupException.NO_SPACE)."""
    parts = [t.value for t in tokens[first:last + 1]]
    if any(t.kind not in ("ident", "op") for t in tokens[first:last + 1]) or \
            any(p != "." for p in parts[1::2]) or len(parts) % 2 == 0:
        return False
    names = parts[0::2]
    if names[0] == decl.name:
        names = names[1:]
    body = decl.tokens
    if len(names) == 2:
        enum, constant = names
        for k in range(decl.open, decl.close):
            if body[k].kind == "ident" and body[k].value == "enum" and k + 2 < decl.close and body[k + 1].value == enum \
                    and body[k + 2].value == "{":
                close = expressions.match_brace(body, k + 2)
                j, depth = k + 3, 0
                while j < close:
                    t = body[j]
                    if t.kind == "op" and t.value in ("(", "{"):
                        j = (expressions.match_forward(body, j) if t.value == "(" else expressions.match_brace(body, j)) + 1
                        continue
                    if t.kind == "op" and t.value == ";":
                        break
                    if t.kind == "ident" and t.value == constant and body[j - 1].value in ("{", ","):
                        return True
                    j += 1
        return False
    if len(names) == 1:
        name = names[0]
        for k in range(decl.open + 1, decl.close - 1):
            if body[k].kind == "ident" and body[k].value == name and body[k + 1].value == "=" and \
                    body_kind(body, k) == "class" and _final_field(body, k) and \
                    any(t.value == "static" for t in body[statement_bounds(body, k)[0]:k]):
                return True
    return False


def exception_retype(types, path, diff, base_span, head_span):
    """(None, None) when the row's base expression is not the whole argument of a new X(...) that head
    turned into a new Y(...), else (problem, (base tokens, head tokens, warning)): head may switch to a
    src class Y that extends X (directly or through other src classes), pass the catalog call (a Msg)
    where the text was and, beside it, constant arguments of Y (an enum constant of an enum Y declares,
    or a static final field of Y), with every other argument kept in order; Y's constructor must keep
    getMessage() the English text (english_constructors), and no class from Y up to X may declare
    getMessage, getLocalizedMessage or toString."""
    base_tokens, head_tokens = diff.base.tokens, diff.head.tokens
    base = _new_site(base_tokens, base_span[0], base_span[-1])
    head = _new_site(head_tokens, head_span[0], head_span[-1])
    if base is None or head is None:
        return None, None
    old = base_tokens[base[2]].value
    new = head_tokens[head[2]].value
    if old == new and _raw(base_tokens, base[1], base[2]) == _raw(head_tokens, head[1], head[2]):
        return None, None
    _, b_first, b_last, _, b_spans, b_at = base
    _, h_first, h_last, h_open, h_spans, h_at = head
    line = head_tokens[h_first].line
    b_code = _excerpt(base_tokens[base[0]:expressions.match_forward(base_tokens, base[3]) + 1], None)
    h_code = _excerpt(head_tokens[head[0]:expressions.match_forward(head_tokens, h_open) + 1], None)
    what = "%s:%d: `%s` becomes `%s`" % (path, line, b_code, h_code)
    if not old.endswith(THROWABLE_SUFFIXES):
        return "%s; only an exception (a type named ...Exception, ...Error or Throwable) may change type around text" % (
            what), None
    decls = types.named(new)
    if len(decls) != 1:
        return ("%s; %s must be one class declared in src (found %d), so G1 can read what it extends and how it "
                "keeps getMessage()" % (what, new, len(decls))), None
    decl = decls[0]
    chain, step = [decl], decl
    while step.extends != old:
        if step.extends is None or len(types.named(step.extends)) != 1:
            return ("%s; %s does not extend %s (its chain: %s), so a catch of %s would no longer catch it" % (
                what, new, old, " extends ".join([d.name for d in chain] + [step.extends or "Object"]), old)), None
        step = types.named(step.extends)[0]
        if step in chain:
            return "%s; %s's extends clauses loop" % (what, new), None
        chain.append(step)
    for d in chain:
        for at, _, _, body in _members(d):
            if d.tokens[at].value in MESSAGE_METHODS and body is not None:
                return ("%s; %s declares %s (%s:%d), which may print other text than the English message" % (
                    what, d.name, d.tokens[at].value, d.path, d.tokens[at].line)), None
    # Head's arguments: base's before and after the text, and in between the call plus constants of Y.
    base_args = [_raw(base_tokens, a, b) for a, b in b_spans]
    head_args = [_raw(head_tokens, a, b) for a, b in h_spans]
    before, after = base_args[:b_at], base_args[b_at + 1:]
    middle = list(range(len(before), len(head_args) - len(after)))
    if head_args[:len(before)] != before or (after and head_args[-len(after):] != after) or h_at not in middle:
        return ("%s; the other arguments must stay as they were, in order, with only constants of %s added beside "
                "the catalog call" % (what, new)), None
    ids = [n for n in middle if n != h_at]
    for n in ids:
        a, b = h_spans[n]
        if not _id_argument(types, decl, head_tokens, a, b):
            return ("%s; `%s` is not a constant of %s (an enum constant of an enum it declares, or a static final "
                    "field of it), and only those may join the catalog call" % (
                        what, _excerpt(head_tokens[a:b + 1], None), new)), None
    call = head_tokens[h_spans[h_at][0]]
    if not (call.kind == "ident" and call.value in ("M", "Msg")):
        return ("%s; the call that replaces the text must be a deferred M.<key>(...) (a Msg), so %s can keep the "
                "English for getMessage() and render the language at display time" % (what, new)), None
    problem, constructors = english_constructors(types, decl, len(head_args), h_at)
    if problem:
        return "%s; %s" % (what, problem), None
    id_tokens = set()
    if ids:
        lo, hi = min(min(ids), h_at), max(max(ids), h_at)
        id_tokens.update(range(h_spans[lo][0], h_spans[hi][1] + 1))
    via = "" if len(chain) == 1 else " (%s)" % " extends ".join([d.name for d in chain] + [old])
    warning = ("%s: %s extends %s%s, so a catch of %s still catches it, and getMessage() stays the English text "
               "through %s%s; check by hand that nothing shows or logs the exception itself (toString(), "
               "String.valueOf(e), \"\" + e or a stack trace print the new class name %s) or compares its class" % (
                   what, new, old, via, old, "; ".join(constructors),
                   "; constant argument(s) " + ", ".join("`%s`" % _excerpt(head_tokens[h_spans[n][0]:h_spans[n][1] + 1], None)
                                                        for n in ids) if ids else "", new))
    return None, (range(b_first, b_last + 1), range(h_first, h_last + 1), id_tokens, warning)


# ---------------------------------------------------------------------------
# Locale.US becoming Locale.ROOT at a data site (plan section 4.1, Telemetry:626)
# ---------------------------------------------------------------------------

# Conversions that print the same under Locale.US and Locale.ROOT: hex, octal and plain decimal digits
# (no grouping), and %s, which prints String.valueOf of its argument. %f, %e, %g, a grouping flag, %S,
# %c and the %t dates are refused: G1 does not prove what they print in either locale.
ROOT_SAFE_CONVERSIONS = frozenset("xXods%n")
ROOT_SAFE_FLAGS = frozenset("-0#")


def root_unsafe_specifiers(pattern):
    """The specifiers of a String.format pattern that could print differently under Locale.ROOT than
    under Locale.US ([] when every one is %x, %X, %o, %d or %s with only -, 0 or # flags and a width)."""
    unsafe = []
    for m in FORMAT_SPEC.finditer(pattern):
        index, flags, width, precision, conversion = m.groups()
        if conversion not in ROOT_SAFE_CONVERSIONS or set(flags) - ROOT_SAFE_FLAGS or precision:
            unsafe.append(m.group(0))
    rest = FORMAT_SPEC.sub("", pattern)
    if "%" in rest:
        unsafe.append("%")
    return unsafe


def _format_calls(index, first_line, last_line, locale):
    """[(locale token, first, close, literal token)] of String.format(Locale.<locale>, "<literal>", ...)
    calls whose locale sits on lines first_line..last_line."""
    tokens, found = index.tokens, []
    for k in range(len(tokens) - 4):
        if [t.raw for t in tokens[k:k + 4]] != ["String", ".", "format", "("] or (k > 0 and tokens[k - 1].value == "."):
            continue
        close, spans = _argument_spans(tokens, k + 3)
        if close is None or len(spans) < 2:
            continue
        a, b = spans[0]
        raw = [t.raw for t in tokens[a:b + 1]]
        if raw not in (["Locale", ".", locale], ["java", ".", "util", ".", "Locale", ".", locale]):
            continue
        if not first_line <= tokens[b].line <= last_line or spans[1][0] != spans[1][1] or \
                tokens[spans[1][0]].kind != "string":
            continue
        found.append((b, k, close, spans[1][0]))
    return found


def locale_swaps(path, diff):
    """[(base US token, head ROOT token, problem or None, warning)] for each String.format(Locale.US,
    "<pattern>", ...) of a changed region that head kept token for token except Locale.ROOT for
    Locale.US (plan section 4.1 moves such data sites to ROOT with identical output). The swap is
    allowed around text only when the pattern's every specifier prints the same in both locales
    (root_unsafe_specifiers); each one is listed."""
    found = []
    if diff.base is None or diff.head is None:
        return found
    for b1, b2, h1, h2 in diff.hunks:
        heads = _format_calls(diff.head, h1, h2, "ROOT")
        taken = set()
        for b_loc, b_first, b_close, b_lit in _format_calls(diff.base, b1, b2, "US"):
            shape = [t.raw if j != b_loc else "ROOT" for j, t in
                     enumerate(diff.base.tokens[b_first:b_close + 1], b_first)]
            for n, (h_loc, h_first, h_close, h_lit) in enumerate(heads):
                if n in taken or [t.raw for t in diff.head.tokens[h_first:h_close + 1]] != shape:
                    continue
                taken.add(n)
                pattern = diff.base.tokens[b_lit].value
                unsafe = root_unsafe_specifiers(pattern)
                code = _excerpt(diff.head.tokens[h_first:h_close + 1], None)
                problem = None
                if unsafe:
                    problem = ("%s:%d: `%s` moves from Locale.US to Locale.ROOT, but its pattern %s has %s, which G1 "
                               "does not prove prints the same in both (only %%x, %%X, %%o, %%d and %%s with the -, 0 "
                               "or # flags and a width); keep Locale.US, or move the number into a format row" % (
                                   path, diff.head.tokens[h_loc].line, code, l10nlib.json_string(pattern),
                                   ", ".join(unsafe)))
                warning = ("%s:%d: `%s` moves from Locale.US to Locale.ROOT (plan section 4.1, the data tier); its "
                           "pattern %s has only conversions that print the same in both locales; check by hand that "
                           "each %%s argument is an id or a string, not a number or a Formattable" % (
                               path, diff.head.tokens[h_loc].line, code, l10nlib.json_string(pattern)))
                found.append((b_loc, h_loc, problem, warning))
                break
    return found


# ---------------------------------------------------------------------------
# A private method's String parameter retyped for the catalog calls its callers now pass (B7)
# ---------------------------------------------------------------------------

NOT_METHOD_NAMES = NOT_METHOD_HEADERS | {"new", "return", "throw", "case", "assert", "super", "this"}
PARAMETER_TYPES = ("Msg", "CharSequence")


def method_declarations(tokens):
    """[(name token, ( token, ) token, header first token)] of every method or constructor that has a
    body: NAME(...) [throws ...] { with a type, a modifier or nothing in front of NAME."""
    found = []
    for k in range(1, len(tokens) - 1):
        t = tokens[k]
        if t.kind != "ident" or t.value in NOT_METHOD_NAMES or tokens[k + 1].kind != "op" or tokens[k + 1].value != "(":
            continue
        before = tokens[k - 1]
        if before.kind == "op" and before.value not in (">", "]", ";", "{", "}"):
            continue
        if before.kind == "ident" and before.value in NOT_METHOD_NAMES:
            continue
        close = expressions.match_forward(tokens, k + 1)
        if close is None:
            continue
        j = close + 1
        if j < len(tokens) and tokens[j].kind == "ident" and tokens[j].value == "throws":
            while j < len(tokens) and not (tokens[j].kind == "op" and tokens[j].value in ("{", ";")):
                j += 1
        if j >= len(tokens) or tokens[j].kind != "op" or tokens[j].value != "{":
            continue
        first = k
        while first > 0 and not (tokens[first - 1].kind == "op" and tokens[first - 1].value in (";", "{", "}")):
            first -= 1
        found.append((k, k + 1, close, first))
    return found


def _parameter_type_tokens(tokens, opener, close):
    """[(type token index or None, name)] of a parameter list, the type token being a simple name."""
    out, start, depth = [], opener + 1, 0
    if close == opener + 1:
        return out
    for k in range(opener + 1, close + 1):
        t = tokens[k]
        if k == close or (depth == 0 and t.kind == "op" and t.value == ","):
            part = [j for j in range(start, k) if not (tokens[j].kind == "ident" and tokens[j].value == "final")]
            simple = len(part) == 2 and all(tokens[j].kind == "ident" for j in part)
            out.append((part[0] if simple else None, tokens[part[-1]].value if part else None))
            start = k + 1
        elif t.kind == "op" and t.value in ("(", "<", "["):
            depth += 1
        elif t.kind == "op" and t.value in (")", ">", "]"):
            depth -= 1
    return out


def _call_sites(tokens, name, arity, skip):
    """[(name token, [(first, last)] argument spans)] of the calls name(...) (or this.name(...)) with arity
    arguments in one file, other than the declaration at token skip."""
    sites = []
    for k in range(len(tokens) - 1):
        if k == skip or tokens[k].kind != "ident" or tokens[k].value != name or tokens[k + 1].value != "(":
            continue
        if k > 0 and tokens[k - 1].value == "." and not (k > 1 and tokens[k - 2].value == "this"):
            continue
        if k > 0 and (tokens[k - 1].kind == "ident" and tokens[k - 1].value not in ("return", "throw", "case", "else")
                      or tokens[k - 1].value in (">", "]")):
            continue  # another declaration of the name
        _, spans = _argument_spans(tokens, k + 1)
        if len(spans) == arity:
            sites.append((k, spans))
    return sites


def parameter_retypes(path, diff, catalog_calls, other_texts):
    """[(base type token, head type token, problem or None, warning or None)] for each method parameter
    that changed from String to Msg or CharSequence. That is allowed around text only for a private
    method (or a package-private one no other src file calls) declared once with that name and arity,
    when the argument at every call of it in the file is a catalog call that serves a ledger row (an M
    call for a Msg; T or M for a CharSequence). The method, every call site and every use of the
    parameter in its body are listed. catalog_calls is {(first, last) head span: T or M token} of the
    calls that serve rows in this file."""
    found = []
    if diff.base is None or diff.head is None:
        return found
    b_tokens, h_tokens = diff.base.tokens, diff.head.tokens
    base_decls = collections.Counter((b_tokens[k].value, len(_parameter_type_tokens(b_tokens, o, c)))
                                     for k, o, c, _ in method_declarations(b_tokens))
    head_decls = method_declarations(h_tokens)
    head_counts = collections.Counter((h_tokens[k].value, len(_parameter_type_tokens(h_tokens, o, c)))
                                      for k, o, c, _ in head_decls)
    base_by_key = {}
    for k, o, c, first in method_declarations(b_tokens):
        base_by_key.setdefault((b_tokens[k].value, len(_parameter_type_tokens(b_tokens, o, c))), (k, o, c, first))
    for k, opener, close, first in head_decls:
        name = h_tokens[k].value
        params = _parameter_type_tokens(h_tokens, opener, close)
        key = (name, len(params))
        if key not in base_by_key:
            continue
        bk, b_open, b_close, b_first = base_by_key[key]
        b_params = _parameter_type_tokens(b_tokens, b_open, b_close)
        for position, ((h_type, h_name), (b_type, b_name)) in enumerate(zip(params, b_params)):
            if h_type is None or b_type is None or h_name != b_name or b_tokens[b_type].value != "String" or \
                    h_tokens[h_type].value not in PARAMETER_TYPES:
                continue
            hunk = next((n for n, (b1, b2, h1, h2) in enumerate(diff.hunks) if h1 <= h_tokens[h_type].line <= h2), None)
            if hunk is None or not (
                    any(t.kind in expressions.LITERALS for t in _region_tokens(diff.base, *diff.hunks[hunk][:2])) or
                    any(t.kind in expressions.LITERALS for t in _region_tokens(diff.head, *diff.hunks[hunk][2:]))):
                continue  # away from text, other_changes lists it as before
            new_type = h_tokens[h_type].value
            header = _excerpt(h_tokens[first:close + 1], None)
            what = "%s:%d: the parameter `%s %s` of `%s` changes from String to %s" % (
                path, h_tokens[h_type].line, new_type, h_name, header, new_type)
            modifiers = [t.value for t in h_tokens[first:k]]
            problem = None
            if base_decls[key] != 1 or head_counts[key] != 1:
                problem = "%s; %s(...) with %d parameter(s) is declared more than once in the file" % (
                    what, name, len(params))
            elif "public" in modifiers or "protected" in modifiers:
                problem = "%s; the method is %s, so other files may call it" % (
                    what, "public" if "public" in modifiers else "protected")
            elif "private" not in modifiers:
                callers = sorted(p for p, text in other_texts.items()
                                 if p != path and text and re.search(r"\b%s\s*\(" % re.escape(name), text))
                if callers:
                    problem = "%s; the method is package-private and %s name %s(...)" % (what, ", ".join(callers), name)
            sites = _call_sites(h_tokens, name, len(params), k)
            listed = []
            for at, spans in sites:
                a, b = spans[position]
                served = catalog_calls.get((a, b))
                ok = served is not None and (new_type == "CharSequence" or h_tokens[served].value == "M" or
                                             h_tokens[a].value == "Msg")
                statement = expressions.statement_span(h_tokens, at)
                excerpt = _excerpt(h_tokens[max(statement[0], at - 8):min(statement[1], spans[-1][1] + 2) + 1], None)
                listed.append("line %d `%s`" % (h_tokens[at].line, excerpt))
                if not ok and problem is None:
                    problem = ("%s; at line %d `%s` its argument is not %s catalog call that serves a ledger row, and "
                               "every caller must pass one" % (
                                   what, h_tokens[at].line, excerpt, "an M" if new_type == "Msg" else "a T or M"))
            if not sites and problem is None:
                problem = "%s; nothing in the file calls it" % what
            body_open = close + 1
            while h_tokens[body_open].value != "{":
                body_open += 1
            body_close = expressions.match_brace(h_tokens, body_open)
            uses = ["line %d `%s`" % (h_tokens[j].line, _excerpt(
                h_tokens[max(expressions.statement_span(h_tokens, j)[0], j - 8):
                         min(expressions.statement_span(h_tokens, j)[1], j + 8) + 1], None))
                    for j in range(body_open, body_close) if h_tokens[j].kind == "ident" and h_tokens[j].value == h_name
                    and not (j > 0 and h_tokens[j - 1].value == ".")]
            warning = None if problem else (
                "%s, and every call passes a catalog call: %s; check by hand that each use of %s in the body behaves "
                "the same with a %s (an overload chosen by type, equals, + with a String renders it): %s" % (
                    what, "; ".join(listed), h_name, new_type, "; ".join(uses) or "none"))
            found.append((b_type, h_type, problem, warning))
    return found


# ---------------------------------------------------------------------------
# Choice rows: a + chain holding a ternary becomes a ternary between whole templates (B8, B9, B10)
# ---------------------------------------------------------------------------

# Operators that bind looser than +: a branch holding one at its top level is one value (a hole).
LOOSER_THAN_PLUS = frozenset(("<<", ">>", ">>>", "<", ">", "<=", ">=", "==", "!=", "&", "^", "|", "&&", "||", "=",
                              "+=", "-=", "instanceof", "->"))
# A head choice may start right after one of these and end right before one of the closers.
CHOICE_OPENERS = frozenset(("(", "[", "=", ",", "?", ":", "->", "{", ";", "+=", "return", "yield"))
CHOICE_CLOSERS = frozenset((")", "]", ",", ";", ":", "}"))
CASE_LOCALES = (("Locale", ".", "ROOT"), ("java", ".", "util", ".", "Locale", ".", "ROOT"), ("Locale", ".", "US"),
                ("java", ".", "util", ".", "Locale", ".", "US"), ("Locale", ".", "ENGLISH"),
                ("java", ".", "util", ".", "Locale", ".", "ENGLISH"))


def _op(tokens, j, *values):
    return 0 <= j < len(tokens) and tokens[j].kind == "op" and tokens[j].value in values


def _top_level(tokens, first, last):
    """[index] of the tokens first..last outside any ( ), [ ] or { }."""
    out, depth = [], 0
    for j in range(first, last + 1):
        if _op(tokens, j, "(", "[", "{"):
            depth += 1
        elif _op(tokens, j, ")", "]", "}"):
            depth -= 1
        elif depth == 0:
            out.append(j)
    return out


def _ternary(tokens, first, last):
    """(condition, then, else) spans when first..last is c ? x : y at its top level, else None."""
    top = _top_level(tokens, first, last)
    marks = [j for j in top if _op(tokens, j, "?")]
    if not marks:
        return None
    q, nested = marks[0], 0
    for j in top:
        if j <= q:
            continue
        if _op(tokens, j, "?"):
            nested += 1
        elif _op(tokens, j, ":"):
            if nested == 0:
                return (first, q - 1), (q + 1, j - 1), (j + 1, last)
            nested -= 1
    return None


def _group(tokens, first, last):
    """True when tokens first..last are ( ... ) with the ( closed by the last token."""
    return _op(tokens, first, "(") and expressions.match_forward(tokens, first) == last


def _chain_around(tokens, first, last):
    """(first, last) of the + chain an operand first..last sits in (itself when it is in none)."""
    spans = expressions.concatenation(tokens, first) if first == last else None
    if spans:
        return spans[0][0], spans[-1][1]
    lo, hi = first, last
    while lo >= 2 and expressions._is(tokens[lo - 1], "+"):
        start = expressions._term_start(tokens, lo - 2)
        if start is None:
            break
        lo = start
    while hi + 2 < len(tokens) and expressions._is(tokens[hi + 1], "+"):
        end = expressions._term_end(tokens, hi + 2)
        if end is None:
            break
        hi = end
    return lo, hi


def _has_text_literal(tokens, first, last):
    """True when a branch of the ternary first..last (or ( ... ) around it) holds a string or char literal;
    literals in its condition (an id test) do not count."""
    while _group(tokens, first, last):
        first, last = first + 1, last - 1
    parts = _ternary(tokens, first, last)
    if parts is None:
        return any(tokens[j].kind in ("string", "char") for j in range(first, last + 1))
    return any(tokens[j].kind in ("string", "char") for j in range(parts[1][0], parts[2][1] + 1))


def _string_typed(tokens, first, last):
    """True when first..last is surely a String in Java: a string literal, a + chain with a String among its
    first two operands, or a ternary one of whose branches is (then its result is a reference and no
    numeric promotion happens). Unknown values (names, calls) are not."""
    while _group(tokens, first, last):
        first, last = first + 1, last - 1
    if first == last:
        return tokens[first].kind == "string"
    parts = _ternary(tokens, first, last)
    if parts is not None:
        return _string_typed(tokens, *parts[1]) or _string_typed(tokens, *parts[2])
    top = _top_level(tokens, first, last)
    if any(tokens[j].kind == "op" and tokens[j].value in LOOSER_THAN_PLUS for j in top):
        return False
    cuts = [j for j in top if _op(tokens, j, "+")]
    if not cuts:
        return False
    operands, start = [], first
    for j in cuts + [last + 1]:
        operands.append((start, j - 1))
        start = j + 1
    return any(_string_typed(tokens, a, b) for a, b in operands[:2])


def parse_choice(index, first, last, literals, conditions):
    """The base expression first..last as items: ("s", text), ("c", char), ("h", source) or
    ("t", condition source key, condition source, then items, else items). literals collects the
    string and char tokens that are text (not inside a condition), conditions those in conditions.
    Raises LedgerError for a numeric sum before the first string."""
    tokens = index.tokens
    while _group(tokens, first, last) and not _ternary(tokens, first + 1, last - 1):
        inner = _top_level(tokens, first + 1, last - 1)
        if not any(_op(tokens, j, "+") for j in inner):
            break
        first, last = first + 1, last - 1
    parts = _ternary(tokens, first + 1, last - 1) if _group(tokens, first, last) else _ternary(tokens, first, last)
    if parts is not None and not _has_text_literal(tokens, first, last):
        # A ternary with no text in it picks a value, not words: it is one hole, passed as it is.
        return [("h", " ".join(expressions.source_of(index.text, tokens, first, last).split()))]
    if parts is not None:
        (c1, c2), (t1, t2), (e1, e2) = parts
        if not (_string_typed(tokens, t1, t2) or _string_typed(tokens, e1, e2)):
            raise LedgerError("`%s` chooses between char or number values (Java promotes them to one type), which a "
                              "choice row does not model" % expressions.source_of(index.text, tokens, first, last))
        conditions.update(j for j in range(c1, c2 + 1) if tokens[j].kind in expressions.LITERALS)
        source = expressions.source_of(index.text, tokens, c1, c2)
        return [("t", expressions.source_key(source), " ".join(source.split()),
                 parse_choice(index, t1, t2, literals, conditions), parse_choice(index, e1, e2, literals, conditions))]
    top = _top_level(tokens, first, last)
    if any(tokens[j].kind == "op" and tokens[j].value in LOOSER_THAN_PLUS or
           (tokens[j].kind == "ident" and tokens[j].value == "instanceof") for j in top):
        return [("h", " ".join(expressions.source_of(index.text, tokens, first, last).split()))]
    cuts = [j for j in top if _op(tokens, j, "+")]
    operands, start = [], first
    for j in cuts + [last + 1]:
        operands.append((start, j - 1))
        start = j + 1
    items = []
    texty = [n for n, (a, b) in enumerate(operands) if (a == b and tokens[a].kind == "string") or
             (_group(tokens, a, b) and _ternary(tokens, a + 1, b - 1) and _string_typed(tokens, a, b))]
    if len(operands) > 1 and (not texty or texty[0] > 1 or (texty[0] == 1 and tokens[operands[0][0]].kind == "char"
                                                            and operands[0][0] == operands[0][1])):
        if not texty:
            return [("h", " ".join(expressions.source_of(index.text, tokens, first, last).split()))]
        raise LedgerError("`%s` adds numbers before its first string, which a choice row does not model" % (
            expressions.source_of(index.text, tokens, first, last)))
    for a, b in operands:
        if a == b and tokens[a].kind == "string":
            items.append(("s", tokens[a].value))
            literals.add(a)
        elif a == b and tokens[a].kind == "char":
            items.append(("c", tokens[a].value))
            literals.add(a)
        elif _group(tokens, a, b) and _ternary(tokens, a + 1, b - 1) and _has_text_literal(tokens, a, b):
            items.extend(parse_choice(index, a, b, literals, conditions))
        else:
            items.append(("h", " ".join(expressions.source_of(index.text, tokens, a, b).split())))
    return items


def id_test(condition):
    """(literal, variable) when a condition is "LIT".equals(v) with v a plain name, else None."""
    try:
        t = l10nlib.lex_java(condition)
    except l10nlib.LexError:
        return None
    if len(t) == 6 and t[0].kind == "string" and [x.raw for x in t[1:4]] == [".", "equals", "("] and \
            t[4].kind == "ident" and t[5].raw == ")":
        return t[0].value, t[4].value
    return None


def _case_mapped(source, variable, literal):
    """What hole source prints when variable holds literal: the literal itself for the bare name, its
    ASCII lower or upper case for v.toLowerCase(Locale.ROOT) (or US, ENGLISH) and v.toUpperCase(...).
    None when G1 cannot tell."""
    key = expressions.source_key(source)
    if key == (variable,):
        return literal
    if not literal.isascii() or len(key) < 6 or key[:2] != (variable, ".") or key[3] != "(" or key[-1] != ")":
        return None
    if key[2] not in ("toLowerCase", "toUpperCase") or tuple(key[4:-1]) not in CASE_LOCALES:
        return None
    return literal.lower() if key[2] == "toLowerCase" else literal.upper()


def force_choice(items, condition, truth):
    """items with condition taken as truth: a base ternary on that condition becomes its branch; an id
    test "LIT".equals(v) that holds puts LIT into the holes that print v. (items, resolved anything)."""
    key = expressions.source_key(condition)
    test = id_test(condition)
    out, hit = [], False
    for item in items:
        if item[0] == "t":
            if item[1] == key:
                branch, _ = force_choice(item[3] if truth else item[4], condition, truth)
                out.extend(branch)
                hit = True
                continue
            then, a = force_choice(item[3], condition, truth)
            other, b = force_choice(item[4], condition, truth)
            out.append(("t", item[1], item[2], then, other))
            hit = hit or a or b
        elif item[0] == "h" and test and truth:
            value = _case_mapped(item[1], test[1], test[0])
            if value is None:
                out.append(item)
            else:
                out.append(("s", value))
                hit = True
        else:
            out.append(item)
    return out, hit


def _merged_shape(items):
    """A comparable shape: adjacent strings joined (a template prints them together), holes by tokens."""
    out = []
    for kind, value in items:
        if kind == "s" and out and out[-1][0] == "string":
            out[-1] = ("string", out[-1][1] + value)
        elif kind == "s":
            out.append(("string", value))
        elif kind == "c":
            out.append(("char", value))
        else:
            out.append(("hole", expressions.source_key(value)))
    return tuple(o for o in out if o != ("string", ""))


def _leaf_items(sequence):
    out = []
    for piece in sequence:
        if isinstance(piece, str):
            out.append(("s", piece))
        elif "char" in piece:
            out.append(("c", piece["char"]))
        else:
            out.append(("h", piece["hole"]))
    return out


def _render_items(items):
    shown = []
    for item in items:
        if item[0] == "s":
            shown.append(l10nlib.json_string(item[1]))
        elif item[0] == "c":
            shown.append("'%s'" % item[1])
        elif item[0] == "h":
            shown.append(item[1])
        else:
            shown.append("(%s ? %s : %s)" % (item[2], _render_items(item[3]), _render_items(item[4])))
    return " + ".join(shown)


def check_choice_branches(row, items, ids):
    """Problems when the row's branches are not the base expression with each condition forced. ids
    collects the (literal, variable) of every id test."""
    problems = []

    def walk(node, current, path):
        if isinstance(node, list):
            if any(i[0] == "t" for i in current):
                problems.append("the branch %s still holds the choice %s of the base expression; give each of its "
                                "branches a condition of its own" % (
                                    " and ".join(path) or "(top)", _render_items([i for i in current if i[0] == "t"])))
                return
            if _merged_shape(current) != _merged_shape(_leaf_items(node)):
                problems.append("the branch %s is %s, but the base expression with %s is %s" % (
                    " and ".join(path), json.dumps(node, ensure_ascii=False),
                    " and ".join(path), _render_items(current)))
            return
        condition = node["if"]
        test = id_test(condition)
        then, hit = force_choice(current, condition, True)
        other, _ = force_choice(current, condition, False)
        if not hit and test is None:
            problems.append("the condition %s is neither a choice of the base expression on this branch nor a test of "
                            "a data id (\"On\".equals(value))" % condition)
            return
        if test is not None:
            ids.add(test)
        walk(node["then"], then, path + [condition])
        walk(node["else"], other, path + ["!(%s)" % condition])
    walk(row.pieces, items, [])
    return problems


def locate_choice(row, diff):
    """([(literal tokens, (first, last) base span, items)], problem) for a choice row: each + chain (or
    parenthesized ternary) on the row's line holding a ternary operand, or the plain chain an id test
    chooses on. Its text literals are every string and char outside the conditions."""
    index = diff.base
    tokens = index.tokens
    tried, last_problem, found = set(), None, []
    for k, tok in enumerate(tokens):
        if tok.kind not in expressions.LITERALS or tok.line != row.base_line:
            continue
        first, last = _chain_around(tokens, k, k)
        while True:
            before, after = first - 1, last + 1
            if not (_op(tokens, before, "?", ":") and _op(tokens, after, ":", ")")):
                break
            opener = before
            depth = 0
            while opener >= 0:
                if _op(tokens, opener, ")", "]", "}"):
                    depth += 1
                elif _op(tokens, opener, "(", "[", "{"):
                    if depth == 0:
                        break
                    depth -= 1
                opener -= 1
            if opener < 0 or not _op(tokens, opener, "(") or (opener > 0 and expressions._callee(tokens[opener - 1])):
                break
            close = expressions.match_forward(tokens, opener)
            if close is None or _ternary(tokens, opener + 1, close - 1) is None or \
                    any(_op(tokens, j, ",", ";") for j in _top_level(tokens, opener + 1, close - 1)):
                break
            first, last = _chain_around(tokens, opener, close)
        if (first, last) in tried:
            continue
        tried.add((first, last))
        literals, conditions = set(), set()
        try:
            items = parse_choice(index, first, last, literals, conditions)
        except LedgerError as error:
            last_problem = str(error)
            continue
        if not literals or min(tokens[j].line for j in literals) != row.base_line:
            continue
        found.append((sorted(literals), (first, last), items))
    if found:
        return found, None
    return [], last_problem or "no + chain or ternary with text starts on line %d of %s" % (row.base_line, row.path)


def _leaf_call(index, pos, key, sequence):
    """The last token of T.<key>(...) or M.<key>(...) at pos passing the branch's holes in placeholder
    order (plus a .render() on an M call), else None."""
    tokens = index.tokens
    if not (tokens[pos].kind == "ident" and tokens[pos].value in ("T", "M") and _op(tokens, pos + 1, ".") and
            pos + 3 < len(tokens) and tokens[pos + 2].value == gen_catalog.camel(key) and _op(tokens, pos + 3, "(")):
        return None
    if pos > 0 and _op(tokens, pos - 1, "."):
        return None
    _, end = call_span(tokens, pos)
    want = Row(None, 0, "", "", 0, sequence, key, "template", "", None)
    return end if _passes_holes(want, _arguments(index, pos + 3)) else None


def match_choice_head(diff_head, pos, node, keys, calls):
    """The last token of a head choice at pos that mirrors node: [(] condition ? branch : branch [)],
    each leaf a call of its key passing its holes; calls collects the T/M tokens. Else None."""
    tokens = diff_head.tokens
    if isinstance(node, list):
        key = keys.pop(0)
        end = _leaf_call(diff_head, pos, key, node)
        if end is not None:
            calls.append(pos)
        return end
    if _op(tokens, pos, "("):
        close = expressions.match_forward(tokens, pos)
        if close is None:
            return None
        end = match_choice_head(diff_head, pos + 1, node, keys, calls)
        return close if end == close - 1 else None
    want = [t.raw for t in l10nlib.lex_java(node["if"])]
    if [t.raw for t in tokens[pos:pos + len(want)]] != want or not _op(tokens, pos + len(want), "?"):
        return None
    end = match_choice_head(diff_head, pos + len(want) + 1, node["then"], keys, calls)
    if end is None or not _op(tokens, end + 1, ":"):
        return None
    return match_choice_head(diff_head, end + 2, node["else"], keys, calls)


def run(base, head, explicit_ledgers=None, cwd=None):
    """(problems, warnings, summary)."""
    merge = l10nlib.merge_base(base, head, cwd)
    files = [p for p in l10nlib.changed_files(merge, head, ["src"], cwd) if p.endswith(".java")]
    before = l10nlib.read_files(merge, files, cwd)
    after = l10nlib.read_files(head, files, cwd)
    diffs = {}
    problems, warnings = [], []
    for path in files:
        try:
            diffs[path] = expressions.FileDiff(before[path], after[path])
        except l10nlib.LexError as error:
            problems.append("%s: %s" % (path, error))
    rows, row_problems = range_rows(merge, head, explicit_ledgers, cwd)
    problems.extend(row_problems)

    head_sources = [p for p in l10nlib.list_files(head, ["src"], cwd) if p.endswith(".java")]
    catalog_rows = any(r.kind in CATALOG_KINDS + (CHOICE,) for r in rows)
    head_texts = l10nlib.read_files(head, head_sources, cwd) if catalog_rows else {}
    calls = call_index(head_texts) if catalog_rows else None
    types = SourceTypes(head_texts)

    claimed = {}  # (path, token) -> row
    staying = []  # (row, path, token) gone literals a stays-english or id-kept row says moved
    converted = []  # (path, first base token, row, where, head line ranges) for the call-site check
    manual = 0
    allowed_base = collections.defaultdict(dict)  # path -> {base token: span id} a row may replace or delete
    allowed_head = collections.defaultdict(dict)  # path -> {head token: span id} of the calls that serve rows
    manual_rows = set()
    removed_own = collections.defaultdict(set)  # path -> base tokens of removed rows' own statements
    removed_frames = collections.defaultdict(set)  # path -> headers and braces of blocks they deleted
    widened = []  # (row, where, path, own statement span, widened span) for removed rows past their statement
    format_rows = {}  # id(row) -> the base span a format row's call replaces
    choices = []  # (row, where, path, base span, items, id tests, head line ranges) of choice rows
    for row in rows:
        if not row.path.startswith("src/"):
            continue
        where = "%s:%d" % (row.source, row.line)
        diff = diffs.get(row.path)
        if diff is None or diff.base is None:
            if row.kind in MOVING:
                problems.append("%s: %s did not change since the merge base, or did not exist there" % (where, row.path))
            continue
        if row.kind == CHOICE:
            candidates, problem = locate_choice(row, diff)
            chosen, first_problems = None, None
            for literals, span, items in candidates:
                ids = set()
                branch_problems = check_choice_branches(row, items, ids)
                if not branch_problems:
                    chosen = (literals, span, items, ids)
                    break
                if first_problems is None or len(literals) > len(candidates[0][0]):
                    first_problems = branch_problems
            if chosen is None:
                problems.extend("%s: choice row at %s:%d: %s" % (where, row.path, row.base_line, p)
                                for p in (first_problems or [problem]))
                continue
            literals, span, items, ids = chosen
            for j in range(span[0], span[1] + 1):
                allowed_base[row.path].setdefault(j, id(row))
            for k in literals:
                tok = diff.base_token(k)
                if (row.path, k) in claimed:
                    other = claimed[(row.path, k)]
                    problems.append("%s: %s on line %d is already covered by %s:%d" % (
                        where, l10nlib.json_string(tok.value), tok.line, other.source, other.line))
                    continue
                claimed[(row.path, k)] = row
                if k not in diff.gone:
                    problems.append("%s: the row says %s (line %d) left, but it is still there at head" % (
                        where, l10nlib.json_string(tok.value), tok.line))
            hunks = sorted(set(diff.gone[k] for k in literals if k in diff.gone))
            choices.append((row, where, row.path, span, items, ids, [diff.hunks[h][2:] for h in hunks]))
            continue
        tokens, is_manual, problem = locate(row, diff, claimed)
        if problem:
            problems.append("%s: %s" % (where, problem))
            continue
        format_span = None
        if row.kind == "format":
            problem, format_span, _ = format_site(row, diff, tokens)
            if problem:
                problems.append("%s: %s" % (where, problem))
                continue
            format_rows[id(row)] = format_span
        manual += 1 if is_manual else 0
        if is_manual:
            manual_rows.add(id(row))
        for k in tokens:
            if row.kind == "removed":
                first, last, terminator = statement_of(diff, k)
                if terminator is not None and diff.base_token(terminator).value == ";":
                    last = terminator
                own = range(first, last + 1)
                removed_own[row.path].update(own)
                first, last, frame = deleted_scope(diff, first, last)
                removed_frames[row.path].update(frame)
                span = range(first, last + 1)
                if len(span) > len(own) and not any(w[0] is row and w[4] == span for w in widened):
                    widened.append((row, where, row.path, own, span))
            elif row.kind == "format":
                # A String.format literal's row replaces the whole String.format(...) call.
                first, last = format_span
                span = range(first, last + 1)
            elif row.kind in CATALOG_KINDS and is_manual:
                first, last, _ = statement_of(diff, k)
                span = range(first, last + 1)
            elif row.kind in CATALOG_KINDS:
                first, last = diff.base.expression_of(k).span
                span = range(first, last + 1)
            else:
                continue
            for j in span:
                allowed_base[row.path].setdefault(j, id(row))
        if row.kind == "removed" and tokens:
            # Each statement the row deletes is listed, unless a whole-block warning already prints it;
            # a row may cover literals in several statements, only some of them inside a deleted block.
            shown = set()
            for k in sorted(tokens):
                first, last, _ = statement_of(diff, k)
                if (first, last) in shown or any(
                        w[2] == row.path and w[4][0] <= first and last <= w[4][-1] for w in widened):
                    continue
                shown.add((first, last))
                warnings.append("%s: removed row at %s:%d deletes `%s`; check by hand that no screen showed this "
                                "text" % (where, row.path, row.base_line, _excerpt(diff.base.tokens[first:last + 1], None)))
        for piece in helper_pieces(row.pieces):
            problem = helper_problem(piece, diff.base)
            if problem:
                problems.append("%s: %s" % (where, problem))
            else:
                shown = "; ".join("%s at %d: %s" % (form, q, json.dumps(evaluate_helper_form(row.pieces[form], q),
                                                                         ensure_ascii=False))
                                  for form in ("one", "other") for q in FORM_QUANTITIES[form][:2])
                warnings.append("%s: at %s:%d `%s` is modelled as %s (its declaration matches the model token for token); "
                                "key %s's forms must print what the base prints with the quantity %s (G1 and G2 check "
                                "one at 1 and other at 0, 2 and 21): %s" % (
                                    where, row.path, row.base_line, helper_source(piece), piece["model"], row.key,
                                    " ".join(expected_arguments(row)[0]), shown))
        if is_manual:
            warnings.append("%s: manual row at %s:%d; check its pieces, and the statement around its call, by hand: %s" % (
                where, row.path, row.base_line, json.dumps(row.pieces, ensure_ascii=False)))
        for hole in holes(row.pieces) if row.kind != "format" else ():
            if "as" in hole:
                warnings.append("%s: at %s:%d the hole %s is passed as %s; check by hand that it renders the same" % (
                    where, row.path, row.base_line, hole["hole"], hole["as"]))
        for k in tokens:
            tok = diff.base_token(k)
            if (row.path, k) in claimed:
                other = claimed[(row.path, k)]
                problems.append("%s: %s on line %d is already covered by %s:%d" % (
                    where, l10nlib.json_string(tok.value), tok.line, other.source, other.line))
                continue
            claimed[(row.path, k)] = row
            if row.kind in MOVING and k not in diff.gone:
                problems.append("%s: the row says %s (line %d) left, but it is still there at head" % (
                    where, l10nlib.json_string(tok.value), tok.line))
            elif row.kind == "removed" and added_in_hunk(diff, diff.gone[k]) is not None:
                replacement = added_in_hunk(diff, diff.gone[k])
                problems.append("%s: removed is for deleted code, but the change that removes %s puts %s in its "
                                "place (head line %d); deleting code only loses tokens, so record where the text "
                                "went instead" % (where, l10nlib.json_string(tok.value), replacement.raw,
                                                  replacement.line))
            elif row.kind == "removed" and not deleted_whole(diff, k):
                first, last, _ = statement_of(diff, k)
                deleted, _ = diff.changed_tokens()
                kept = [diff.base_token(j) for j in range(first, last + 1) if j not in deleted]
                problems.append("%s: removed is for deleted code, but only part of the statement holding %s (line "
                                "%d) was deleted; %s still runs at head. Delete the whole statement, or record "
                                "where the text went" % (where, l10nlib.json_string(tok.value), tok.line,
                                                        _excerpt(kept)))
            elif row.kind in STAYING and k in diff.gone:
                staying.append((row, row.path, k))
        if row.kind in CATALOG_KINDS and calls is not None:
            hunks = sorted(set(diff.gone[k] for k in tokens if k in diff.gone))
            if hunks:
                converted.append((row.path, min(tokens), row, where, [diff.hunks[h][2:] for h in hunks]))

    # A removed row whose statement went with its enclosing block (an if, a method) frees that whole
    # block. Every other statement deleted with it must hold a removed row's literal itself, unless
    # the row's note asks for the whole block; either way the reviewer sees the entire block.
    asked = [(path, span) for row, _, path, _, span in widened if row.note.startswith(WHOLE_BLOCK)]
    listed = set()
    for row, where, path, own, span in widened:
        tokens = diffs[path].base.tokens
        others = [j for j in span if j not in removed_own[path] and j not in removed_frames[path]]
        whole = any(p == path and s[0] <= span[0] and span[-1] <= s[-1] for p, s in asked)
        if others and not whole:
            first, last, _ = statement_of(diffs[path], others[0])
            problems.append("%s: removed row at %s:%d deletes the whole block around its statement (base lines "
                            "%d-%d), which also deletes `%s` (base line %d), code with no removed row of its own; "
                            "record that code, or start a note of a removed row in the block with \"%s\" to ask "
                            "for the whole block" % (
                                where, path, row.base_line, tokens[span[0]].line, tokens[span[-1]].line,
                                _excerpt(tokens[first:last + 1], None), tokens[others[0]].line, WHOLE_BLOCK))
        if (path, span[0], span[-1]) in listed:
            continue
        listed.add((path, span[0], span[-1]))
        warnings.append("%s: removed row at %s:%d deletes the whole block (base lines %d-%d)%s: `%s`; check by "
                        "hand that no screen showed this text and nothing in it set what a screen shows" % (
                            where, path, row.base_line, tokens[span[0]].line, tokens[span[-1]].line,
                            ", with other code, as a whole block: note asks" if others and whole else "",
                            _excerpt(tokens[span[0]:span[-1] + 1], None)))

    used, last, callers = set(), {}, None
    retyped_names = collections.defaultdict(set)  # path -> names of String declarations now CharSequence
    for path, first_token, row, where, ranges in sorted(converted, key=lambda c: (c[0], c[1])):
        problem, call = check_call_sites(row, calls, path, ranges, used, last.get(path, -1))
        if problem:
            problems.append("%s: %s" % (where, problem))
        else:
            used.add((call[0], call[2]))
            last[path] = call[2]
            if path in diffs and diffs[path].head is not None:
                head_tokens = diffs[path].head.tokens
                if id(row) in manual_rows:
                    # A manual row's base side is its whole statement, so the head side is too.
                    first, end, _ = expressions.statement_span(head_tokens, call[2])
                else:
                    first, end = call_span(head_tokens, call[2])
                for j in range(first, end + 1):
                    allowed_head[path].setdefault(j, id(row))
                if id(row) not in manual_rows:
                    base_span = format_rows[id(row)] if row.kind == "format" else \
                        diffs[path].base.expression_of(first_token).span
                    if row.kind != "format":
                        problem, retyped = exception_retype(types, path, diffs[path], base_span, (first, end))
                        if problem:
                            problems.append("%s: %s" % (where, problem))
                        elif retyped is not None:
                            # A throw switched to a subclass that keeps getMessage() English (B10, B6, B4).
                            b_name, h_name, id_tokens, warning = retyped
                            for j in b_name:
                                allowed_base[path].setdefault(j, ("exception", id(row)))
                            for j in h_name:
                                allowed_head[path].setdefault(j, ("exception", id(row)))
                            for j in id_tokens:
                                allowed_head[path].setdefault(j, id(row))
                            warnings.append("%s: %s" % (where, warning))
                    split = split_declaration(diffs[path], base_span, (first, end))
                    retyped = None if split is not None else retyped_declaration(diffs[path], base_span, (first, end))
                    if retyped is not None:
                        # A deferred M call needs a CharSequence where a String held the literal.
                        b_type, h_type, name = retyped
                        allowed_base[path].setdefault(b_type, ("retype", id(row)))
                        allowed_head[path].setdefault(h_type, ("retype", id(row)))
                        # Every declarator of the declaration is a CharSequence now.
                        _, h_end = statement_bounds(head_tokens, h_type)
                        retyped_names[path].update(head_tokens[f].value for f, _ in _declarators(head_tokens, h_type, h_end)
                                                   if head_tokens[f].kind == "ident")
                        reach = retype_reach_problem(where, path, diffs[path], h_type)
                        if reach:
                            problems.append(reach)
                        else:
                            warnings.append(retype_warning(where, path, diffs[path], h_type, name))
                    elif split is not None:
                        # The same, for one declarator of several, moved into a declaration of its own.
                        b_tokens, h_tokens, h_type, name = split
                        for j in b_tokens:
                            if allowed_base[path].get(j) != id(row):
                                allowed_base[path][j] = ("retype", id(row))
                        for j in h_tokens:
                            if allowed_head[path].get(j) != id(row):
                                allowed_head[path][j] = ("retype", id(row))
                        retyped_names[path].add(name)
                        reach = retype_reach_problem(where, path, diffs[path], h_type)
                        if reach:
                            problems.append(reach)
                        else:
                            warnings.append(retype_warning(
                                where, path, diffs[path], h_type, name,
                                _excerpt(diffs[path].base.tokens[b_tokens[0]:b_tokens[-1] + 1], None)))
                if row.kind == "format":
                    if callers is None:
                        callers = test_callers(head, cwd)
                    found, warning = format_review(row, where, diffs[path], format_rows[id(row)], (first, end),
                                                   callers)
                    problems.extend(found)
                    warnings.append(warning)
                    # The text keys' calls it passes serve this row, not an exact row of the same key; the T
                    # calls inside a %s argument serve the exact rows of the literals they replaced.
                    if head_tokens[call[2] + 3].value == "(":
                        _, spans = _argument_spans(head_tokens, call[2] + 3)
                    else:  # Msg.of(T.KEY, ...) or T.render(T.KEY, ...): the template's args follow the id
                        _, spans = _argument_spans(head_tokens, call[2] - 1)
                        spans = spans[1:]
                    texts = sorted(item["arg"] for item in row.split if isinstance(item, dict) and "key" in item)
                    for arg in texts:
                        if arg <= len(spans):
                            used.add((path, spans[arg - 1][0]))

    tested_ids = set()  # (path, head token) of the data ids a choice row's conditions compare
    base_literals = None
    for row, where, path, span, items, ids, ranges in choices:
        index = diffs[path].head
        tokens = index.tokens
        found = None
        for pos in range(1, len(tokens) - 1):
            if not any(h1 <= tokens[pos].line <= h2 for h1, h2 in ranges) or tokens[pos - 1].value not in CHOICE_OPENERS:
                continue
            if _op(tokens, pos, "(") and expressions._callee(tokens[pos - 1]):
                continue
            leaf_calls = []
            try:
                end = match_choice_head(index, pos, row.pieces, row.key.split(","), leaf_calls)
            except l10nlib.LexError:
                end = None
            if end is None or not _op(tokens, end + 1, *CHOICE_CLOSERS) or any((path, c) in used for c in leaf_calls):
                continue
            found = (pos, end, leaf_calls)
            break
        base_code = _excerpt(diffs[path].base.tokens[span[0]:span[1] + 1], None)
        if found is None:
            problems.append("%s: choice row at %s:%d: `%s` must become a ternary on the same condition(s), %s, with "
                            "each branch one call of its key (%s) passing that branch's holes in placeholder order, "
                            "in the head lines that replaced it (%s)" % (
                                where, path, row.base_line, base_code, ", ".join(choice_conditions(row.pieces)),
                                row.key.replace(",", ", "), ", ".join("%d-%d" % r for r in ranges) or "none"))
            continue
        pos, end, leaf_calls = found
        for j in range(pos, end + 1):
            allowed_head[path].setdefault(j, id(row))
        used.update((path, c) for c in leaf_calls)
        tested = []
        for literal, variable in sorted(ids):
            arrived = [j for j in range(pos, end + 1) if tokens[j].kind == "string" and tokens[j].value == literal]
            tested_ids.update((path, j) for j in arrived if j in diffs[path].added)
            if base_literals is None:
                base_literals = {}
                names = [p for p in l10nlib.list_files(merge, ["src"], cwd) if p.endswith(".java")]
                for p, text in sorted(l10nlib.read_files(merge, names, cwd).items()):
                    try:
                        for t in l10nlib.lex_java(text or ""):
                            if t.kind == "string":
                                base_literals.setdefault(t.value, "%s:%d" % (p, t.line))
                    except l10nlib.LexError:
                        pass
            if literal not in base_literals:
                problems.append("%s: choice row at %s:%d tests %s.equals(%s), but %s is no literal in src at the merge "
                                "base; a choice tests only a data id the code already has" % (
                                    where, path, row.base_line, l10nlib.json_string(literal), variable,
                                    l10nlib.json_string(literal)))
            else:
                tested.append("%s (%s at the merge base)" % (l10nlib.json_string(literal), base_literals[literal]))
        branches = ["%s: %s" % (key, json.dumps(leaf, ensure_ascii=False))
                    for key, leaf in zip(row.key.split(","), choice_leaves(row.pieces))]
        warnings.append("%s: choice row at %s:%d: `%s` becomes `%s`; branches %s%s; the conditions now run before the "
                        "holes, so check by hand that no hole has a side effect the condition reads" % (
                            where, path, row.base_line, base_code, _excerpt(tokens[pos:end + 1], None),
                            "; ".join(branches),
                            "; data id(s) tested: " + ", ".join(tested) if tested else ""))

    arrivals = []  # [path, hunk, token, used] for every literal added to a changed src file
    for path in sorted(diffs):
        for k in sorted(diffs[path].added):
            arrivals.append([path, diffs[path].added[k], k, "choice" if (path, k) in tested_ids else False])
    named = collections.defaultdict(list)  # (path, head token) of a constant -> [(row, path, base token)] naming it
    existing = set()  # constants named above that were already in src (not arrivals)
    head_lexed = {}

    def head_tokens(p):
        if p in diffs and diffs[p].head is not None:
            return diffs[p].head.tokens
        if p not in head_lexed:
            head_lexed[p] = []
            try:
                head_lexed[p] = l10nlib.lex_java(l10nlib.read_files(head, [p], cwd).get(p) or "")
            except l10nlib.LexError:
                pass
        return head_lexed[p]

    def sources():
        unchanged = [p for p in head_sources if p not in diffs and p not in head_lexed]
        if unchanged:
            for p, text in l10nlib.read_files(head, unchanged, cwd).items():
                head_lexed[p] = []
                try:
                    head_lexed[p] = l10nlib.lex_java(text or "")
                except l10nlib.LexError:
                    pass
        for p in head_sources:
            skip = set(diffs[p].added) if p in diffs and diffs[p].head is not None else set()
            yield p, head_tokens(p), skip
    guesses = collections.defaultdict(list)  # arrival number -> [(row, path, base token, chosen arrival)]
    for row, path, k in staying:
        tok = diffs[path].base_token(k)
        hunk = diffs[path].gone[k]
        here = (path, hunk)
        moved_whole = added_in_hunk(diffs[path], hunk) is None
        # A move lands outside the changed region it left (within one region it is a reorder, a swap).
        # The code it left names the constant it went to (several sites may share one constant), or
        # was moved out whole (one site per arrival).
        same = [n for n, a in enumerate(arrivals) if (a[0], a[1]) != here and
                (diffs[a[0]].head_token(a[2]).kind, diffs[a[0]].head_token(a[2]).value) == (tok.kind, tok.value)]
        chosen = next((n for n in same if arrivals[n][3] in (False, "named") and
                       _points_to(diffs[path], hunk, path, arrivals[n][0], arrivals[n][2], diffs[arrivals[n][0]])), None)
        if chosen is not None:
            arrivals[chosen][3] = "named"
            named[(arrivals[chosen][0], arrivals[chosen][2])].append((row, path, k))
            continue
        free = [n for n in same if arrivals[n][3] is False] if moved_whole else []
        if free:
            # Code moved out whole: prefer the copy that arrived as the same expression (all the row's
            # literals together), then one in a class or method the region's head lines name, so a new
            # literal of the same value elsewhere (a "f" in the file that sorts first) is not taken for it.
            ranked = sorted(free, key=lambda n: (-arrival_match(row, diffs[path], k, hunk, arrivals[n], diffs), n))
            chosen = ranked[0]
            arrivals[chosen][3] = "whole"
            if len(free) > 1 and arrival_match(row, diffs[path], k, hunk, arrivals[chosen], diffs) == \
                    arrival_match(row, diffs[path], k, hunk, arrivals[ranked[1]], diffs):
                for n in ranked[1:]:
                    guesses[n].append((row, path, k, chosen))
            continue
        # A constant already in src that holds the same value (a later batch moving its data units to
        # B1's UnitCode), named by the code the literal left.
        if tok.kind == "string":
            found = next(((p, j) for p, j in existing_constants(sources(), tok.value)
                          if _references(diffs[path], hunk, path, p, constant_name(head_tokens(p), j))), None)
            if found is not None:
                existing.add(found)
                named[found].append((row, path, k))
                continue
        problems.append("%s:%d: %s row: %s left %s:%d and is not added anywhere else in src where the code it "
                        "left points (a constant those lines name, or code moved out whole), nor held by an "
                        "existing constant those lines name; text that leaves src needs exact, template, plural, "
                        "format or removed" % (
                            row.source, row.line, row.kind, l10nlib.json_string(tok.value), path, tok.line))
    problems_named, warnings_named = check_named_moves(named, head_tokens, diffs, existing)
    problems.extend(problems_named)
    warnings.extend(warnings_named)

    # A new lower-case key, path or id is not text (a preference name, a requireNonNull message); it is
    # allowed, and listed with its statement, since a person could still read one.
    # A new format-only literal ("%.1f", "%d") holds no words either; it is allowed and listed the same way.
    new_ids, new_formats = collections.defaultdict(set), collections.defaultdict(set)
    for path, _, k, used_by_move in arrivals:
        tok = diffs[path].head_token(k)
        if not used_by_move and tok.kind == "string" and l10nlib.is_identifier_like(tok.value):
            new_ids[path].add(k)
        elif not used_by_move and tok.kind == "string" and is_format_only(tok.value):
            new_formats[path].add(k)

    moved_hunks = set((path, diffs[path].gone[k]) for _, path, k in staying)
    moved_hunks.update((a[0], a[1]) for a in arrivals if a[3])
    wrapped, english_only = 0, None
    for path in sorted(diffs):
        served = {}
        if diffs[path].head is not None:
            for p, k in used:
                if p == path and diffs[path].head.tokens[k].value in ("T", "M"):
                    served[call_span(diffs[path].head.tokens, k)] = k
        for n, (b_type, h_type, problem, warning) in enumerate(parameter_retypes(path, diffs[path], served, head_texts)):
            allowed_base[path].setdefault(b_type, ("parameter", n))
            allowed_head[path].setdefault(h_type, ("parameter", n))
            if problem:
                problems.append(problem)
            else:
                warnings.append(warning)
        for n, (b_loc, h_loc, problem, warning) in enumerate(locale_swaps(path, diffs[path])):
            allowed_base[path].setdefault(b_loc, ("root", n))
            allowed_head[path].setdefault(h_loc, ("root", n))
            if problem:
                problems.append(problem)
            else:
                warnings.append(warning)
        wraps = set()
        problem_list, warning_list = other_changes(path, diffs[path], allowed_base[path], allowed_head[path],
                                                   moved_hunks, new_ids[path] | new_formats[path], wraps,
                                                   retyped_names[path])
        problems.extend(problem_list)
        warnings.extend(warning_list)
        for kind, k in sorted(wraps):
            if kind != "string":
                continue
            tokens = diffs[path].head.tokens
            first, last, _ = expressions.statement_span(tokens, k)
            warnings.append("%s:%d: `%s` becomes `%s . toString ( )` in `%s`, since its declaration is a CharSequence "
                            "now (listed above); check by hand that it is the String it was" % (
                                path, tokens[k].line, tokens[k].raw, tokens[k].raw, _excerpt(tokens[first:last + 1], 40)))
        wraps = set(k for kind, k in wraps if kind == "locale")
        if wraps and callers is None:
            callers = test_callers(head, cwd)
        if wraps and english_only is None:
            english_only, unread = english_only_files(head, cwd)
            if unread:
                problems.append(unread)
        for k in sorted(wraps):
            wrapped += 1
            found, warning = locale_wrap_review(path, diffs[path], k, callers, english_only)
            problems.extend(p for p in found if p not in problems)
            warnings.append(warning)

    gone_total = 0
    gone_files = set()
    for path in sorted(diffs):
        diff = diffs[path]
        for k in sorted(diff.gone):
            gone_total += 1
            gone_files.add(path)
            if (path, k) not in claimed:
                tok = diff.base_token(k)
                problems.append("%s:%d: %s %s left with no ledger row" % (
                    path, tok.line, "char" if tok.kind == "char" else "literal", l10nlib.json_string(tok.value)))

    new_literals = 0
    for found, what, check in ((new_ids, "key or id", "that no screen shows it"),
                               (new_formats, "format-only", "that the number it formats prints as before")):
        for path in sorted(found):
            head_tokens = diffs[path].head.tokens
            for k in sorted(found[path]):
                first, last, _ = expressions.statement_span(head_tokens, k)
                # A window centred on the literal, so a long statement never hides the code that uses it.
                start, end = max(first, k - 20), min(last, k + 20)
                warnings.append("%s:%d: new %s literal %s in `%s%s%s`; check by hand %s" % (
                    path, head_tokens[k].line, what, l10nlib.json_string(head_tokens[k].value),
                    "... " if start > first else "", _excerpt(head_tokens[start:end + 1], None),
                    " ..." if end < last else "", check))
    for path, _, k, used_by_move in arrivals:
        tok = diffs[path].head_token(k)
        if k in new_ids[path] or k in new_formats[path]:
            continue
        if not used_by_move and not (tok.kind == "string" and tok.value == ""):
            new_literals += 1
            number = next(n for n, a in enumerate(arrivals) if (a[0], a[2]) == (path, k))
            hint = "".join(
                "; G1 could not tell which copy of %s the %s row at %s:%d (%s:%d) moved to, and took the one at "
                "%s:%d: if this one is that copy, make the code it left name this one's class or method (or move "
                "it as the same expression), or record the other as new text" % (
                    l10nlib.json_string(tok.value), row.kind, row.source, row.line, left_path,
                    diffs[left_path].base_token(left_k).line, arrivals[chosen][0],
                    diffs[arrivals[chosen][0]].head_token(arrivals[chosen][2]).line)
                for row, left_path, left_k, chosen in guesses.get(number, []))
            problems.append("%s:%d: new %s %s; an extraction batch adds no text to src (put it in the catalog), and "
                            "a literal that moved needs a stays-english or id-kept row%s" % (
                                path, tok.line, "char" if tok.kind == "char" else "literal",
                                l10nlib.json_string(tok.value), hint))

    catalog_problems, catalog_rows = catalog_consistency(head, cwd)
    problems.extend(catalog_problems)
    summary = "%d literal(s) left %d src file(s); %d new ledger row(s)%s; %d new literal(s); " \
              "%d new key or id literal(s), listed; %s%d ledger row(s) agree with the catalog" % (
                  gone_total, len(gone_files), len(rows), " (%d manual)" % manual if manual else "",
                  new_literals, sum(len(v) for v in new_ids.values()),
                  ("%d new format-only literal(s), listed; " % sum(len(v) for v in new_formats.values())
                   if any(new_formats.values()) else "") +
                  ("%d legacy locale(s) wrapped in Fmt.locale, listed; " % wrapped if wrapped else ""), catalog_rows)
    return problems, warnings, summary


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--base", help="base revision, for example origin/main")
    parser.add_argument("--head", default="HEAD", help="head revision, or WORKTREE for uncommitted files")
    parser.add_argument("--ledger", nargs="*", help="ledger files (default: rows added since the merge base)")
    parser.add_argument("--catalog-only", action="store_true",
                        help="only check every ledger at head against the catalog (any pull request)")
    args = parser.parse_args(argv)
    head = l10nlib.resolve(args.head)
    if args.catalog_only:
        problems, count = catalog_consistency(head)
        warnings, summary = [], "%d ledger row(s) agree with the catalog" % count
    else:
        if not args.base:
            parser.error("--base is required")
        problems, warnings, summary = run(args.base, head, args.ledger)
    for warning in warnings:
        print("ledger_check: warning: " + warning, file=sys.stderr)
    for problem in problems:
        print("ledger_check: " + problem, file=sys.stderr)
    print("ledger_check: %s: %s" % ("FAILED" if problems else "PASS", summary))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
