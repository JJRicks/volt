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

    def prove(self):
        result = subprocess.run(["bash", "tools/l10n/prove-english.sh", self.base, "--skip-build", "--skip-tests"],
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


if __name__ == "__main__":
    unittest.main()
