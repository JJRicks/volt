#!/usr/bin/env python3
"""CI's check of the languages Volt ships (plan 7.2 step 8): what `status` beta or stable promises.

  ship_check.py [--root <repo>] [--aapt auto|off|required]

Every language with status beta or stable must have:
  - res/<folder>/ text that passes merge.py's validation (keys, placeholders, plural forms, current
    src hashes, ladders, l10n_tag) and equals, byte for byte, what merge.py writes from its decisions;
  - the owner's beta bar (council.py beta), read from the committed decisions;
  - a line in res/xml/locales_config.xml, and android:localeConfig in the manifest.
In the other direction, every res/values-* folder with text must belong to such a language, and
locales_config.xml lists nothing else. This mirrors CatalogCheckTest's localesConfig(), which needs a
compiled catalog, so that it also runs on fixture roots.

A language at any other status is not read at all: drafts need no keys and cannot fail this. Nothing
here calls a provider or reaches the network; every rule reads committed files. aapt runs only where
the SDK is installed (CI has none; the local build compiles the same text).
"""

import argparse
import os
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


def folder_problems(paths, languages):
    """Text folders whose language does not ship. check_text_folders already refuses every unlisted folder."""
    problems = gen_catalog.check_text_folders(paths.res(), languages, paths.root)
    for lang in languages:
        if lang.folder != "values" and lang.status not in SHIPPED_STATUS and has_text(paths.res(lang.folder)):
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
    translated = shipped - set(l.tag for l in languages if l.folder == "values")
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


def language_problems(paths, lang, aapt="auto"):
    """(problems, lines) for one shipped language, from merge.py's rules and council.py's bar."""
    problems = []
    if has_text(paths.res(lang.folder)):
        found, notes = merge.validate_folder(paths, lang.tag, aapt, shipped=True)
        problems += found + merge.drift_problems(paths, lang.tag)
    else:
        problems.append("res/%s has no text, but %s has status %s" % (lang.folder, lang.tag, lang.status))
        notes = []
    passes, lines = council.beta_report(paths, lang.tag)
    if not passes:
        problems.append("%s is under the beta bar: %s" % (lang.tag, "; ".join(l for l in lines if l[:2] != "  ")))
    return problems, ["%s: %s" % (lang.status, lines[0])] + ["note: %s: %s" % (lang.tag, note) for note in notes]


def check(paths, aapt="auto"):
    """(problems, lines to print). Problems repeat across languages when a rule is shared, so they are deduplicated."""
    languages = gen_catalog.load_languages(paths.lang_dir())
    shipped = [l for l in languages if l.status in SHIPPED_STATUS and l.folder != "values"]
    problems = folder_problems(paths, languages) + wiring_problems(paths, languages)
    lines = []
    for lang in shipped:
        found, said = language_problems(paths, lang, aapt)
        problems += found
        lines += said
    if not shipped:
        waiting = sorted(l.tag for l in languages if l.folder != "values")
        lines.append("no language has status beta or stable, so no translated text was checked%s; only the wiring was: "
                     "no res/values-* text, locales_config.xml and the manifest" % (
                         " (not read: %s)" % ", ".join(waiting) if waiting else ""))
    return list(dict.fromkeys(problems)), lines


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root")
    parser.add_argument("--aapt", choices=("auto", "off", "required"), default="auto")
    args = parser.parse_args(argv)
    try:
        problems, lines = check(translib.Paths(args.root), args.aapt)
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
