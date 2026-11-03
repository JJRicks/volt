"""ci_gates.py: which gates CI runs for which kind of change."""

import contextlib
import io
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ci_gates  # noqa: E402
import ledger_check  # noqa: E402
from repo_fixture import TempRepo, java_class  # noqa: E402

STORE = "src/com/jjricks/volt/PhoneRideStore.java"
GOLDEN = "tests/l10n/golden/phone-track.gpx"
HEADER = "\t".join(ledger_check.HEADER) + "\n"


class KindTest(unittest.TestCase):
    def test_kinds(self):
        self.assertEqual(ci_gates.kind_of(["src/com/jjricks/volt/Ui.java", "tests/UiTest.java"]), "ordinary")
        self.assertEqual(ci_gates.kind_of(["res/values/strings.xml"]), "ordinary")
        self.assertEqual(ci_gates.kind_of(["res/values/strings_common.xml"]), "batch")
        self.assertEqual(ci_gates.kind_of(["l10n/ledger/b2-ui.tsv"]), "batch")
        self.assertEqual(ci_gates.kind_of(["l10n/ledger/b2-ui.tsv", "l10n/english-changes.tsv"]), "english-change")

    def test_plans(self):
        labels = lambda kind, pr="": [s[0] for s in ci_gates.plan(kind, "origin/main", "HEAD", pr, "/repo")]  # noqa: E731
        self.assertEqual(labels("ordinary"), ["ledgers agree with the catalog", "G8 baselines list every finding"])
        batch = labels("batch", "abc")
        for gate in ("G1 ledger", "G4 frozen test statements", "G5 characterization first", "G8 shrink-only baselines",
                     "G9 goldens unchanged since the merge base", "proof tooling unchanged"):
            self.assertIn(gate, batch)
        self.assertNotIn("G5 characterization first", labels("batch"))
        change = {s[0]: s for s in ci_gates.plan("english-change", "origin/main", "HEAD", "", "/repo")}
        self.assertIn("--report", change["G4 frozen test statements"][1])
        self.assertFalse(change["G9 goldens unchanged since the merge base"][2])
        tight = {s[0]: s for s in ci_gates.plan("batch", "origin/main", "HEAD", "", "/repo")}
        self.assertIn("--tight", tight["G8 shrink-only baselines"][1])

    def test_tooling(self):
        self.assertEqual(ci_gates.tooling_changes(["tools/l10n/frozen_literals.py", ".github/workflows/tests.yml",
                                                   "tests/l10n/golden.sh", "tests/l10n/GoldenReports.java", "src/A.java"]),
                         ["tools/l10n/frozen_literals.py", ".github/workflows/tests.yml", "tests/l10n/golden.sh"])


class GoldenRewriteTest(unittest.TestCase):
    """Findings 7, 15 and 19: a batch that changes report output and rewrites the goldens fails CI."""

    def setUp(self):
        self.repo = TempRepo()
        self.repo.write(STORE, java_class("PhoneRideStore", '    String name() { return "<trk><name>Ride"; }'))
        self.repo.write(GOLDEN, "<trk><name>Ride GOLDEN-RIDE-1</name>\n")
        self.repo.write("res/values/strings.xml", '<resources><string name="app_name">Volt</string></resources>\n')
        self.base = self.repo.commit("base")
        self.repo.write(STORE, java_class("PhoneRideStore", '    String name() { return "<trk><name>Trip"; }'))
        self.repo.write(GOLDEN, "<trk><name>Trip GOLDEN-RIDE-1</name>\n")

    def tearDown(self):
        self.repo.close()

    def run_ci(self, *extra):
        head = self.repo.commit("head")
        here = os.getcwd()
        os.chdir(self.repo.root)
        out, err = io.StringIO(), io.StringIO()
        try:
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                code = ci_gates.main(["--base", self.base, "--head", head] + list(extra))
        finally:
            os.chdir(here)
        return code, out.getvalue() + err.getvalue()

    def test_golden_rewrite_in_a_batch_fails(self):
        self.repo.write("l10n/ledger/b9.tsv", HEADER + STORE + ':4\t["<trk><name>Ride"]\t-\tremoved\t\n')
        code, output = self.run_ci()
        self.assertEqual(code, 1, output)
        self.assertIn("ci_gates: batch change", output)
        self.assertIn("FAILED G9 goldens unchanged since the merge base", output)
        self.assertEqual(ci_gates.golden_changes(self.base, "HEAD", self.repo.root), [GOLDEN])

    def test_an_ordinary_change_may_update_goldens(self):
        code, output = self.run_ci()
        self.assertIn("ci_gates: ordinary change", output)
        self.assertNotIn("G9", output)

    def test_tooling_edits_in_a_batch_fail(self):
        self.repo.write(GOLDEN, "<trk><name>Ride GOLDEN-RIDE-1</name>\n")
        self.repo.write(STORE, java_class("PhoneRideStore", '    String name() { return "<trk><name>Ride"; }'))
        self.repo.write("res/values/strings_rides.xml", '<resources><string name="rides_x">X</string></resources>\n')
        self.repo.write("tools/l10n/frozen_literals.py", "def run(*a, **k):\n    return [], 'ok'\n")
        code, output = self.run_ci()
        self.assertEqual(code, 1, output)
        self.assertIn("FAILED proof tooling unchanged", output)


if __name__ == "__main__":
    unittest.main()
