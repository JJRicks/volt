"""G4 (frozen_literals.py) and G5 (char_first.py) against throwaway git repositories."""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import char_first  # noqa: E402
import frozen_literals  # noqa: E402
import ledger_check  # noqa: E402
from repo_fixture import TempRepo, java_class  # noqa: E402

TEST = "tests/ChargeAlertPolicyTest.java"
VALIDATION = "android-tests/ParkedValidation.java"
HEADER = "\t".join(ledger_check.HEADER) + "\n"


class FrozenLiteralsTest(unittest.TestCase):
    def setUp(self):
        self.repo = TempRepo()
        self.repo.write(TEST, java_class("ChargeAlertPolicyTest", '''    void a() { check("Charge alert", "battery_alerts"); }
    void b() { check("Battery at 80%", "x"); }'''))
        self.repo.write(VALIDATION, java_class("ParkedValidation", '    void c() { clickText("Lock scooter"); field("scroll"); }'))
        self.base = self.repo.commit("base")

    def tearDown(self):
        self.repo.close()

    def run_gate(self, ledger=None):
        if ledger:
            self.repo.write("l10n/ledger/b5.tsv", HEADER + ledger + "\n")
        head = self.repo.commit("head")
        return frozen_literals.run(self.base, head, cwd=self.repo.root)

    def test_adding_is_fine(self):
        self.repo.write(TEST, self.repo_text(TEST).replace('"x"); }', '"x"); check("New text", "y"); }'))
        problems, summary = self.run_gate()
        self.assertEqual(problems, [])
        self.assertIn("1 English literal(s) added, 0 removed", summary)

    def test_changing_english_fails(self):
        self.repo.write(TEST, self.repo_text(TEST).replace("Battery at 80%", "Battery at 80 %"))
        problems, _ = self.run_gate()
        self.assertEqual(len(problems), 1)
        self.assertIn('"Battery at 80%" removed or changed 1 time(s) (base: tests/ChargeAlertPolicyTest.java:5)', problems[0])

    def test_keys_and_ids_are_not_frozen(self):
        self.repo.write(TEST, self.repo_text(TEST).replace("battery_alerts", "charge_alerts"))
        self.repo.write(VALIDATION, self.repo_text(VALIDATION).replace('"scroll"', '"scrollView"'))
        problems, _ = self.run_gate()
        self.assertEqual(problems, [])

    def test_moving_between_files_is_fine_unless_per_file(self):
        self.repo.write(VALIDATION, self.repo_text(VALIDATION).replace('clickText("Lock scooter"); ', ""))
        self.repo.write("android-tests/TextCensus.java", java_class("TextCensus", '    String s = "Lock scooter";'))
        head = self.repo.commit("move")
        self.assertEqual(frozen_literals.run(self.base, head, cwd=self.repo.root)[0], [])
        self.assertEqual(len(frozen_literals.run(self.base, head, per_file=True, cwd=self.repo.root)[0]), 1)

    def test_deleting_a_test_needs_a_removed_ledger_row(self):
        self.repo.remove(VALIDATION)
        self.assertEqual(len(self.run_gate()[0]), 1)
        row = VALIDATION + ':4\t["Lock scooter"]\t-\tremoved\tthe parked check was deleted with its feature'
        problems, summary = self.run_gate(row)
        self.assertEqual(problems, [])
        self.assertIn("(1 recorded in the ledger)", summary)

    def repo_text(self, path):
        with open(self.repo.path(path), encoding="utf-8") as handle:
            return handle.read()


class CharacterizationFirstTest(unittest.TestCase):
    def setUp(self):
        self.repo = TempRepo()
        self.repo.write("src/com/jjricks/volt/DisplayUnits.java", java_class("DisplayUnits", '    String km = " km";'))
        self.base = self.repo.commit("base")
        self.repo.git("checkout", "-q", "-b", "batch")

    def tearDown(self):
        self.repo.close()

    def first_commit_with_tests(self):
        self.repo.write("tests/l10n/DisplayUnitsTest.java", java_class("DisplayUnitsTest", '    String expect = "12 km";'))
        return self.repo.commit("characterization")

    def test_untouched_characterization_passes(self):
        first = self.first_commit_with_tests()
        self.repo.write("src/com/jjricks/volt/DisplayUnits.java", java_class("DisplayUnits", "    String km = T.unitKm();"))
        head = self.repo.commit("extract")
        problems, notes, found, files = char_first.run(self.base, head, cwd=self.repo.root)
        self.assertEqual(problems, [])
        self.assertEqual(found, first)
        self.assertEqual(files, ["tests/l10n/DisplayUnitsTest.java"])

    def test_editing_characterization_later_fails(self):
        self.first_commit_with_tests()
        self.repo.write("tests/l10n/DisplayUnitsTest.java", java_class("DisplayUnitsTest", '    String expect = "12 Km";'))
        head = self.repo.commit("oops")
        problems = char_first.run(self.base, head, cwd=self.repo.root)[0]
        self.assertTrue(any("changed later" in p for p in problems), problems)

    def test_first_commit_must_not_change_behaviour(self):
        self.repo.write("tests/l10n/DisplayUnitsTest.java", java_class("DisplayUnitsTest", '    String expect = "12 km";'))
        self.repo.write("src/com/jjricks/volt/DisplayUnits.java", java_class("DisplayUnits", "    String km = T.unitKm();"))
        head = self.repo.commit("mixed")
        problems = char_first.run(self.base, head, cwd=self.repo.root)[0]
        self.assertTrue(any("characterization commits change tests only" in p for p in problems), problems)

    def test_no_characterization_is_a_note_unless_required(self):
        self.repo.write("tools/x.py", "print(1)\n")
        head = self.repo.commit("tooling")
        problems, notes, _, _ = char_first.run(self.base, head, cwd=self.repo.root)
        self.assertEqual(problems, [])
        self.assertTrue(notes)
        self.assertTrue(char_first.run(self.base, head, require=True, cwd=self.repo.root)[0])

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
