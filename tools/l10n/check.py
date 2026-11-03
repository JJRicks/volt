#!/usr/bin/env python3
"""Automated checks on translation drafts (plan 7.2 step 3; CatalogCheckTest mirrors the merged result).

  check.py --tag es --area common                 every draft in l10n/work/es/common/drafts/
  check.py --tag es --area common --draft openai  one draft

Writes l10n/work/<tag>/<area>/checks.jsonl, one line per draft and key:
  {"draft", "provider", "model", "attempt", "key", "source_sha1", "ok", "findings", "width_method"}
where each finding is {"check", "severity", "quantity", "message"}. Severity "error" fails the draft
for that key; "warn" goes to the judge; "info" is a note. Exit status 1 when any key failed.

Checks, per key (a plural is checked per quantity):
  kind          the draft has a string where English has a string, plurals where it has plurals
  empty         no empty text where English has text
  placeholders  the same %n$ placeholders with the same conversions, and %% where English has it;
                only positional forms and %%; a plural category that covers more than one number
                (every one but n=1-only "one" and the like) keeps the count
  numbers       every number written in the English appears in the target (any script's digits)
  plurals       every CLDR category the language needs is present
  escapes       the draft's spelling is one aapt stores exactly (apostrophes, quotes, escapes), and
                there are no control characters
  dnt           volt:dnt tokens, do-not-translate glossary terms and fixed tokens (file names, CLI
                flags, URLs, {z}/{x}/{y}, the U+FFFC placeholder) are kept exactly
  markup        the same number of ** and backtick marks, and every `code span` unchanged
  glossary      approved glossary terms are used, as whole words (warn; error for a safety term in a
                safety key)
  whitespace    no leading or trailing whitespace drift; no new repeated spaces or line breaks
  glyphs        Saira has every character (Latin-script languages)
  ladder        every rung English has (full, _short, _compact) is drafted together
  width         each key with volt:max fits its budget, measured with Saira (see fontmetrics.py)
  polarity      negation in the English (not, no, never, un-...) is present in the target, and none is
                added where the English has none (on -> "no activada")
  script        no letters from another script, except words copied from the English (a Latin
                acronym or a name in LATIN_LOANS only warns)
  untranslated  the target is not just the English (warn)
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
# distinct by the judge's safety confirmation, not by this word check.
EN_UN_EXCEPTIONS = re.compile(r"^(?:unlock\w*|under\w*|unit\w*|until|unique\w*|univers\w*|union\w*|unif\w*|"
                              r"uninstall\w*|undo\w*|unpair\w*|unmute\w*|unzip\w*)$")

# English words whose translation often carries a negating prefix ("off" -> "desactivada").
EN_OFF_WORDS = {"off", "disable", "disabled", "disconnect", "disconnected", "inactive", "stop", "stopped",
                "cancel", "cancelled", "canceled", "lost", "missing", "unknown", "failed", "invalid", "incorrect",
                "deny", "denied", "remove", "removed", "delete", "deleted", "clear", "cleared", "offline", "empty"}
# The on/off state words: an "Alarm off" target with no negation at all is worth the judge's look.
EN_STATE_OFF = {"off", "disabled", "inactive", "deactivated"}
# Negation words that are also ordinary words (French "personne" is "person"): not counted as added.
AMBIGUOUS_NEGATION = {"fr": {"personne"}}

# Negation words (whole words) and negating prefixes per language (primary subtag). zh uses
# characters, matched anywhere.
NEGATION = {
    "es": ({"no", "nunca", "jamás", "ni", "ningún", "ninguno", "ninguna", "nada", "nadie", "sin", "tampoco"},
           ("des", "in", "im", "ir")),
    "pt": ({"não", "nunca", "jamais", "nem", "nenhum", "nenhuma", "nada", "ninguém", "sem"}, ("des", "in", "im")),
    "fr": ({"ne", "n", "pas", "jamais", "aucun", "aucune", "rien", "personne", "sans", "non", "ni"},
           ("in", "im", "non", "dé", "dés")),
    "it": ({"non", "mai", "nessun", "nessuno", "nessuna", "niente", "nulla", "senza", "né"}, ("in", "im", "non", "dis")),
    "de": ({"nicht", "kein", "keine", "keinen", "keinem", "keiner", "keines", "nie", "niemals", "ohne", "nichts",
            "weder"}, ("un", "nicht")),
    "nl": ({"niet", "geen", "nooit", "zonder", "niets", "noch"}, ("on",)),
    "sv": ({"inte", "ej", "ingen", "inget", "inga", "aldrig", "utan", "icke"}, ("o",)),
    "da": ({"ikke", "ingen", "intet", "aldrig", "uden"}, ("u",)),
    "nb": ({"ikke", "ingen", "intet", "aldri", "uten"}, ("u",)),
    "pl": ({"nie", "nigdy", "żaden", "żadna", "żadne", "żadnych", "bez", "ani", "nic"}, ("nie", "bez")),
    "id": ({"tidak", "tak", "bukan", "jangan", "belum", "tanpa"}, ()),
    "fi": ({"ei", "en", "et", "emme", "ette", "eivät", "älä", "ilman", "koskaan"}, ()),
    "cs": ({"ne", "nikdy", "žádný", "žádná", "žádné", "bez", "nic"}, ("ne", "bez")),
    "sk": ({"nie", "nikdy", "žiadny", "žiadna", "žiadne", "bez", "nič"}, ("ne", "bez")),
    "ro": ({"nu", "niciodată", "fără", "niciun", "nicio", "nimic"}, ("ne",)),
    "hu": ({"nem", "ne", "soha", "nincs", "sincs", "nélkül", "semmi"}, ()),
    "hr": ({"ne", "nije", "nikad", "nikada", "bez", "nema", "ništa"}, ("ne", "bez")),
    "tr": ({"değil", "yok", "asla", "hiç", "olmadan", "hayır"}, ()),
    "ru": ({"не", "нет", "никогда", "без", "ни", "ничего"}, ("не", "без")),
    "uk": ({"не", "ні", "немає", "ніколи", "без", "нічого"}, ("не", "без")),
    "el": ({"δεν", "μη", "μην", "ποτέ", "χωρίς", "όχι", "κανένα"}, ("μη", "α")),
    "zh": ({"不", "没", "沒", "无", "無", "未", "非", "别", "勿", "否"}, ()),
    "hi": ({"नहीं", "न", "मत", "कभी", "बिना"}, ("अ",)),
    "bn": ({"না", "নয়", "নেই", "কখনো", "ছাড়া"}, ("অ",)),
}

# CLDR plural categories that select exactly one whole number (or none), per language: only these
# may drop the count placeholder ("Un patinete" for n=1). Everything else ("one" in fr, pt-BR, hi, bn,
# ru, uk, hr, which also covers 0, 21, 31 or 101; every "few" and "many"; "other") must keep it.
_EXACT_ONE = {"en", "es", "it", "de", "nl", "sv", "da", "nb", "nn", "no", "fi", "el", "hu", "tr", "ro", "cs", "sk",
              "pl", "bg", "ca", "et", "he", "ar", "pt-PT", "ja", "zh", "ko", "id", "vi", "th"}
_EXACT_OTHER = {"ar": {"zero", "two"}, "cy": {"zero", "two"}, "he": {"two"}, "cs": {"many"}, "sk": {"many"}}


def single_number_categories(tag):
    """Plural categories of a language that select a single whole number (or none)."""
    primary = tag.split("-")[0]
    out = set(_EXACT_OTHER.get(primary, ()))
    if tag in _EXACT_ONE or (primary in _EXACT_ONE and primary != "pt"):
        out.add("one")
    return out


SCRIPT_PREFIXES = (("LATIN", "Latn"), ("CYRILLIC", "Cyrl"), ("GREEK", "Grek"), ("CJK", "Hans"),
                   ("HIRAGANA", "Jpan"), ("KATAKANA", "Jpan"), ("DEVANAGARI", "Deva"), ("BENGALI", "Beng"),
                   ("ARABIC", "Arab"), ("HEBREW", "Hebr"), ("HANGUL", "Hang"), ("THAI", "Thai"))
# Latin-script names a non-Latin translation may use although the English does not (a warning only).
# Any other Latin word there is leakage from another language ("Abbrechen", "Cancelar"): an error.
LATIN_LOANS = {"Android", "Bluetooth", "Google", "Wi", "Fi", "Volt", "NAVEE", "Play", "Pixel", "Shizuku", "iPhone"}
WORD = re.compile(r"[^\W\d_]+(?:['’-][^\W\d_]+)*", re.UNICODE)


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


def target_negation(primary, text, stems=()):
    """(negation words, words that may carry a negating prefix) in a target.

    A word that is a negating prefix plus one of stems (the approved glossary targets, such as
    "bloquear") is the opposite term ("desbloquear"), not a negation of the sentence: it is left out.
    """
    words, prefixes = NEGATION[primary]
    lower = text.lower()
    if primary == "zh":
        return [w for w in words if w in lower], []
    tokens = re.findall(r"[^\W\d_]+", lower, re.UNICODE)
    found = [t for t in tokens if t in words]
    prefixed = [t for t in tokens if any(t.startswith(p) and len(t) > len(p) + 3 and
                                         not any(t[len(p):].startswith(stem) for stem in stems)
                                         for p in prefixes)]
    return found, prefixed


def glossary_targets(entry):
    """Every approved target form of the entry's glossary hits, lower case, whole words only."""
    out = set()
    for hit in entry.get("glossary") or []:
        for target in hit.get("target") or []:
            out.update(w.lower() for w in WORD.findall(target))
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
        if not text.strip() and reference.strip():
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
        source_numbers = collections.Counter(numbers(strip_placeholders(reference) if formatted else reference))
        target_numbers = collections.Counter(numbers(strip_placeholders(text) if formatted else text))
        lost = source_numbers - target_numbers
        if lost:
            out.append(finding("numbers", ERROR, "numbers %s from the English are missing" % _list(lost.elements()), q))
        added = target_numbers - source_numbers
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
        if language.get("font") == "saira" and font is not None:
            missing = [ch for ch in font.missing(text) if ch != "\ufffc"]
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
        out.extend(_glossary(entry, reference, text, safety, q))
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


def _chars(chars):
    return ", ".join("%r (U+%04X)" % (ch, ord(ch)) for ch in chars)


def _polarity(language, reference, text, safety, q, entry=None):
    """Negation kept, and none added, in either direction.

    A negating prefix ("des-", "in-") is only a guess: "información" is not a negation and
    "desbloquear" is the opposite of "bloquear". So in a safety key an English un-word must become a
    negation word, or a prefixed word the glossary approves; a prefix alone is an error there.
    """
    entry = entry or {}
    plain, un = english_negation(reference)
    primary = language["tag"].split("-")[0]
    severity = ERROR if safety else WARN
    if primary not in NEGATION:
        if plain or un:
            return [finding("polarity", WARN, "no negation word list for %s; the judge checks polarity" % primary, q)]
        return []
    stems = glossary_targets(entry)
    found, prefixed = target_negation(primary, text, stems)
    if plain or un:
        if plain and not found:
            return [finding("polarity", severity, "the English says %s; the target has no negation word" %
                            ", ".join(repr(w) for w in plain[:3]), q)]
        if un and not plain and not found:
            approved = [w for w in prefixed if w in stems]
            if safety and not approved:
                return [finding("polarity", ERROR, "the English says %s; in a safety key the target needs a "
                                "negation word or the glossary's term, not just a prefix%s" % (
                                    ", ".join(repr(w) for w in un[:3]),
                                    " (%s)" % ", ".join(prefixed[:3]) if prefixed else ""), q)]
            if not prefixed:
                return [finding("polarity", severity, "the English says %s; the target has no negation" %
                                ", ".join(repr(w) for w in un[:3]), q)]
        return []
    # The English has no negation: one in the target may invert the meaning (on -> "no activada").
    opposite = english_opposite_words(reference)
    found = [w for w in found if w not in AMBIGUOUS_NEGATION.get(primary, ())]
    if found and not opposite:
        return [finding("polarity", severity, "the target says %s; the English has no negation" %
                        ", ".join(repr(w) for w in found[:3]), q)]
    if found:
        return [finding("polarity", WARN, "the target says %s; the English says %s; the judge checks polarity" % (
            ", ".join(repr(w) for w in found[:3]), ", ".join(repr(w) for w in sorted(opposite)[:3])), q)]
    if prefixed and not opposite:
        return [finding("polarity", WARN, "%s may carry a negating prefix the English does not have; the judge "
                        "checks polarity" % ", ".join(repr(w) for w in prefixed[:3]), q)]
    if not prefixed and opposite & EN_STATE_OFF:
        return [finding("polarity", WARN, "the English says %s; check the target is not the opposite" % ", ".join(
            repr(w) for w in sorted(opposite & EN_STATE_OFF)), q)]
    return []


def english_opposite_words(text):
    """English words that may translate to a prefixed word: un-, dis-, de-, in-, non- words and the off words."""
    words = set(w.lower() for w in WORD.findall(text.replace("’", "'")))
    return set(w for w in words if w in EN_OFF_WORDS or
               (len(w) > 4 and re.match(r"^(?:un|dis|de|in|im|ir|il|non|anti)", w)))


def _acronym(word):
    return 2 <= len(word) <= 5 and word.isupper()


def _script(language, english, entry, text, q):
    expected = language.get("script") or "Latn"
    allowed_words = set(WORD.findall(english)) | set(entry.get("dnt") or [])
    bad, borrowed = [], []
    for word in WORD.findall(text):
        scripts = set(s for s in (script_of(ch) for ch in word) if s)
        foreign = scripts - {expected}
        if not foreign:
            continue
        if word in allowed_words or any(word in token for token in allowed_words):
            continue
        if expected != "Latn" and foreign == {"Latn"} and (word in LATIN_LOANS or _acronym(word)):
            borrowed.append(word)
        else:
            bad.append(word)
    out = []
    if bad:
        out.append(finding("script", ERROR, "letters from another script: %s" % ", ".join(sorted(set(bad))[:5]), q))
    if borrowed:
        out.append(finding("script", WARN, "Latin technical words not in the English: %s" % ", ".join(sorted(set(borrowed))[:5]), q))
    return out


def _glossary(entry, reference, text, safety, q):
    out = []
    for hit in entry.get("glossary") or []:
        if hit.get("dnt") or not translib.term_pattern(hit["term"]).search(reference):
            continue
        targets = hit.get("target")
        if not targets:
            continue
        if not any(translib.term_pattern(t).search(text) for t in targets):
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
            requests.append((entry["budget_sp"], packlib.sample_text(text, _formatted(entry))))
            owners.append((key, quantity if kind == "plural" else None))
    if requests and measurer is not None:
        widths = measurer.measure(requests)
        for (key, quantity), width in zip(owners, widths):
            entry = entries[key]
            budget = entry["budget_dp"]
            if width > budget + 0.01:
                english_fits = entry["english_width_dp"] <= budget + 0.01
                out = finding("width", ERROR if english_fits else WARN,
                              "%.1fdp at %gsp exceeds the %gdp budget (English %.1fdp; %s)" % (
                                  width, entry["budget_sp"], budget, entry["english_width_dp"], measurer.method),
                              quantity)
                results[key].append(out)
    return results


def draft_targets(draft):
    return collections.OrderedDict((k, (e.kind, e.items, e.problems)) for k, e in draft.entries.items())


def check_draft(pack, draft, font, measurer):
    """Rows for checks.jsonl, one per key of the draft, plus the keys the draft left out."""
    results = check_targets(pack, draft_targets(draft), font, measurer)
    sha = dict((e["key"], e["source_sha1"]) for e in pack["entries"])
    rows = []
    for key, findings in results.items():
        rows.append(collections.OrderedDict([
            ("draft", draft.name), ("provider", draft.provider), ("model", draft.model),
            ("attempt", draft.entries[key].attempt), ("key", key), ("source_sha1", sha.get(key)),
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
