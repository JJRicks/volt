import contextlib
import io
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_catalog  # noqa: E402
import l10nlib  # noqa: E402

B = chr(92)
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
NS = 'xmlns:volt="%s" xmlns:xliff="%s"' % (gen_catalog.VOLT_NS, gen_catalog.XLIFF_NS)

CATALOG = """<?xml version="1.0" encoding="utf-8"?>
<resources %s>
    <string name="app_name" volt:dnt="Volt">Volt</string>
    <!-- The lock row's caption. -->
    <string name="ui_lock" volt:screen="Home" volt:ladder="full" volt:safety="lock" volt:max="96dp@14sp">Lock scooter</string>
    <string name="ui_lock_short" volt:ladder="short">Lock</string>
    <string name="dash_move_widget">Move %%1$s to slot %%2$d</string>
    <string name="fmt_percent">%%1$s%%%%</string>
    <string name="fmt_distance">%%1$.1f km</string>
    <string name="common_dont">Don%s't clear storage.</string>
    <string name="common_wrapped">"  Connecting  "</string>
    <string name="raw_percent" formatted="false" translatable="false">100%% sure</string>
    <string name="l10n_tag">en</string>
    <plurals name="backup_scooters_skipped" volt:english-fix="pending">
        <item quantity="one">%%1$d scooter skipped</item>
        <item quantity="other">%%1$d scooters skipped</item>
    </plurals>
    <plurals name="rides_count">
        <item quantity="one">One ride</item>
        <item quantity="other">%%1$d rides</item>
    </plurals>
</resources>
""" % (NS, B)

LANG = ("field\tvalue\ntag\ten\nfolder\tvalues\nendonym\tEnglish\nwave\tsource\nstatus\tstable\nformality\tn/a\n"
        "plurals\tone,other\nquote_style\tx\npercent_style\tx\ndecimal_style\tx\nscript\tLatn\nfont\tsaira\n"
        "rtl\tfalse\nunit_symbols\tsi\ndrive_mode_names\tenglish\n")


def find_javac():
    for home in (os.environ.get("JAVA_HOME"), "/Applications/Android Studio.app/Contents/jbr/Contents/Home"):
        if home and os.path.isfile(os.path.join(home, "bin", "javac")):
            return os.path.join(home, "bin")
    found = shutil.which("javac")
    return os.path.dirname(found) if found else None


class DecodeTest(unittest.TestCase):
    def decode(self, raw):
        return gen_catalog.decode_resource_text(raw)

    def test_accepted_spellings(self):
        cases = {
            "Lock scooter": "Lock scooter",
            '"  both  "': "  both  ",
            "\"Don't\"": "Don't",
            "Don" + B + "'t": "Don't",
            "Tap " + B + '"Save' + B + '"': 'Tap "Save"',
            "a" + B + "nb" + B + "tc": "a\nb\tc",
            B + "@home": "@home",
            B + "?x": "?x",
            B + "#1": "#1",
            "Wait" + B + "u2026": "Wait" + chr(0x2026),
            "end" + B + "u0020": "end ",
            B + "ud83d" + B + "udd0b": chr(0x1F50B),
            "C:" + B + B + "x": "C:" + B + "x",
            "": "",
            '" "': " ",
        }
        for raw, text in cases.items():
            decoded, problems, _ = self.decode(raw)
            self.assertEqual(decoded, text, raw)
            self.assertEqual(problems, [], raw)

    def test_refused_spellings(self):
        cases = {
            " lead": "leading whitespace",
            "trail ": "trailing whitespace",
            "two  spaces": "repeated spaces",
            "line\n  two": "raw tab or line break",
            "a\tb": "raw tab or line break",
            "a" + B + "qb": "unknown escape",
            "a" + B + "rb": "unknown escape",
            "Don't": "unescaped apostrophe",
            'a "b" c': 'unescaped "',
            'Say "hi': 'unescaped "',
            "@home": "resource reference",
            "?x": "resource reference",
            B + "u12g4": "bad " + B + "u escape",
            "end" + B: "lone backslash",
        }
        for raw, expected in cases.items():
            _, problems, _ = self.decode(raw)
            self.assertTrue(any(expected in p for p in problems), "%r: %r" % (raw, problems))

    def test_intent_keeps_what_was_typed(self):
        self.assertEqual(self.decode(" lead")[0], " lead")
        self.assertEqual(self.decode('Say "hi')[0], 'Say "hi')
        self.assertEqual(self.decode("a" + B + "qb")[0], "a" + B + "qb")

    def test_format_analysis(self):
        self.assertEqual(gen_catalog.analyze_format("Move %1$s to %2$d"), ({1: "s", 2: "d"}, []))
        self.assertEqual(gen_catalog.analyze_format("%1$.1f km, %2$02d min, %1$.2f"), ({1: "f", 2: "d"}, []))
        self.assertEqual(gen_catalog.analyze_format("100%%"), ({}, []))
        for text, message in (("%s", "non-positional"), ("75% full", "lone %"), ("%1$x", "unsupported"),
                              ("%1$s %1$d", "both"), ("%1$tH", "unsupported"), ("%0$s", "start at 1"),
                              # B1 review: Fmt.compose marks each %n$s argument's ends; a cut leaks a mark.
                              ("%1$.2s", "width, precision or flags"), ("%1$-8s", "width, precision or flags"),
                              ("%1$8s", "width, precision or flags"), ("%2$#s %1$s", "width, precision or flags"),
                              ("%1$S", "unsupported")):
            problems = gen_catalog.analyze_format(text)[1]
            self.assertTrue(any(message in p for p in problems), "%r: %r" % (text, problems))


class CatalogTest(unittest.TestCase):
    maxDiff = None
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="l10n-catalog-")
        os.makedirs(os.path.join(self.dir, "res", "values"))
        os.makedirs(os.path.join(self.dir, "lang"))
        l10nlib.write_text(os.path.join(self.dir, "lang", "en.tsv"), LANG)

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def write(self, name, text):
        l10nlib.write_text(os.path.join(self.dir, "res", "values", name), text)

    def load(self):
        return gen_catalog.load_catalog(os.path.join(self.dir, "res", "values"))

    def problems(self, body, name="strings_test.xml"):
        self.write(name, '<?xml version="1.0" encoding="utf-8"?>\n<resources %s>\n%s\n</resources>\n' % (NS, body))
        try:
            self.load()
        except gen_catalog.CatalogError as error:
            return error.problems
        return []

    def test_catalog_entries_and_signatures(self):
        self.write("strings_test.xml", CATALOG)
        entries, tag = self.load()
        self.assertEqual(tag.items["other"].text, "en")
        by_name = {e.name: e for e in entries}
        self.assertNotIn("l10n_tag", by_name)
        self.assertEqual(gen_catalog.parameters(by_name["dash_move_widget"]),
                         [(1, "CharSequence", "a1"), (2, "long", "a2")])
        self.assertEqual(gen_catalog.parameters(by_name["fmt_distance"]), [(1, "double", "a1")])
        self.assertEqual(gen_catalog.parameters(by_name["rides_count"]), [(1, "long", "a1")])
        self.assertEqual(by_name["common_wrapped"].items["other"].text, "  Connecting  ")
        self.assertEqual(gen_catalog.flag(by_name["raw_percent"]), "s")
        self.assertEqual(gen_catalog.flag(by_name["fmt_percent"]), "f")
        self.assertEqual(gen_catalog.flag(by_name["backup_scooters_skipped"]), "q")
        self.assertEqual(by_name["ui_lock"].note, "The lock row's caption.")

    def test_expected_tsv_round_trips(self):
        self.write("strings_test.xml", CATALOG)
        entries, tag = self.load()
        expected = gen_catalog.read_expected_tsv(gen_catalog.expected_tsv(entries, tag))
        self.assertEqual(expected[("string", "common_wrapped", "other")], "  Connecting  ")
        self.assertEqual(expected[("plural", "backup_scooters_skipped", "one")], "%1$d scooter skipped")
        self.assertEqual(expected[("string", "l10n_tag", "other")], "en")

    def test_todays_repository_catalog(self):
        entries, tag = gen_catalog.load_catalog(os.path.join(ROOT, "res", "values"))
        self.assertIn("app_name", [e.name for e in entries])
        languages = gen_catalog.load_languages(os.path.join(ROOT, "l10n", "lang"))
        self.assertEqual(languages[0].tag, "en")

    def test_rejections(self):
        cases = [
            ('<string name="a">x</string><string name="a">y</string>', "duplicate key"),
            ('<string name="Bad">x</string>', "lower_snake_case"),
            ('<string name="a_b1">x</string><string name="a_b_1">y</string>', "camel-cases"),
            ('<string name="install">x</string>', "already uses"),
            ('<string name="a" volt:colour="red">x</string>', "unknown attribute volt:colour"),
            ('<string name="a" volt:ladder="tiny">x</string>', "volt:ladder"),
            ('<string name="a" volt:safety="brakes">x</string>', "volt:safety"),
            ('<string name="a" volt:max="96">x</string>', "volt:max"),
            ('<string name="a" volt:english-fix="done">x</string>', "english-fix"),
            ('<string name="a" volt:dnt="NAVEE">Volt</string>', "volt:dnt token"),
            ('<string name="a"><b>Bold</b></string>', "markup <b>"),
            ('<string-array name="a"><item>x</item></string-array>', "string-arrays are banned"),
            ('<plurals name="a"><item quantity="one">x</item></plurals>', 'needs an "other"'),
            ('<plurals name="a"><item quantity="several">x</item><item quantity="other">y</item></plurals>', "quantity"),
            ('<plurals name="a"><item quantity="few">x</item><item quantity="other">y</item></plurals>',
             'English plural holds only "one" and "other"'),
            ('<plurals name="a"><item quantity="zero">x</item><item quantity="other">y</item></plurals>',
             'English plural holds only "one" and "other"'),
            ('<plurals name="a"><item quantity="one">%1$s</item><item quantity="other">%1$d</item></plurals>',
             "different conversions"),
            ('<string name="a">%2$s</string>', "no gaps"),
            ('<string name="a">%1$-8s|</string>', "width, precision or flags"),
            ('<plurals name="a"><item quantity="one">%1$.1s</item><item quantity="other">%1$s</item></plurals>',
             "width, precision or flags"),
            ('<string name="a_short" volt:ladder="short">x</string>', "rung must be named"),
            ('<string name="a">%1$s</string><string name="a_short" volt:ladder="short">%1$d</string>', "rung placeholder"),
            ('<string name="l10n_tag">fr</string>', "l10n_tag"),
            ('<string name="a" translatable="no">x</string>', "true or false"),
            ('<color name="a">#fff</color>', "not allowed in a strings file"),
            ('<string name="a"> lead</string>', "leading whitespace"),
        ]
        for body, message in cases:
            problems = self.problems(body)
            self.assertTrue(any(message in p for p in problems), "%s -> %r" % (body, problems))

    def test_problems_name_the_file_and_line(self):
        problems = self.problems('<string name="a">two  spaces</string>')
        self.assertTrue(problems[0].endswith("repeated spaces: aapt collapses them to one unless the whole "
                                             "string is wrapped in \"...\""), problems)
        self.assertIn("strings_test.xml:3: a", problems[0])

    def test_languages(self):
        languages = gen_catalog.load_languages(os.path.join(self.dir, "lang"))
        self.assertEqual([l.tag for l in languages], ["en"])
        l10nlib.write_text(os.path.join(self.dir, "lang", "es.tsv"), LANG.replace("\ten\n", "\tes\n", 1)
                           .replace("values\n", "values-es\n").replace("stable", "sometime"))
        with self.assertRaises(gen_catalog.CatalogError) as caught:
            gen_catalog.load_languages(os.path.join(self.dir, "lang"))
        self.assertTrue(any("status" in p for p in caught.exception.problems))

    def test_text_only_in_approved_language_folders(self):
        """Finding 4: values-en, values-night or values-v31 text would override the catalog English."""
        res = os.path.join(self.dir, "res")
        resources = '<resources><string name="app_name">%s</string></resources>\n'
        for folder in ("values-en", "values-en-rGB", "values-night", "values-v31", "values-es"):
            l10nlib.write_text(os.path.join(res, folder, "strings.xml"), resources % "Volt!")
        l10nlib.write_text(os.path.join(res, "values-night", "colors.xml"),
                           '<resources><color name="bg">#000000</color></resources>\n')
        l10nlib.write_text(os.path.join(res, "values-v31", "more.xml"),
                           '<resources><item name="x" type="string">Hi</item></resources>\n')
        languages = gen_catalog.load_languages(os.path.join(self.dir, "lang"))
        problems = gen_catalog.check_text_folders(res, languages, self.dir)
        flagged = sorted(p.split(":")[0] for p in problems)
        self.assertEqual(flagged, ["res/values-en-rGB/strings.xml", "res/values-en/strings.xml",
                                   "res/values-es/strings.xml", "res/values-night/strings.xml",
                                   "res/values-v31/more.xml", "res/values-v31/strings.xml"])
        l10nlib.write_text(os.path.join(self.dir, "lang", "es.tsv"), LANG.replace("\ten\n", "\tes\n", 1)
                           .replace("values\n", "values-es\n").replace("English", "Español"))
        languages = gen_catalog.load_languages(os.path.join(self.dir, "lang"))
        self.assertNotIn("res/values-es/strings.xml",
                         [p.split(":")[0] for p in gen_catalog.check_text_folders(res, languages, self.dir)])
        self.write("strings.xml", resources % "Volt")
        quiet = io.StringIO()
        with contextlib.redirect_stdout(quiet), contextlib.redirect_stderr(quiet):
            self.assertEqual(gen_catalog.main(["--res", res, "--lang", os.path.join(self.dir, "lang"), "--check"]), 1)
        self.assertIn("would replace the catalog English wherever values-en matches", quiet.getvalue())

    def test_cli(self):
        self.write("strings_test.xml", CATALOG)
        out = os.path.join(self.dir, "out")
        quiet = io.StringIO()
        with contextlib.redirect_stdout(quiet), contextlib.redirect_stderr(quiet):
            code = gen_catalog.main(["--res", os.path.join(self.dir, "res"), "--lang", os.path.join(self.dir, "lang"),
                                     "--out", out, "--tsv", os.path.join(out, "en.expected.tsv"), "--android"])
        self.assertEqual(code, 0)
        self.assertIn("9 strings, 2 plurals, l10n_tag", quiet.getvalue())
        for name in ("T", "M", "EnglishText", "AndroidText", "AppLanguages"):
            self.assertTrue(os.path.isfile(os.path.join(out, "com", "jjricks", "volt", name + ".java")), name)
        self.write("strings_bad.xml", '<resources><string name="b"> x</string></resources>')
        with contextlib.redirect_stdout(quiet), contextlib.redirect_stderr(quiet):
            self.assertEqual(gen_catalog.main(["--res", os.path.join(self.dir, "res"), "--lang",
                                               os.path.join(self.dir, "lang"), "--check"]), 1)
        self.assertIn("strings_bad.xml:1: b: leading whitespace", quiet.getvalue())

    def test_javadoc_and_java_strings_are_safe(self):
        self.assertEqual(gen_catalog.java_string('a"b' + B + "\n" + chr(1)), '"a' + B + '"b' + B + B + B + "n" + B + '001"')
        doc = gen_catalog.javadoc_text("*/ " + B + "u0041 <b> @x")
        self.assertNotIn("*/", doc)
        self.assertNotIn(B, doc)
        self.assertNotIn("<b>", doc)

    @unittest.skipUnless(find_javac(), "javac not found")
    def test_generated_java_compiles_types_and_renders(self):
        self.write("strings_test.xml", CATALOG)
        out = os.path.join(self.dir, "gen")
        gen_catalog.generate(os.path.join(self.dir, "res"), os.path.join(self.dir, "lang"), out, None, True)
        pkg = os.path.join(out, "com", "jjricks", "volt")
        l10nlib.write_text(os.path.join(pkg, "Msg.java"), MSG_STUB)
        l10nlib.write_text(os.path.join(pkg, "R.java"), R_STUB)
        write_android_stubs(out)
        l10nlib.write_text(os.path.join(pkg, "Probe.java"), PROBE)
        bin_dir = find_javac()
        classes = os.path.join(self.dir, "classes")
        sources = []
        for directory, _, names in os.walk(out):
            sources.extend(os.path.join(directory, n) for n in names if n.endswith(".java"))
        compiled = subprocess.run([os.path.join(bin_dir, "javac"), "--release", "8", "-Xlint:-options", "-encoding", "UTF-8",
                                   "-d", classes] + sources, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        self.assertEqual(compiled.returncode, 0, compiled.stdout.decode("utf-8", "replace"))
        run = subprocess.run([os.path.join(bin_dir, "java"), "-Duser.language=tr", "-Duser.country=TR",
                              "-cp", classes, "com.jjricks.volt.Probe"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        self.assertEqual(run.returncode, 0, run.stdout.decode("utf-8", "replace"))
        self.assertEqual(run.stdout.decode("utf-8").split("\n"), [
            "Volt", "Move Speed to slot 3", "75%", "12.3 km", "Don't clear storage.", "  Connecting  ",
            "100% sure", "1 scooter skipped", "2 scooters skipped", "One ride", "0 rides",
            "deferred: Move Range to slot 4", "android: Move Speed to slot 3|res:2 skipped (q=2)|en", "en", ""])
        # A wrong argument type or count is a compile error.
        l10nlib.write_text(os.path.join(pkg, "Probe.java"), PROBE.replace('T.dashMoveWidget("Speed", 3)', "T.dashMoveWidget(3)"))
        wrong = subprocess.run([os.path.join(bin_dir, "javac"), "--release", "8", "-Xlint:-options", "-d", classes] + sources,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        self.assertNotEqual(wrong.returncode, 0)
        self.assertIn("dashMoveWidget", wrong.stdout.decode("utf-8", "replace"))


    def compile_and_run(self, catalog, probes, languages=()):
        """Generates the catalog with --android, compiles it with the stubs and probes, runs each probe."""
        self.write("strings_test.xml", catalog)
        for name, text in languages:
            l10nlib.write_text(os.path.join(self.dir, "lang", name), text)
        out = os.path.join(self.dir, "gen")
        gen_catalog.generate(os.path.join(self.dir, "res"), os.path.join(self.dir, "lang"), out, None, True)
        pkg = os.path.join(out, "com", "jjricks", "volt")
        l10nlib.write_text(os.path.join(pkg, "Msg.java"), MSG_STUB)
        l10nlib.write_text(os.path.join(pkg, "R.java"), R_STUB.replace("rides_count = 12;", "rides_count = 12, minutes_left = 13;"))
        write_android_stubs(out)
        for name, text in probes.items():
            l10nlib.write_text(os.path.join(pkg, name + ".java"), text)
        bin_dir = find_javac()
        classes = os.path.join(self.dir, "classes")
        sources = [os.path.join(d, n) for d, _, names in os.walk(out) for n in names if n.endswith(".java")]
        compiled = subprocess.run([os.path.join(bin_dir, "javac"), "--release", "8", "-Xlint:-options", "-encoding", "UTF-8",
                                   "-d", classes] + sources, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        self.assertEqual(compiled.returncode, 0, compiled.stdout.decode("utf-8", "replace"))
        results = {}
        for name in probes:
            run = subprocess.run([os.path.join(bin_dir, "java"), "-cp", classes, "com.jjricks.volt." + name],
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            self.assertEqual(run.returncode, 0, run.stdout.decode("utf-8", "replace"))
            results[name] = run.stdout.decode("utf-8").splitlines()
        return results

    @unittest.skipUnless(find_javac(), "javac not found")
    def test_plural_selection_follows_the_served_language(self):
        """T.Plurals.standIn, pure: under every phone language's rules Android is made to serve the item the
        served language's rules choose, and an English phone passes every quantity it passed before."""
        out = self.compile_and_run(CATALOG, {"PluralProbe": PLURAL_PROBE})["PluralProbe"]
        self.assertEqual(out, [
            "english under en: every quantity from 0 passes as it is",
            "english under de: every quantity from 0 passes as it is",
            "english under th: no stand-in for 1",
            "english under ja: no stand-in for 1",
            "english under zh: no stand-in for 1",
            "english under ko: no stand-in for 1",
            "english under vi: no stand-in for 1",
            "english under id: no stand-in for 1",
            "english under ru: served",
            "english under uk: served",
            "english under pl: served",
            "english under cs: served",
            "english under lv: served",
            "english under ar: served",
            "english under es: served",
            "english under en at -1: other (Android alone: one)",
            "spanish under es: unchanged",
            "spanish under ru: served",
            "spanish under ja: no stand-in for many, one",
            "ok"])

    @unittest.skipUnless(find_javac(), "javac not found")
    def test_android_plurals_use_english_rules_while_volt_shows_english(self):
        """The B8 and B3 repro: Android picks a plural's item by the phone's first language even while it
        serves the English default, so a Thai phone said "1 minutes left" and a Russian one "21 minute left"."""
        catalog = CATALOG.replace("    <plurals name=\"rides_count\">", "    <plurals name=\"minutes_left\">\n"
                                  "        <item quantity=\"one\">%1$d minute left</item>\n"
                                  "        <item quantity=\"other\">%1$d minutes left</item>\n"
                                  "    </plurals>\n    <plurals name=\"rides_count\">")
        spanish = (LANG.replace("\ten\n", "\tes\n", 1).replace("values\n", "values-es\n").replace("English", "Español")
                   .replace("stable", "beta").replace("one,other", "one,many,other"))
        japanese = (LANG.replace("\ten\n", "\tja\n", 1).replace("values\n", "values-ja\n").replace("English", "Japanese")
                    .replace("stable", "beta").replace("one,other", "other"))
        russian = (LANG.replace("\ten\n", "\tru\n", 1).replace("values\n", "values-ru\n").replace("English", "Russian")
                   .replace("stable", "draft").replace("one,other", "one,few,many,other"))
        out = self.compile_and_run(catalog, {"AndroidProbe": ANDROID_PROBE},
                                   [("es.tsv", spanish), ("ja.tsv", japanese), ("ru.tsv", russian)])["AndroidProbe"]
        self.assertEqual(out, [
            "en en-US: 0 minutes left | 1 minute left | 2 minutes left | 5 minutes left | 21 minutes left | 22 minutes left | 101 minutes left",
            "en th: 0 minutes left | 1 minute left (Android alone: 1 minutes left) | 2 minutes left | 5 minutes left | 21 minutes left"
            " | 22 minutes left | 101 minutes left",
            "en ja: 0 minutes left | 1 minute left (Android alone: 1 minutes left) | 2 minutes left | 5 minutes left | 21 minutes left"
            " | 22 minutes left | 101 minutes left",
            "en ru: 0 minutes left | 1 minute left | 2 minutes left | 5 minutes left | 21 minutes left (Android alone: 21 minute left)"
            " | 22 minutes left | 101 minutes left (Android alone: 101 minute left)",
            "en lv: 0 minutes left | 1 minute left | 2 minutes left | 5 minutes left | 21 minutes left (Android alone: 21 minute left)"
            " | 22 minutes left | 101 minutes left (Android alone: 101 minute left)",
            "en ar: 0 minutes left | 1 minute left | 2 minutes left | 5 minutes left | 21 minutes left | 22 minutes left | 101 minutes left",
            "es es: 1 minuto | 2 minutos | 21 minutos | 1000000 de minutos",
            "es ru: 1 minuto | 2 minutos | 21 minutos (Android alone: 21 minuto) | 1000000 de minutos",
            "es ja: 1 minutos | 2 minutos | 21 minutos | 1000000 minutos",
            "mangled tag under th: 1 minute left",
            # A language that leaves the key out (shipped Japanese, unshipped Russian) serves the English default:
            # English rules, not its own.
            "en ja (Volt in ja): 0 minutes left | 1 minute left (Android alone: 1 minutes left) | 2 minutes left | 5 minutes left"
            " | 21 minutes left | 22 minutes left | 101 minutes left",
            "en ru (Volt in en): 0 minutes left | 1 minute left | 2 minutes left | 5 minutes left | 21 minutes left"
            " (Android alone: 21 minute left) | 22 minutes left | 101 minutes left (Android alone: 101 minute left)",
            # A folder whose language never shipped (language() is English) keeps its own language's rules.
            "ru ru (Volt in en): 1 ONE | 2 FEW | 5 MANY | 21 ONE | 22 FEW",
            # An English phone reaches only one and other: Russian's one is served, few and many are not reachable.
            "ru en-US (Volt in en): 1 ONE | 2 OTHER | 5 OTHER | 21 ONE (Android alone: 21 OTHER) | 22 OTHER",
            "ru ja (Volt in en): 1 OTHER | 2 OTHER | 5 OTHER | 21 OTHER | 22 OTHER"])


PLURAL_PROBE = """package com.jjricks.volt;
import android.icu.text.PluralRules;
import java.util.Locale;
public final class PluralProbe {
    static T.Plurals.Rule rules(String tag) {
        final PluralRules r = PluralRules.forLocale(Locale.forLanguageTag(tag));
        return new T.Plurals.Rule() { public String select(long q) { return r.select(q); } };
    }
    static String item(String category, String[] items) { for (String i : items) if (i.equals(category)) return i; return "other"; }
    static void require(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    public static void main(String[] args) {
        long[] extra = {1000000, 21000000, Integer.MAX_VALUE, Integer.MAX_VALUE + 2L, Integer.MIN_VALUE - 2L, Long.MAX_VALUE, Long.MIN_VALUE + 1};
        long[] quantities = new long[2100 + extra.length];
        for (int i = 0; i < 2100; i++) quantities[i] = i - 3;
        System.arraycopy(extra, 0, quantities, 2100, extra.length);
        for (String phone : new String[]{"en", "de", "th", "ja", "zh", "ko", "vi", "id", "ru", "uk", "pl", "cs", "lv", "ar", "es"}) {
            T.Plurals.Rule android = rules(phone);
            int same = 0, positive = 0;
            StringBuilder none = new StringBuilder();
            for (long q : quantities) {
                String want = q == 1 ? "one" : "other";  // EnglishText's choice
                require(T.Plurals.wanted(q, T.Plurals.ENGLISH, T.Plurals.ENGLISH_ITEMS).equals(want), "English wants " + want + " for " + q);
                long n = T.Plurals.standIn(q, T.Plurals.ENGLISH, T.Plurals.ENGLISH_ITEMS, android);
                if (n == T.Plurals.NONE) { none.append(none.length() == 0 ? "" : ", ").append(q); continue; }
                require(n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE, "an int stand-in");
                require(item(android.select(n), T.Plurals.ENGLISH_ITEMS).equals(want), phone + ": " + q + " is served " + want + " through " + n);
                if (q >= 0) { positive++; if (n == Math.min(q, Integer.MAX_VALUE)) same++; }
            }
            if (phone.equals("en") || phone.equals("de")) {
                require(none.length() == 0, phone + " always has a stand-in");
                System.out.println("english under " + phone + ": " + (same == positive ? "every quantity from 0 passes as it is" : "changed"));
            } else {
                System.out.println("english under " + phone + ": " + (none.length() == 0 ? "served" : "no stand-in for " + none));
            }
        }
        T.Plurals.Rule en = rules("en");
        System.out.println("english under en at -1: " + item(en.select(T.Plurals.standIn(-1, T.Plurals.ENGLISH, T.Plurals.ENGLISH_ITEMS, en)),
                T.Plurals.ENGLISH_ITEMS) + " (Android alone: " + en.select(-1) + ")");
        String[] spanish = {"one", "many", "other"};
        T.Plurals.Rule es = rules("es");
        for (String phone : new String[]{"es", "ru", "ja"}) {
            T.Plurals.Rule android = rules(phone);
            java.util.Set<String> none = new java.util.TreeSet<>();
            boolean same = true;
            for (long q : quantities) {
                String want = item(es.select(q), spanish);
                long n = T.Plurals.standIn(q, es, spanish, android);
                if (n == T.Plurals.NONE) { none.add(want); continue; }
                require(item(android.select(n), spanish).equals(want), phone + ": " + q + " is served Spanish " + want);
                if (q >= 0 && n != Math.min(q, Integer.MAX_VALUE)) same = false;
            }
            System.out.println("spanish under " + phone + ": " + (!none.isEmpty() ? "no stand-in for " + String.join(", ", none) : same ? "unchanged" : "served"));
        }
        System.out.println("ok");
    }
}
"""

ANDROID_PROBE = """package com.jjricks.volt;
import android.content.res.Resources;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
public final class AndroidProbe {
    static String line(String language, String phone, long[] quantities) {
        Resources.first = Locale.forLanguageTag(phone);
        Resources resources = new Resources();
        T.install(new AndroidText(resources));
        StringBuilder out = new StringBuilder(language + " " + phone + ":");
        for (long q : quantities) {
            String shown = T.minutesLeft(q, q);
            String alone = String.format(Locale.ROOT, resources.getQuantityString(R.plurals.minutes_left, (int) q), q);
            out.append(q == quantities[0] ? " " : " | ").append(shown).append(shown.equals(alone) ? "" : " (Android alone: " + alone + ")");
        }
        return out.toString();
    }
    public static void main(String[] args) {
        Map<String, String> english = new HashMap<>();
        english.put("one", "%1$d minute left");
        english.put("other", "%1$d minutes left");
        Resources.items = english;
        long[] quantities = {0, 1, 2, 5, 21, 22, 101};
        for (String phone : new String[]{"en-US", "th", "ja", "ru", "lv", "ar"}) System.out.println(line("en", phone, quantities));
        // A shipped Spanish served under other first languages (Android resolves values-es from a later locale).
        Map<String, String> spanish = new HashMap<>();
        spanish.put("one", "%1$d minuto");
        spanish.put("many", "%1$d de minutos");
        spanish.put("other", "%1$d minutos");
        Resources.items = spanish;
        Resources.tag = "es";
        for (String phone : new String[]{"es", "ru", "ja"}) System.out.println(line("es", phone, new long[]{1, 2, 21, 1000000}));
        // A pseudo-locale or broken l10n_tag counts as English.
        Resources.items = english;
        Resources.tag = "[\u00e9\u00f1]";
        Resources.first = Locale.forLanguageTag("th");
        T.install(new AndroidText(new Resources()));
        System.out.println("mangled tag under th: " + T.minutesLeft(1, 1));
        Resources.tag = "ja";
        System.out.println(line("en", "ja", quantities).replaceFirst(":", " (Volt in " + T.language() + "):"));
        Resources.tag = "ru";
        System.out.println(line("en", "ru", quantities).replaceFirst(":", " (Volt in " + T.language() + "):"));
        Map<String, String> russian = new HashMap<>();
        russian.put("one", "%1$d ONE");
        russian.put("few", "%1$d FEW");
        russian.put("many", "%1$d MANY");
        russian.put("other", "%1$d OTHER");
        Resources.items = russian;
        for (String phone : new String[]{"ru", "en-US", "ja"})
            System.out.println(line("ru", phone, new long[]{1, 2, 5, 21, 22}).replaceFirst(":", " (Volt in " + T.language() + "):"));
        T.install(null);
    }
}
"""


MSG_STUB = """package com.jjricks.volt;
final class Msg {
    final int id; final boolean plural; final long quantity; final Object[] args;
    private Msg(int id, boolean plural, long quantity, Object[] args) { this.id = id; this.plural = plural; this.quantity = quantity; this.args = args; }
    static Msg of(int id, Object... args) { return new Msg(id, false, 0, args); }
    static Msg plural(int id, long quantity, Object... args) { return new Msg(id, true, quantity, args); }
    String render() { return plural ? T.renderPlural(id, quantity, args) : T.render(id, args); }
}
"""

R_STUB = """package com.jjricks.volt;
final class R {
    static final class string { static final int app_name = 1, ui_lock = 2, ui_lock_short = 3, dash_move_widget = 4, fmt_percent = 5,
        fmt_distance = 6, common_dont = 7, common_wrapped = 8, raw_percent = 9, l10n_tag = 10; }
    static final class plurals { static final int backup_scooters_skipped = 11, rides_count = 12; }
}
"""

RESOURCES_STUB = """package android.content.res;
/** Resources as ResourcesImpl.getQuantityText behaves: the item the first locale's rules pick, else "other". */
public class Resources {
    public static java.util.Locale first = java.util.Locale.forLanguageTag("en-US");
    public static String tag = "en";
    /** When set, every plural has these items (quantity -> raw text), as the resolved folder holds them. */
    public static java.util.Map<String, String> items;
    public Configuration getConfiguration() { return new Configuration(first); }
    public String getString(int id) { return id == 4 ? "Move %1$s to slot %2$d" : id == 10 ? tag : "res:" + id; }
    public String getQuantityString(int id, int quantity) {
        if (items == null) return id == 11 ? "%1$d skipped (q=" + quantity + ")" : "res:" + id;
        String item = items.get(android.icu.text.PluralRules.forLocale(first).select(quantity));
        return item != null ? item : items.get("other");
    }
}
"""

ANDROID_STUBS = {
    ("android", "content", "res", "Configuration.java"): """package android.content.res;
public class Configuration {
    private final android.os.LocaleList locales;
    public Configuration(java.util.Locale first) { locales = new android.os.LocaleList(first); }
    public android.os.LocaleList getLocales() { return locales; }
}
""",
    ("android", "os", "LocaleList.java"): """package android.os;
public class LocaleList {
    private final java.util.Locale first;
    public LocaleList(java.util.Locale first) { this.first = first; }
    public java.util.Locale get(int index) { return index == 0 ? first : null; }
}
""",
    # CLDR's integer rules (operands of the absolute value, as ICU reads them) for the languages the tests use.
    ("android", "icu", "text", "PluralRules.java"): """package android.icu.text;
public final class PluralRules {
    private final String language;
    private PluralRules(String language) { this.language = language; }
    public static PluralRules forLocale(java.util.Locale locale) { return new PluralRules(locale.getLanguage()); }
    public String select(double number) {
        long n = (long) Math.abs(number), d = n % 10, h = n % 100;
        switch (language) {
            case "en": case "de": case "sv": case "nl": case "it": return n == 1 ? "one" : "other";
            case "th": case "ja": case "zh": case "ko": case "vi": case "id": case "in": return "other";
            case "ru": case "uk": return d == 1 && h != 11 ? "one" : d >= 2 && d <= 4 && (h < 12 || h > 14) ? "few" : "many";
            case "pl": return n == 1 ? "one" : d >= 2 && d <= 4 && (h < 12 || h > 14) ? "few" : "many";
            case "cs": return n == 1 ? "one" : n >= 2 && n <= 4 ? "few" : "other";
            case "lv": return d == 0 || (h >= 11 && h <= 19) ? "zero" : d == 1 && h != 11 ? "one" : "other";
            case "ar": return n == 0 ? "zero" : n == 1 ? "one" : n == 2 ? "two" : h >= 3 && h <= 10 ? "few" : h >= 11 ? "many" : "other";
            case "es": case "fr": case "pt": return n == 1 ? "one" : n != 0 && n % 1000000 == 0 ? "many" : "other";
            default: throw new IllegalArgumentException("no test rules for " + language);
        }
    }
}
""",
}


def write_android_stubs(out):
    l10nlib.write_text(os.path.join(out, "android", "content", "res", "Resources.java"), RESOURCES_STUB)
    for parts, text in ANDROID_STUBS.items():
        l10nlib.write_text(os.path.join(out, *parts), text)

PROBE = """package com.jjricks.volt;
public final class Probe {
    public static void main(String[] args) {
        System.out.println(T.appName());
        System.out.println(T.dashMoveWidget("Speed", 3));
        System.out.println(T.fmtPercent("75"));
        System.out.println(T.fmtDistance(12.25));
        System.out.println(T.commonDont());
        System.out.println(T.commonWrapped());
        System.out.println(T.rawPercent());
        System.out.println(T.backupScootersSkipped(1, 1));
        System.out.println(T.backupScootersSkipped(2, 2));
        System.out.println(T.ridesCount(1, 1));
        System.out.println(T.ridesCount(0, 0));
        Msg later = M.dashMoveWidget("Range", 4);
        System.out.println("deferred: " + later.render());
        T.install(new AndroidText(new android.content.res.Resources()));
        System.out.println("android: " + T.dashMoveWidget("Speed", 3) + "|res:" + T.backupScootersSkipped(2, 2) + "|" + T.language());
        T.install(null);
        System.out.println(T.language());
    }
}
"""


if __name__ == "__main__":
    unittest.main()
