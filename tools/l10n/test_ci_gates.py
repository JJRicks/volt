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
        self.assertEqual(ci_gates.kind_of(["src/com/jjricks/volt/Ui.java"], gains_catalog_references=True), "batch")

    def test_catalog_references(self):
        refs = ci_gates.catalog_references('class A { void f() { a(T.statsWindowTitle()); b(R.string.app_name); '
                                           'c(Msg.of(T.STATS_X)); d(x.T.y); e(M.rides(n)); } }')
        self.assertEqual(sorted(refs), ["M.rides", "R.string.app_name", "T.STATS_X", "T.statsWindowTitle"])

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
        # Re-review finding 5: the golden generator and the test runners are tooling too.
        changed = ["tools/l10n/frozen_literals.py", ".github/workflows/tests.yml", "tests/l10n/golden.sh",
                   "tests/l10n/GoldenReports.java", "tests/run.sh", "build.sh", "android-tests/build.sh",
                   "tests/l10n/LedgerTest.java", "src/A.java", "tests/RouteSpeedScaleTest.java",
                   "tests/l10n/b2-ui.sources", "tests/l10n/characterization/DisplayUnitsTest.java",
                   "tests/l10n/golden/phone-track.gpx", "android-tests/run-release-sweep.py"]
        self.assertEqual(ci_gates.tooling_changes(changed), changed[:8])
        # Re-review 3, finding 4: a new tests/l10n test only adds checks; editing one is still tooling.
        added = {"tests/l10n/LedgerTest.java", "tests/l10n/golden.sh", "tests/run.sh", "tools/l10n/new_gate.py",
                 "tests/l10n/sub/Helper.java"}
        self.assertEqual(ci_gates.tooling_changes(changed + ["tools/l10n/new_gate.py", "tests/l10n/sub/Helper.java"],
                                                  added),
                         changed[:7] + ["tools/l10n/new_gate.py", "tests/l10n/sub/Helper.java"])


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

    def test_post_processing_goldens_in_the_generator_fails(self):
        """Re-review finding 5: GoldenReports.java rewrites the output so the goldens stay byte-identical."""
        self.repo.write(GOLDEN, "<trk><name>Ride GOLDEN-RIDE-1</name>\n")
        self.repo.git("stash", "-q")
        self.repo.write("tests/l10n/GoldenReports.java", "class GoldenReports { String gpx(String t) { return t; } }\n")
        self.base = self.repo.commit("golden generator")
        self.repo.git("stash", "pop", "-q")
        self.repo.write(GOLDEN, "<trk><name>Ride GOLDEN-RIDE-1</name>\n")
        self.repo.write("tests/l10n/GoldenReports.java", 'class GoldenReports { String gpx(String t) { '
                                                         'return t.replace("<name>Trip", "<name>Ride"); } }\n')
        self.repo.write("l10n/ledger/b9.tsv", HEADER + STORE + ':4\t["<trk><name>Ride"]\t-\tremoved\tmanual: x\n')
        code, output = self.run_ci()
        self.assertEqual(code, 1, output)
        self.assertIn("FAILED proof tooling unchanged (tests/l10n/GoldenReports.java)", output)

    def test_a_conversion_without_a_ledger_is_a_batch(self):
        """Re-review finding 7: forgetting the ledger must not make the change ordinary."""
        self.repo.write(GOLDEN, "<trk><name>Ride GOLDEN-RIDE-1</name>\n")
        self.repo.write(STORE, java_class("PhoneRideStore", "    String name() { return T.ridesGpxOpen(); }"))
        code, output = self.run_ci()
        self.assertEqual(code, 1, output)
        self.assertIn("ci_gates: batch change", output)
        self.assertIn("src gained catalog references (T.ridesGpxOpen)", output)
        self.assertIn("FAILED G1 ledger", output)

    def test_an_english_change_may_not_add_ledger_rows(self):
        """Re-review finding 9."""
        self.repo.write(GOLDEN, "<trk><name>Ride GOLDEN-RIDE-1</name>\n")
        self.repo.write(STORE, java_class("PhoneRideStore", '    String name() { return "<trk><name>Ride"; }'))
        self.repo.write("l10n/english-changes.tsv", "key\tquantity\told\tnew\tnote\nzz_nothing\t-\ta\tb\towner approved in #999\n")
        self.repo.write("l10n/ledger/b9.tsv", HEADER)
        code, output = self.run_ci()
        self.assertEqual(code, 1, output)
        self.assertIn("ci_gates: english-change change", output)
        self.assertIn("FAILED no ledger rows in an English change", output)
        self.assertIn("names key zz_nothing, which is not in res/values", output)

    def test_a_batch_may_add_a_catalog_test_but_not_the_run_sh_hook(self):
        """Re-review 3, finding 4: B0b and B1 add tests/l10n tests in a batch; run.sh comes first."""
        self.repo.write(GOLDEN, "<trk><name>Ride GOLDEN-RIDE-1</name>\n")
        self.repo.write(STORE, java_class("PhoneRideStore", '    String name() { return "<trk><name>Ride"; }'))
        self.repo.write("res/values/strings_common.xml", '<resources><string name="common_x">X</string></resources>\n')
        self.repo.write("tests/l10n/FmtLegacyTest.java", java_class("FmtLegacyTest", ""))
        head = self.repo.commit("batch")
        self.assertEqual(ci_gates.tooling_changes(["tests/l10n/FmtLegacyTest.java"],
                                                  ci_gates.added_paths(self.base, head, ["tests/l10n/FmtLegacyTest.java"],
                                                                       self.repo.root)), [])
        self.repo.write("tests/run.sh", "#!/bin/sh\n")
        code, output = self.run_ci()
        self.assertEqual(code, 1, output)
        self.assertIn("FAILED proof tooling unchanged (tests/run.sh)", output)
        self.assertNotIn("FmtLegacyTest", output.split("proof tooling unchanged")[1].split("\n")[0])

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
