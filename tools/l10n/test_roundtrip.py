"""G3 tests against fixtures captured from the real aapt 36.1.0 (tools/l10n/fixtures/aapt/capture.sh)."""

import contextlib
import io
import os
import shutil
import sys
import tempfile
import types
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_catalog  # noqa: E402
import l10nlib  # noqa: E402
import roundtrip  # noqa: E402

AAPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures", "aapt")


QUALIFIED = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures", "aapt-qualified")


def qualified_bytes(name):
    with open(os.path.join(QUALIFIED, name), "rb") as handle:
        return handle.read()


def fixture_bytes(name):
    with open(os.path.join(AAPT, name), "rb") as handle:
        return handle.read()



def mock_value(text):
    return types.SimpleNamespace(text=text)


def mock_entry(type_name, config, name, value=None, bag=None):
    return types.SimpleNamespace(type_name=type_name, config=config, name=name, value=value, bag=bag)


class FixtureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.entries = roundtrip.read_arsc(fixture_bytes("resources.arsc"))
        cls.compiled, problems = roundtrip.compiled_english(cls.entries)
        assert not problems, problems
        cls.catalog, _ = gen_catalog.read_strings_file(os.path.join(AAPT, "res", "values", "strings.xml"))

    def test_fixture_was_captured_with_the_pinned_aapt(self):
        self.assertIn("v0.2-14042983", l10nlib.read_text(os.path.join(AAPT, "aapt-version.txt")))

    def test_arsc_reader_agrees_with_aapt_dump(self):
        dumped = roundtrip.read_dump(l10nlib.read_text(os.path.join(AAPT, "dump.txt")))
        self.assertEqual(sorted(dumped), sorted(self.compiled))
        for key, value in self.compiled.items():
            self.assertEqual(dumped[key].text, value.text, key)

    def test_every_catalog_item_was_compiled(self):
        keys = set()
        for entry in self.catalog:
            for quantity in entry.items:
                keys.add(("plural" if entry.kind == "plural" else "string", entry.name, quantity))
        self.assertEqual(keys, set(self.compiled))

    def test_accepted_spellings_compile_exactly_and_refused_ones_are_rewritten(self):
        """The generator's lint and real aapt agree on every fixture item."""
        checked = 0
        for entry in self.catalog:
            for quantity, item in entry.items.items():
                key = ("plural" if entry.kind == "plural" else "string", entry.name, quantity)
                actual = self.compiled[key].text
                if item.problems:
                    self.assertTrue(entry.name.startswith("bad_"), "%s refused: %s" % (key, item.problems))
                    self.assertNotEqual(actual, item.text, "%s: aapt kept the text, so the refusal is too strict" % (key,))
                else:
                    self.assertEqual(actual, item.text, "%s: accepted but aapt changed it" % (key,))
                checked += 1
        self.assertGreater(checked, 45)
        bad = [e.name for e in self.catalog if e.name.startswith("bad_")]
        self.assertGreaterEqual(len(bad), 12)

    def test_known_rewrites(self):
        text = {k[1]: v.text for k, v in self.compiled.items() if k[0] == "string"}
        self.assertEqual(text["bad_leading_space"], "leading")
        self.assertEqual(text["bad_double_space"], "two spaces")
        self.assertEqual(text["bad_unquoted_double_space"], "Connecting" + chr(0x2026) + " " + chr(0xFFFC))
        self.assertEqual(text["ok_wrapped_double_space"], "Connecting" + chr(0x2026) + "  " + chr(0xFFFC))
        self.assertEqual(text["bad_unknown_escape"], "ab")
        self.assertEqual(text["bad_partial_quote"], "a b  c d")
        self.assertEqual(text["ok_trailing_unicode_space"], "end ")
        self.assertEqual(text["ok_surrogates"], "Battery " + chr(0x1F50B))
        self.assertEqual(text["ok_nbsp"], "10" + chr(0xA0) + "km")

    def test_compare_flags_each_rewrite_with_a_reason(self):
        expected = {}
        for entry in self.catalog:
            for quantity, item in entry.items.items():
                expected[("plural" if entry.kind == "plural" else "string", entry.name, quantity)] = item.text
        problems = roundtrip.compare(expected, self.compiled)
        flagged = sorted(set(p.split(":")[0] for p in problems))
        self.assertEqual(len(flagged), len(problems))
        self.assertTrue(all("/bad_" in label for label in flagged), flagged)
        reasons = {p.split(":")[0]: p for p in problems}
        self.assertIn("stripped leading or trailing whitespace", reasons["string/bad_leading_space"])
        self.assertIn("collapsed or trimmed whitespace", reasons["string/bad_double_space"])
        self.assertIn("dropped quote marks, apostrophes or escapes", reasons["string/bad_unknown_escape"])
        self.assertIn("plurals/bad_plural_trailing_space[one]", reasons)

    def test_missing_extra_and_non_text(self):
        compiled = {("string", "a", "other"): roundtrip.Value("A"),
                    ("string", "b", "other"): roundtrip.Value("B", styled=True),
                    ("string", "c", "other"): roundtrip.Value(None, False, 0x01, 0x7f020001),
                    ("string", "extra", "other"): roundtrip.Value("x")}
        expected = {("string", "a", "other"): "A", ("string", "b", "other"): "B", ("string", "c", "other"): "C",
                    ("string", "gone", "other"): "G"}
        problems = "\n".join(roundtrip.compare(expected, compiled))
        self.assertIn("string/b: compiled with style spans", problems)
        self.assertIn("string/c: compiled as value type 0x01", problems)
        self.assertIn("string/extra: compiled by aapt but not in the catalog", problems)
        self.assertIn("string/gone: in the catalog but not compiled", problems)
        self.assertNotIn("string/a:", problems)

    def test_pseudo_configurations(self):
        self.assertEqual(roundtrip.configurations(self.entries), ["default"])
        pseudo = roundtrip.read_arsc(fixture_bytes("resources-pseudo.arsc"))
        self.assertEqual(roundtrip.configurations(pseudo), ["ar-rXB", "default", "en-rXA"])
        compiled, _ = roundtrip.compiled_english(pseudo)
        self.assertEqual({k: v.text for k, v in compiled.items()}, {k: v.text for k, v in self.compiled.items()},
                         "pseudo-localization leaves the default English alone")
        xa = [e for e in pseudo if e.config == "en-rXA" and e.name == "ok_plain"][0]
        self.assertNotEqual(xa.value.text, "Lock scooter")

    def test_pseudo_quirks_name_the_key_and_the_cause(self):
        value = mock_value
        entries = [mock_entry("string", "en-rXA", "battery", value=value("[βåţţéŕý »%")),
                   mock_entry("string", "en-rXA", "fine", value=value("[ƒîñé öñé]")),
                   mock_entry("string", "en-rXA", "fine_percent", value=value("[%1$s ƒîñé öñé]")),
                   mock_entry("string", "en-rXA", "cut", value=value("[çûţ")),
                   mock_entry("string", "ar-rXB", "battery", value=value("Battery %")),
                   mock_entry("string", "default", "battery", value=value("Battery %")),
                   mock_entry("plurals", "en-rXA", "n", bag={"one": value("[%1$d öñé]"), "other": value("[%1$d »%")})]
        lines = roundtrip.pseudo_quirks(entries)
        self.assertEqual(len(lines), 3, lines)
        self.assertIn("string/battery en-XA", lines[0])
        self.assertIn("reads its bare %", lines[0])
        self.assertIn("string/cut en-XA", lines[1])
        self.assertIn("not aapt's known bare % case", lines[1])
        self.assertIn("plurals/n[other] en-XA", lines[2])

    def test_cli_with_apk(self):
        work = tempfile.mkdtemp(prefix="l10n-roundtrip-")
        try:
            apk = os.path.join(work, "resources.apk")
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("resources.arsc", fixture_bytes("resources.arsc"))
            good = [e for e in self.catalog if not e.name.startswith("bad_")]
            tsv = os.path.join(work, "en.expected.tsv")
            l10nlib.write_text(tsv, gen_catalog.expected_tsv(good, None))
            out, err = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                # The bad_ entries are compiled but missing from this catalog: extra strings fail.
                self.assertEqual(roundtrip.main(["--apk", apk, "--expected", tsv]), 1)
            self.assertIn("compiled by aapt but not in the catalog", err.getvalue())
            all_items = gen_catalog.expected_tsv(self.catalog, None)
            l10nlib.write_text(tsv, all_items)
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                self.assertEqual(roundtrip.main(["--apk", apk, "--expected", tsv]), 1)
            # A catalog that says what aapt really stored passes.
            rows = ["kind\tname\tquantity\ttext"] + ["%s\t%s\t%s\t%s" % (
                key[0], key[1], key[2] if key[0] == "plural" else "", l10nlib.escape_field(value.text))
                for key, value in sorted(self.compiled.items())]
            l10nlib.write_text(tsv, "\n".join(rows) + "\n")
            out = io.StringIO()
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(roundtrip.main(["--apk", apk, "--expected", tsv]), 0)
            self.assertIn("compiled exactly as the catalog says", out.getvalue())
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertEqual(roundtrip.main(["--configs", "--apk", apk]), 0)
            self.assertEqual(out.getvalue().split(), ["default"])
        finally:
            shutil.rmtree(work, ignore_errors=True)

    def test_text_in_english_or_qualified_configurations_fails(self):
        """Finding 4, on a table real aapt compiled from values-en, values-v31, values-night, values-es and
        values-b+zh+Hans (fixtures/aapt-qualified)."""
        entries = roundtrip.read_arsc(qualified_bytes("resources.arsc"))
        self.assertEqual(roundtrip.configurations(entries), ["b+zh+Hans", "default", "en", "es", "v31", "v8-+other"])
        problems = roundtrip.qualified_text_problems(entries)
        self.assertEqual(sorted(p.split(" has a value in configuration ")[1].split(",")[0] for p in problems),
                         ["en", "en", "v31", "v8-+other"])
        self.assertEqual(roundtrip.qualified_text_problems(self.entries), [])
        pseudo = roundtrip.read_arsc(fixture_bytes("resources-pseudo.arsc"))
        self.assertTrue(roundtrip.qualified_text_problems(pseudo))
        self.assertEqual(roundtrip.qualified_text_problems(pseudo, allow_pseudo=True), [])
        work = tempfile.mkdtemp(prefix="l10n-roundtrip-")
        try:
            arsc = os.path.join(work, "resources.arsc")
            with open(arsc, "wb") as handle:
                handle.write(qualified_bytes("resources.arsc"))
            tsv = os.path.join(work, "en.expected.tsv")
            catalog, _ = gen_catalog.read_strings_file(os.path.join(QUALIFIED, "res", "values", "strings.xml"))
            l10nlib.write_text(tsv, gen_catalog.expected_tsv(catalog, None))
            err = io.StringIO()
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(err):
                # The default English matches; the values-en override still fails the round-trip.
                self.assertEqual(roundtrip.main(["--arsc", arsc, "--expected", tsv]), 1)
                self.assertEqual(roundtrip.main(["--guard", "--arsc", arsc]), 1)
            self.assertIn("string/units_dual has a value in configuration en,", err.getvalue())
        finally:
            shutil.rmtree(work, ignore_errors=True)

    def test_dump_agrees_on_the_qualified_configurations(self):
        dump = l10nlib.read_text(os.path.join(QUALIFIED, "dump.txt"))
        for config in ("config en:", "config v31:", "config night-v8:", "config es:", "config b+zh+Hans:"):
            self.assertIn(config, dump)

    def test_not_a_table(self):
        with self.assertRaises(roundtrip.ArscError):
            roundtrip.read_arsc(b"\x03\x00\x08\x00\x08\x00\x00\x00")


if __name__ == "__main__":
    unittest.main()
