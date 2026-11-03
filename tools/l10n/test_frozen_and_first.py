"""G4 (frozen_literals.py) and G5 (char_first.py) against throwaway git repositories."""

import contextlib
import io
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import char_first  # noqa: E402
import frozen_literals  # noqa: E402
import ledger_check  # noqa: E402
from repo_fixture import TempRepo, java_class  # noqa: E402

TEST = "tests/ChargeAlertPolicyTest.java"
SCALE_TEST = "tests/RouteSpeedScaleTest.java"
VALIDATION = "android-tests/ParkedValidation.java"
HEADER = "\t".join(ledger_check.HEADER) + "\n"
CHAR = "tests/l10n/characterization/DisplayUnitsTest.java"


class FrozenLiteralsTest(unittest.TestCase):
    def setUp(self):
        self.repo = TempRepo()
        self.repo.write(TEST, java_class("ChargeAlertPolicyTest", '''    void a() { check("Charge alert", "battery_alerts"); }
    void b() { check("Battery at 80%", "x"); }
    void c() { prefs("battery_alerts"); }'''))
        self.repo.write(SCALE_TEST, java_class("RouteSpeedScaleTest", '''    void metric() {
        check(RouteSpeedScale.label(30).equals("30 km/h"), "Metric label");
        check(RouteSpeedScale.label(19).equals("19 mph"), "Imperial label");
    }'''))
        self.repo.write(VALIDATION, java_class("ParkedValidation", '    void c() { clickText("Lock scooter"); field("scroll"); }'))
        self.base = self.repo.commit("base")

    def tearDown(self):
        self.repo.close()

    def text(self, path):
        with open(self.repo.path(path), encoding="utf-8") as handle:
            return handle.read()

    def edit(self, path, old, new):
        self.assertIn(old, self.text(path))
        self.repo.write(path, self.text(path).replace(old, new))

    def run_gate(self, ledger=None, per_file=False):
        if ledger:
            self.repo.write("l10n/ledger/b5.tsv", HEADER + "\n".join(ledger) + "\n")
        head = self.repo.commit("head")
        return frozen_literals.run(self.base, head, per_file=per_file, cwd=self.repo.root)

    def test_adding_is_fine(self):
        self.edit(TEST, '"x"); }', '"x"); check("New text", "y"); }')
        problems, summary = self.run_gate()
        self.assertEqual(problems, [])
        self.assertIn("1 added, 0 removed", summary)

    def test_changing_english_fails(self):
        self.edit(TEST, "Battery at 80%", "Battery at 80 %")
        problems, _ = self.run_gate()
        self.assertEqual(len(problems), 1)
        self.assertIn('ChargeAlertPolicyTest.java:5: a test statement with "Battery at 80%" was changed', problems[0])

    def test_keys_in_statements_without_english_are_free(self):
        self.edit(TEST, 'prefs("battery_alerts")', 'prefs("charge_alerts")')
        self.edit(VALIDATION, '"scroll"', '"scrollView"')
        self.assertEqual(self.run_gate()[0], [])

    def test_a_key_beside_english_is_frozen_with_it(self):
        self.edit(TEST, 'check("Charge alert", "battery_alerts")', 'check("Charge alert", "charge_alerts")')
        self.assertEqual(len(self.run_gate()[0]), 1)

    def test_decoy_keeping_the_old_literal_fails(self):
        """Finding 3: the expectation changes while a decoy keeps "30 km/h" in the file."""
        self.edit(SCALE_TEST, 'equals("30 km/h"), "Metric label");',
                  'equals("30 kph"), "Metric label"); check(!"30 km/h".isEmpty(), "Metric label");')
        problems, _ = self.run_gate()
        self.assertTrue(any('"30 km/h", "Metric label" was changed' in p for p in problems), problems)
        self.assertEqual(len(self.run_gate(per_file=True)[0]), 1)

    def test_weakened_assertion_fails(self):
        self.edit(SCALE_TEST, '.equals("30 km/h")', '.equals("30 km/h") || true')
        self.assertEqual(len(self.run_gate()[0]), 1)

    def test_swapped_expectations_fail(self):
        self.edit(SCALE_TEST, '"30 km/h"), "Metric label"', '"30 km/h"), "Imperial label"')
        self.edit(SCALE_TEST, '"19 mph"), "Imperial label"', '"19 mph"), "Metric label"')
        self.assertEqual(len(self.run_gate()[0]), 2)

    def test_moving_a_statement_between_files_is_fine_unless_per_file(self):
        self.edit(VALIDATION, 'clickText("Lock scooter"); ', "")
        self.repo.write("android-tests/TextCensus.java",
                        java_class("TextCensus", '    void lock() { TextFit.clickText("Lock scooter"); }'))
        head = self.repo.commit("move")
        self.assertEqual(frozen_literals.run(self.base, head, cwd=self.repo.root)[0], [])
        self.assertEqual(len(frozen_literals.run(self.base, head, per_file=True, cwd=self.repo.root)[0]), 1)

    def test_deleting_a_test_needs_a_removed_ledger_row(self):
        self.repo.remove(VALIDATION)
        self.assertEqual(len(self.run_gate()[0]), 1)
        row = VALIDATION + ':4\t["Lock scooter"]\t-\tremoved\tthe parked check was deleted with its feature'
        problems, summary = self.run_gate([row])
        self.assertEqual(problems, [])
        self.assertIn("(1 recorded in the ledger)", summary)

    def test_a_removed_row_must_name_the_statement(self):
        """Finding 2b: a row on a line that does not exist covers nothing."""
        self.edit(SCALE_TEST, '.equals("30 km/h")', '.equals("30 kph")')
        problems, _ = self.run_gate([SCALE_TEST + ':9999\t["30 km/h"]\t-\tremoved\t'])
        text = "\n".join(problems)
        self.assertIn("was changed or removed", text)
        self.assertIn("no frozen test statement on line 9999", text)

    def test_a_changed_test_is_not_a_removal(self):
        """Finding 2b: the row names the right line, but the test was changed, not deleted."""
        self.edit(SCALE_TEST, '.equals("30 km/h")', '.equals("30 kph")')
        problems, _ = self.run_gate([SCALE_TEST + ':5\t["30 km/h"]\t-\tremoved\t'])
        self.assertTrue(any("removed is for a deleted test" in p and "30 kph" in p for p in problems), problems)

    def test_a_removed_statement_cannot_come_back_changed_elsewhere(self):
        self.edit(SCALE_TEST, '        check(RouteSpeedScale.label(30).equals("30 km/h"), "Metric label");\n', "")
        self.repo.write("tests/OtherTest.java", java_class("OtherTest", '    void m() { check(x.equals("30 km/h") || true, "Metric label"); }'))
        problems, _ = self.run_gate([SCALE_TEST + ':5\t["30 km/h", "Metric label"]\t-\tremoved\t'])
        self.assertTrue(any("comes back in a new test statement" in p for p in problems), problems)

    def test_report_mode_never_fails(self):
        self.edit(TEST, "Battery at 80%", "Battery at 80 %")
        head = self.repo.commit("english change")
        here = os.getcwd()
        os.chdir(self.repo.root)
        try:
            with contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(frozen_literals.main(["--base", self.base, "--head", head]), 1)
                self.assertEqual(frozen_literals.main(["--base", self.base, "--head", head, "--report"]), 0)
            self.assertIn("frozen_literals: REPORT:", out.getvalue())
        finally:
            os.chdir(here)


class CharacterizationFirstTest(unittest.TestCase):
    UNITS = "src/com/jjricks/volt/DisplayUnits.java"

    def setUp(self):
        self.repo = TempRepo()
        self.repo.write(self.UNITS, java_class("DisplayUnits", '    String km() { return " km"; }'))
        self.base = self.repo.commit("base")
        self.repo.git("checkout", "-q", "-b", "batch")

    def tearDown(self):
        self.repo.close()

    def first_commit_with_tests(self):
        self.repo.write(CHAR, java_class("DisplayUnitsTest", '    String expect = "12 km";'))
        self.repo.write("tests/l10n/b1.sources", "tests/l10n/characterization/DisplayUnitsTest.java\n")
        return self.repo.commit("characterization")

    def convert(self, ledger_kind="template"):
        self.repo.write(self.UNITS, java_class("DisplayUnits", "    String km() { return T.unitKm(); }"))
        self.repo.write("l10n/ledger/b1.tsv", "\t".join(ledger_check.HEADER) + "\n" + self.UNITS +
                        ':4\t[" km"]\tunit_km\t%s\t\n' % ledger_kind)

    def gate(self, head, **kwargs):
        return char_first.run(self.base, head, cwd=self.repo.root, **kwargs)

    def test_untouched_characterization_passes(self):
        first = self.first_commit_with_tests()
        self.convert()
        head = self.repo.commit("extract")
        problems, notes, found, files = self.gate(head)
        self.assertEqual(problems, [])
        self.assertEqual(found, first)
        self.assertEqual(files, [CHAR])

    def test_editing_characterization_later_fails(self):
        self.first_commit_with_tests()
        self.repo.write(CHAR, java_class("DisplayUnitsTest", '    String expect = "12 Km";'))
        problems = self.gate(self.repo.commit("oops"))[0]
        self.assertTrue(any("changed later" in p for p in problems), problems)

    def test_first_commit_must_not_change_behaviour(self):
        self.repo.write(CHAR, java_class("DisplayUnitsTest", '    String expect = "12 km";'))
        self.convert()
        problems = self.gate(self.repo.commit("mixed"))[0]
        self.assertTrue(any("characterization commits change tests only" in p for p in problems), problems)

    def test_characterization_after_the_change_fails(self):
        """Finding 6: tests committed after the src change used to pass without --require."""
        self.convert()
        self.repo.commit("extract first")
        self.repo.write(CHAR, java_class("DisplayUnitsTest", '    String expect = "12 km";'))
        problems = self.gate(self.repo.commit("tests second"))[0]
        self.assertTrue(any("does not add it; characterization tests come first" in p for p in problems), problems)

    def test_a_template_row_requires_characterization(self):
        self.convert()
        problems = self.gate(self.repo.commit("no characterization"))[0]
        self.assertTrue(any("template or plural ledger row" in p for p in problems), problems)

    def test_an_exact_only_batch_needs_none(self):
        self.convert("exact")
        problems, notes, _, _ = self.gate(self.repo.commit("exact only"))
        self.assertEqual(problems, [])
        self.assertTrue(notes)

    def test_sources_lists_and_catalog_tests_are_not_characterization(self):
        """Finding 6: an honest single commit adding a .sources list and src passes."""
        self.repo.write("tests/l10n/b0b.sources", "src/com/jjricks/volt/Msg.java\n")
        self.repo.write("tests/l10n/LedgerTest.java", java_class("LedgerTest", ""))
        self.repo.write("src/com/jjricks/volt/Msg.java", java_class("Msg", ""))
        problems, _, _, files = self.gate(self.repo.commit("b0b in one commit"))
        self.assertEqual((problems, files), ([], []))

    def test_require_flag(self):
        self.repo.write("tools/x.py", "print(1)\n")
        head = self.repo.commit("tooling")
        self.assertEqual(self.gate(head)[0], [])
        self.assertTrue(self.gate(head, require=True)[0])

    def test_merge_base_is_used(self):
        """A base that moved on after the branch started does not count main's commits."""
        first = self.first_commit_with_tests()
        self.repo.git("checkout", "-q", "main")
        self.repo.write("README.md", "main moved on\n")
        main_tip = self.repo.commit("main work")
        self.repo.git("checkout", "-q", "batch")
        head = self.repo.git("rev-parse", "HEAD")
        problems, _, found, _ = char_first.run(main_tip, head, cwd=self.repo.root)
        self.assertEqual((problems, found), ([], first))


if __name__ == "__main__":
    unittest.main()
