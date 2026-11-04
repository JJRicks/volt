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


def find_icu4j():
    for jar in (os.environ.get("VOLT_ICU4J_JAR"), "/Applications/Android Studio.app/Contents/lib/intellij.libraries.icu4j.jar"):
        if jar and os.path.isfile(jar):
            return jar
    return None


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


    def compile_and_run(self, catalog, probes, languages=(), folders=None):
        """Generates the catalog with --android, compiles it with the stubs and probes, runs each probe.
        folders maps a res/values-* folder to its keys (folder_xml)."""
        self.write("strings_test.xml", catalog)
        for name, text in languages:
            l10nlib.write_text(os.path.join(self.dir, "lang", name), text)
        for folder, keys in (folders or {}).items():
            l10nlib.write_text(os.path.join(self.dir, "res", folder, "strings.xml"), folder_xml(keys))
        out = os.path.join(self.dir, "gen")
        gen_catalog.generate(os.path.join(self.dir, "res"), os.path.join(self.dir, "lang"), out, None, True)
        pkg = os.path.join(out, "com", "jjricks", "volt")
        l10nlib.write_text(os.path.join(pkg, "Msg.java"), MSG_STUB)
        l10nlib.write_text(os.path.join(pkg, "R.java"), R_STUB.replace("rides_count = 12;", "rides_count = 12, minutes_left = 13, watts = 14, minutes = 15, days = 16, "
                                                                 "hours = 17;"))
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
    def test_android_plurals_follow_the_folder_android_serves(self):
        """Android picks a plural's item by the configuration's first locale even where it serves another folder's
        text: a Thai phone said "1 minutes left" for the English default, a Russian one "21 minute left". The rules
        now come from the folder Android serves the key from (the build-time folder table), never from comparing
        text: French "0 watt" stays French although English has the same forms (Codex's repro)."""
        catalog = CATALOG.replace('    <plurals name="rides_count">', "".join(
            '    <plurals name="%s">\n        <item quantity="one">%s</item>\n        <item quantity="other">%s</item>\n'
            '    </plurals>\n' % (name, one, other) for name, (one, other) in ENGLISH_PLURALS) + '    <plurals name="rides_count">')
        languages = [(tag + ".tsv", language_tsv(tag, folder, status, plurals))
                     for tag, folder, status, plurals in FOLDER_LANGUAGES]
        out = self.compile_and_run(catalog, {"AndroidProbe": android_probe()}, languages, FOLDER_TEXT)["AndroidProbe"]
        self.assertEqual(out, [
            # Codex's repro: one French folder whose forms equal English's; French CLDR says "one" for 0 and 1.
            "watts fr: 0 watt | 1 watt | 2 watts | 1000000 watts",
            "minutes_left fr: 0 minutes left (Android alone: 0 minute left) | 1 minute left | 2 minutes left",
            # values-pt-rBR (pt-BR rules: one for 0 and 1) and values-pt (pt-PT rules: one for 1 only).
            "days pt-BR: 0 dia BR | 1 dia BR | 2 dias BR | 1000000 de dias BR",
            "days pt-PT: 0 dias PT | 1 dia PT | 2 dias PT | 1000000 de dias PT",
            # Angola is served values-pt, while ICU gives pt-AO Brazil's rules.
            "days pt-AO: 0 dias PT (Android alone: 0 dia PT) | 1 dia PT | 2 dias PT | 1000000 de dias PT",
            "hours pt-BR: 0 hora BR | 1 hora BR | 2 horas BR",
            # values-pt lacks the key, so Android serves values-pt-rBR's text (same script): Brazilian rules.
            "hours pt-PT: 0 hora BR (Android alone: 0 horas BR) | 1 hora BR | 2 horas BR",
            # A shipped Japanese folder and an unshipped Russian one that leave the key out serve the English
            # default: English rules, not their own.
            "minutes_left ja (Volt in ja): 0 minutes left | 1 minute left (Android alone: 1 minutes left) | 2 minutes left"
            " | 5 minutes left | 21 minutes left | 22 minutes left | 101 minutes left",
            "minutes_left ru (Volt in en): 0 minutes left | 1 minute left | 2 minutes left | 5 minutes left | 21 minutes left"
            " (Android alone: 21 minute left) | 22 minutes left | 101 minutes left (Android alone: 101 minute left)",
            # The unshipped Russian folder's own text keeps Russian rules.
            "minutes ru (Volt in en): 1 ONE | 2 FEW | 5 MANY | 21 ONE | 22 FEW",
            "minutes_left en-US: 0 minutes left | 1 minute left | 2 minutes left | 5 minutes left | 21 minutes left"
            " | 22 minutes left | 101 minutes left",
            "minutes_left th: 0 minutes left | 1 minute left (Android alone: 1 minutes left) | 2 minutes left | 5 minutes left"
            " | 21 minutes left | 22 minutes left | 101 minutes left",
            "minutes_left lv: 0 minutes left | 1 minute left | 2 minutes left | 5 minutes left | 21 minutes left"
            " (Android alone: 21 minute left) | 22 minutes left | 101 minutes left (Android alone: 101 minute left)",
            "minutes_left ar: 0 minutes left | 1 minute left | 2 minutes left | 5 minutes left | 21 minutes left"
            " | 22 minutes left | 101 minutes left",
            # Several languages: Android moves the first one Volt has a folder for (or English) to the front.
            "minutes th es: 1 minuto | 2 minutos | 21 minutos | 1000000 de minutos",
            "minutes ja es: 1 minute (Android alone: 1 minutes) | 2 minutes | 21 minutes | 1000000 minutes",
            "minutes es fr: 1 minuto | 2 minutos | 1000000 de minutos",
            # A key the first language's folder lacks comes from res/values, not from the second language.
            "watts es fr: 0 watts | 1 watt | 2 watts",
            "minutes ja ru: 1 minute (Android alone: 1 minutes) | 2 minutes | 21 minutes",
            "watts fr es: 0 watt | 1 watt | 2 watts",
            "minutes en-US es: 1 minute | 2 minutes | 1000000 minutes",
            # Pseudo-locale folders are not in the table: their text is not the table's, so Android's choice stands.
            "minutes_left en-XA: [0 mmminutes left] | [1 mmminute left] | [2 mmminutes left]",
            "minutes_left ar-XB: <0 setunim> | <1 etunim> | <2 setunim> | <3 setunim> | <11 setunim> | <100 setunim>",
            # English phones: every quantity from 0 to 1000000 of every key asks Android exactly what it always did.
            "en-US: 5000005 quantities served as before",
            "en-GB: 5000005 quantities served as before",
            "en-IN: 5000005 quantities served as before"])

    def test_plural_folder_table(self):
        """The folder table AndroidText resolves plurals with, and the folders it refuses to guess about."""
        self.assertEqual(gen_catalog.folder_locale("values-pt-rBR"), ("pt", "", "BR"))
        self.assertEqual(gen_catalog.folder_locale("values-b+zh+Hant+TW"), ("zh", "Hant", "TW"))
        self.assertEqual(gen_catalog.folder_locale("values-in"), ("id", "", ""))
        for folder in ("values-night", "values-es-night", "values-b+es+x"):
            self.assertIsNone(gen_catalog.folder_locale(folder), folder)
        self.assertIn("region other regions inherit from", gen_catalog.folder_locale_problems("values-b+es+419")[0])
        self.assertIn("region other regions inherit from", gen_catalog.folder_locale_problems("values-pt-rPT")[0])
        self.assertEqual(gen_catalog.folder_locale_problems("values-pt-rBR"), [])
        self.write("strings_test.xml", CATALOG)
        res = os.path.join(self.dir, "res")
        for tag, folder, status, plurals in FOLDER_LANGUAGES:
            l10nlib.write_text(os.path.join(self.dir, "lang", tag + ".tsv"), language_tsv(tag, folder, status, plurals))
        l10nlib.write_text(os.path.join(res, "values-fr", "strings.xml"), folder_xml({
            "l10n_tag": "fr", "rides_count": {"one": "Une course", "other": "%1$d courses"},
            "backup_scooters_skipped": {"one": "Don't", "other": "%1$d"}, "not_english": {"other": "x"}}))
        languages = gen_catalog.load_languages(os.path.join(self.dir, "lang"))
        entries, _ = self.load()
        folders, held = gen_catalog.load_folder_plurals(res, languages, entries)
        self.assertEqual([f.name for f in folders], ["values-fr"])
        self.assertEqual(held["rides_count"], [(0, [("one", "Une course"), ("other", "%1$d courses")])])
        # aapt would reject the unescaped apostrophe: no text, so AndroidText keeps Android's choice.
        self.assertEqual(held["backup_scooters_skipped"], [(0, [("one", None), ("other", "%1$d")])])
        self.assertNotIn("not_english", held)
        java = gen_catalog.gen_android(entries, None, "com.jjricks.volt", folders, held)
        self.assertIn('FOLDER_TAGS = {"fr"};', java)
        self.assertIn('HELD_TEXTS[%d] = new String[][]{{null, "%%1$d"}};' % [e.name for e in entries].index(
            "backup_scooters_skipped"), java)
        # Two Brazilian-style regions without the region-less folder: Android ranks them by CLDR distance.
        l10nlib.write_text(os.path.join(self.dir, "lang", "pt-AO.tsv"), language_tsv("pt-AO", "values-pt-rAO", "beta",
                                                                                     "one,many,other"))
        for folder in ("values-pt-rBR", "values-pt-rAO"):
            l10nlib.write_text(os.path.join(res, folder, "strings.xml"), folder_xml({"rides_count": {"other": "%1$d"}}))
        languages = gen_catalog.load_languages(os.path.join(self.dir, "lang"))
        with self.assertRaises(gen_catalog.CatalogError) as caught:
            gen_catalog.load_folder_plurals(res, languages, entries)
        self.assertIn("rides_count is in values-pt-rAO, values-pt-rBR but not in their language's folder without "
                      "a region", caught.exception.problems[0])
        l10nlib.write_text(os.path.join(res, "values-pt", "strings.xml"), folder_xml({"rides_count": {"other": "%1$d"}}))
        self.assertEqual(len(gen_catalog.load_folder_plurals(res, languages, entries)[0]), 4)
        l10nlib.write_text(os.path.join(self.dir, "lang", "es-419.tsv"), language_tsv("es-419", "values-b+es+419", "beta",
                                                                                      "one,many,other"))
        with self.assertRaises(gen_catalog.CatalogError) as caught:
            gen_catalog.load_languages(os.path.join(self.dir, "lang"))
        self.assertIn("values-b+es+419 names a region other regions inherit from", caught.exception.problems[0])

    @unittest.skipUnless(find_javac() and find_icu4j(), "javac or ICU4J not found")
    def test_plural_rule_stub_is_cldr(self):
        """The ICU stub the tests run under gives real ICU's plural categories and likely scripts (ICU4J from
        Android Studio, or VOLT_ICU4J_JAR), so the expectations above are CLDR's, not the stub's."""
        out = os.path.join(self.dir, "icu")
        write_android_stubs(out)
        l10nlib.write_text(os.path.join(out, "IcuCheck.java"), ICU_CHECK)
        bin_dir, jar = find_javac(), find_icu4j()
        classes = os.path.join(self.dir, "icu-classes")
        sources = [os.path.join(d, n) for d, _, names in os.walk(out) for n in names if n.endswith(".java")]
        compiled = subprocess.run([os.path.join(bin_dir, "javac"), "-encoding", "UTF-8", "-cp", jar, "-d", classes] + sources,
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        self.assertEqual(compiled.returncode, 0, compiled.stdout.decode("utf-8", "replace"))
        run = subprocess.run([os.path.join(bin_dir, "java"), "-cp", classes + os.pathsep + jar, "IcuCheck"],
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        self.assertEqual(run.stdout.decode("utf-8", "replace").splitlines()[-1:], ["ok"], run.stdout.decode("utf-8", "replace"))


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

# The English plurals the Android probe adds to CATALOG: (one, other).
ENGLISH_PLURALS = (("minutes_left", ("%1$d minute left", "%1$d minutes left")),
                   ("watts", ("%1$d watt", "%1$d watts")),
                   ("minutes", ("%1$d minute", "%1$d minutes")),
                   ("days", ("%1$d day", "%1$d days")),
                   ("hours", ("%1$d hour", "%1$d hours")))
# (tag, folder, status, plurals) of the probe's languages; Russian never shipped.
FOLDER_LANGUAGES = (("ja", "values-ja", "beta", "other"), ("ru", "values-ru", "draft", "one,few,many,other"),
                    ("es", "values-es", "beta", "one,many,other"), ("fr", "values-fr", "beta", "one,many,other"),
                    ("pt-PT", "values-pt", "beta", "one,many,other"), ("pt-BR", "values-pt-rBR", "beta", "one,many,other"))
# What each language folder holds. Japanese leaves every plural out, Spanish leaves watts out, French has watts
# with English's very forms, and Brazil alone has hours.
FOLDER_TEXT = {
    "values-ja": {"l10n_tag": "ja"},
    "values-ru": {"l10n_tag": "ru", "minutes": {"one": "%1$d ONE", "few": "%1$d FEW", "many": "%1$d MANY", "other": "%1$d OTHER"}},
    "values-es": {"l10n_tag": "es", "minutes": {"one": "%1$d minuto", "many": "%1$d de minutos", "other": "%1$d minutos"}},
    "values-fr": {"l10n_tag": "fr", "watts": {"one": "%1$d watt", "other": "%1$d watts"}},
    "values-pt": {"l10n_tag": "pt-PT", "days": {"one": "%1$d dia PT", "many": "%1$d de dias PT", "other": "%1$d dias PT"}},
    "values-pt-rBR": {"l10n_tag": "pt-BR", "days": {"one": "%1$d dia BR", "many": "%1$d de dias BR", "other": "%1$d dias BR"},
                      "hours": {"one": "%1$d hora BR", "other": "%1$d horas BR"}},
}
# What aapt --pseudo-localize adds in a VOLT_PSEUDO_LOCALES=1 build; never in res/, so never in the folder table.
PSEUDO_TEXT = {
    "en-XA": {"minutes_left": {"one": "[%1$d mmminute left]", "other": "[%1$d mmminutes left]"}},
    "ar-XB": {"minutes_left": {"one": "<%1$d etunim>", "other": "<%1$d setunim>"}},
}


def language_tsv(tag, folder, status, plurals):
    return (LANG.replace("\ten\n", "\t%s\n" % tag, 1).replace("values\n", folder + "\n").replace("English", tag)
            .replace("stable", status).replace("one,other", plurals))


def _xml_text(text):
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def folder_xml(keys):
    out = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for name, value in keys.items():
        if isinstance(value, str):
            out.append('    <string name="%s">%s</string>' % (name, _xml_text(value)))
        else:
            out.append('    <plurals name="%s">' % name)
            out.extend('        <item quantity="%s">%s</item>' % (q, _xml_text(t)) for q, t in value.items())
            out.append("    </plurals>")
    return "\n".join(out + ["</resources>", ""])


def stub_folder(folder):
    """values-pt-rBR -> pt-BR, the locale the Resources stub keys a folder by ("" for res/values)."""
    language, script, region = gen_catalog.folder_locale(folder)
    return "-".join(p for p in (language, script, region) if p)


def android_probe():
    def java(value):
        return gen_catalog.java_string(value)
    data = ['put("", "l10n_tag", "other", "en");']
    for name, (one, other) in ENGLISH_PLURALS:
        data.append('put("", %s, "one", %s); put("", %s, "other", %s);' % (java(name), java(one), java(name), java(other)))
    folders = [(stub_folder(f), keys) for f, keys in sorted(FOLDER_TEXT.items())] + sorted(PSEUDO_TEXT.items())
    for folder, keys in folders:
        for name, value in keys.items():
            for quantity, text in ({"other": value} if isinstance(value, str) else value).items():
                data.append("put(%s, %s, %s, %s);" % (java(folder), java(name), java(quantity), java(text)))
    for name, _ in ENGLISH_PLURALS:
        data.append("key(%s, R.plurals.%s, T.%s);" % (java(name), name, name.upper()))
    return ANDROID_PROBE.replace("        // DATA\n", "".join("        %s\n" % line for line in data))


ANDROID_PROBE = """package com.jjricks.volt;
import android.content.res.Resources;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
public final class AndroidProbe {
    static final Map<String, Integer> RIDS = new LinkedHashMap<>();
    static final Map<String, Integer> TIDS = new LinkedHashMap<>();
    static void put(String folder, String key, String quantity, String text) {
        Resources.folders.computeIfAbsent(folder, f -> new LinkedHashMap<>()).computeIfAbsent(key, k -> new HashMap<>()).put(quantity, text);
    }
    static void key(String name, int rid, int tid) { RIDS.put(name, rid); TIDS.put(name, tid); Resources.names.put(rid, name); }
    static {
        Resources.names.put(R.string.l10n_tag, "l10n_tag");
        // DATA
    }
    static void install(String phones) {
        Resources.phone = phones.split(" ");
        T.install(new AndroidText(new Resources()));
    }
    static String line(String key, String phones, long... quantities) {
        install(phones);
        Resources resources = new Resources();
        StringBuilder out = new StringBuilder(key + " " + phones + ":");
        for (int i = 0; i < quantities.length; i++) {
            long q = quantities[i];
            String shown = T.renderPlural(TIDS.get(key), q, q);
            String alone = String.format(Locale.ROOT, resources.getQuantityString(RIDS.get(key), (int) q), q);
            out.append(i == 0 ? " " : " | ").append(shown).append(shown.equals(alone) ? "" : " (Android alone: " + alone + ")");
        }
        return out.toString();
    }
    static String volt(String line) { return line.replaceFirst(":", " (Volt in " + T.language() + "):"); }
    /** Every quantity from 0 to 1000000 of every key: the English text, through exactly one call with that quantity. */
    static String sweep(String phone) {
        install(phone);
        long checked = 0;
        for (Map.Entry<String, Integer> key : TIDS.entrySet()) {
            int id = key.getValue();
            for (long q = 0; q <= 1000000; q++) {
                Resources.asked.clear();
                String raw = T.source().plural(id, q);
                if (!raw.equals(EnglishText.INSTANCE.plural(id, q))) return phone + ": " + key.getKey() + " " + q + " serves " + raw;
                if (Resources.asked.size() != 1 || Resources.asked.get(0) != q)
                    return phone + ": " + key.getKey() + " " + q + " asks Android for " + Resources.asked;
                checked++;
            }
        }
        return phone + ": " + checked + " quantities served as before";
    }
    public static void main(String[] args) {
        long[] few = {0, 1, 2, 5, 21, 22, 101};
        long[] big = {0, 1, 2, 1000000};
        System.out.println(line("watts", "fr", big));
        System.out.println(line("minutes_left", "fr", 0, 1, 2));
        System.out.println(line("days", "pt-BR", big));
        System.out.println(line("days", "pt-PT", big));
        System.out.println(line("days", "pt-AO", big));
        System.out.println(line("hours", "pt-BR", 0, 1, 2));
        System.out.println(line("hours", "pt-PT", 0, 1, 2));
        System.out.println(volt(line("minutes_left", "ja", few)));
        System.out.println(volt(line("minutes_left", "ru", few)));
        System.out.println(volt(line("minutes", "ru", 1, 2, 5, 21, 22)));
        for (String phone : new String[]{"en-US", "th", "lv", "ar"}) System.out.println(line("minutes_left", phone, few));
        System.out.println(line("minutes", "th es", 1, 2, 21, 1000000));
        System.out.println(line("minutes", "ja es", 1, 2, 21, 1000000));
        System.out.println(line("minutes", "es fr", 1, 2, 1000000));
        System.out.println(line("watts", "es fr", 0, 1, 2));
        System.out.println(line("minutes", "ja ru", 1, 2, 21));
        System.out.println(line("watts", "fr es", 0, 1, 2));
        System.out.println(line("minutes", "en-US es", 1, 2, 1000000));
        System.out.println(line("minutes_left", "en-XA", 0, 1, 2));
        System.out.println(line("minutes_left", "ar-XB", 0, 1, 2, 3, 11, 100));
        for (String phone : new String[]{"en-US", "en-GB", "en-IN"}) System.out.println(sweep(phone));
        T.install(null);
    }
}
"""

ICU_CHECK = """import java.util.Locale;
public final class IcuCheck {
    public static void main(String[] args) {
        String[] tags = {"en", "en-GB", "en-IN", "de", "sv", "nl", "it", "es", "fr", "pt", "pt-PT", "pt-BR", "pt-AO", "th", "ja",
                "zh", "ko", "vi", "id", "ru", "uk", "pl", "cs", "lv", "ar"};
        long[] extra = {1000000, 2000000, 21000000, 1000000000L};
        int bad = 0;
        for (String tag : tags) {
            Locale locale = Locale.forLanguageTag(tag);
            android.icu.text.PluralRules stub = android.icu.text.PluralRules.forLocale(locale);
            com.ibm.icu.text.PluralRules real = com.ibm.icu.text.PluralRules.forLocale(locale);
            for (long q = 0; q < 2100 + extra.length; q++) {
                long n = q < 2100 ? q : extra[(int) (q - 2100)];
                if (!stub.select(n).equals(real.select(n))) { bad++; System.out.println(tag + " " + n + ": stub " + stub.select(n) + ", ICU " + real.select(n)); }
            }
        }
        String[] scripts = {"en", "en_US", "en_XA", "fr", "es", "pt", "pt_PT", "pt_BR", "pt_AO", "ja", "ru", "th", "lv", "ar", "ar_XB",
                "zh", "zh_TW", "zh_HK"};
        for (String tag : scripts) {
            String stub = android.icu.util.ULocale.addLikelySubtags(new android.icu.util.ULocale(tag)).getScript();
            String real = com.ibm.icu.util.ULocale.addLikelySubtags(new com.ibm.icu.util.ULocale(tag)).getScript();
            if (!stub.equals(real)) { bad++; System.out.println(tag + ": stub script " + stub + ", ICU " + real); }
        }
        System.out.println(bad == 0 ? "ok" : bad + " mismatch(es)");
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
/**
 * Resources as Android 7 to 14 resolve them for an app whose locales_config names no defaultLocale.
 * ResourcesImpl.updateConfiguration moves the first locale of the list that the app has resources for (or English)
 * to the front (LocaleList.getFirstMatchWithEnglishSupported); every resource then resolves with that one locale
 * (ResTable_config::match and isLocaleBetterThan; a key no matching folder holds comes from res/values); and a
 * plural's item is the one that locale's rules pick, else "other" (ResourcesImpl.getQuantityText).
 * With no folders set, the fixed answers the generator's compile test expects.
 */
public class Resources {
    /** The phone's (or the per-app) language list. */
    public static String[] phone = {"en-US"};
    /** Folder locale ("" for res/values, "pt-BR" for values-pt-rBR) -> key -> quantity -> raw text. */
    public static final Map<String, Map<String, Map<String, String>>> folders = new LinkedHashMap<>();
    /** Resource id -> key. */
    public static final Map<Integer, String> names = new HashMap<>();
    /** The quantities getQuantityString was asked for. */
    public static final List<Integer> asked = new ArrayList<>();
    private static final Map<String, Locale> LOCALES = new HashMap<>();
    private final Configuration configuration;

    public Resources() {
        Locale[] list = new Locale[phone.length];
        for (int i = 0; i < list.length; i++) list[i] = Locale.forLanguageTag(phone[i]);
        configuration = new Configuration(new android.os.LocaleList(folders.isEmpty() ? list : front(list)));
    }
    public Configuration getConfiguration() { return configuration; }

    private static Locale locale(String tag) { return LOCALES.computeIfAbsent(tag, Locale::forLanguageTag); }
    private static boolean pseudo(Locale l) { return l.toLanguageTag().equals("en-XA") || l.toLanguageTag().equals("ar-XB"); }
    /** The script ResTable_config computes: LocaleDataTables gives the pseudo-locales scripts of their own. */
    static String script(Locale l) {
        if (pseudo(l)) return l.getLanguage().equals("en") ? "~~~A" : "~~~B";
        if (!l.getScript().isEmpty()) return l.getScript();
        String tag = l.getCountry().isEmpty() ? l.getLanguage() : l.getLanguage() + "_" + l.getCountry();
        return android.icu.util.ULocale.addLikelySubtags(new android.icu.util.ULocale(tag)).getScript();
    }
    /** LocaleList.matchScore: same language and likely script (never a pseudo-locale unless equal). */
    private static boolean matches(Locale supported, Locale desired) {
        if (supported.equals(desired)) return true;
        if (!supported.getLanguage().equals(desired.getLanguage()) || pseudo(supported) || pseudo(desired)) return false;
        return script(supported).equals(script(desired));
    }
    private static Locale[] front(Locale[] list) {
        if (list.length < 2) return list;
        List<Locale> supported = new ArrayList<>();
        supported.add(Locale.forLanguageTag("en-Latn"));
        for (String folder : folders.keySet()) if (!folder.isEmpty()) supported.add(locale(folder));
        int best = Integer.MAX_VALUE;
        for (Locale s : supported) for (int i = 0; i < list.length; i++) if (matches(s, list[i])) { best = Math.min(best, i); break; }
        if (best == Integer.MAX_VALUE || best == 0) return list;
        Locale[] out = new Locale[list.length];
        out[0] = list[best];
        for (int i = 0, j = 1; i < list.length; i++) if (i != best) out[j++] = list[i];
        return out;
    }
    /** The folder a key resolves to under the configuration's locale. */
    private Map<String, String> resolve(String key) {
        Locale l = configuration.getLocales().get(0);
        String best = "";
        int bestRank = -1;
        for (Map.Entry<String, Map<String, Map<String, String>>> folder : folders.entrySet()) {
            if (folder.getKey().isEmpty() || !folder.getValue().containsKey(key)) continue;
            Locale f = locale(folder.getKey());
            if (!f.getLanguage().equals(l.getLanguage()) || !script(f).equals(script(l))) continue;
            // localeDataCompareRegions, for folders whose regions have no CLDR parent but the language.
            int rank = f.getCountry().equals(l.getCountry()) ? 2 : f.getCountry().isEmpty() ? 1 : 0;
            if (rank > bestRank) { best = folder.getKey(); bestRank = rank; }
        }
        return folders.get(best).get(key);
    }
    public String getString(int id) {
        if (folders.isEmpty()) return id == 4 ? "Move %1$s to slot %2$d" : id == 10 ? "en" : "res:" + id;
        return resolve(names.get(id)).get("other");
    }
    public String getQuantityString(int id, int quantity) {
        asked.add(quantity);
        if (folders.isEmpty()) return id == 11 ? "%1$d skipped (q=" + quantity + ")" : "res:" + id;
        Map<String, String> items = resolve(names.get(id));
        String item = items.get(android.icu.text.PluralRules.forLocale(configuration.getLocales().get(0)).select(quantity));
        return item != null ? item : items.get("other");
    }
}
"""

ANDROID_STUBS = {
    ("android", "content", "res", "Configuration.java"): """package android.content.res;
public class Configuration {
    private final android.os.LocaleList locales;
    public Configuration(android.os.LocaleList locales) { this.locales = locales; }
    public android.os.LocaleList getLocales() { return locales; }
}
""",
    ("android", "os", "LocaleList.java"): """package android.os;
public class LocaleList {
    private final java.util.Locale[] list;
    public LocaleList(java.util.Locale... list) { this.list = list.clone(); }
    public java.util.Locale get(int index) { return index >= 0 && index < list.length ? list[index] : null; }
    public int size() { return list.length; }
}
""",
    # CLDR's integer rules (operands of the absolute value, as ICU reads them) for the languages the tests use;
    # test_plural_rule_stub_is_cldr compares them with real ICU.
    ("android", "icu", "text", "PluralRules.java"): """package android.icu.text;
public final class PluralRules {
    private final String language, region;
    private PluralRules(String language, String region) { this.language = language; this.region = region; }
    public static PluralRules forLocale(java.util.Locale locale) { return new PluralRules(locale.getLanguage(), locale.getCountry()); }
    public String select(double number) {
        long n = (long) Math.abs(number), d = n % 10, h = n % 100;
        boolean million = n != 0 && n % 1000000 == 0;
        switch (language) {
            case "en": case "de": case "sv": case "nl": return n == 1 ? "one" : "other";
            case "es": case "it": return n == 1 ? "one" : million ? "many" : "other";
            case "fr": return n <= 1 ? "one" : million ? "many" : "other";
            case "pt": return (region.equals("PT") ? n == 1 : n <= 1) ? "one" : million ? "many" : "other";
            case "th": case "ja": case "zh": case "ko": case "vi": case "id": case "in": return "other";
            case "ru": case "uk": return d == 1 && h != 11 ? "one" : d >= 2 && d <= 4 && (h < 12 || h > 14) ? "few" : "many";
            case "pl": return n == 1 ? "one" : d >= 2 && d <= 4 && (h < 12 || h > 14) ? "few" : "many";
            case "cs": return n == 1 ? "one" : n >= 2 && n <= 4 ? "few" : "other";
            case "lv": return d == 0 || (h >= 11 && h <= 19) ? "zero" : d == 1 && h != 11 ? "one" : "other";
            case "ar": return n == 0 ? "zero" : n == 1 ? "one" : n == 2 ? "two" : h >= 3 && h <= 10 ? "few" : h >= 11 ? "many" : "other";
            default: throw new IllegalArgumentException("no test rules for " + language);
        }
    }
}
""",
    # ICU's likely script for the languages the tests use (addLikelySubtags only fills the script here).
    ("android", "icu", "util", "ULocale.java"): """package android.icu.util;
public final class ULocale {
    private final String language, script, region;
    public ULocale(String id) {
        String[] parts = id.split("_");
        String s = "", r = "";
        for (int i = 1; i < parts.length; i++) if (parts[i].length() == 4) s = parts[i]; else r = parts[i];
        language = parts[0]; script = s; region = r;
    }
    private ULocale(String language, String script, String region) { this.language = language; this.script = script; this.region = region; }
    public static ULocale addLikelySubtags(ULocale l) {
        if (!l.script.isEmpty()) return l;
        String s;
        switch (l.language) {
            case "zh": s = l.region.equals("TW") || l.region.equals("HK") || l.region.equals("MO") ? "Hant" : "Hans"; break;
            case "ja": s = "Jpan"; break;
            case "ko": s = "Kore"; break;
            case "th": s = "Thai"; break;
            case "ru": case "uk": s = "Cyrl"; break;
            case "ar": s = "Arab"; break;
            case "en": case "de": case "sv": case "nl": case "it": case "es": case "fr": case "pt": case "pl": case "cs":
            case "lv": case "vi": case "id": case "in": s = "Latn"; break;
            default: throw new IllegalArgumentException("no test script for " + l.language);
        }
        return new ULocale(l.language, s, l.region);
    }
    public String getScript() { return script; }
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
