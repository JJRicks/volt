"""pseudo_fix.py against tables captured from the real aapt 36.1.0 (tools/l10n/fixtures/aapt/capture.sh)."""

import contextlib
import io
import os
import shutil
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pseudo_fix  # noqa: E402
import roundtrip  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
FIXTURE = os.path.join(HERE, "fixtures", "aapt-pseudo-percent", "resources-pseudo.arsc")
SHARED = os.path.join(HERE, "fixtures", "aapt-pseudo-percent-shared", "resources-pseudo.arsc")


def values(entries):
    """{(config, name, quantity): text} of a compiled table."""
    out = {}
    for entry in entries:
        if entry.value is not None:
            out[(entry.config, entry.name, "other")] = entry.value.text
        for quantity, value in (entry.bag or {}).items():
            out[(entry.config, entry.name, quantity)] = value.text
    return out


class PseudoFixTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(FIXTURE, "rb") as handle:
            cls.raw = handle.read()
        cls.fixed, cls.repaired = pseudo_fix.repair_arsc(cls.raw)
        cls.before = values(roundtrip.read_arsc(cls.raw))
        cls.after = values(roundtrip.read_arsc(cls.fixed))

    def test_aapt_really_mangles_the_fixture(self):
        """If aapt ever stops doing this, the repair has nothing left to do and can go."""
        self.assertEqual(self.before[("en-rXA", "trailing", "other")][:11], "[βåţţéŕý »%")
        self.assertEqual(self.before[("en-rXA", "trailing", "other")][11:], "\0" * 12)
        self.assertEqual(sorted(pseudo_fix.nul_texts(roundtrip.read_arsc(self.raw))), [
            "en-rXA/digits_after", "en-rXA/in_brackets", "en-rXA/plural_percent[one]",
            "en-rXA/plural_percent[other]", "en-rXA/trailing", "en-rXA/two_percents"])

    def test_a_bare_percent_stays_and_no_nul_is_left(self):
        self.assertEqual(pseudo_fix.nul_texts(roundtrip.read_arsc(self.fixed)), [])
        self.assertEqual(len(self.repaired), 6)
        expected = {
            "trailing": "[βåţţéŕý % one two]",
            "in_brackets": "[βåţţéŕý çĥåŕĝé (%) one two three]",
            "digits_after": "[50% 1 one]",
            # The first % is a whole (wrong, but readable) aapt placeholder and stays as aapt wrote it.
            "two_percents": "[»% o«ñļý åñð 100% one two]",
        }
        for name, text in expected.items():
            self.assertEqual(self.after[("en-rXA", name, "other")], text)
        for quantity, word in (("one", "Öñé"), ("other", "Ḿåñý")):
            text = self.after[("en-rXA", "plural_percent", quantity)]
            self.assertTrue(text.startswith("[%s %%" % word) and text.endswith("]"), text)

    def test_nothing_else_changes(self):
        self.assertEqual(len(self.fixed), len(self.raw))
        changed = sorted(k for k in self.before if self.before[k] != self.after[k])
        self.assertEqual(changed, [("en-rXA", "digits_after", "other"), ("en-rXA", "in_brackets", "other"),
                                   ("en-rXA", "plural_percent", "one"), ("en-rXA", "plural_percent", "other"),
                                   ("en-rXA", "trailing", "other"), ("en-rXA", "two_percents", "other")])
        self.assertEqual(self.after[("default", "trailing", "other")], "Battery %")
        self.assertEqual(self.after[("ar-rXB", "trailing", "other")],
                         "‏‮Battery‬‏ ‏‮%‬‏")
        self.assertEqual(self.after[("en-rXA", "letter_after", "other")], "[50»% d«öñé one]")
        self.assertEqual(self.after[("en-rXA", "placeholder_percent", "other")], "[»%1$s«%%]")
        self.assertEqual(pseudo_fix.repair_arsc(self.fixed)[1], [], "a repaired table has nothing left to repair")

    def test_only_a_cut_placeholder_counts(self):
        for raw in (b"[plain]", b"[plain\0\0", "[»%1$s«\0\0".encode("utf-8"),
                    "plain »%\0\0".encode("utf-8"), "[no mark %\0\0".encode("utf-8")):
            self.assertIsNone(pseudo_fix.repaired_utf8(raw), raw)

    def apk(self, table):
        directory = tempfile.mkdtemp(prefix="l10n-pseudo-")
        self.addCleanup(shutil.rmtree, directory, True)
        path = os.path.join(directory, "resources.apk")
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"manifest bytes", zipfile.ZIP_DEFLATED)
            archive.writestr("resources.arsc", table, zipfile.ZIP_STORED)
            archive.writestr("res/x.xml", b"x", zipfile.ZIP_DEFLATED)
        return path

    def run_main(self, path):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = pseudo_fix.main(["--apk", path])
        return code, out.getvalue() + err.getvalue()

    def test_apk_keeps_its_entries_and_compression(self):
        path = self.apk(self.raw)
        code, out = self.run_main(path)
        self.assertEqual(code, 0, out)
        self.assertIn("repaired 6 en-XA", out)
        with zipfile.ZipFile(path) as archive:
            self.assertEqual([(i.filename, i.compress_type) for i in archive.infolist()], [
                ("AndroidManifest.xml", zipfile.ZIP_DEFLATED), ("resources.arsc", zipfile.ZIP_STORED),
                ("res/x.xml", zipfile.ZIP_DEFLATED)])
            self.assertEqual(archive.read("resources.arsc"), self.fixed)
            self.assertEqual(archive.read("AndroidManifest.xml"), b"manifest bytes")

    def test_a_nul_it_cannot_repair_fails(self):
        # Turn the [ that opens a mangled text into something else, so the repair does not recognize it.
        start = "[βåţţéŕý »%".encode("utf-8")
        self.assertIn(start, self.raw)
        code, out = self.run_main(self.apk(self.raw.replace(start, b"(" + start[1:])))
        self.assertEqual(code, 1, out)
        self.assertIn("en-rXA/trailing still holds a NUL character", out)

    def test_no_damage_on_the_texts_it_repairs(self):
        self.assertEqual(pseudo_fix.repair_damage(roundtrip.read_arsc(self.raw), roundtrip.read_arsc(self.fixed)), [])

    def test_english_sharing_a_pool_slot_and_text_cut_short_fail(self):
        with open(SHARED, "rb") as handle:
            raw = handle.read()
        before = values(roundtrip.read_arsc(raw))
        self.assertEqual(before[("default", "cut_short", "other")], "%{x}%")
        self.assertTrue(before[("default", "shares_the_slot", "other")].endswith("\0"), "aapt shares the slot")
        self.assertEqual(before[("en-rXA", "cut_short", "other")], before[("default", "shares_the_slot", "other")])
        code, out = self.run_main(self.apk(raw))
        self.assertEqual(code, 1, out)
        self.assertIn("default/shares_the_slot[other]: the repair changed a text outside en-XA", out)
        self.assertIn("en-rXA/cut_short[other]: aapt cut the text before its end", out)

    def test_a_table_that_is_not_one_is_refused(self):
        self.assertEqual(self.run_main(self.apk(b"\x03\x00\x08\x00\x08\x00\x00\x00"))[0], 2)


if __name__ == "__main__":
    unittest.main()
