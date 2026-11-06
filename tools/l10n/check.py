#!/usr/bin/env python3
"""Automated checks on translation drafts (plan 7.2 step 3; CatalogCheckTest mirrors the merged result).

  check.py --tag es --area common                 every draft in l10n/work/es/common/drafts/
  check.py --tag es --area common --draft openai  one draft

Writes l10n/work/<tag>/<area>/checks.jsonl, one line per draft and key:
  {"draft", "provider", "model", "attempt", "key", "source_sha1", "draft_src", "ok", "findings",
   "width_method"}
where each finding is {"check", "severity", "quantity", "message"}. Severity "error" fails the draft
for that key; "warn" goes to the judge; "info" is a note. Exit status 1 when any key failed.
draft_src is the entry's volt:src; an entry whose draft_src is missing or is not source_sha1 fails
the "source" check: it translated other English, so it is redrafted, never relabelled.

Checks, per key (a plural is checked per quantity):
  source        the entry's volt:src is the hash of the current English (check_draft only; the
                council drops such entries before every other step)
  kind          the draft has a string where English has a string, plurals where it has plurals
  empty         no empty text where English has text; spaces and invisible characters alone are empty
  placeholders  the same %n$ placeholders with the same conversions, and %% where English has it;
                only positional forms and %%; a plural category that covers more than one number
                (every one but n=1-only "one" and the like) keeps the count
  numbers       every number written in the English appears in the target (any script's digits;
                "1,024" in the English may be 1024, 1.024, 1,024 or 1 024 with a space, U+00A0 or U+202F)
  plurals       every CLDR category the language needs is present
  escapes       the draft's spelling is one aapt stores exactly (apostrophes, quotes, escapes), and
                there are no control characters
  dnt           volt:dnt tokens, do-not-translate glossary terms and fixed tokens (file names, CLI
                flags, URLs, {z}/{x}/{y}, the U+FFFC placeholder) are kept exactly
  markup        the same number of ** and backtick marks, and every `code span` unchanged
  glossary      approved glossary terms are used: as whole words in scripts with word spaces (at a
                word start where the language takes suffixes, with endings where it inflects; a lock
                term may start a compound in de, nl and sv), anywhere in zh, ja and Thai; a lock term
                next to words that make it unlock or remove the lock is not found (warn; error for a
                safety term in a safety key). A term found only inside a longer term ("Lock" in "Lock
                duration") is not required on its own, and a setting value ("Off", an Energy recovery
                level) only where it has that sense (translib.glossary_hits)
  whitespace    no leading or trailing whitespace drift; no new repeated spaces or line breaks
  glyphs        Saira has every character (Latin-script languages)
  ladder        every rung English has (full, _short, _compact) is drafted together
  width         each key with volt:max fits its budget, measured with Saira (see fontmetrics.py); a
                budget with *N lines fits when a greedy whole-word wrap takes at most N lines
  polarity      negation in the English (not, no, never, un-...) is present in the target, and none is
                added where the English has none (on -> "no activada"); per-language lexicons in
                lexicon.py (word-level for zh and ja, native negating prefixes such as ru "не-"); and
                an English on or off state not drafted as only the other state's words
                (lexicon.STATE_WORDS, first-wave languages and nb, hu, ro, hr, tr, ru: "Lock: on" ->
                "Sperre: aus"); in tr a lock key that calls the lock open or closed ("Kilit: açık",
                lexicon.LOCK_STATE_TRAP)
  script        no letters from another script, except words copied from the English (any case,
                hyphen-aware); a Latin acronym or a loan in LATIN_LOANS (App, Wi-Fi) only warns
  untranslated  the target is not just the English (warn)

Limits. These checks are a first filter, not the guarantee. They catch the mechanical faults
(placeholders, numbers, plurals, escapes, markup, tokens, glyphs, widths) reliably, and the
common meaning faults by word lists: a missing glossary term (Unlock has its own term, so an unlock
key drafted with the lock word lacks it), the lock word inside its unlock word,
unlock as the lock word plus a separate word (lexicon.OPPOSITE_CONTEXT), the lock said to be
removed or turned off (lexicon.REMOVAL_CONTEXT: es, pt, fr, it, ro, de, nl, pl, sv, nb, fi, hu,
hr, tr, id, ru, uk, el, ja, zh), negation dropped or added where the language has a list, an
English "never" drafted as always (lexicon.NEVER_WORDS: pl), and an on or off state drafted as the
other in the first-wave languages and nb, hu, ro, hr, tr, ru (lexicon.STATE_WORDS). Known gaps: a
removal verb
or phrasing not in the lists, or far from the lock word; separable verbs except at the end of the
text (de, nl); Slavic stems under five letters; Arabic text without vowel marks (غير); zh, ja and
ko, where a word list stands in for word boundaries; languages with no negation or removal list,
where polarity only warns; English that itself says off or remove (the removal context is skipped);
on or off said with a word not in STATE_WORDS, in a later-wave language with no state list, or in a
target that has both states' words; a negation further from the state word than the word right
before it or one copula or particle between (lexicon.STATE_NEGATION_GAP: "no se encuentra activa");
an -s or -ing lock verb inside a longer term ("Lock duration: time before locking"), where only the
longer term is required; in loose matching, a different word that starts like an approved target; a glossary term without an approved target in the language
(nothing is required, Unlock included); a lock compound whose rest is a removal word not in
lexicon.COMPOUND_OPPOSITE, or with the lock word in second place ("Rollersperre" is not found);
and other inversions by antonym. For safety keys (lock, unlock, alarms, power, speed) the guarantee is
the council's later stages: every drafter must agree, a model that drafted nothing back-translates
the agreed text, the judge rules on that exact text, and a failure falls back to English. A false
alarm here fails closed (the key falls back to English). See l10n/README.md, "Limits of the
automated checks".
"""

import argparse
import collections
import os
import re
import sys
import unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fontmetrics  # noqa: E402
import gen_catalog  # noqa: E402
import lexicon  # noqa: E402
import pack as packlib  # noqa: E402
import translib  # noqa: E402

ERROR, WARN, INFO = "error", "warn", "info"

FIXED_TOKENS = [
    re.compile(r"`[^`\n]+`"),                                    # code spans
    re.compile(r"\{z\}/\{x\}/\{y\}"),
    re.compile(r"\ufffc"),
    re.compile(r"(?<![\w-])--?[a-z][a-z0-9-]*(?![\w-])"),        # CLI flags
    re.compile(r"https?://[^\s)\]]+"),
    re.compile(r"(?<![\w.-])[\w-]+(?:\.[\w-]+)*\.(?:json|py|apk|csv|gpx|txt|xml|zip|sh|ttf)(?![\w])"),
]

EN_NEGATION_WORDS = {"not", "no", "never", "none", "nothing", "nobody", "nowhere", "neither", "nor",
                     "without", "cannot", "can't", "don't", "doesn't", "didn't", "won't", "isn't", "aren't",
                     "wasn't", "weren't", "hasn't", "haven't", "couldn't", "shouldn't", "mustn't"}
# Words that start with "un" but carry no negation the checks care about. Lock and unlock are kept
# distinct by the glossary check (an unlock phrasing is not the lock term; see
# lexicon.OPPOSITE_CONTEXT) and the judge's safety confirmation, not by this word check.
EN_UN_EXCEPTIONS = re.compile(r"^(?:unlock\w*|under\w*|unit\w*|until|unique\w*|univers\w*|union\w*|unif\w*|"
                              r"uninstall\w*|undo\w*|unpair\w*|unmute\w*|unzip\w*)$")

# Glossary terms that are the lock itself (Lock, Locked, Lock bar, electronic lock; not unlock): only
# these get the opposite-term and lock-removed contexts (lexicon.OPPOSITE_CONTEXT, REMOVAL_CONTEXT).
LOCK_TERM = re.compile(r"(?<![a-z])lock", re.IGNORECASE)
NAMES_LOCK = re.compile(r"(?<![a-z])(?:un)?lock", re.IGNORECASE)
# English that itself turns something off, removes it or unlocks: then "Desactivar el bloqueo" or
# "Odblokuj zablokowaną hulajnogę" may be right, so the lock-removed context is not applied.
EN_REMOVAL = re.compile(r"(?<![a-z])(?:off|disabl\w*|remov\w*|releas\w*|lift\w*|clear\w*|cancel\w*|"
                        r"deactivat\w*|delet\w*|open\w*|unlock\w*|end|ends|ended|ending|turn\w*\s+off|switch\w*\s+off)"
                        r"(?![a-z])", re.IGNORECASE)

# English words whose translation often carries a negating prefix ("off" -> "desactivada").
EN_OFF_WORDS = {"off", "disable", "disabled", "disconnect", "disconnected", "inactive", "stop", "stopped",
                "cancel", "cancelled", "canceled", "lost", "missing", "unknown", "failed", "invalid", "incorrect",
                "deny", "denied", "remove", "removed", "delete", "deleted", "clear", "cleared", "offline", "empty"}
# The on/off state words: an "Alarm off" target with no negation at all is worth the judge's look.
EN_STATE_OFF = {"off", "disabled", "inactive", "deactivated"}
# English that states on or off ("Lock: on", "Alarm sound off", "Low battery alert enabled", "Turn on
# alerts"). A bare "on" counts only at the end of the text, before punctuation or after turn/switch/
# stay/keep, so the preposition ("on this model") does not.
EN_SAYS_ON = re.compile(r"(?<![a-z])(?:on(?=\s*(?:$|[.,;:!?)\]]))|(?:turn\w*|switch\w*|stay\w*|keep\w*|"
                         r"is|are|always|:)\s+on|enabl(?:e|ed|es)|activ(?:e|ated?|ates))(?![a-z])", re.IGNORECASE)
EN_SAYS_OFF = re.compile(r"(?<![a-z])(?:off|disabl(?:e|ed|es)|inactive|deactivat\w*|power\w*\s+down|shut\w*\s+down)"
                          r"(?![a-z])", re.IGNORECASE)
# Negation words that are also ordinary words (French "personne" is "person", Arabic "دون" is also
# "below", Urdu "بنا" is also "made"): not counted as added.
BLANK_LETTERS = "\u115f\u1160\u2800\u3164\uffa0"  # Hangul fillers and the blank Braille pattern
AMBIGUOUS_NEGATION = {"fr": {"personne"}, "ar": {"دون"}, "ur": {"بنا"}}

NEGATION = lexicon.NEGATION

# CLDR plural categories that select exactly one whole number (or none), per language: only these
# may drop the count placeholder ("Un patinete" for n=1). Everything else ("one" in fr, pt-BR, hi, bn,
# ru, uk, hr, which also covers 0, 21, 31 or 101; every "few" and "many"; "other") must keep it.
_EXACT_ONE = {"en", "es", "it", "de", "nl", "sv", "da", "nb", "nn", "no", "fi", "el", "hu", "tr", "ro", "cs", "sk",
              "pl", "bg", "ca", "et", "he", "ar", "ur", "pt-PT", "ja", "zh", "ko", "id", "vi", "th"}
_EXACT_OTHER = {"ar": {"zero", "two"}, "cy": {"zero", "two"}, "he": {"two"}, "cs": {"many"}, "sk": {"many"}}


def single_number_categories(tag):
    """Plural categories of a language that select a single whole number (or none)."""
    primary = tag.split("-")[0]
    out = set(_EXACT_OTHER.get(primary, ()))
    if tag in _EXACT_ONE or (primary in _EXACT_ONE and primary != "pt"):
        out.add("one")
    return out


SCRIPT_PREFIXES = (("LATIN", "Latn"), ("CYRILLIC", "Cyrl"), ("GREEK", "Grek"), ("CJK", "Hani"),
                   ("IDEOGRAPHIC", "Hani"), ("HIRAGANA", "Kana"), ("KATAKANA", "Kana"), ("DEVANAGARI", "Deva"),
                   ("BENGALI", "Beng"), ("ARABIC", "Arab"), ("HEBREW", "Hebr"), ("HANGUL", "Hang"), ("THAI", "Thai"))
# Latin-script names a non-Latin translation may use although the English does not (a warning only;
# compared without case, hyphen-aware). Any other Latin word there is leakage from another language
# ("Abbrechen", "Cancelar"): an error.
LATIN_LOANS = lexicon.LATIN_LOANS
WORD = re.compile(r"[^\W\d_]+(?:['’\u2010\u2011-][^\W\d_]+)*", re.UNICODE)


def finding(check, severity, message, quantity=None):
    return collections.OrderedDict([("check", check), ("severity", severity), ("quantity", quantity),
                                    ("message", message)])


def is_ok(findings):
    return not any(f["severity"] == ERROR for f in findings)


# ---------------------------------------------------------------------------
# Small helpers
# ---------------------------------------------------------------------------

def placeholder_specs(text):
    return [m.group(0) for m in gen_catalog.PLACEHOLDER.finditer(text) if m.group(5) != "%"]


def strip_placeholders(text):
    return gen_catalog.PLACEHOLDER.sub(" ", text)


# An English number with comma thousands groups ("1,024", "12,345.6"): one number, not 1 and 024.
ENGLISH_GROUPED = re.compile(r"(?<![0-9])(?<![0-9],)[0-9]{1,3}(?:,[0-9]{3})+(?![0-9])(?!,[0-9])")
# The same number in a translation, grouped by one separator throughout: . , space, U+00A0 or U+202F.
TARGET_GROUPED = re.compile(r"(?<!\d)(?<!\d[., \u00a0\u202f])\d{1,3}([., \u00a0\u202f])\d{3}(?:\1\d{3})*(?!\d)(?!\1\d)")


def ascii_digits(text):
    return "".join(str(unicodedata.digit(ch)) for ch in text if unicodedata.category(ch) == "Nd")


def compare_numbers(reference, text):
    """(lost, added): Counters of the English's numbers missing from text, and of text's not in it.

    A number the English writes with comma thousands groups counts as one number, and the target may
    write the same value with no separator or grouped by . , space, U+00A0 or U+202F (Spanish style
    wants "1024"). Only such values are read whole in the target; any other digits are compared run by
    run as before, so a decimal comma still matches an English decimal point.
    """
    grouped = set(m.group(0).replace(",", "") for m in ENGLISH_GROUPED.finditer(reference))
    source = collections.Counter(numbers(ENGLISH_GROUPED.sub(lambda m: m.group(0).replace(",", ""), reference)))
    if grouped:
        text = TARGET_GROUPED.sub(
            lambda m: ascii_digits(m.group(0)) if ascii_digits(m.group(0)) in grouped else m.group(0), text)
    target = collections.Counter(numbers(text))
    return source - target, target - source


def numbers(text):
    """Runs of decimal digits in any script, as ASCII strings."""
    out, run = [], []
    for ch in text:
        if unicodedata.category(ch) == "Nd":
            run.append(str(unicodedata.digit(ch)))
            continue
        if run:
            out.append("".join(run))
        run = []
    if run:
        out.append("".join(run))
    return out


def script_of(ch):
    if not unicodedata.category(ch).startswith("L"):
        return None
    name = unicodedata.name(ch, "")
    for prefix, code in SCRIPT_PREFIXES:
        if name.startswith(prefix):
            return code
    return "Other"


def english_negation(text):
    words = [w.lower() for w in WORD.findall(text.replace("’", "'"))]
    plain = [w for w in words if w in EN_NEGATION_WORDS or w.endswith("n't")]
    un = [w for w in words if w.startswith("un") and len(w) > 4 and not EN_UN_EXCEPTIONS.match(w)]
    return plain, un


def has_negation_list(primary):
    return primary in NEGATION or primary in lexicon.UNSPACED_NEGATION


def target_negation(primary, text, stems=()):
    """(negation words, words that may carry a negating prefix, native negated words) in a target.

    Native negated words ("неизвестно", "imposible", see lexicon.py) are a negation also in a safety
    key; the other prefixed words are only a guess ("información"). A word that is a negating
    prefix plus one of stems (the approved glossary targets, such as "bloquear") is the opposite
    term ("desbloquear"), not a negation of the sentence: it is left out of both.
    """
    text = lexicon.negation_text(primary, text)
    if primary in lexicon.UNSPACED_NEGATION:
        return lexicon.unspaced_negations(primary, text), [], []
    words, prefixes = NEGATION[primary]
    tokens = lexicon.tokens(text)
    found = [t for t in tokens if t in words or lexicon.suffix_negated(primary, t)]
    native = [t for t in tokens if t not in words and lexicon.native_negated(primary, t, stems)]
    prefixed = [t for t in tokens if t not in words and (t in native or any(
        t.startswith(p) and len(t) > len(p) + 3 and not any(t[len(p):].startswith(stem) for stem in stems)
        for p in prefixes))]
    return found, prefixed, native


def glossary_targets(entry, primary=None):
    """Every approved target form of the entry's glossary hits, in the language's lower case
    (lexicon.lower_for), whole words only."""
    out = set()
    for hit in entry.get("glossary") or []:
        for target in hit.get("target") or []:
            out.update(lexicon.lower_for(primary, w) for w in WORD.findall(target))
    return out


def protected_tokens(entry):
    """Tokens that must stay unchanged: volt:dnt tokens and the do-not-translate glossary terms."""
    tokens = list(entry.get("dnt") or [])
    for hit in entry.get("glossary") or []:
        if hit.get("dnt") and hit["term"] not in tokens:
            tokens.append(hit["term"])
    return tokens


def fixed_tokens(text):
    counts = collections.Counter()
    for pattern in FIXED_TOKENS:
        for m in pattern.finditer(text):
            counts[m.group(0)] += 1
    return counts


def is_blank(text):
    """Nothing a person could read: only spaces, invisible format characters ("\\u200b") and blank letters."""
    return all(ch.isspace() or unicodedata.category(ch)[0] in "ZC" or ch in BLANK_LETTERS for ch in text)


def _items(kind, value):
    items = translib.items_of(kind, value)
    if items is None:
        raise translib.L10nError("malformed text for a %s: %r" % (kind, value))
    return items


# ---------------------------------------------------------------------------
# Checks on one key
# ---------------------------------------------------------------------------

def check_key(entry, language, kind, items, raw_problems=(), font=None):
    """Findings for one target (kind, {quantity: text}) against its pack entry. Width is separate."""
    out = []
    source = _items(entry["kind"], entry["english"])
    safety = bool(entry.get("safety"))
    if kind != entry["kind"]:
        return [finding("kind", ERROR, "English is a %s; the draft has a %s" % (entry["kind"], kind))]
    for problem in raw_problems:
        out.append(finding("escapes", ERROR, problem))
    formatted = _formatted(entry)
    source_union = set()
    for text in source.values():
        source_union.update(placeholder_specs(text) if formatted else [])
    english_all = " ".join(source.values())
    english_other = source.get("other", english_all)

    if kind == "plural":
        required = language["plurals"]
        for quantity in required:
            if quantity not in items:
                out.append(finding("plurals", ERROR, "missing the %r form the language needs" % quantity))
        for quantity in items:
            if quantity not in required:
                out.append(finding("plurals", WARN, "%r is not a category of this language; it is ignored" % quantity,
                                   quantity))

    target_union = set()
    for quantity, text in items.items():
        q = quantity if kind == "plural" else None
        reference = source.get(quantity, english_other)
        if is_blank(text) and reference.strip():
            out.append(finding("empty", ERROR, "empty text", q))
            continue
        for ch in text:
            if (unicodedata.category(ch) == "Cc" and ch not in "\n\t") or ch in "\u2028\u2029":
                out.append(finding("escapes", ERROR, "control character U+%04X" % ord(ch), q))
                break
        # placeholders
        if formatted:
            _, problems = gen_catalog.analyze_format(text)
            for problem in problems:
                out.append(finding("placeholders", ERROR, problem, q))
            specs = set(placeholder_specs(text))
            target_union |= specs
            ref_specs = set(placeholder_specs(reference))
            if kind == "string" and specs != ref_specs:
                out.append(finding("placeholders", ERROR, "placeholders %s, English has %s" % (
                    _list(specs), _list(ref_specs)), q))
            elif kind == "plural":
                extra = specs - source_union
                if extra:
                    out.append(finding("placeholders", ERROR, "placeholders %s are not in the English" % _list(extra), q))
                needed = set(placeholder_specs(english_other))
                if quantity not in single_number_categories(language["tag"]) and not needed <= specs:
                    out.append(finding("placeholders", ERROR, "the %s form covers more than one number, so it must "
                                       "keep %s" % (quantity, _list(needed - specs)), q))
            if ("%%" in reference) != ("%%" in text):
                out.append(finding("placeholders", ERROR, "the English %s a %%%% percent sign; the target %s" % (
                    "has" if "%%" in reference else "has no", "does not" if "%%" in reference else "does"), q))
        elif text.count("%") != reference.count("%"):
            out.append(finding("placeholders", WARN, "a different number of % signs (formatted=\"false\")", q))
        # numbers
        lost, added = compare_numbers(strip_placeholders(reference) if formatted else reference,
                                      strip_placeholders(text) if formatted else text)
        if lost["1"] and kind == "plural" and formatted and quantity not in single_number_categories(language["tag"]):
            count = set(placeholder_specs(english_other))
            if count - set(placeholder_specs(reference)) and count <= set(placeholder_specs(text)):
                # The English spells this form's count as 1 ("1 widget"). Here the form covers more than one
                # number, so it must keep the count placeholder (above), and that placeholder stands for the 1.
                lost.subtract({"1": 1})
                lost = +lost
        if lost:
            out.append(finding("numbers", ERROR, "numbers %s from the English are missing" % _list(lost.elements()), q))
        if added:
            out.append(finding("numbers", WARN, "numbers %s are not in the English" % _list(added.elements()), q))
        # dnt and markup
        required = fixed_tokens(reference)
        for token in protected_tokens(entry):
            found = len(re.findall(r"(?<![^\W_])" + re.escape(token) + r"(?![^\W_])", reference))
            required[token] = max(required[token], found)
        for token, count in sorted(required.items()):
            if count and text.count(token) < count:
                check = "markup" if token.startswith("`") else "dnt"
                out.append(finding(check, ERROR, "%r must appear unchanged%s" % (
                    token, "" if count == 1 else " %d times" % count), q))
        for mark in ("**", "`"):
            if reference.count(mark) != text.count(mark):
                out.append(finding("markup", ERROR, "%d %s marks in the English, %d in the target" % (
                    reference.count(mark), mark, text.count(mark)), q))
        # whitespace
        lead = lambda s: re.match(r"^\s*", s).group(0)  # noqa: E731
        trail = lambda s: re.search(r"\s*$", s).group(0)  # noqa: E731
        if lead(text) != lead(reference) or trail(text) != trail(reference):
            out.append(finding("whitespace", ERROR, "leading or trailing whitespace differs from the English", q))
        if "  " in text and "  " not in reference:
            out.append(finding("whitespace", WARN, "repeated spaces the English does not have", q))
        if text.count("\n") != reference.count("\n"):
            out.append(finding("whitespace", WARN, "%d line breaks in the English, %d in the target" % (
                reference.count("\n"), text.count("\n")), q))
        # glyphs
        for problem in fallback_symbol_problems(text):
            out.append(finding("glyphs", ERROR, problem, q))
        for ch in sorted(FALLBACK_ALLOWED):
            if text.count(ch) != reference.count(ch):
                out.append(finding("glyphs", ERROR, "%d of %s in the English, %d in the target; keep each mark as the "
                                   "English has it" % (reference.count(ch), _chars([ch]), text.count(ch)), q))
        if language.get("font") == "saira" and font is not None:
            missing = [ch for ch in font.missing(text) if ch != "\ufffc" and ch not in FALLBACK_ALLOWED]
            spaces = [ch for ch in missing if unicodedata.category(ch) in ("Zs", "Cf")]
            hard = [ch for ch in missing if ch not in spaces]
            if hard:
                out.append(finding("glyphs", ERROR, "Saira has no glyph for %s" % _chars(hard), q))
            if spaces:
                out.append(finding("glyphs", WARN, "Saira has no glyph for %s; Android falls back to another font" %
                                   _chars(spaces), q))
        # polarity
        out.extend(_polarity(language, reference, text, safety, q, entry))
        # script
        out.extend(_script(language, english_all, entry, text, q))
        # glossary
        out.extend(_glossary(language, entry, reference, text, safety, q))
        # untranslated
        if translib.normalize(text) == translib.normalize(reference) and len(WORD.findall(reference)) >= 2:
            protected = protected_tokens(entry)
            words = [w for w in WORD.findall(reference) if not any(w in p for p in protected)]
            if words:
                out.append(finding("untranslated", WARN, "the target is the English text", q))
    if kind == "plural" and formatted and not source_union <= target_union:
        out.append(finding("placeholders", ERROR, "placeholders %s are in no form" % _list(source_union - target_union)))
    return out


def _formatted(entry):
    return entry.get("formatted", True)


def _list(values):
    values = sorted(values)
    return ", ".join(values) if values else "none"


# tests/l10n/GlyphCoverageTest.FALLBACK_SYMBOLS, the reviewed list of symbols no Saira weight has that
# Android's fallback font draws as plain text (l10n/README, "Glyph coverage"); test_check.py keeps the two
# lists equal. True marks an emoji-capable symbol, which U+FE0E must follow directly.
FALLBACK_SYMBOLS = {"\u2713": False, "\u26a0": True, "\u2606": False, "\u2605": False}
TEXT_STYLE = "\ufe0e"
FALLBACK_ALLOWED = frozenset(FALLBACK_SYMBOLS) | {TEXT_STYLE}


def fallback_symbol_problems(text):
    """The rules GlyphCoverageTest holds the catalog to, in every language, for one draft: an emoji-capable
    fallback symbol needs U+FE0E directly after it, and U+FE0E goes nowhere else."""
    problems = []
    for i, ch in enumerate(text):
        following = text[i + 1] if i + 1 < len(text) else ""
        if FALLBACK_SYMBOLS.get(ch) and following != TEXT_STYLE:
            problems.append("%s needs U+FE0E directly after it, or a phone may draw it as a colour emoji" % _chars([ch]))
        elif ch == TEXT_STYLE and not (i > 0 and FALLBACK_SYMBOLS.get(text[i - 1])):
            problems.append("U+FE0E belongs only directly after an emoji-capable symbol (%s)" % _chars(
                [c for c, emoji in FALLBACK_SYMBOLS.items() if emoji]))
    return problems


def _chars(chars):
    return ", ".join("%r (U+%04X)" % (ch, ord(ch)) for ch in chars)


def _polarity(language, reference, text, safety, q, entry=None):
    """Negation kept or not added (_negation), and an on or off state not inverted (_state)."""
    return (_negation(language, reference, text, safety, q, entry) + _state(language, reference, text, safety, q) +
            _lock_state_trap(language, text, q, entry))


def _lock_state_trap(language, text, q, entry):
    """The lock word next to a bare open or closed in a lock key (lexicon.LOCK_STATE_TRAP): "Kilit: açık"
    reads as unlocked whatever the English says, so it is an error also when the English says "on"."""
    primary = language["tag"].split("-")[0]
    trap = lexicon.LOCK_STATE_TRAP.get(primary)
    if not trap or "lock" not in ((entry or {}).get("safety") or []):
        return []
    m = trap.search(lexicon.normalize(text, primary))
    if not m:
        return []
    return [finding("polarity", ERROR, "%r says the lock is open or closed, not that a setting is on or off" %
                    m.group(0), q)]


def _english_state(reference):
    """(on match, off match) of an English that says just one of them with no negation, or None."""
    plain, un = english_negation(reference)
    english_on, english_off = EN_SAYS_ON.search(reference), EN_SAYS_OFF.search(reference)
    if plain or un or bool(english_on) == bool(english_off):
        return None
    return english_on, english_off


def _state(language, reference, text, safety, q):
    """An English on (or off) state drafted as only the other state's words (lexicon.STATE_WORDS):
    "Lock: on" as "Sperre: aus", "Alarm sound off" as "Sonido de alarma activado". An error in a
    safety key. English with a negation is left to _negation ("Alarm not active")."""
    found = lexicon.state_words(language["tag"].split("-")[0], text)
    if found is None:
        return []
    stated = _english_state(reference)
    if stated is None:
        return []
    english_on, english_off = stated
    on, off = found
    english, wrong, right = (english_on, off, on) if english_on else (english_off, on, off)
    if wrong and not right:
        return [finding("polarity", ERROR if safety else WARN, "the English says %r; the target says %s, the "
                        "opposite state" % (english.group(0), ", ".join(repr(w) for w in wrong[:3])), q)]
    return []


def _negation(language, reference, text, safety, q, entry=None):
    """Negation kept, and none added, in either direction.

    A negating prefix ("des-", "in-") is only a guess: "información" is not a negation and
    "desbloquear" is the opposite of "bloquear". So in a safety key an English un-word must become a
    negation word, the language's own negated form ("неизвестно", "nieznany", "imposible"; see
    lexicon.py), or a prefixed word the glossary approves; any other prefixed word is an error there.
    """
    entry = entry or {}
    plain, un = english_negation(reference)
    primary = language["tag"].split("-")[0]
    severity = ERROR if safety else WARN
    if not has_negation_list(primary):
        if plain or un:
            return [finding("polarity", WARN, "no negation word list for %s; the judge checks polarity" % primary, q)]
        return []
    stems = glossary_targets(entry, primary)
    found, prefixed, native = target_negation(primary, text, stems)
    if plain or un:
        # cs and sk join "ne" to the verb ("nepotvrdila"): there a native negated word keeps the
        # English "not" too. A ne- word the English lacks still only warns: "neplatný" (invalid) and
        # "nesprávné" (wrong) are ordinary adjectives, as in the other languages.
        joined = primary in lexicon.JOINED_VERB_NEGATION and native
        if plain and not found and not joined:
            return [finding("polarity", severity, "the English says %s; the target has no negation word" %
                            ", ".join(repr(w) for w in plain[:3]), q)]
        inverted = _never_inverted(primary, reference, plain, text)
        if inverted:
            return [finding("polarity", severity, inverted, q)]
        if un and not plain and not found:
            approved = [w for w in prefixed if w in stems] + native
            if safety and not approved:
                return [finding("polarity", ERROR, "the English says %s; in a safety key the target needs a "
                                "negation word, a native negated form or the glossary's term, not just a "
                                "prefix%s" % (", ".join(repr(w) for w in un[:3]),
                                              " (%s)" % ", ".join(prefixed[:3]) if prefixed else ""), q)]
            if not prefixed:
                return [finding("polarity", severity, "the English says %s; the target has no negation" %
                                ", ".join(repr(w) for w in un[:3]), q)]
        return []
    # The English has no negation: one in the target may invert the meaning (on -> "no activada").
    opposite = english_opposite_words(reference)
    found = [w for w in found if w not in AMBIGUOUS_NEGATION.get(primary, ())]
    if found and q == "zero":
        # The zero form naturally says "no scooters" ("لا توجد سكوترات") where the English has a count.
        return [finding("polarity", WARN, "the zero form says %s; the judge checks polarity" %
                        ", ".join(repr(w) for w in found[:3]), q)]
    if found and not opposite:
        return [finding("polarity", severity, "the target says %s; the English has no negation" %
                        ", ".join(repr(w) for w in found[:3]), q)]
    if found:
        return [finding("polarity", WARN, "the target says %s; the English says %s; the judge checks polarity" % (
            ", ".join(repr(w) for w in found[:3]), ", ".join(repr(w) for w in sorted(opposite)[:3])), q)]
    if prefixed and not opposite:
        return [finding("polarity", WARN, "%s may carry a negating prefix the English does not have; the judge "
                        "checks polarity" % ", ".join(repr(w) for w in prefixed[:3]), q)]
    state = lexicon.state_words(primary, text)
    if not prefixed and opposite & EN_STATE_OFF and not (state and state[1]):
        # A target with the language's own off word ("apagado", "ausschalten") already says off.
        return [finding("polarity", WARN, "the English says %s; check the target is not the opposite" % ", ".join(
            repr(w) for w in sorted(opposite & EN_STATE_OFF)), q)]
    return []


def _never_inverted(primary, reference, plain, text):
    """Why a target does not keep an English "never" (lexicon.NEVER_WORDS), or None."""
    if "never" not in plain or primary not in lexicon.NEVER_WORDS:
        return None
    needed, opposite = lexicon.NEVER_WORDS[primary]
    tokens = set(lexicon.tokens(lexicon.negation_text(primary, text)))
    always = [w for w in tokens & opposite if not re.search(r"(?<![a-z])always(?![a-z])", reference, re.IGNORECASE)]
    if always:
        return "the English says 'never'; the target says %s" % ", ".join(repr(w) for w in sorted(always))
    if not tokens & needed:
        return "the English says 'never'; the target has neither %s" % " nor ".join(repr(w) for w in sorted(needed))
    return None


def english_opposite_words(text):
    """English words that may translate to a prefixed word: un-, dis-, de-, in-, non- words and the off words."""
    words = set(w.lower() for w in WORD.findall(text.replace("’", "'")))
    return set(w for w in words if w in EN_OFF_WORDS or
               (len(w) > 4 and re.match(r"^(?:un|dis|de|in|im|ir|il|non|anti)", w)))


def _acronym(word):
    return 2 <= len(word) <= 5 and word.isupper()


def _script(language, english, entry, text, q):
    expected = language.get("script") or "Latn"
    native = lexicon.SCRIPT_SETS.get(expected, {expected})
    allowed_words = set(lexicon.normalize(w) for w in WORD.findall(english)) | set(
        lexicon.normalize(w) for w in entry.get("dnt") or [])
    bad, borrowed = [], []
    for word in WORD.findall(text):
        scripts = set(s for s in (script_of(ch) for ch in word) if s)
        foreign = scripts - native
        if not foreign:
            continue
        folded = lexicon.normalize(word)
        parts = [p for p in re.split("[%s]" % lexicon.HYPHENS, folded) if p]
        if all(p in allowed_words or any(p in token for token in allowed_words) for p in parts):
            continue
        if "Latn" not in native and foreign == {"Latn"} and (lexicon.latin_loan(word, allowed_words) or
                                                             _acronym(word)):
            borrowed.append(word)
        else:
            bad.append(word)
    out = []
    if bad:
        out.append(finding("script", ERROR, "letters from another script: %s" % ", ".join(sorted(set(bad))[:5]), q))
    if borrowed:
        out.append(finding("script", WARN, "Latin technical words not in the English: %s" % ", ".join(sorted(set(borrowed))[:5]), q))
    return out


# English phrases that use a glossary term's word in another sense: "Try" is the experimental
# action ("Try this control"), while "try again" is an ordinary retry ("Tap Read PIN status to try
# again"). Where every use of the word is such a phrase, the term's target is not required.
NOT_THE_TERM = {"try": re.compile(r"(?<![a-z])(?:try|tries|tried|trying)\s+again(?![a-z])", re.IGNORECASE)}


def _terms_in(entry, reference):
    """The entry's glossary hits this English needs translated, in order."""
    hits = entry.get("glossary") or []
    names = [h["term"] for h in hits]
    for hit in hits:
        other_sense = NOT_THE_TERM.get(hit["term"].lower())
        text = other_sense.sub(" ", reference) if other_sense else reference
        if hit.get("dnt") or not translib.term_pattern(hit["term"], hit.get("safety") == "lock").search(text):
            continue
        if translib.nested_in_longer(hit["term"], names, reference):
            continue  # "Lock" in "Lock duration": the longer term's translation is required
        yield hit


def safety_terms_without_target(entry):
    """Safety terms of a safety key with no approved target, for which _glossary can check nothing.

    The council has a judge behind that gap. A caller with no judge must not trust such a text.
    """
    if not entry.get("safety"):
        return []
    found = []
    for reference in _items(entry["kind"], entry["english"]).values():
        for hit in _terms_in(entry, reference):
            if hit.get("safety") and not hit.get("target") and hit["term"] not in found:
                found.append(hit["term"])
    return found


def word_lists_missing(primary):
    """The word lists a safety key's meaning checks need that this language does not have."""
    missing = []
    if not has_negation_list(primary):
        missing.append("NEGATION")
    if primary not in lexicon.REMOVAL_CONTEXT and primary not in lexicon.REMOVAL_AROUND:
        missing.append("REMOVAL_CONTEXT")
    if primary not in lexicon.STATE_WORDS:
        missing.append("STATE_WORDS")
    return missing


def safety_checks_without_list(entry, language):
    """Word lists this safety key would be checked against, which the language lacks.

    Without NEGATION any added or dropped negation only warns ("No bloquejat" for "Locked"). Without
    REMOVAL_CONTEXT a glossary target is found in "Odstranit zámek" ("remove lock") for "Lock status".
    Without STATE_WORDS an English on or off state is not compared at all. The council has a judge
    behind these gaps; a caller with no judge must not trust such a text.
    """
    if not entry.get("safety"):
        return []
    missing = word_lists_missing(language["tag"].split("-")[0])
    references = list(_items(entry["kind"], entry["english"]).values())
    needed = []
    if "NEGATION" in missing:
        needed.append("NEGATION")
    if "REMOVAL_CONTEXT" in missing and any(
            hit.get("safety") and LOCK_TERM.search(hit["term"]) and not EN_REMOVAL.search(reference)
            for reference in references for hit in _terms_in(entry, reference)):
        needed.append("REMOVAL_CONTEXT")
    if "STATE_WORDS" in missing and any(_english_state(reference) for reference in references):
        needed.append("STATE_WORDS")
    return needed


def meaning_unverified(entry):
    """Why check_key cannot catch an antonym in this key even when it passes, or None.

    A safety term with an approved target is the only check on what a text says. So a safety key whose
    English has none ("Full" for a charge alarm) rests on the drafter alone, and so does a key that
    names a lock word with no volt:safety, where a missing target only warns.
    """
    references = list(_items(entry["kind"], entry["english"]).values())
    if entry.get("safety"):
        if any(hit.get("safety") and hit.get("target") for r in references for hit in _terms_in(entry, r)):
            return None
        return "safety key with no glossary safety term that has a target: nothing checks its meaning"
    if any(NAMES_LOCK.search(r) for r in references):
        return "names a lock word but has no volt:safety: a wrong term only warns"
    return None


def _glossary(language, entry, reference, text, safety, q):
    """Approved targets used: whole words in spaced scripts, anywhere (but not as part of the
    opposite term) in scripts without word spaces; see lexicon.term_found."""
    out = []
    primary = language["tag"].split("-")[0]
    script = language.get("script") or "Latn"
    for hit in _terms_in(entry, reference):
        targets = hit.get("target")
        if not targets:
            continue
        lock = bool(LOCK_TERM.search(hit["term"]))
        removal = lock and not EN_REMOVAL.search(reference)
        # Only an -s or -ing form in the English ("before locking", "Unlocking…"): any form of the
        # target counts ("bloqueando", "Blokowanie"), not just the listed ones.
        loose = not translib.term_pattern(hit["term"]).search(reference)
        if not any(lexicon.term_found(primary, script, t, text, lock, removal, loose) for t in targets):
            severity = ERROR if safety and hit.get("safety") else WARN
            out.append(finding("glossary", severity, "%r should be %s" % (
                hit["term"], " or ".join(repr(t) for t in targets)), q))
    return out


# ---------------------------------------------------------------------------
# A set of targets: ladders and widths
# ---------------------------------------------------------------------------

def check_targets(pack, targets, font=None, measurer=None, ladders=True):
    """{key: findings} for {key: (kind, {quantity: text}, raw_problems)} against a pack.

    Adds the ladder check (every rung of a group drafted together) and the width check.
    """
    entries = dict((e["key"], e) for e in pack["entries"])
    language = pack["language"]
    results = collections.OrderedDict()
    for key, (kind, items, raw_problems) in targets.items():
        entry = entries.get(key)
        if entry is None:
            results[key] = [finding("kind", ERROR, "%s is not in the pack" % key)]
            continue
        results[key] = check_key(entry, language, kind, items, raw_problems, font)
    if ladders:
        for key in results:
            entry = entries.get(key)
            group = entry and entry.get("ladder_group")
            if group:
                missing = [k for k in group if k not in targets]
                if missing:
                    results[key].append(finding("ladder", ERROR, "the ladder is incomplete without %s" % ", ".join(missing)))
    # widths
    requests, owners = [], []
    for key, (kind, items, _) in targets.items():
        entry = entries.get(key)
        if not entry or not entry.get("budget_dp") or kind != entry["kind"]:
            continue
        if language.get("font") != "saira":
            results[key].append(finding("width", INFO, "%s uses the system font; fit is checked on a device" %
                                        language["tag"]))
            continue
        for quantity, text in items.items():
            requests.append((entry["budget_sp"], packlib.sample_text(text, _formatted(entry)),
                             entry.get("budget_lines") or 1))
            owners.append((key, quantity if kind == "plural" else None))
    if requests and measurer is not None:
        # With budget_lines above 1 a width is the narrowest that holds the text in that many lines of
        # whole words (fontmetrics.measure_lines), and english_width_dp is the English measured the same way.
        widths = fontmetrics.measure_lines(measurer, requests)
        for (key, quantity), width in zip(owners, widths):
            entry = entries[key]
            budget = entry["budget_dp"]
            lines = entry.get("budget_lines") or 1
            if width > budget + 0.01:
                english_fits = entry["english_width_dp"] <= budget + 0.01
                shown = "no width" if width >= fontmetrics.NO_FIT else "%.1fdp" % width
                where = "" if lines == 1 else " in %d lines of whole words" % lines
                out = finding("width", ERROR if english_fits else WARN,
                              "%s at %gsp exceeds the %gdp budget%s (English %.1fdp; %s)" % (
                                  shown, entry["budget_sp"], budget, where, entry["english_width_dp"],
                                  measurer.method),
                              quantity)
                results[key].append(out)
    return results


def stale_source(src, sha):
    """Why a draft entry recorded as translating src is not a translation of the English sha, or None."""
    if not src:
        return "no volt:src: the English this entry translated is unknown"
    if src != sha:
        return "drafted from other English (volt:src %s, the English is now %s)" % (src[:12], (sha or "?")[:12])
    return None


def draft_targets(draft, pack=None):
    """{key: (kind, items, problems)} of a draft; with pack, only the entries of the current English."""
    sha = dict((e["key"], e["source_sha1"]) for e in pack["entries"]) if pack else None
    return collections.OrderedDict((k, (e.kind, e.items, e.problems)) for k, e in draft.entries.items()
                                   if sha is None or k not in sha or stale_source(e.src, sha[k]) is None)


def check_draft(pack, draft, font, measurer):
    """Rows for checks.jsonl, one per key of the draft, plus the keys the draft left out."""
    results = check_targets(pack, draft_targets(draft), font, measurer)
    sha = dict((e["key"], e["source_sha1"]) for e in pack["entries"])
    rows = []
    for key, findings in results.items():
        stale = stale_source(draft.entries[key].src, sha.get(key)) if key in sha else None
        if stale:
            findings = findings + [finding("source", ERROR, stale)]
        rows.append(collections.OrderedDict([
            ("draft", draft.name), ("provider", draft.provider), ("model", draft.model),
            ("attempt", draft.entries[key].attempt), ("key", key), ("source_sha1", sha.get(key)),
            ("draft_src", draft.entries[key].src),
            ("ok", is_ok(findings)), ("findings", findings), ("width_method", measurer.method if measurer else None)]))
    return rows


def run(paths, tag, area, only=None, use_java=True, out=None):
    out = out or sys.stdout
    pack = packlib.load_pack(paths, tag, area)
    drafts = translib.list_drafts(paths, tag, area)
    if only:
        drafts = [d for d in drafts if d.name in only]
    if not drafts:
        raise translib.L10nError("no drafts in %s" % paths.rel(paths.drafts(tag, area)))
    font = fontmetrics.Font(paths.font())
    measurer = fontmetrics.Measurer(paths.font(), use_java=use_java)
    rows = []
    for draft in drafts:
        draft_rows = check_draft(pack, draft, font, measurer)
        rows.extend(draft_rows)
        failed = [r for r in draft_rows if not r["ok"]]
        missing = [e["key"] for e in pack["entries"] if e["key"] not in draft.entries and e["status"] != "current"]
        out.write("%s (%s): %d keys, %d failed, %d missing\n" % (draft.name, draft.model, len(draft_rows),
                                                                 len(failed), len(missing)))
        for row in failed:
            for f in row["findings"]:
                if f["severity"] == ERROR:
                    out.write("  %s%s: %s: %s\n" % (row["key"], "[%s]" % f["quantity"] if f["quantity"] else "",
                                                     f["check"], f["message"]))
    path = paths.work(tag, area, "checks.jsonl")
    if only:
        keep = [r for r in translib.read_jsonl(path) if r.get("draft") not in only]
        rows = keep + rows
    translib.write_jsonl(path, rows)
    return rows


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root")
    parser.add_argument("--tag", required=True)
    parser.add_argument("--area", required=True)
    parser.add_argument("--draft", action="append", help="check only this draft (repeatable)")
    parser.add_argument("--no-java", action="store_true")
    args = parser.parse_args(argv)
    paths = translib.Paths(args.root)
    try:
        translib.check_tag_area(args.tag, args.area)
        rows = run(paths, args.tag, args.area, args.draft, not args.no_java)
    except (translib.L10nError, fontmetrics.FontError) as error:
        print("check.py: %s" % error, file=sys.stderr)
        return 2
    return 0 if all(r["ok"] for r in rows) else 1


if __name__ == "__main__":
    sys.exit(main())
