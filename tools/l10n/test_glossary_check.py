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

    def test_narrow_no_break_space_at_either_end_of_a_form(self):
        for form in ("trottinette\u202f", "\u202ftrottinette", "a | b\u202f"):
            self.assert_invalid(HEAD + "scooter\t%s\tX.\tnoun\tno\t-\tfixture\n" % form + AFFILIATED + LOCK, "U+202F")

    def test_a_glossary_not_in_utf8_is_named_not_a_traceback(self):
        with open(self.repo.path("l10n/glossary/es.tsv"), "ab") as handle:
            handle.write(b"\xff\n")
        code, output = self.run_check("--tag", "es")
        self.assertEqual(code, 1)
        self.assertIn("es.tsv: not UTF-8", output)
        err = io.StringIO()
        with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
            code = pack.main(["--root", self.repo.root, "--tag", "es", "--area", "common", "--no-java"])
        self.assertEqual(code, 1)
        self.assertIn("es.tsv: not UTF-8", err.getvalue())

    def test_blank_columns_and_shapes_ci_would_refuse(self):
        for row, message in (
                ("scooter\tpatinete\t\tnoun\tno\t-\tfixture\n", "scooter has an empty definition"),
                ("scooter\tpatinete\tX.\t\tno\t-\tfixture\n", "scooter has an empty pos"),
                ("scooter\tpatinete\tX.\tnoun\tno\t-\t\n", "scooter has an empty source"),
                ("scooter\tpatinete\tX.\tnoun; verb\tno\t-\tfixture\n", "one part of speech"),
                ("scooter\tpatinete\tX.\tnoun\tmaybe\t-\tfixture\n", "dnt must be yes or no"),
                ("scooter\tpatinete\tX.\tnoun\tno\tscary\tfixture\n", "'scary' is not - or a known value"),
                ("scooter\tpatinete (m)\tX.\tnoun\tno\t-\tfixture\n", "a term holds no"),
                ("scooter\tpatinete; moto\tX.\tnoun\tno\t-\tfixture\n", "a term holds no")):
            self.assert_invalid(HEAD + row + AFFILIATED + LOCK, message)

    def test_every_committed_glossary_is_checked(self):
        self.assertEqual(translib.committed_glossary_problems(self.paths), {})
        self.repo.write("l10n/glossary/fr.tsv", HEAD + "not-a-real-id\tx\tX.\tnoun\tno\t-\tfixture\n")
        problems = translib.committed_glossary_problems(self.paths)
        self.assertEqual(list(problems), ["fr.tsv"])
        self.assertIn("not-a-real-id is not an id in en.tsv", "\n".join(problems["fr.tsv"]))

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


class StyleGuideRequiredTest(unittest.TestCase):
    def setUp(self):
        self.repo = cf.FixtureRepo()
        self.addCleanup(self.repo.close)
        self.paths = self.repo.paths

    def build(self):
        pack.write_pack(self.paths, pack.build_pack(self.paths, "es", "common",
                                                    fontmetrics.Measurer(self.paths.font(), use_java=False)))

    def prompt(self, *args):
        err = io.StringIO()
        with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
            code = council.main(["--root", self.repo.root, "--no-java", "prompt", "--tag", "es", "--area", "common"] +
                                list(args))
        return code, err.getvalue()

    def test_a_missing_guide_refuses_unless_waived(self):
        os.remove(self.paths.style("es"))
        self.build()
        code, err = self.prompt("--name", "x")
        self.assertEqual(code, 2)
        self.assertIn("l10n/style/es.md does not exist", err)
        self.assertIn("--no-style-guide", err)
        self.assertFalse(os.path.exists(self.paths.work("es", "common", "prompts", "claude-x.md")))
        self.assertEqual(self.prompt("--name", "x", "--no-style-guide")[0], 0)
        # The prompt does not point at a guide it does not have.
        text = self.repo.read("l10n/work/es/common/prompts/claude-x.md")
        self.assertNotIn("style guide", text)
        self.assertIn("Abbreviate rather than overflow", text)

    def test_with_a_guide_the_rules_are_unchanged(self):
        self.build()
        self.assertEqual(self.prompt("--name", "x")[0], 0)
        text = self.repo.read("l10n/work/es/common/prompts/claude-x.md")
        self.assertIn("Abbreviate per the style guide rather than overflow", text)
        self.assertIn("Bloq.", text)

    def test_an_empty_guide_is_no_guide(self):
        self.repo.write("l10n/style/es.md", " \n")
        self.build()
        code, err = self.prompt("--name", "x")
        self.assertEqual(code, 2)
        self.assertIn("l10n/style/es.md is empty", err)
        self.assertEqual(self.prompt("--name", "x", "--no-style-guide")[0], 0)
        self.assertNotIn("style guide", self.repo.read("l10n/work/es/common/prompts/claude-x.md"))

    def test_a_waiver_refuses_a_pack_that_quotes_a_deleted_guide(self):
        self.build()
        os.remove(self.paths.style("es"))
        code, err = self.prompt("--name", "x", "--no-style-guide")
        self.assertEqual(code, 2)
        self.assertIn("changed after the pack was built", err)
        self.assertFalse(os.path.exists(self.paths.work("es", "common", "prompts", "claude-x.md")))
        self.build()
        self.assertEqual(self.prompt("--name", "x", "--no-style-guide")[0], 0)

    def test_a_guide_newer_than_the_pack_refuses(self):
        self.build()
        self.repo.write("l10n/style/es.md", "A new rule.\n")
        code, err = self.prompt("--name", "x")
        self.assertEqual(code, 2)
        self.assertIn("changed after the pack was built", err)

    def test_only_a_translator_needs_it(self):
        os.remove(self.paths.style("es"))
        self.build()
        self.assertEqual(self.prompt("--role", "draft", "--name", "opus")[0], 2)
        self.assertEqual(self.prompt("--role", "judge")[0], 0)
        self.assertEqual(self.prompt("--role", "backtranslate")[0], 0)

    def test_a_judge_prompt_is_gated_no_more_than_judge_requests(self):
        self.build()
        self.repo.write("l10n/style/es.md", self.repo.read("l10n/style/es.md") + "\nOne more line.\n")
        self.assertEqual(self.prompt("--role", "draft", "--name", "opus")[0], 2)
        self.assertEqual(self.prompt("--role", "judge")[0], 0)
        err = io.StringIO()
        with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
            code = council.main(["--root", self.repo.root, "--no-java", "judge-requests", "--tag", "es", "--area", "common"])
        self.assertEqual(code, 0, err.getvalue())

    def test_the_readme_names_the_required_sections(self):
        text = translib.l10nlib.read_text(os.path.join(translib.REPO_ROOT, "l10n", "style", "README.md"))
        for section in ("Register and form of address", "Punctuation and quotes",
                        "Capitalization of buttons, labels and titles", "Numbers, dates and units",
                        "How to shorten labels for ladder rungs", "Safety wording", "Sources consulted"):
            self.assertIn("## " + section, text)


if __name__ == "__main__":
    unittest.main()
