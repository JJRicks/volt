"""ci_gates.py: which gates CI runs for which kind of change."""

import contextlib
import io
import os
import re
import shutil
import subprocess
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

    def test_qualified_and_static_imported_catalog_references(self):
        """Codex review of #149: the app's package spelled out, or a static import, is still the catalog."""
        refs = ci_gates.catalog_references(
            "import static com.jjricks.volt.T.commonSave; import static com.jjricks.volt.M.*; "
            "import static java.lang.Math.max; class A { void f() { a(com.jjricks.volt.T.commonSave()); "
            "b(com.jjricks.volt.R.string.app_name); c(com.jjricks.volt.M.rides(n)); d(android.R.string.ok); "
            "e(x.T.y); g(foo().T.z); h(other.pkg.T.q()); } }")
        self.assertEqual(sorted(refs), ["M.rides", "R.string.app_name", "T.commonSave",
                                        "import static com.jjricks.volt.M.*",
                                        "import static com.jjricks.volt.T.commonSave"])

    def test_plans(self):
        labels = lambda kind, pr="": [s[0] for s in ci_gates.plan(kind, "origin/main", "HEAD", pr, "/repo")]  # noqa: E731
        self.assertEqual(labels("ordinary"), ["ledgers agree with the catalog", "no static imports of the catalog",
                                              "G8 baselines list every finding"])
        for kind in ("batch", "english-change"):
            self.assertIn("no static imports of the catalog", labels(kind))
        batch = labels("batch", "abc")
        for gate in ("G1 ledger", "G4 frozen test statements", "G5 characterization first", "G8 shrink-only baselines",
                     "G9 goldens unchanged since the merge base", "proof tooling unchanged"):
            self.assertIn(gate, batch)
        self.assertNotIn("G5 characterization first", labels("batch"))
        change = {s[0]: s for s in ci_gates.plan("english-change", "origin/main", "HEAD", "", "/repo")}
        self.assertIn("--relax-all", change["G4 frozen test statements"][1])
        self.assertNotIn("--report", change["G4 frozen test statements"][1])
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

    def test_a_qualified_conversion_to_an_existing_key_without_a_ledger_is_a_batch(self):
        """Codex review of #149: neither the catalog nor the ledger changes, and the call is spelled out
        with the app's package (or reached through a static import), but it is still a batch."""
        self.repo.git("stash", "-q")
        self.repo.write("res/values/strings_rides.xml", '<resources xmlns:volt="https://volt.jjricks.com/ns/l10n">'
                        '<string name="rides_gpx_open">&lt;trk&gt;&lt;name&gt;Ride</string></resources>\n')
        self.base = self.repo.commit("existing key")
        self.repo.git("stash", "pop", "-q")
        self.repo.write(GOLDEN, "<trk><name>Ride GOLDEN-RIDE-1</name>\n")
        for body, gained in (
                ("    String name() { return com.jjricks.volt.T.ridesGpxOpen(); }", "T.ridesGpxOpen"),
                ("    String name() { return ridesGpxOpen(); }", "import static com.jjricks.volt.T.ridesGpxOpen")):
            with self.subTest(body=body):
                text = java_class("PhoneRideStore", body)
                if "import" in gained:
                    text = text.replace("\n\n", "\n\nimport static com.jjricks.volt.T.ridesGpxOpen;\n\n", 1)
                self.repo.write(STORE, text)
                code, output = self.run_ci()
                self.assertEqual(code, 1, output)
                self.assertIn("ci_gates: batch change", output)
                self.assertRegex(output, r"src gained catalog references \([^)]*%s" % re.escape(gained))
                self.assertIn("FAILED G1 ledger", output)
                self.repo.git("reset", "-q", "--soft", "HEAD~1")

    def test_a_static_import_already_at_the_base_is_refused(self):
        """Codex review of #149, round 2: the import and its first use are at the merge base, so a later
        conversion through it gains no reference CI can see. Every PR refuses catalog static imports."""
        self.repo.git("checkout", "-q", "--", ".")
        for imported in ("com.jjricks.volt.T.ridesGpxOpen", "com.jjricks.volt.T.*"):
            with self.subTest(imported=imported):
                base = java_class("PhoneRideStore", '    String open() { return ridesGpxOpen(); }\n'
                                  '    String name() { return "<trk><name>Ride"; }').replace(
                    "\n\n", "\n\nimport static %s;\n\n" % imported, 1)
                self.repo.write(STORE, base)
                self.base = self.repo.commit("import at the base")
                self.repo.write(STORE, base.replace('return "<trk><name>Ride";', "return ridesGpxOpen();"))
                code, output = self.run_ci()
                self.assertEqual(code, 1, output)
                self.assertIn("ci_gates: ordinary change", output)
                self.assertIn("%s: import static %s; call T.x()" % (STORE, imported), output)
                self.assertIn("FAILED no static imports of the catalog (1 found)", output)

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


class EnglishChangeTestEditsTest(unittest.TestCase):
    """An owner-approved English change updates the tests that assert the old English (l10n/README.md), so
    an edit to an existing non-gate tests/l10n test is reported for the reviewer; the rest stays strict."""

    CHARGING = "tests/l10n/ChargingTest.java"
    LEDGER_TEST = "tests/l10n/LedgerTest.java"

    def setUp(self):
        self.repo = TempRepo()
        self.addCleanup(self.repo.close)
        self.repo.write("res/values/strings_common.xml",
                        '<resources><string name="common_x">Old words</string></resources>\n')
        self.repo.write(self.CHARGING, java_class("ChargingTest", '    String s() { return "Old words"; }'))
        self.repo.write(self.LEDGER_TEST, java_class("LedgerTest", '    String s() { return "a"; }'))
        self.repo.write("tests/l10n/XTest.java", java_class("XTest", '    String s() { return "a"; }'))
        self.repo.write("tools/l10n/frozen_literals.py", "def run():\n    pass\n")
        self.base = self.repo.commit("base")

    def english_change(self):
        self.repo.write("res/values/strings_common.xml",
                        '<resources><string name="common_x">New words</string></resources>\n')
        self.repo.write("l10n/english-changes.tsv", "key\tquantity\told\tnew\tnote\n"
                        "common_x\t-\tOld words\tNew words\towner approved in #999\n")

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

    def print_tooling(self):
        code, output = self.run_ci("--print-tooling")
        self.assertEqual(code, 0, output)
        return output.split()

    def test_an_english_change_may_edit_a_test_that_asserts_the_old_english(self):
        self.english_change()
        self.repo.write(self.CHARGING, java_class("ChargingTest", '    String s() { return "New words"; }'))
        code, output = self.run_ci()
        self.assertIn("ci_gates: english-change change", output)
        self.assertIn("PASS proof tooling unchanged (unchanged)", output)
        self.assertRegex(output, r"REPORT tests/l10n edits follow l10n/english-changes.tsv \(tests/l10n/ChargingTest"
                                 r"\.java; check that each edit only follows a row of l10n/english-changes.tsv\)")
        self.assertEqual(code, 0, output)
        self.repo.git("reset", "-q", "--soft", "HEAD~1")
        self.assertEqual(self.print_tooling(), [])
        self.repo.git("reset", "-q", "--soft", "HEAD~1")
        code, output = self.run_ci("--print-english-tests")
        self.assertEqual(output.split(), [self.CHARGING])

    def test_an_english_change_may_not_edit_a_gate_test(self):
        self.english_change()
        self.repo.write(self.LEDGER_TEST, java_class("LedgerTest", '    String s() { return "b"; }'))
        code, output = self.run_ci()
        self.assertEqual(code, 1, output)
        self.assertIn("FAILED proof tooling unchanged (tests/l10n/LedgerTest.java)", output)
        self.repo.git("reset", "-q", "--soft", "HEAD~1")
        self.assertEqual(self.print_tooling(), [self.LEDGER_TEST])

    def test_an_english_change_may_not_edit_the_tools(self):
        self.english_change()
        self.repo.write("tools/l10n/frozen_literals.py", "def run():\n    return []\n")
        code, output = self.run_ci()
        self.assertEqual(code, 1, output)
        self.assertIn("FAILED proof tooling unchanged (tools/l10n/frozen_literals.py)", output)

    def test_an_english_change_may_not_delete_a_test(self):
        self.english_change()
        self.repo.remove("tests/l10n/XTest.java")
        code, output = self.run_ci()
        self.assertEqual(code, 1, output)
        self.assertIn("FAILED proof tooling unchanged (tests/l10n/XTest.java)", output)

    def test_a_batch_may_still_not_edit_a_test(self):
        self.repo.write("res/values/strings_rides.xml", '<resources><string name="rides_x">X</string></resources>\n')
        self.repo.write(self.CHARGING, java_class("ChargingTest", '    String s() { return "Old words"; }\n'
                                                                  '    String t() { return "a"; }'))
        code, output = self.run_ci()
        self.assertEqual(code, 1, output)
        self.assertIn("ci_gates: batch change", output)
        self.assertIn("FAILED proof tooling unchanged (tests/l10n/ChargingTest.java)", output)
        self.assertNotIn("tests/l10n edits follow", output)
        self.repo.git("reset", "-q", "--soft", "HEAD~1")
        self.assertEqual(self.print_tooling(), [self.CHARGING])

    def test_only_existing_non_gate_tests_are_reported(self):
        for path, reported in (("tests/l10n/ChargingTest.java", True), ("tests/l10n/MainTextTest.java", True),
                               ("tests/l10n/LedgerTest.java", False), ("tests/l10n/FmtLegacyTest.java", False),
                               ("tests/l10n/ReportsStayEnglishTest.java", False),
                               ("tests/l10n/GlyphCoverageTest.java", False), ("tests/l10n/CatalogCheckTest.java", False),
                               ("tests/l10n/CatalogUsageTest.java", False), ("tests/l10n/GoldenReports.java", False),
                               ("tests/l10n/L10nFiles.java", False), ("tests/l10n/sub/ChargingTest.java", False),
                               ("tests/l10n/golden.sh", False), ("tests/ChargingTest.java", False)):
            self.assertEqual(ci_gates.english_test_file(path), reported, path)


class ProveEnglishRuleTest(unittest.TestCase):
    def test_g8_follows_ci_gates_for_each_kind(self):
        here = os.path.dirname(os.path.abspath(__file__))
        with open(os.path.join(here, "prove-english.sh"), encoding="utf-8") as handle:
            script = handle.read()
        ordinary = script.index('if [[ "$KIND" == ordinary ]]; then\n    gate "G8')
        branch = script[ordinary:script.index("fi\n", ordinary)]
        listed, rest = branch.split("else\n")
        self.assertNotIn("--base", listed)
        self.assertIn('--base "$BASE" --tight', rest, "a batch or an English change may only shrink")

    def test_g5_follows_ci_gates_for_each_kind(self):
        """ci_gates.plan adds G5 for every kind but an ordinary change; the proof does the same."""
        self.assertNotIn("G5 characterization first",
                         [s[0] for s in ci_gates.plan("ordinary", "origin/main", "HEAD", "abc", "/repo")])
        for kind in ("batch", "english-change"):
            self.assertIn("G5 characterization first",
                          [s[0] for s in ci_gates.plan(kind, "origin/main", "HEAD", "abc", "/repo")])
        here = os.path.dirname(os.path.abspath(__file__))
        with open(os.path.join(here, "prove-english.sh"), encoding="utf-8") as handle:
            script = handle.read()
        ordinary = script.index('if [[ "$KIND" == ordinary ]]; then\n    record "G5 characterization first" "not applied"')
        branch = script[ordinary:script.index("\nfi\n", ordinary)]
        listed, rest = branch.split("else\n")
        self.assertNotIn("char_first.py", listed)
        self.assertIn("char_first.py", rest)


class ProveEnglishKindsTest(unittest.TestCase):
    """prove-english.sh itself, on a throwaway repository: the tools are this checkout's, the unit tests and
    goldens are stubs so that only the kind's rules decide the result."""

    G1_ROW = re.compile(r"^\| G1 ledger of moved literals \| ([^|]+) \|", re.M)

    def setUp(self):
        self.repo = TempRepo()
        self.addCleanup(self.repo.close)
        here = os.path.dirname(os.path.abspath(__file__))
        os.makedirs(self.repo.path("tools/l10n"))
        for name in os.listdir(here):
            if name.endswith((".py", ".sh")) and not name.startswith("test_"):
                shutil.copy(os.path.join(here, name), self.repo.path("tools/l10n"))
        self.repo.write("tools/l10n/test_stub.py", "import unittest\n\n\nclass T(unittest.TestCase):\n"
                                                   "    def test_stub(self):\n        pass\n")
        self.repo.write("tests/l10n/golden.sh", "echo 'golden: PASS: stub'\n")
        self.repo.write(GOLDEN, "<trk>\n")
        self.repo.write("res/values/strings.xml", "<resources>\n</resources>\n")
        os.makedirs(self.repo.path("l10n"))
        shutil.copytree(os.path.join(here, "..", "..", "l10n", "lang"), self.repo.path("l10n/lang"))
        self.repo.write(STORE, java_class("PhoneRideStore", '    String name() { return "x_y"; }\n'
                                          '    String open() { return "<trk>"; }'))
        self.base = self.repo.commit("base")

    def prove(self, run_tests=False):
        flags = ["--skip-build"] + ([] if run_tests else ["--skip-tests"])
        result = subprocess.run(["bash", "tools/l10n/prove-english.sh", self.base] + flags,
                                cwd=self.repo.root, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        report = result.stdout.decode("utf-8", "replace")
        everything = report + result.stderr.decode("utf-8", "replace")
        match = self.G1_ROW.search(report)
        self.assertIsNotNone(match, everything)
        return result.returncode, match.group(1).strip(), report, everything

    def change_a_literal(self):
        self.repo.write(STORE, java_class("PhoneRideStore", '    String name() { return "x_y"; }\n'
                                          '    String open() { return "<trk_changed>"; }'))

    def test_an_ordinary_change_of_a_literal_passes_without_claiming_g1(self):
        self.change_a_literal()
        self.repo.commit("a fix")
        code, g1, report, everything = self.prove()
        self.assertEqual(code, 0, everything)
        self.assertEqual(g1, "not applied")
        self.assertIn("ordinary change", report)
        self.assertRegex(report, r"\| Ledgers agree with the catalog \| PASS \|")
        self.assertIn("not a proof that English is unchanged", report)
        self.assertNotIn("every gate that runs locally passed", report)
        self.assertNotRegex(report, r"\| G1[^|]*\| PASS")

    def test_an_ordinary_golden_update_is_reported_and_a_batch_one_fails(self):
        self.repo.write(GOLDEN, "<trk updated>\n")
        self.repo.commit("an ordinary report format change")
        code, _, report, everything = self.prove()
        self.assertEqual(code, 0, everything)
        self.assertRegex(report, r"\| G9 reports stay English \(goldens\) \| REPORT \|")
        self.repo.write("res/values/strings_rides.xml", '<resources><string name="rides_x">X</string></resources>\n')
        self.repo.commit("now a batch")
        code, _, report, everything = self.prove()
        self.assertEqual(code, 1, everything)
        self.assertRegex(report, r"\| G9 reports stay English \(goldens\) \| FAIL \|")

    def test_an_ordinary_change_is_still_held_to_the_catalog(self):
        self.repo.write("l10n/ledger/b1.tsv", HEADER + "\t".join(
            ["src/com/jjricks/volt/A.java:1", '["x"]', "zz_gone", "exact", ""]) + "\n")
        self.base = self.repo.commit("a base whose ledger names a key the catalog lacks")
        self.change_a_literal()
        self.repo.commit("a fix")
        code, g1, report, everything = self.prove()
        self.assertEqual(code, 1, everything)
        self.assertEqual(g1, "not applied")
        self.assertRegex(report, r"\| Ledgers agree with the catalog \| FAIL \|")
        self.assertIn("Result: FAILED", report)
        self.assertNotIn("not a proof", report)

    def test_a_batch_that_changes_a_literal_fails_g1(self):
        self.change_a_literal()
        self.repo.write("res/values/strings_rides.xml", '<resources><string name="rides_x">X</string></resources>\n')
        self.repo.commit("a batch")
        code, g1, report, everything = self.prove()
        self.assertEqual(code, 1, everything)
        self.assertEqual(g1, "FAIL")
        self.assertIn("batch change", report)
        self.assertIn("Result: FAILED", report)

    def test_a_conversion_that_forgot_its_ledger_is_a_batch_and_fails_g1(self):
        self.repo.write(STORE, java_class("PhoneRideStore", '    String name() { return "x_y"; }\n'
                                          '    String open() { return T.ridesOpen(); }'))
        self.repo.commit("no ledger, no catalog file")
        code, g1, report, everything = self.prove()
        self.assertEqual(code, 1, everything)
        self.assertEqual(g1, "FAIL")
        self.assertIn("batch change", report)

    def test_an_english_change_runs_g1(self):
        self.change_a_literal()
        self.repo.write("l10n/english-changes.tsv", "key\tquantity\told\tnew\tnote\nzz\t-\ta\tb\towner approved in #999\n")
        self.repo.commit("an English change")
        code, g1, report, everything = self.prove()
        self.assertEqual(code, 1, everything)
        self.assertEqual(g1, "FAIL")
        self.assertIn("english-change change", report)

    def test_a_feature_text_change_is_held_to_the_batch_gates_and_lists_its_feature_test(self):
        """The feature-text kind in the proof: G1 runs, the feature test's edit is listed, and a proof test's isn't
        allowed."""
        self.repo.write("tools/l10n/test-kinds.tsv", "test\tkind\treason\ntests/l10n/RulesTest.java\tfeature\trules\n")
        self.repo.write("tests/l10n/RulesTest.java", java_class("RulesTest", "    static int n() { return 1; }"))
        self.repo.write("tests/l10n/WordsTest.java", java_class("WordsTest", '    String s() { return "Old words"; }'))
        self.repo.write("res/values/strings_rides.xml", rides_catalog(OLD_KEY))
        self.repo.write(".gitignore", "__pycache__/\n")  # the first proof run compiles tools/l10n; the second commit must not hold it
        self.base = self.repo.commit("a base with a feature test and a proof test")
        self.repo.write("res/values/strings_rides.xml", rides_catalog(OLD_KEY, NEW_KEY))
        self.repo.write("tests/l10n/RulesTest.java", java_class("RulesTest", "    static int n() { return 2; }"))
        self.repo.commit("new English and the feature's test")
        code, g1, report, everything = self.prove()
        self.assertEqual(code, 0, everything)
        self.assertIn("feature-text change", report)
        self.assertEqual(g1, "PASS")
        self.assertRegex(report, r"\| Feature text: only new English \| PASS \| 1 new English key \(rides_not_saved\)")
        self.assertRegex(report, r"\| Feature tests and runners edited \| REPORT \| 1 file\(s\): tests/l10n/RulesTest.java;")
        self.assertRegex(report, r"\| G4 frozen test statements \| REPORT \|")
        self.assertRegex(report, r"\| Proof tooling unchanged \| PASS \|")
        self.assertIn("every gate that runs locally passed", report)
        self.repo.write("tests/l10n/WordsTest.java", java_class("WordsTest", '    String s() { return "New words"; }'))
        self.repo.commit("and a proof test")
        code, _, report, everything = self.prove()
        self.assertEqual(code, 1, everything)
        self.assertRegex(report, r"\| Proof tooling unchanged \| FAIL \| changed in a feature-text pull request: 1 file\(s\), "
                                 r"e\.g\. tests/l10n/WordsTest.java")

    # tests/run.sh's shape in small: compile what the tests/l10n/*.sources lists name, then run their tests by name,
    # with the JDK navee_find_java would put first on PATH.
    SUITE_RUNNER = r"""#!/usr/bin/env bash
set -euo pipefail
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
out="$(mktemp -d)"
sources=()
l10n_tests=()
for list in tests/l10n/*.sources; do
    while IFS= read -r line || [[ -n "$line" ]]; do
        line="${line%%#*}"; line="${line//[[:space:]]/}"
        [[ -n "$line" ]] || continue
        sources+=("$line")
        if [[ "$line" == tests/l10n/*Test.java ]]; then l10n_tests+=("$(basename -- "$line" .java)"); fi
    done < "$list"
done
javac -d "$out" "${sources[@]}"
for name in ${l10n_tests[@]+"${l10n_tests[@]}"}; do java -cp "$out" "com.jjricks.volt.$name"; done
"""
    SUITE = ("package com.jjricks.volt;\n\npublic final class %s {\n    public static void main(String[] args) {\n"
             "        System.out.println(\"%s passed\");\n    }\n}\n")

    def feature_text_with_suites(self, runner_edit):
        """A base whose tests/run.sh runs a proof suite (WordsTest) and a feature suite (RulesTest); then a feature-text
        change that edits the feature test and tests/run.sh with runner_edit (old, new)."""
        self.repo.write("tools/l10n/test-kinds.tsv", "test\tkind\treason\ntests/l10n/RulesTest.java\tfeature\trules\n")
        self.repo.write("tests/run.sh", self.SUITE_RUNNER)
        for name in ("WordsTest", "RulesTest"):
            self.repo.write("tests/l10n/%s.java" % name, self.SUITE % (name, name))
        self.repo.write("tests/l10n/rides.sources", "tests/l10n/WordsTest.java\ntests/l10n/RulesTest.java\n")
        self.repo.write("res/values/strings_rides.xml", rides_catalog(OLD_KEY))
        self.repo.write(".gitignore", "__pycache__/\n")
        self.base = self.repo.commit("a base with a proof suite and a feature suite")
        self.repo.write("res/values/strings_rides.xml", rides_catalog(OLD_KEY, NEW_KEY))
        self.repo.write("tests/l10n/RulesTest.java", (self.SUITE % ("RulesTest", "RulesTest")).replace("passed", "passed!"))
        if runner_edit:
            self.assertIn(runner_edit[0], self.SUITE_RUNNER)
            self.repo.write("tests/run.sh", self.SUITE_RUNNER.replace(runner_edit[0], runner_edit[1], 1))
        self.repo.commit("new English, the feature's test and a runner edit")
        return self.prove(run_tests=True)

    PROOF_SUITES = re.compile(r"^\| G6 proof suites ran, by name \| ([A-Z]+) \| ([^|]*)\|", re.M)

    def test_a_feature_text_change_runs_its_proof_suites_by_name(self):
        code, _, report, everything = self.feature_text_with_suites(None)
        self.assertEqual(code, 0, everything)
        self.assertRegex(report, r"\| G6 existing suites \(tests/run.sh\) \| PASS \|")
        self.assertEqual(self.PROOF_SUITES.search(report).group(1), "PASS", everything)

    def test_a_runner_edit_that_stops_the_proof_suites_fails(self):
        """Council F02 on #646: each edit keeps run.sh's l10n lines in order, and run.sh still exits 0."""
        for edit in (("for name in ${l10n_tests", "l10n_tests=()\nfor name in ${l10n_tests"),  # an emptied list
                     ("javac -d", "exit 0\njavac -d"),  # an early exit
                     ('        sources+=("$line")\n', '        sources+=("$line")\n'
                      '        [[ "$line" == *WordsTest* ]] && continue\n')):  # a filter in the sources loop
            with self.subTest(edit=edit[1]):
                code, _, report, everything = self.feature_text_with_suites(edit)
                self.assertEqual(code, 1, everything)
                self.assertIn("feature-text change", report)
                self.assertRegex(report, r"\| G6 existing suites \(tests/run.sh\) \| PASS \|", everything)
                self.assertRegex(report, r"\| Feature text: only new English \| PASS \|", everything)
                match = self.PROOF_SUITES.search(report)
                self.assertEqual(match.group(1), "FAIL", everything)
                self.assertIn("1 of 1 proof suite(s) did not run and pass at head: WordsTest", match.group(2))
                self.repo.git("reset", "-q", "--hard", self.base)

    CHAR = "tests/l10n/characterization/RidesEnglishTest.java"
    OTHER = "tests/l10n/OtherTest.java"
    # Reads the tests/l10n/*.sources lists as the real tests/run.sh does ("#" starts a comment, the rest is
    # trimmed) and runs only the tests/l10n/**Test.java entries; a test still pinning "Old words" fails.
    LIST_ONLY_RUNNER = r"""#!/bin/bash
for list in tests/l10n/*.sources; do
  [[ -f "$list" ]] || continue
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%%#*}"; line="${line#"${line%%[![:space:]]*}"}"; line="${line%"${line##*[![:space:]]}"}"
    [[ -n "$line" ]] || continue
    case "$line" in tests/l10n/*Test.java)
      grep -q 'Old words' "$line" && { echo "$line failed"; exit 1; }
      echo "$line passed" ;; esac
  done < "$list"
done
"""
    UNLISTED = (r"\| G5 tests pass at the first commit \| FAIL \| not an active entry of any tests/l10n/\*\.sources "
                r"list( at head)?, so tests/run.sh never runs them: " + re.escape(CHAR))

    def english_change_with_characterization(self, first_sources, head_sources=None):
        """A base with a list-only tests/run.sh; a first commit pinning the new English, with first_sources as
        tests/l10n/rides.sources (None: no list); then the English change, which may rewrite the list."""
        self.repo.write("tests/run.sh", self.LIST_ONLY_RUNNER)
        self.repo.write(self.OTHER, java_class("OtherTest", '    String s() { return "Other words"; }'))
        self.base = self.repo.commit("a list-only test runner")
        self.repo.write(self.CHAR, java_class("RidesEnglishTest", '    String s() { return "New words"; }'))
        if first_sources is not None:
            self.repo.write("tests/l10n/rides.sources", first_sources)
        self.repo.commit("pin the new English")
        self.repo.write("l10n/english-changes.tsv", "key\tquantity\told\tnew\tnote\nzz\t-\ta\tb\towner approved in #999\n")
        if head_sources is not None:
            self.repo.write("tests/l10n/rides.sources", head_sources)
        self.repo.commit("the English change")

    def assert_unlisted(self):
        for run_tests in (True, False):
            code, _, report, everything = self.prove(run_tests=run_tests)
            self.assertEqual(code, 1, everything)
            self.assertRegex(report, self.UNLISTED, everything)

    def test_an_english_change_does_not_run_its_characterization_tests_on_the_base_code(self):
        """Its characterization tests pin the new English, which the base code does not print yet."""
        self.english_change_with_characterization("# the rides suites\n" + self.CHAR + "\n")
        _, _, report, everything = self.prove()
        self.assertIn("english-change change", report)
        self.assertRegex(report, r"\| G5 characterization first \| PASS \|", everything)
        self.assertRegex(report, r"\| G5 tests pass at the first commit \| n/a \| an English change's characterization "
                                 r"tests pin the new English")

    def test_an_english_change_runs_its_registered_characterization_tests_at_head(self):
        """Re-review of #207: the exemption is from running them on the base code, not from running them at all.
        An entry with surrounding whitespace and an inline comment is still an entry."""
        self.english_change_with_characterization("# the rides suites\n  \t" + self.CHAR + "   # the Rides screen\n")
        _, _, report, everything = self.prove(run_tests=True)
        self.assertRegex(report, r"\| G5 tests pass at the first commit \| n/a \|", everything)
        self.assertRegex(report, r"\| G6 existing suites \(tests/run.sh\) \| PASS \| 1 suites reported passing \|",
                         everything)

    def test_an_english_change_with_an_unregistered_characterization_test_fails(self):
        """Re-review of #207: tests/run.sh would never run it, so G6 passing would prove nothing about it."""
        self.english_change_with_characterization(None)
        self.assert_unlisted()

    def test_an_english_change_that_comments_out_its_characterization_entry_fails(self):
        """Second re-review of #207: a commented-out entry is no entry, though the path is still in the file."""
        self.english_change_with_characterization(self.CHAR + "\n", head_sources="# " + self.CHAR + "\n")
        self.assert_unlisted()

    def test_a_characterization_path_in_another_entrys_comment_is_not_an_entry(self):
        """Second re-review of #207: tests/run.sh runs OtherTest only."""
        self.english_change_with_characterization(self.OTHER + " # replaces " + self.CHAR + "\n")
        self.assert_unlisted()

    def test_a_batch_still_runs_its_characterization_tests_at_the_first_commit(self):
        self.repo.write(self.CHAR, java_class("RidesEnglishTest", '    String s() { return "Same words"; }'))
        self.repo.commit("characterize")
        self.repo.write("res/values/strings_rides.xml", '<resources><string name="rides_x">X</string></resources>\n')
        self.repo.commit("a batch")
        _, _, report, everything = self.prove()
        self.assertIn("batch change", report)
        # --skip-tests (see prove) skips the run itself; the n/a row is the English change's alone.
        self.assertNotIn("G5 tests pass at the first commit", report, everything)

    def test_a_batch_with_a_commented_out_characterization_entry_fails_at_the_first_commit(self):
        """Second re-review of #207: the batch check reads the lists the same way."""
        self.repo.write("tests/run.sh", self.LIST_ONLY_RUNNER)
        self.base = self.repo.commit("a list-only test runner")
        self.repo.write(self.CHAR, java_class("RidesEnglishTest", '    String s() { return "Same words"; }'))
        self.repo.write("tests/l10n/rides.sources", "#" + self.CHAR + "\n")
        self.repo.commit("characterize")
        self.repo.write("res/values/strings_rides.xml", '<resources><string name="rides_x">X</string></resources>\n')
        self.repo.commit("a batch")
        code, _, report, everything = self.prove(run_tests=True)
        self.assertIn("batch change", report)
        self.assertEqual(code, 1, everything)
        self.assertRegex(report, self.UNLISTED, everything)

    G5_ROW = r"\| G5 characterization first \| ([^|]+) \|"

    def characterize_with_code(self):
        """One commit that changes a characterization test and src/ together: G5's rule 2 fails it."""
        self.repo.write(self.CHAR, java_class("RidesEnglishTest", '    String s() { return "Same words"; }'))
        self.change_a_literal()
        self.repo.commit("a test and the code it covers, together")

    def test_an_ordinary_change_is_not_held_to_g5(self):
        """As ci_gates.plan: G5 binds batches and English changes; an ordinary change may edit a
        characterization test with the code, so G5 reads "not applied" (never PASS) and nothing runs at a
        first commit."""
        self.repo.write("tests/run.sh", self.LIST_ONLY_RUNNER)
        self.base = self.repo.commit("a list-only test runner")
        self.characterize_with_code()
        for run_tests in (False, True):
            code, _, report, everything = self.prove(run_tests=run_tests)
            self.assertIn("ordinary change", report)
            self.assertEqual(code, 0, everything)
            self.assertEqual(re.search(self.G5_ROW, report).group(1).strip(), "not applied", everything)
            self.assertNotIn("G5 tests pass at the first commit", report, everything)
            self.assertIn("G1 and G5 were not applied", report)

    def test_the_same_commits_in_a_batch_fail_g5(self):
        self.characterize_with_code()
        self.repo.write("res/values/strings_rides.xml", '<resources><string name="rides_x">X</string></resources>\n')
        self.repo.commit("now a batch")
        code, _, report, everything = self.prove()
        self.assertIn("batch change", report)
        self.assertEqual(code, 1, everything)
        self.assertEqual(re.search(self.G5_ROW, report).group(1).strip(), "FAIL", everything)

    def test_a_kind_ci_gates_could_not_tell_is_held_to_g5(self):
        self.repo.write("tools/l10n/ci_gates.py", "import sys\nsys.exit(3)\n")
        self.base = self.repo.commit("ci_gates cannot run")
        self.characterize_with_code()
        code, _, report, everything = self.prove()
        self.assertIn("unknown change", report)
        self.assertEqual(code, 1, everything)
        self.assertEqual(re.search(self.G5_ROW, report).group(1).strip(), "FAIL", everything)

    LISTING = "tests/l10n/characterization/rides-english.tsv"
    READER = "tests/l10n/characterization/RidesListingTest.java"
    PRINTED = "src/rides-printed.txt"
    ROWS = "entry a\tA\nentry b\tB\nentry c\tC\n"
    # The listing test as tests/run.sh runs it: the listing must be what the code prints, line for line.
    LISTING_RUNNER = r"""#!/bin/bash
cmp -s tests/l10n/characterization/rides-english.tsv src/rides-printed.txt || { echo "RidesListingTest failed"; exit 1; }
echo "RidesListingTest passed"
"""
    FIRST_ROW = r"\| G5 tests pass at the first commit \| ([^|]+) \| ([^|]+) \|"
    MAIN = "    public static void main(String[] args) { /* the runner above stands in for it */ }"

    def listing_change(self, first_rows, printed_rows, kind="batch", reader=None, sources=None):
        """A base whose code prints ROWS and whose listing pins them; a first commit that writes first_rows to
        the listing (and reader to the listing test); then the change, whose code prints printed_rows."""
        self.repo.write("tests/run.sh", self.LISTING_RUNNER)
        self.repo.write(self.LISTING, self.ROWS)
        self.repo.write(self.PRINTED, self.ROWS)
        self.repo.write(self.READER, java_class("RidesListingTest", '    String listing = "%s";\n%s' % (self.LISTING, self.MAIN)))
        self.repo.write("tests/l10n/rides.sources", sources if sources is not None else self.READER + "\n")
        self.base = self.repo.commit("a listing the code prints")
        self.repo.write(self.LISTING, first_rows)
        if reader is not None:
            self.repo.write(self.READER, reader)
        self.repo.commit("characterize")
        self.repo.write(self.PRINTED, printed_rows)
        if kind == "batch":
            self.repo.write("res/values/strings_rides.xml", '<resources><string name="rides_x">X</string></resources>\n')
        elif kind == "english-change":
            self.repo.write("l10n/english-changes.tsv", "key\tquantity\told\tnew\tnote\nzz\t-\ta\tb\towner approved in #999\n")
        self.repo.commit("the change")
        return self.prove(run_tests=True)

    def first_row(self, report, everything):
        match = re.search(self.FIRST_ROW, report)
        self.assertIsNotNone(match, everything)
        return match.group(1).strip(), match.group(2).strip()

    def test_a_batch_that_only_adds_listing_rows_is_not_run_at_the_first_commit(self):
        """Owner-approved Nov 13 2026 (#437): a row for an entry new in the pull request cannot pass on the
        base code. The report says the run did not happen, never PASS, and leaves the rows to G6 at head."""
        rows = self.ROWS + "entry new\tNew\n"
        code, _, report, everything = self.listing_change(rows, rows)
        self.assertIn("batch change", report)
        self.assertEqual(code, 0, everything)
        result, detail = self.first_row(report, everything)
        self.assertEqual(result, "not run", everything)
        self.assertRegex(detail, r"^the first commit [0-9a-f]{12} only adds rows to " + re.escape(self.LISTING) +
                         r"; existing rows unchanged \(3 lines\), new rows \(1\) are left to the run at head \(G6\)$")
        self.assertRegex(report, r"\| G6 existing suites \(tests/run.sh\) \| PASS \|", everything)
        self.assertNotRegex(report, r"\| G5 tests pass at the first commit \| PASS", everything)

    def test_the_new_rows_still_fail_at_head(self):
        """Review of #446 (finding 2): the G5 row claims nothing G6 then contradicts."""
        rows = self.ROWS + "entry new\tNew\n"
        code, _, report, everything = self.listing_change(rows, self.ROWS)
        self.assertEqual(code, 1, everything)
        result, detail = self.first_row(report, everything)
        self.assertEqual(result, "not run", everything)
        self.assertNotIn("proven", detail)
        self.assertRegex(report, r"\| G6 existing suites \(tests/run.sh\) \| FAIL \|", everything)

    def assert_run_at_the_first_commit(self, first_rows):
        code, _, report, everything = self.listing_change(first_rows, first_rows)
        self.assertEqual(code, 1, everything)
        result, detail = self.first_row(report, everything)
        self.assertEqual(result, "FAIL", everything)
        self.assertIn("tests/run.sh or golden.sh at", detail)

    def test_an_added_row_with_a_changed_existing_row_runs_at_the_first_commit(self):
        self.assert_run_at_the_first_commit("entry a\tA\nentry b\tB changed\nentry c\tC\nentry new\tNew\n")

    def test_a_removed_row_runs_at_the_first_commit(self):
        self.assert_run_at_the_first_commit("entry a\tA\nentry c\tC\n")

    def test_a_reordered_listing_runs_at_the_first_commit(self):
        self.assert_run_at_the_first_commit("entry b\tB\nentry a\tA\nentry c\tC\n")

    def test_an_addition_beside_a_changed_test_file_runs_at_the_first_commit(self):
        rows = self.ROWS + "entry new\tNew\n"
        reader = java_class("RidesListingTest", '    String listing = "%s";\n    int rows;\n%s' % (self.LISTING, self.MAIN))
        code, _, report, everything = self.listing_change(rows, rows, reader=reader)
        self.assertEqual(code, 1, everything)
        self.assertEqual(self.first_row(report, everything)[0], "FAIL", everything)

    def test_a_listed_helper_without_the_listed_test_runs_at_the_first_commit(self):
        """Review of #446 (finding 1): tests/run.sh runs only listed tests/l10n/*Test.java, so a listed helper
        that mentions the listing reads nothing at head; the first commit gets the real run."""
        helper = "tests/l10n/characterization/RidesListingRows.java"
        self.repo.write(helper, java_class("RidesListingRows", '    String listing = "%s";\n%s' % (self.LISTING, self.MAIN)))
        rows = self.ROWS + "entry new\tNew\n"
        code, _, report, everything = self.listing_change(rows, rows, sources=helper + "\n")
        self.assertEqual(code, 1, everything)
        result, detail = self.first_row(report, everything)
        self.assertEqual(result, "FAIL", everything)
        self.assertIn("tests/run.sh or golden.sh at", detail)

    def test_an_english_change_that_adds_listing_rows_is_as_before(self):
        rows = self.ROWS + "entry new\tNew\n"
        _, _, report, everything = self.listing_change(rows, rows, kind="english-change")
        self.assertIn("english-change change", report)
        self.assertEqual(self.first_row(report, everything)[0], "n/a", everything)

    def test_an_ordinary_change_that_adds_listing_rows_is_as_before(self):
        rows = self.ROWS + "entry new\tNew\n"
        code, _, report, everything = self.listing_change(rows, rows, kind="ordinary")
        self.assertIn("ordinary change", report)
        self.assertEqual(code, 0, everything)
        self.assertEqual(re.search(self.G5_ROW, report).group(1).strip(), "not applied", everything)
        self.assertNotIn("G5 tests pass at the first commit", report, everything)

    def test_a_batch_that_keeps_its_literals_gets_a_real_g1_pass(self):
        self.repo.write("res/values/strings_rides.xml", '<resources><string name="rides_x">X</string></resources>\n')
        self.repo.commit("catalog only")
        _, g1, report, everything = self.prove()
        self.assertEqual(g1, "PASS", everything)
        self.assertNotIn("Ledgers agree with the catalog", report)

    def test_a_kind_ci_gates_could_not_tell_is_held_to_g1(self):
        self.repo.write("tools/l10n/ci_gates.py", "import sys\nsys.exit(3)\n")
        self.base = self.repo.commit("ci_gates cannot run")
        self.change_a_literal()
        self.repo.commit("a fix")
        code, g1, report, everything = self.prove()
        self.assertEqual(code, 1, everything)
        self.assertEqual(g1, "FAIL")
        self.assertIn("unknown change", report)



VOLT_NS = 'xmlns:volt="https://volt.jjricks.com/ns/l10n"'


def rides_catalog(*entries):
    return "<resources %s>\n%s</resources>\n" % (VOLT_NS, "".join("    %s\n" % e for e in entries))


OLD_KEY = '<string name="rides_old" volt:screen="Rides" volt:purpose="a button">Old words</string>'
NEW_KEY = '<string name="rides_not_saved" volt:screen="Rides" volt:purpose="a toast">Ride not saved, too short</string>'
FEATURE_TEST = "tests/l10n/AutoStartPolicyTest.java"
PROOF_TEST = "tests/l10n/DashboardScreenTextTest.java"
RUN_SH = "tests/run.sh"
RUN_SH_TEXT = ('#!/usr/bin/env bash\nset -euo pipefail\njava -cp x com.jjricks.volt.RideStoreTest\n'
               'l10n_tests=()\nfor list in tests/l10n/*.sources; do :; done\n'
               'for name in ${l10n_tests[@]+"${l10n_tests[@]}"}; do java "com.jjricks.volt.$name"; done\n')
TEST_KINDS = ("test\tkind\treason\n"
              "tests/l10n/AutoStartPolicyTest.java\tfeature\trules only\n"
              "tests/l10n/DashboardScreenTextTest.java\tproof\tasserts the dashboard's text\n"
              "tests/l10n/CatalogUsageTest.java\tfeature\ta gate test listed by mistake stays proof\n")


class FeatureTextTest(unittest.TestCase):
    """The feature-text kind (owner-approved Nov 24 2026): a pull request that only adds new English keys (and T or
    M calls) alongside a feature. Existing English stays provably unchanged; feature tests, validations, tests/run.sh
    and android-tests/build.sh may change as in an ordinary pull request; the proof tests stay frozen."""

    def setUp(self):
        self.repo = TempRepo()
        self.addCleanup(self.repo.close)
        self.repo.write("res/values/strings_rides.xml", rides_catalog(OLD_KEY))
        self.repo.write("res/values-de/strings_rides.xml", '<resources><string name="rides_old">Alte Worte</string></resources>\n')
        self.repo.write(STORE, java_class("PhoneRideStore", '    String id() { return "x_y"; }\n\n'
                                          '    String label() { return "Saved"; }\n\n'
                                          '    int count() { return 1; }'))
        self.repo.write(GOLDEN, "<trk><name>Ride GOLDEN-RIDE-1</name>\n")
        self.repo.write(FEATURE_TEST, java_class("AutoStartPolicyTest", '    static boolean gate() { return true; }'))
        self.repo.write(PROOF_TEST, java_class("DashboardScreenTextTest", '    String s() { return "Old words"; }'))
        self.repo.write("tests/l10n/CatalogUsageTest.java", java_class("CatalogUsageTest", '    String s() { return "a"; }'))
        self.repo.write("tests/l10n/UnlistedTest.java", java_class("UnlistedTest", '    String s() { return "a"; }'))
        self.repo.write("tests/l10n/rides.sources", FEATURE_TEST + "\n" + PROOF_TEST + "\n" +
                        "tests/l10n/CatalogUsageTest.java\ntests/l10n/UnlistedTest.java\n")
        self.repo.write(RUN_SH, RUN_SH_TEXT)
        self.repo.write("android-tests/build.sh", "#!/bin/sh\njavac A.java\n")
        self.repo.write("tools/l10n/test-kinds.tsv", TEST_KINDS)
        self.base = self.repo.commit("base")

    store_body = None  # set by base_with

    def add_text(self):
        """The feature's new English key and its one call, in a new method away from the old literals."""
        keys = (OLD_KEY, self.OTHER_KEY, NEW_KEY) if self.store_body else (OLD_KEY, NEW_KEY)
        self.repo.write("res/values/strings_rides.xml", rides_catalog(*keys))
        body = self.store_body or ('    String id() { return "x_y"; }\n\n    String label() { return "Saved"; }\n\n'
                                   '    int count() { return 1; }')
        self.repo.write(STORE, java_class("PhoneRideStore", body + '\n\n'
                                          '    CharSequence tooShort() { return T.ridesNotSaved(); }'))

    def edit_feature_test(self):
        self.repo.write(FEATURE_TEST, java_class("AutoStartPolicyTest", '    static boolean gate() { return false; }'))

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

    def assert_batch(self, why):
        code, output = self.run_ci()
        self.assertEqual(code, 1, output)
        self.assertIn("ci_gates: batch change", output)
        self.assertRegex(output, r"not feature-text: [^\n]*%s" % re.escape(why))
        self.assertIn("FAILED proof tooling unchanged (%s)" % FEATURE_TEST, output)
        return output

    def test_new_text_with_a_feature_test_edit_qualifies_and_passes(self):
        self.add_text()
        self.edit_feature_test()
        code, output = self.run_ci("--pr-head", "HEAD")
        self.assertIn("ci_gates: feature-text change", output)
        self.assertIn("1 new English key (rides_not_saved), no existing English changed", output)
        self.assertIn("src gained catalog references (T.ridesNotSaved)", output)
        for gate in ("G1 ledger", "G5 characterization first", "G8 shrink-only baselines",
                     "G9 goldens unchanged since the merge base", "proof tooling unchanged",
                     "feature text adds only new English", "proof tests still run"):
            self.assertIn("PASS " + gate, output)
        self.assertRegex(output, r"REPORT feature tests and runners edited \(%s; " % re.escape(FEATURE_TEST))
        self.assertEqual(code, 0, output)

    def test_a_t_call_to_an_existing_key_alone_qualifies(self):
        self.repo.write(STORE, java_class("PhoneRideStore", '    String id() { return "x_y"; }\n\n'
                                          '    String label() { return "Saved"; }\n\n'
                                          '    int count() { return 1; }\n\n'
                                          '    CharSequence old() { return T.ridesOld(); }'))
        self.edit_feature_test()
        code, output = self.run_ci()
        self.assertIn("ci_gates: feature-text change", output)
        self.assertIn("no new English key, no existing English changed", output)
        self.assertEqual(code, 0, output)

    def test_a_changed_existing_value_stays_a_batch(self):
        self.add_text()
        self.edit_feature_test()
        self.repo.write("res/values/strings_rides.xml", rides_catalog(OLD_KEY.replace("Old words", "New words"), NEW_KEY))
        self.assert_batch("res/values/strings_rides.xml: rides_old changes (its English or its attributes)")

    def test_changed_metadata_on_an_existing_key_stays_a_batch(self):
        self.add_text()
        self.edit_feature_test()
        self.repo.write("res/values/strings_rides.xml", rides_catalog(OLD_KEY.replace("a button", "the button"), NEW_KEY))
        self.assert_batch("rides_old changes (its English or its attributes)")

    def test_a_removed_key_stays_a_batch(self):
        self.add_text()
        self.edit_feature_test()
        self.repo.write("res/values/strings_rides.xml", rides_catalog(NEW_KEY))
        self.assert_batch("res/values/strings_rides.xml: rides_old is removed or renamed")

    def test_a_key_moved_to_another_file_stays_a_batch(self):
        self.add_text()
        self.edit_feature_test()
        self.repo.write("res/values/strings_rides.xml", rides_catalog(NEW_KEY))
        self.repo.write("res/values/strings_more.xml", rides_catalog(OLD_KEY))
        self.assert_batch("rides_old changes (its English or its attributes)")

    def test_a_translation_touched_stays_a_batch(self):
        self.add_text()
        self.edit_feature_test()
        self.repo.write("res/values-de/strings_rides.xml", '<resources><string name="rides_old">Neue Worte</string></resources>\n')
        self.assert_batch("res/values-de/strings_rides.xml changed (translations)")

    def test_a_ledger_row_stays_a_batch(self):
        self.add_text()
        self.edit_feature_test()
        self.repo.write("l10n/ledger/b9.tsv", HEADER)
        self.assert_batch("l10n/ledger/b9.tsv changed (moving old text records ledger rows)")

    def test_a_new_key_without_its_metadata_stays_a_batch(self):
        self.add_text()
        self.edit_feature_test()
        self.repo.write("res/values/strings_rides.xml", rides_catalog(
            OLD_KEY, '<string name="rides_not_saved" volt:screen="Rides">Ride not saved, too short</string>'))
        self.assert_batch("res/values/strings_rides.xml: new key rides_not_saved has no volt:purpose")

    def test_a_src_literal_removed_stays_a_batch(self):
        self.add_text()
        self.edit_feature_test()
        self.repo.write(STORE, java_class("PhoneRideStore", '    String id() { return "x_y"; }\n\n'
                                          '    String label() { return T.ridesNotSaved().toString(); }\n\n'
                                          '    int count() { return 1; }'))
        output = self.assert_batch('src/com/jjricks/volt/PhoneRideStore.java:6: literal "Saved" left or changed')
        self.assertIn("FAILED G1 ledger", output)

    def test_a_proof_test_edited_is_refused(self):
        self.add_text()
        self.repo.write(PROOF_TEST, java_class("DashboardScreenTextTest", '    String s() { return "New words"; }'))
        code, output = self.run_ci()
        self.assertEqual(code, 1, output)
        self.assertIn("ci_gates: feature-text change", output)
        self.assertIn("FAILED proof tooling unchanged (%s)" % PROOF_TEST, output)
        self.assertIn("tools/l10n/test-kinds.tsv", output)

    def test_an_unlisted_test_and_a_gate_test_listed_as_feature_stay_proof(self):
        self.add_text()
        self.repo.write("tests/l10n/UnlistedTest.java", java_class("UnlistedTest", '    String s() { return "b"; }'))
        self.repo.write("tests/l10n/CatalogUsageTest.java", java_class("CatalogUsageTest", '    String s() { return "b"; }'))
        code, output = self.run_ci()
        self.assertEqual(code, 1, output)
        self.assertIn("FAILED proof tooling unchanged (tests/l10n/CatalogUsageTest.java, tests/l10n/UnlistedTest.java)",
                      output)

    def test_the_test_list_is_read_at_the_merge_base(self):
        """A pull request can't make a proof test editable for itself; editing the list is a tooling change."""
        self.add_text()
        self.repo.write("tools/l10n/test-kinds.tsv", TEST_KINDS.replace("proof\tasserts", "feature\tasserts"))
        self.repo.write(PROOF_TEST, java_class("DashboardScreenTextTest", '    String s() { return "New words"; }'))
        code, output = self.run_ci()
        self.assertEqual(code, 1, output)
        self.assertIn("FAILED proof tooling unchanged (%s, tools/l10n/test-kinds.tsv)" % PROOF_TEST, output)

    def test_a_feature_test_edited_is_allowed(self):
        self.add_text()
        self.edit_feature_test()
        code, output = self.run_ci()
        self.assertEqual(code, 0, output)
        self.assertIn("PASS proof tooling unchanged (unchanged)", output)
        self.assertRegex(output, r"REPORT G4 frozen test statements|PASS G4 frozen test statements")
        self.repo.git("reset", "-q", "--soft", "HEAD~1")
        code, output = self.run_ci("--print-tooling")
        self.assertEqual(output.split(), [])
        self.repo.git("reset", "-q", "--soft", "HEAD~1")
        code, output = self.run_ci("--print-feature-edits")
        self.assertEqual(output.split(), [FEATURE_TEST])

    def test_run_sh_and_build_sh_edited_are_allowed(self):
        self.add_text()
        self.repo.write(RUN_SH, RUN_SH_TEXT.replace("RideStoreTest\n", "RideStoreTest\njava -cp x com.jjricks.volt.NewTest\n"))
        self.repo.write("android-tests/build.sh", "#!/bin/sh\njavac A.java NewValidation.java\n")
        code, output = self.run_ci()
        self.assertEqual(code, 0, output)
        self.assertIn("PASS proof tooling unchanged (unchanged)", output)
        self.assertRegex(output, r"REPORT feature tests and runners edited \(android-tests/build.sh, tests/run.sh; ")

    def test_run_sh_must_keep_its_localization_lines(self):
        self.add_text()
        self.repo.write(RUN_SH, RUN_SH_TEXT.replace('for name in ${l10n_tests[@]+"${l10n_tests[@]}"}; do java "com.jjricks.volt.$name"; done\n', ""))
        code, output = self.run_ci()
        self.assertEqual(code, 1, output)
        self.assertIn("FAILED proof tests still run", output)
        self.assertIn("tests/run.sh: a line of its localization part changed or went", output)

    def test_a_proof_test_must_stay_listed(self):
        self.add_text()
        self.edit_feature_test()
        self.repo.write("tests/l10n/rides.sources", FEATURE_TEST + "\n# " + PROOF_TEST + "\n" +
                        "tests/l10n/CatalogUsageTest.java\ntests/l10n/UnlistedTest.java\n")
        code, output = self.run_ci()
        self.assertEqual(code, 1, output)
        self.assertIn("FAILED proof tests still run", output)
        self.assertIn("%s: no tests/l10n/*.sources list runs it at head" % PROOF_TEST, output)
        # A feature test may leave the lists with its feature.
        self.repo.git("reset", "-q", "--hard", "HEAD~1")
        self.add_text()
        self.repo.write("tests/l10n/rides.sources", PROOF_TEST + "\n" +
                        "tests/l10n/CatalogUsageTest.java\ntests/l10n/UnlistedTest.java\n")
        code, output = self.run_ci()
        self.assertEqual(code, 0, output)

    def test_a_golden_changed_is_refused(self):
        self.add_text()
        self.repo.write(GOLDEN, "<trk><name>Trip GOLDEN-RIDE-1</name>\n")
        code, output = self.run_ci()
        self.assertEqual(code, 1, output)
        self.assertIn("ci_gates: feature-text change", output)
        self.assertIn("FAILED G9 goldens unchanged since the merge base", output)

    def test_new_hard_coded_text_is_refused(self):
        self.add_text()
        self.repo.write(STORE, java_class("PhoneRideStore", '    String id() { return "x_y"; }\n\n'
                                          '    String label() { return "Saved"; }\n\n'
                                          '    int count() { return 1; }\n\n'
                                          '    CharSequence tooShort() { return T.ridesNotSaved(); }\n\n'
                                          '    void toast() { show("Ride not kept at all"); }'))
        code, output = self.run_ci()
        self.assertEqual(code, 1, output)
        self.assertIn("ci_gates: feature-text change", output)
        self.assertIn("FAILED G1 ledger", output)

    OTHER_KEY = '<string name="rides_other" volt:screen="Rides" volt:purpose="a caution">Other words</string>'
    CHAR = "tests/l10n/characterization/RidesEnglishTest.java"

    def base_with(self, store_body, *extra):
        """A new merge base: the catalog with OTHER_KEY too, STORE with store_body, and extra (path, text) pairs."""
        self.repo.write("res/values/strings_rides.xml", rides_catalog(OLD_KEY, self.OTHER_KEY))
        self.repo.write(STORE, java_class("PhoneRideStore", store_body))
        self.store_body = store_body
        for path, text in extra:
            self.repo.write(path, text)
        self.base = self.repo.commit("a base that calls the catalog")

    def test_repointing_an_existing_call_stays_a_batch(self):
        """Council F01 on #646: an existing call switched to another existing key, its feature test edited to match."""
        self.base_with('    CharSequence caution() { return T.ridesOld(); }')
        self.repo.write(STORE, java_class("PhoneRideStore", '    CharSequence caution() { return T.ridesOther(); }'))
        self.edit_feature_test()
        self.assert_batch("PhoneRideStore.java:4: a line holding T.ridesOld changed or went")
        self.repo.git("reset", "-q", "--hard", "HEAD~1")
        # Its line removed, the call moved to a new line: still a batch.
        self.repo.write(STORE, java_class("PhoneRideStore", '    CharSequence caution() {\n        return T.ridesOther();\n    }'))
        self.edit_feature_test()
        self.assert_batch("a line holding T.ridesOld changed or went")

    def test_reindenting_an_existing_call_keeps_feature_text(self):
        """#643 wraps an existing toast's line in a new if block: its tokens are the same, so it still only adds."""
        self.base_with('    void done(boolean v) {\n        if (v) show(T.ridesOld());\n    }')
        self.add_text()
        self.repo.write(STORE, java_class("PhoneRideStore", '    void done(boolean v) {\n        if (kept()) {\n'
                                          '            if (v) show(T.ridesOld());\n        }\n    }\n\n'
                                          '    CharSequence tooShort() { return T.ridesNotSaved(); }'))
        self.edit_feature_test()
        code, output = self.run_ci()
        self.assertIn("ci_gates: feature-text change", output)
        self.assertEqual(code, 0, output)

    def test_a_gained_r_string_stays_a_batch(self):
        """Council F11 on #646: only T. and M. calls qualify."""
        self.add_text()
        self.repo.write(STORE, java_class("PhoneRideStore", '    String id() { return "x_y"; }\n\n'
                                          '    String label() { return "Saved"; }\n\n'
                                          '    int count() { return 1; }\n\n'
                                          '    int cancel() { return R.string.rides_old; }'))
        self.edit_feature_test()
        self.assert_batch("src gains R.string.rides_old (only T. and M. calls qualify)")

    def test_strings_xml_stays_a_batch(self):
        """Council F03 and F05 on #646: gen_catalog reads res/values/strings.xml too."""
        self.add_text()
        self.edit_feature_test()
        self.repo.write("res/values/strings.xml", '<resources><string name="app_name">Volt</string></resources>\n')
        self.assert_batch("res/values/strings.xml changed (English text the catalog reads")

    def test_the_language_table_stays_a_batch(self):
        """Council F04 on #646: l10n/lang/*.tsv names the languages and their status."""
        self.add_text()
        self.edit_feature_test()
        self.repo.write("l10n/lang/de.tsv", "tag\tde\nstatus\tstable\n")
        self.assert_batch("l10n/lang/de.tsv changed (the language table")

    CHAR_TEXT = ('    static void check(boolean ok, String m) { if (!ok) throw new IllegalStateException(m); }\n\n'
                 '    public static void main(String[] a) {\n        check(T.ridesOld().equals("Old words"), "the old words");\n'
                 '        System.out.println("RidesEnglishTest: passed");\n    }')

    def base_with_characterization(self):
        self.base_with('    CharSequence caution() { return T.ridesOld(); }',
                       (self.CHAR, java_class("RidesEnglishTest", self.CHAR_TEXT)),
                       ("tests/l10n/rides-english.sources", self.CHAR + "\n"))

    def test_a_weakened_characterization_test_is_refused(self):
        """Council F16 on #646: G4 stays strict for characterization tests in feature text."""
        self.base_with_characterization()
        self.add_text()
        self.repo.write(self.CHAR, java_class("RidesEnglishTest", self.CHAR_TEXT.replace(
            '        check(T.ridesOld().equals("Old words"), "the old words");\n', "")))
        code, output = self.run_ci()
        self.assertEqual(code, 1, output)
        self.assertIn("ci_gates: feature-text change", output)
        self.assertIn("frozen_literals: %s:" % self.CHAR, output)
        self.assertIn("FAILED G4 frozen test statements", output)

    def test_a_deleted_characterization_test_is_refused(self):
        self.base_with_characterization()
        self.add_text()
        self.repo.remove(self.CHAR)
        self.repo.write("tests/l10n/rides-english.sources", "")
        code, output = self.run_ci()
        self.assertEqual(code, 1, output)
        self.assertIn("FAILED proof tooling unchanged (%s)" % self.CHAR, output)

    def test_a_characterization_test_dropped_from_its_list_is_refused(self):
        self.base_with_characterization()
        self.add_text()
        self.repo.write("tests/l10n/rides-english.sources", "# " + self.CHAR + "\n")
        code, output = self.run_ci()
        self.assertEqual(code, 1, output)
        self.assertIn("%s: no tests/l10n/*.sources list runs it at head" % self.CHAR, output)

    def test_a_quiet_assertion_error_is_refused(self):
        """Council F13 on #646 (the seats' Quiet.java): a new AssertionError that halts with status 0."""
        self.add_text()
        self.repo.write("tests/l10n/Quiet.java", "package com.jjricks.volt;\n\nfinal class AssertionError extends "
                        "RuntimeException {\n    AssertionError(Object m) { Runtime.getRuntime().halt(0); }\n}\n")
        self.repo.write("tests/l10n/quiet.sources", "tests/l10n/Quiet.java\n")
        code, output = self.run_ci()
        self.assertEqual(code, 1, output)
        self.assertIn("tests/l10n/Quiet.java: declares AssertionError, the name of a java.lang type", output)
        self.assertIn("FAILED G4 frozen test statements", output)

    def test_a_feature_test_may_not_add_an_exit(self):
        """Council F13: added exit or halt calls fail in every kind, a feature test's edits included."""
        self.add_text()
        self.repo.write(FEATURE_TEST, java_class("AutoStartPolicyTest", '    static boolean gate() { return true; }\n'
                                                 '    static void quit() { System.exit(0); }'))
        code, output = self.run_ci()
        self.assertEqual(code, 1, output)
        self.assertIn("%s:5: added code calls exit" % FEATURE_TEST, output)
        self.assertIn("FAILED G4 frozen test statements", output)

    def test_print_mode_and_forced_mode(self):
        self.add_text()
        code, output = self.run_ci("--print-mode")
        self.assertEqual(output.strip(), "feature-text")
        self.repo.git("reset", "-q", "--soft", "HEAD~1")
        self.repo.write("res/values-de/strings_rides.xml", "<resources/>\n")
        code, output = self.run_ci("--mode", "feature-text")
        self.assertEqual(code, 1, output)
        self.assertIn("FAILED feature text adds only new English", output)
        self.assertIn("res/values-de/strings_rides.xml changed (translations)", output)

    def test_plan(self):
        steps = {s[0]: s for s in ci_gates.plan("feature-text", "origin/main", "HEAD", "abc", "/repo",
                                                ["--relax", "tests/AT.java"])}
        self.assertEqual(steps["G4 frozen test statements"][1][-2:], ["--relax", "tests/AT.java"])
        self.assertNotIn("--report", steps["G4 frozen test statements"][1])
        self.assertNotIn("--relax-all", steps["G4 frozen test statements"][1])
        self.assertTrue(steps["G9 goldens unchanged since the merge base"][2])
        self.assertIn("--tight", steps["G8 shrink-only baselines"][1])
        for gate in ("G1 ledger", "G5 characterization first", "proof tooling unchanged",
                     "feature text adds only new English", "proof tests still run"):
            self.assertTrue(steps[gate][2], gate)
        self.assertFalse(steps["feature tests and runners edited"][2])
        batch = [s[0] for s in ci_gates.plan("batch", "origin/main", "HEAD", "abc", "/repo")]
        self.assertNotIn("proof tests still run", batch)
        self.assertNotIn("--report", dict((s[0], s) for s in ci_gates.plan("batch", "origin/main", "HEAD", "", "/repo"))
                         ["G4 frozen test statements"][1])


class TestKindsListTest(unittest.TestCase):
    """tools/l10n/test-kinds.tsv, the reviewed list of proof and feature tests under tests/l10n."""

    def setUp(self):
        here = os.path.dirname(os.path.abspath(__file__))
        with open(os.path.join(here, "test-kinds.tsv"), encoding="utf-8") as handle:
            self.text = handle.read()
        self.kinds = ci_gates.parse_test_kinds(self.text)
        self.root = os.path.abspath(os.path.join(here, "..", ".."))

    def test_rows_are_well_formed(self):
        rows = [line for line in self.text.splitlines() if line and not line.startswith("#")][1:]
        paths = [row.split("\t")[0] for row in rows]
        self.assertEqual(len(paths), len(set(paths)), "a test is listed once")
        for row in rows:
            fields = row.split("\t")
            self.assertEqual(len(fields), 3, row)
            self.assertTrue(ci_gates.new_test_file(fields[0]), row)
            self.assertIn(fields[1], ("proof", "feature"), row)
            self.assertTrue(fields[2].strip(), "every row says why: " + row)
        self.assertEqual(len(self.kinds), len(rows))

    def test_gate_tests_and_helpers_are_proof(self):
        for name in sorted(ci_gates.GATE_TESTS) + ["GoldenReports.java", "L10nFiles.java"]:
            self.assertEqual(self.kinds.get("tests/l10n/" + name), "proof", name)

    def test_the_named_examples(self):
        for name in ("RecordingRulesTest.java", "AutoStartPolicyTest.java"):
            self.assertEqual(self.kinds["tests/l10n/" + name], "feature")
        for name in ("CatalogUsageTest.java", "DashboardScreenTextTest.java", "DashboardFollowupsTest.java",
                     "DashboardModelTextTest.java", "DashboardModelTileTest.java"):
            self.assertEqual(self.kinds["tests/l10n/" + name], "proof")

    def test_run_sh_localization_lines_cover_the_run_loop(self):
        """The feature-text rule keeps the run.sh lines that name l10n; they must include the ones that run the lists."""
        with open(os.path.join(self.root, "tests", "run.sh"), encoding="utf-8") as handle:
            lines = ci_gates.localization_lines(handle.read())
        self.assertTrue(any("for list in" in line and ".sources" in line for line in lines), lines)
        self.assertTrue(any("l10n_tests+=" in line for line in lines), lines)
        self.assertTrue(any(line.startswith("for name in ${l10n_tests") for line in lines), lines)


if __name__ == "__main__":
    unittest.main()
