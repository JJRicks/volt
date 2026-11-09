#!/usr/bin/env python3
"""CI's check of the languages Volt ships (plan 7.2 step 8): what `status` beta or stable promises.

  ship_check.py [--root <repo>] [--aapt auto|off|required] [--release]

Every language with status beta or stable must have:
  - res/<folder>/ text that passes merge.py's validation (keys, placeholders, plural forms, current
    src hashes, ladders, l10n_tag) and equals, byte for byte, what merge.py writes from its decisions;
  - every translated lock and alarm key safety-agreed or safety-judged;
  - a line in res/xml/locales_config.xml, and android:localeConfig in the manifest.
In the other direction, every res/values-* folder with text must belong to such a language, and
locales_config.xml lists nothing else.

The English fallback share (keys merge.py leaves to English, counted as council.py beta counts them)
is only a reference line on pull requests. With --release it must be zero for every shipped language:
the owner ships no release until every shipped language is fully translated (2026-11-06). The beta bar
(council.py beta, at most 2%) still governs promoting a language to beta; this tool no longer applies it.

This mirrors CatalogCheckTest's localesConfig(), which needs a
compiled catalog, so that it also runs on fixture roots.

A language at any other status is not read at all: drafts need no keys and cannot fail this. Nothing
here calls a provider or reaches the network; every rule reads committed files. aapt runs only where
the SDK is installed (CI has none; the local build compiles the same text).
"""

import argparse
import os
import re
import sys
import xml.etree.ElementTree
import xml.parsers.expat

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import council  # noqa: E402
import gen_catalog  # noqa: E402
import merge  # noqa: E402
import translib  # noqa: E402

SHIPPED_STATUS = ("beta", "stable")
ANDROID = "{http://schemas.android.com/apk/res/android}"
LOCALES_CONFIG = "@xml/locales_config"


def has_text(directory):
    for filename in sorted(os.listdir(directory)) if os.path.isdir(directory) else []:
        if filename.endswith(".xml"):
            try:
                if gen_catalog._text_resources(os.path.join(directory, filename)):
                    return True
            except xml.parsers.expat.ExpatError:
                return True
    return False


SOURCE_TAG = "en"  # gen_catalog.load_languages requires en.tsv with folder values: the catalog's own language


def tag_locale(tag):
    """(language, script, region) of a BCP 47 tag such as es, pt-BR or zh-Hans, in folder_locale's terms, or None."""
    parts = tag.split("-")
    if not re.match(r"^[A-Za-z]{2,3}$", parts[0]):
        return None
    language, script, region = parts[0].lower(), "", ""
    for part in parts[1:]:
        if re.match(r"^[A-Za-z]{4}$", part) and not script and not region:
            script = part[0].upper() + part[1:].lower()
        elif re.match(r"^(?:[A-Za-z]{2}|\d{3})$", part) and not region:
            region = part.upper()
        else:
            return None
    return gen_catalog.MODERN_LANGUAGE.get(language, language), script, region


def mapping_problems(languages):
    """Problems with which folder each language names, found before anything is filtered by folder or status.

    Only the source language may use res/values: any other language there would be counted as English and never
    checked. No two languages may share a folder, and a translated language's folder must be the one its tag names
    (the language without its region for a CLDR parent region, as pt-PT takes values-pt).
    """
    problems, owners = [], {}
    for lang in languages:
        owners.setdefault(lang.folder, []).append(lang.tag)
        if lang.folder == "values":
            if lang.tag != SOURCE_TAG:
                problems.append("l10n/lang/%s.tsv: folder values belongs to the source language %s; %s needs its own "
                                "values-<qualifier> folder" % (lang.tag, SOURCE_TAG, lang.tag))
            continue
        expected, named = tag_locale(lang.tag), gen_catalog.folder_locale(lang.folder)
        if expected is not None and expected[2] in gen_catalog.PARENT_REGIONS.get(expected[0], ()):
            # A parent region (pt-PT) takes the folder without a region (values-pt): see l10n/README.md.
            expected = expected[:2] + ("",)
        if expected is None or named != expected:
            problems.append("l10n/lang/%s.tsv: folder %s does not match the tag %s" % (lang.tag, lang.folder, lang.tag))
    for folder, tags in sorted(owners.items()):
        if len(tags) > 1:
            problems.append("l10n/lang: %s all name folder %s; each language needs its own folder" % (
                ", ".join(sorted(tags)), folder))
    return problems


def folder_problems(paths, languages):
    """Text folders whose language does not ship. check_text_folders already refuses every unlisted folder."""
    problems = gen_catalog.check_text_folders(paths.res(), languages, paths.root)
    for lang in languages:
        if lang.tag != SOURCE_TAG and lang.status not in SHIPPED_STATUS and has_text(paths.res(lang.folder)):
            problems.append("res/%s holds text, but %s has status %s; only beta and stable ship, so keep its "
                            "text in l10n/ until it does" % (lang.folder, lang.tag, lang.status))
    return problems


def _read_xml(path):
    try:
        return xml.etree.ElementTree.parse(path).getroot()
    except (OSError, xml.etree.ElementTree.ParseError):
        return None


def wiring_problems(paths, languages):
    """locales_config.xml lists exactly the shipped languages; the manifest names it once one besides English ships."""
    shipped = set(l.tag for l in languages if l.status in SHIPPED_STATUS)
    translated = shipped - {SOURCE_TAG}
    problems = []
    config = _read_xml(paths.res("xml", "locales_config.xml"))
    if config is None:
        if translated:
            problems.append("res/xml/locales_config.xml is missing or unreadable; it must list %s" % ", ".join(
                sorted(shipped)))
    else:
        listed = set(e.get(ANDROID + "name") for e in config.iter("locale"))
        for tag in sorted(shipped - listed):
            problems.append("res/xml/locales_config.xml does not list %s, which has status beta or stable" % tag)
        for tag in sorted(listed - shipped):
            problems.append("res/xml/locales_config.xml lists %s, which does not ship" % tag)
    manifest = _read_xml(os.path.join(paths.root, "AndroidManifest.xml"))
    application = manifest.find("application") if manifest is not None else None
    named = application.get(ANDROID + "localeConfig") if application is not None else None
    if translated and named != LOCALES_CONFIG:
        problems.append('AndroidManifest.xml: <application> needs android:localeConfig="%s", since %s ships' % (
            LOCALES_CONFIG, ", ".join(sorted(translated))))
    if not translated and named is not None:
        problems.append("AndroidManifest.xml names android:localeConfig, but no language besides English ships")
    return problems


RELEASE_KEYS_SHOWN = 20  # how many fallback key names --release prints per language


def fallback_report(paths, tag):
    """(total, fallback, unconfirmed): translatable keys, [(key, why)] merge.py leaves to English and lock or
    alarm keys translated without safety agreement, counted exactly as council.beta_report counts them."""
    categories = translib.plural_categories(translib.load_language(paths, tag))
    sources, _ = translib.load_source(paths)
    total, fallback, unconfirmed = 0, [], []
    for area, rows in sources.items():
        decided = dict((r.get("key"), r) for r in translib.read_jsonl(paths.decisions(tag, area)))
        safety = translib.effective_safety(rows)
        for s in rows:
            if not s.translatable:
                continue
            total += 1
            d = decided.get(s.key)
            why = translib.why_english(s, d, categories)
            hit = [x for x in safety[s.key] if x in translib.SAFETY_BLOCKING]
            if why:
                fallback.append((s.key, why + ("; %s" % ", ".join(hit) if hit else "")))
            elif hit and d.get("outcome") not in council.SAFETY_TRANSLATED:
                unconfirmed.append("%s (%s)" % (s.key, ", ".join(hit)))
    return total, fallback, unconfirmed


def language_problems(paths, lang, aapt="auto", release=False):
    """(problems, lines) for one shipped language, from merge.py's rules and its English fallback."""
    problems = []
    if has_text(paths.res(lang.folder)):
        found, notes = merge.validate_folder(paths, lang.tag, aapt, shipped=True)
        problems += found + merge.drift_problems(paths, lang.tag)
    else:
        problems.append("res/%s has no text, but %s has status %s" % (lang.folder, lang.tag, lang.status))
        notes = []
    total, fallback, unconfirmed = fallback_report(paths, lang.tag)
    if unconfirmed:
        problems.append("%s: lock or alarm keys translated but not safety-agreed or safety-judged: %s" % (
            lang.tag, ", ".join(unconfirmed)))
    share = "%s (%s): %d of %d keys fall back to English (%.2f%%)" % (
        lang.tag, lang.folder, len(fallback), total, 100.0 * len(fallback) / total if total else 0.0)
    if release:
        line = share + " — release mode: a release needs 0"
        if fallback:
            shown = ", ".join("%s (%s)" % kw for kw in fallback[:RELEASE_KEYS_SHOWN])
            more = len(fallback) - RELEASE_KEYS_SHOWN
            problems.append("%s is not fully translated, so no release ships: %d key(s) fall back to English: %s%s; "
                            "translate them first (a top-up)" % (
                                lang.tag, len(fallback), shown, " and %d more" % more if more > 0 else ""))
    else:
        line = share + " — reference only; a release needs 0"
    return problems, ["%s: %s" % (lang.status, line)] + ["note: %s: %s" % (lang.tag, note) for note in notes]


def check(paths, aapt="auto", release=False):
    """(problems, lines to print). Problems repeat across languages when a rule is shared, so they are deduplicated."""
    languages = gen_catalog.load_languages(paths.lang_dir())
    malformed = mapping_problems(languages)
    if malformed:
        # Every later rule reads each language's folder, so a wrong mapping would mislead them all: stop here.
        return malformed, ["the language list maps folders wrongly, so nothing else was checked"]
    shipped = [l for l in languages if l.status in SHIPPED_STATUS and l.tag != SOURCE_TAG]
    problems = folder_problems(paths, languages) + wiring_problems(paths, languages)
    lines = []
    for lang in shipped:
        found, said = language_problems(paths, lang, aapt, release)
        problems += found
        lines += said
    if not shipped:
        waiting = sorted(l.tag for l in languages if l.tag != SOURCE_TAG)
        lines.append("no language has status beta or stable, so no translated text was checked%s; only the wiring was: "
                     "no res/values-* text, locales_config.xml and the manifest" % (
                         " (not read: %s)" % ", ".join(waiting) if waiting else ""))
    return list(dict.fromkeys(problems)), lines


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root")
    parser.add_argument("--aapt", choices=("auto", "off", "required"), default="auto")
    parser.add_argument("--release", action="store_true",
                        help="release mode: every shipped language must have no key falling back to English")
    args = parser.parse_args(argv)
    try:
        problems, lines = check(translib.Paths(args.root), args.aapt, args.release)
    except (translib.L10nError, gen_catalog.CatalogError) as error:
        print("ship_check: %s" % error, file=sys.stderr)
        return 2
    for line in lines:
        print("ship_check: %s" % line)
    for problem in problems:
        print("ship_check: problem: %s" % problem)
    print("ship_check: %s" % ("FAILED, %d problem(s)" % len(problems) if problems else "PASS"))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
