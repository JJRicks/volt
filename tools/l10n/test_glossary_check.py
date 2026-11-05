"""glossary-check: the validator for l10n/glossary/*.tsv, and the tools that refuse an invalid one."""

import contextlib
import io
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import council  # noqa: E402
import council_fixture as cf  # noqa: E402
import fontmetrics  # noqa: E402
import pack  # noqa: E402
import translib  # noqa: E402

HEAD = "id\tterm\tdefinition\tpos\tdnt\tsafety\tsource\n"
SCOOTER = "scooter\tpatinete | patinetes\tThe scooter.\tnoun\tno\t-\tfixture\n"
AFFILIATED = "not-affiliated\tno está afiliada a NAVEE\tThe disclaimer.\tphrase\tno\tdisclaimer\tfixture\n"
LOCK = "lock\tbloquear\tLock.\tverb\tno\tlock\tfixture\n"
VALID = HEAD + SCOOTER + AFFILIATED + LOCK


class GlossaryCheckTest(unittest.TestCase):
    def setUp(self):
        self.repo = cf.FixtureRepo()
        self.addCleanup(self.repo.close)
        self.paths = self.repo.paths

    def run_check(self, *args):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = council.main(["--root", self.repo.root, "--no-java", "glossary-check"] + list(args))
        return code, out.getvalue() + err.getvalue()

    def assert_invalid(self, text, *messages):
        self.repo.write("l10n/glossary/es.tsv", text)
        code, output = self.run_check("--tag", "es")
        self.assertEqual(code, 1, output)
        for message in messages:
            self.assertIn(message, output)

    def test_the_fixture_is_valid(self):
        code, output = self.run_check("--tag", "es")
        self.assertEqual(code, 0, output)
        self.assertIn("es glossary: valid", output)
        self.repo.write("l10n/glossary/es.tsv", VALID)
        self.assertEqual(self.run_check("--tag", "es")[0], 0)

    def test_no_glossary(self):
        code, output = self.run_check("--tag", "de")
        self.assertEqual(code, 1)
        self.assertIn("no l10n/glossary/de.tsv", output)

    def test_unknown_id(self):
        # load_glossary ignores this row, so without the check "scooter" would quietly lose its target.
        self.assert_invalid(VALID + "scoter\tpatinete\tTypo.\tnoun\tno\t-\tfixture\n", "scoter is not an id in en.tsv")

    def test_missing_row(self):
        self.assert_invalid(HEAD + SCOOTER + AFFILIATED, "no row for lock (lock)")

    def test_a_dnt_term_takes_no_row(self):
        self.assert_invalid(VALID + "navee\tNAVEE\tMaker.\tnoun\tyes\t-\tfixture\n", "navee is do-not-translate")

    def test_wrong_column_count(self):
        self.assert_invalid(HEAD + SCOOTER + AFFILIATED + "lock\tbloquear\n", ":4: 2 columns, a row has 7")
        self.repo.write("l10n/glossary/es.tsv", "id\tterm\n" + SCOOTER)
        self.assertIn("the header must be", self.run_check("--tag", "es")[1])

    def test_empty_term(self):
        self.assert_invalid(HEAD + SCOOTER + AFFILIATED + "lock\t\tLock.\tverb\tno\tlock\tfixture\n",
                            "lock has an empty term")
        self.assert_invalid(HEAD + "scooter\tpatinete | \tX.\tnoun\tno\t-\tfixture\n" + AFFILIATED + LOCK,
                            "scooter has an empty term")

    def test_placeholder(self):
        self.assert_invalid(HEAD + "scooter\tpatinete %1$s\tX.\tnoun\tno\t-\tfixture\n" + AFFILIATED + LOCK,
                            "has a placeholder")

    def test_narrow_no_break_space(self):
        self.assert_invalid(HEAD + "scooter\ttrottinette X\tX.\tnoun\tno\t-\tfixture\n" + AFFILIATED + LOCK,
                            "U+202F", "U+00A0")
        # U+00A0 is what Saira has, so it is what French uses.
        self.repo.write("l10n/glossary/es.tsv", HEAD + "scooter\ttrottinette X\tX.\tnoun\tno\t-\tfixture\n" +
                        AFFILIATED + LOCK)
        self.assertEqual(self.run_check("--tag", "es")[0], 0)
        font = fontmetrics.Font(self.paths.font())
        self.assertFalse(font.covers(" "))
        self.assertTrue(font.covers(" "))

    def test_duplicate_id(self):
        self.assert_invalid(VALID + LOCK, "lock appears twice")

    def test_english_mode_lists_orphans_as_information(self):
        self.repo.write("l10n/glossary/en.tsv", self.repo.read("l10n/glossary/en.tsv") +
                        "gearbox\tgearbox\tNot in any string.\tnoun\tno\t-\tfixture\n")
        code, output = self.run_check("--english")
        self.assertEqual(code, 0, output)
        self.assertIn("gearbox (gearbox)", output)
        self.assertIn("rows match no English string", output)
        self.assertNotIn("scooter (scooter)", output)
        self.assertEqual(self.run_check("--tag", "en")[0], 0)

    def test_english_mode_fails_on_structure(self):
        self.repo.write("l10n/glossary/en.tsv", self.repo.read("l10n/glossary/en.tsv") + "only\ttwo\n")
        code, output = self.run_check("--english")
        self.assertEqual(code, 1)
        self.assertIn("2 columns", output)

    def test_the_real_english_glossary_is_valid(self):
        self.assertEqual(translib.english_glossary_problems(translib.Paths()), [])

    def test_pack_refuses_an_invalid_glossary(self):
        self.repo.write("l10n/glossary/es.tsv", VALID + "scoter\tx\tX.\tnoun\tno\t-\tfixture\n")
        err = io.StringIO()
        with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
            code = pack.main(["--root", self.repo.root, "--tag", "es", "--area", "common", "--no-java"])
        self.assertEqual(code, 1)
        self.assertIn("the es glossary is invalid", err.getvalue())
        self.assertIn("scoter is not an id", err.getvalue())
        self.assertFalse(os.path.exists(self.paths.pack("es", "common")))
        # A language with no glossary yet still packs.
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(pack.main(["--root", self.repo.root, "--tag", "de", "--area", "common", "--no-java"]), 0)

    def test_prompt_refuses_an_invalid_glossary(self):
        pack.write_pack(self.paths, pack.build_pack(self.paths, "es", "common",
                                                    fontmetrics.Measurer(self.paths.font(), use_java=False)))
        self.repo.write("l10n/glossary/es.tsv", HEAD + SCOOTER)
        err = io.StringIO()
        with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
            code = council.main(["--root", self.repo.root, "--no-java", "prompt", "--tag", "es", "--area", "common",
                                 "--name", "x"])
        self.assertEqual(code, 2)
        self.assertIn("the es glossary is invalid", err.getvalue())
        self.assertFalse(os.path.exists(self.paths.work("es", "common", "prompts", "claude-x.md")))


if __name__ == "__main__":
    unittest.main()
