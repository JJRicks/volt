"""G4 (frozen_literals.py) and G5 (char_first.py) against throwaway git repositories."""

import contextlib
import io
import os
import re
import subprocess
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
        self.assertTrue(any('ChargeAlertPolicyTest.java:5: a test statement with "Battery at 80%" was changed' in p
                            for p in problems), problems)
        self.assertTrue(any("existing test code changed" in p for p in problems), problems)

    def test_existing_code_without_english_is_frozen_too(self):
        """Re-review finding 2: existing test code only gains statements, keys included."""
        self.edit(TEST, 'prefs("battery_alerts")', 'prefs("charge_alerts")')
        self.edit(VALIDATION, '"scroll"', '"scrollView"')
        problems, _ = self.run_gate()
        self.assertEqual(len(problems), 2, problems)
        self.assertTrue(all("existing test code changed" in p for p in problems), problems)

    def test_a_key_beside_english_is_frozen_with_it(self):
        self.edit(TEST, 'check("Charge alert", "battery_alerts")', 'check("Charge alert", "charge_alerts")')
        self.assertTrue(any("was changed or removed" in p for p in self.run_gate()[0]))

    def test_decoy_keeping_the_old_literal_fails(self):
        """Finding 3: the expectation changes while a decoy keeps "30 km/h" in the file."""
        self.edit(SCALE_TEST, 'equals("30 km/h"), "Metric label");',
                  'equals("30 kph"), "Metric label"); check(!"30 km/h".isEmpty(), "Metric label");')
        problems, _ = self.run_gate()
        self.assertTrue(any('"30 km/h", "Metric label" was changed' in p for p in problems), problems)
        self.assertTrue(any('"30 km/h", "Metric label" was changed' in p for p in self.run_gate(per_file=True)[0]))

    def test_weakened_assertion_fails(self):
        self.edit(SCALE_TEST, '.equals("30 km/h")', '.equals("30 km/h") || true')
        problems = self.run_gate()[0]
        self.assertTrue(any("was changed or removed" in p for p in problems), problems)
        self.assertTrue(any("not plain new test code" in p for p in problems), problems)

    def test_swapped_expectations_fail(self):
        self.edit(SCALE_TEST, '"30 km/h"), "Metric label"', '"30 km/h"), "Imperial label"')
        self.edit(SCALE_TEST, '"19 mph"), "Imperial label"', '"19 mph"), "Metric label"')
        self.assertEqual(len([p for p in self.run_gate()[0] if "was changed or removed" in p]), 2)

    def test_moving_a_statement_out_of_an_existing_file_fails(self):
        self.edit(VALIDATION, 'clickText("Lock scooter"); ', "")
        self.repo.write("android-tests/TextCensus.java",
                        java_class("TextCensus", '    void lock() { TextFit.clickText("Lock scooter"); }'))
        head = self.repo.commit("move")
        problems = frozen_literals.run(self.base, head, cwd=self.repo.root)[0]
        self.assertEqual(len(problems), 1, problems)
        self.assertIn("existing test code deleted", problems[0])

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


class FrozenCodeTest(unittest.TestCase):
    """Re-review finding 2: switching an assertion off without touching its tokens."""
    SCALE = java_class("RouteSpeedScaleTest", '''    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(RouteSpeedScale.label(30, "km").equals("30 km/h"), "Metric label");
        check(RouteSpeedScale.label(19, "mi").equals("19 mph / 30 km/h"), "Dual label");
        System.out.println("RouteSpeedScaleTest: " + checks + " checks passed");
    }''')

    def setUp(self):
        self.repo = TempRepo()
        self.repo.write(SCALE_TEST, self.SCALE)
        self.base = self.repo.commit("base")

    def tearDown(self):
        self.repo.close()

    def gate(self, old, new, per_file=False):
        text = self.SCALE
        for o, n in zip(old if isinstance(old, list) else [old], new if isinstance(new, list) else [new]):
            self.assertIn(o, text)
            text = text.replace(o, n, 1)
        self.repo.write(SCALE_TEST, text)
        head = self.repo.commit("head")
        return frozen_literals.run(self.base, head, per_file=per_file, cwd=self.repo.root)[0]

    METRIC = 'check(RouteSpeedScale.label(30, "km").equals("30 km/h"), "Metric label");'

    def assertRejected(self, problems, reason):
        self.assertTrue(any(reason in p for p in problems), problems)

    def test_wrapping_in_if_false_fails(self):
        problems = self.gate(self.METRIC, "if (false) { " + self.METRIC + " }", per_file=True)
        self.assertRejected(problems, "brackets do not balance")

    def test_decoy_copy_in_an_uncalled_method_fails(self):
        decoy = "    private static void unused() {\n        " + self.METRIC + "\n    }\n\n    public static void main"
        problems = self.gate(['.equals("30 km/h"), "Metric label"', "public static void main"],
                             ['.equals("30 kph"), "Metric label"', decoy])
        self.assertRejected(problems, 'existing test code changed ("30 km/h" became "30 kph")')

    def test_neutering_the_check_helper_fails(self):
        self.assertRejected(self.gate("if (!value) throw", "if (!value && message.isEmpty()) throw"),
                            "not plain new test code: it does not start and end at a statement boundary")

    def test_inserted_jumps_and_assignments_fail(self):
        self.assertRejected(self.gate("        checks++;\n", "        checks++;\n        value = true;\n"),
                            "it changes value, which it did not declare")
        self.repo.git("reset", "-q", "--hard", self.base)
        self.assertRejected(self.gate("    public static void main(String[] args) {\n",
                                      "    public static void main(String[] args) {\n        if (true) return;\n"),
                            "it jumps out of the existing code (return)")

    def test_a_method_with_a_parameter_named_record_is_code(self):
        # Gates round 2 review: record is a contextual keyword; check(boolean value, String record) {
        # opens a method body, where an inserted return jumps out of the existing checks.
        self.SCALE = self.SCALE.replace("String message)", "String record)").replace("AssertionError(message)",
                                                                                      "AssertionError(record)")
        self.repo.write(SCALE_TEST, self.SCALE)
        self.base = self.repo.commit("a record parameter")
        self.assertRejected(self.gate("        checks++;\n", "        checks++;\n        if (value) return;\n"),
                            "it jumps out of the existing code (return)")

    def test_inserted_escapes_initializers_and_shadows_fail(self):
        cases = [
            ("    private static int checks;\n", "    private static int checks;\n    static { "
             "Thread.setDefaultUncaughtExceptionHandler((t, e) -> System.exit(0)); }\n", "it calls"),
            ("    private static int checks;\n", "    private static int checks;\n    static int x = (checks = -9);\n",
             "it changes checks"),
            ("    private static int checks;\n", "    private static int checks;\n    static final Fake RouteSpeedScale = "
             "null;\n", "it declares RouteSpeedScale, a name the file already uses"),
            ("    public static void main(String[] args) {\n", "    public static void main(String[] args) {\n"
             "        Fake RouteSpeedScale = new Fake();\n", "it declares RouteSpeedScale"),
            ("    private static int checks;\n", "    private static int checks;\n    static final class RouteSpeedScale {}\n",
             "it declares a type"),
            ("package com.jjricks.volt;\n", "package com.jjricks.volt;\nimport fake.RouteSpeedScale;\n",
             "it imports RouteSpeedScale"),
            ("    private static int checks;\n", "    private static int checks;\n    static { java.util.Locale.setDefault("
             "java.util.Locale.US); }\n", "it calls setDefault"),
        ]
        for old, new, reason in cases:
            self.repo.git("reset", "-q", "--hard", self.base)
            self.assertRejected(self.gate(old, new), reason)

    def test_reflection_and_throws_from_a_new_member_fail(self):
        """Re-review 3, finding 1: a new helper ends the test with exit 0 before its first check."""
        main = "    public static void main(String[] args) {\n"
        cases = [
            ('    private static Object quietRun() throws Exception { Class.forName("java.lang.System")'
             '.getMethod("ex" + "it", int.class).invoke(null, 0); return null; }\n', "it calls Class"),
            ('    private static Object quietRun() throws Exception { java.lang.reflect.Method m = '
             'Object.class.getMethods()[0]; return m; }\n', "it calls"),
            ("    private static Object quietRun() throws Exception { return int.class; }\n",
             "it uses a Class literal"),
            ('    private static Object quietRun() { throw new IllegalStateException("skip"); }\n', "it throws"),
            ('    private static Object quietRun() { return MethodHandles.lookup(); }\n', "it calls MethodHandles"),
        ]
        for member, reason in cases:
            self.repo.git("reset", "-q", "--hard", self.base)
            self.assertRejected(self.gate(main, member + "\n" + main + "        Object warmUp = quietRun();\n"),
                                reason)

    def test_calling_into_a_new_file_fails(self):
        self.repo.write("tests/l10n/characterization/WarmUp.java", java_class(
            "WarmUp", "    static Object quietRun() { System.exit(0); return null; }"))
        self.assertRejected(self.gate("    public static void main(String[] args) {\n",
                                      "    public static void main(String[] args) {\n"
                                      "        Object warmUp = WarmUp.quietRun();\n"),
                            "it uses WarmUp, which a file new in this change declares")

    def test_a_new_test_class_may_not_shadow_a_src_class(self):
        self.repo.write("src/com/jjricks/volt/RouteSpeedScale.java", java_class("RouteSpeedScale", ""))
        self.repo.commit("src")
        self.base = self.repo.git("rev-parse", "HEAD")
        self.repo.write("tests/l10n/FakeScale.java", java_class("RouteSpeedScale", "    static String label() { return \"x\"; }"))
        self.repo.write("tests/l10n/NewTest.java", java_class("NewTest", "    static String t() { return \"y\"; }"))
        head = self.repo.commit("head")
        problems = frozen_literals.run(self.base, head, cwd=self.repo.root)[0]
        self.assertRejected(problems, "tests/l10n/FakeScale.java: declares RouteSpeedScale, the name of a src/ class")
        self.assertFalse(any("NewTest" in p for p in problems), problems)

    def test_a_new_test_class_may_not_shadow_java_lang_or_an_existing_test_type(self):
        """Re-review 4, finding 2: a same-package AssertionError replaces java.lang's in every test."""
        self.repo.write("tests/StockPackParity.java", "package com.jjricks.volt;\n\nfinal class StockPackParity {}\n"
                        "final class ReplayHelper { static class Row {} }\n")
        self.repo.commit("existing helper")
        self.base = self.repo.git("rev-parse", "HEAD")
        self.repo.write("tests/l10n/AssertionError.java", "package com.jjricks.volt;\n\nfinal class AssertionError "
                        "extends Error { AssertionError(Object m) { } }\n")
        self.repo.write("tests/l10n/Helpers.java", java_class("Helpers", "    static final class Row {}\n"
                                                              "    static final class Process {}") +
                        "final class ReplayHelper {}\nfinal class System {}\n")
        self.repo.write("tests/l10n/CatalogCheckTest.java", java_class("CatalogCheckTest", "    static final class Record {}"))
        head = self.repo.commit("head")
        problems = frozen_literals.run(self.base, head, cwd=self.repo.root)[0]
        self.assertRejected(problems, "tests/l10n/AssertionError.java: declares AssertionError, the name of a java.lang type")
        self.assertRejected(problems, "tests/l10n/Helpers.java: declares System, the name of a java.lang type")
        self.assertRejected(problems, "tests/l10n/Helpers.java: declares ReplayHelper, a type tests/StockPackParity.java "
                                      "already declares")
        # Nested types only shadow inside their own class, so an honest nested Row, Process or Record passes.
        self.assertFalse(any("Row" in p or "Process" in p or "CatalogCheckTest" in p for p in problems), problems)

    def test_plain_new_tests_pass(self):
        added = ('''        System.out.println("RouteSpeedScaleTest: " + checks + " checks passed");
        double kmh = 45;
        for (int i = 0; i < 2; i++) kmh += i;
        check(RouteSpeedScale.label(kmh, "km").equals("46 km/h"), "Rounded metric label");
        newCases();
''', '''    private static void newCases() {
        String expected = "12 km/h";
        check(RouteSpeedScale.label(12, "km").equals(expected), "Small metric label");
    }

    public static void main''')
        problems = self.gate(['        System.out.println("RouteSpeedScaleTest: " + checks + " checks passed");\n',
                              "    public static void main"], list(added), per_file=True)
        self.assertEqual(problems, [])


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
        self.assertTrue(any("template, plural or format ledger row" in p for p in problems), problems)

    def test_a_format_row_requires_characterization(self):
        self.repo.write(self.UNITS, java_class("DisplayUnits", "    String km() { return T.unitKm(); }"))
        self.repo.write("l10n/ledger/b1.tsv", "\t".join(ledger_check.HEADER) + "\n" + self.UNITS +
                        ':4\t[" ", {"text": "km", "key": "units_km", "arg": 1}]\tunit_km\tformat\t\n')
        problems = self.gate(self.repo.commit("no characterization"))[0]
        self.assertTrue(any("adds 1 template, plural or format ledger row" in p for p in problems), problems)

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


class ListingAdditionsTest(unittest.TestCase):
    """char_first.py --listing-additions (owner-approved Nov 13 2026): only a first commit that adds rows to
    existing listings, which a listed characterization test reads, skips the first-commit run."""

    LISTING = "tests/l10n/characterization/rides-english.tsv"
    READER = "tests/l10n/characterization/RidesEnglishTest.java"
    HELPER = "tests/l10n/characterization/RidesListingRows.java"
    ROWS = "entry a\tlabel\tA\nentry b\tlabel\tB\nentry c\tlabel\tC\n"

    def reader(self, name="RidesEnglishTest", main=True):
        """A characterization test that compares the listing with the code's output when tests/run.sh runs it."""
        body = '    static final String LISTING = "%s";' % self.LISTING
        if main:
            body += "\n    public static void main(String[] args) { /* compares LISTING with the code */ }"
        return java_class(name, body)

    def setUp(self):
        self.repo = TempRepo()
        self.addCleanup(self.repo.close)
        self.repo.write(self.LISTING, self.ROWS)
        self.repo.write(self.READER, self.reader())
        self.repo.write("tests/l10n/rides.sources", "# the rides listing\n" + self.READER + "  # reads rides-english.tsv\n")
        self.base = self.repo.commit("base")
        self.repo.git("checkout", "-q", "-b", "batch")

    def first_commit(self, rows, path=None):
        self.repo.write(path or self.LISTING, rows)
        first = self.repo.commit("characterize")
        self.repo.write("src/com/jjricks/volt/Rides.java", java_class("Rides", ""))
        return first, self.repo.commit("the change")

    def additions(self):
        head = self.repo.git("rev-parse", "HEAD")
        return char_first.listing_additions(self.base, head, cwd=self.repo.root)

    def test_added_rows_only(self):
        first, _ = self.first_commit("entry a\tlabel\tA\nentry new\tlabel\tNew\nentry b\tlabel\tB\nentry c\tlabel\tC\n"
                                     "entry z\tlabel\tZ\n")
        ok, detail = self.additions()
        self.assertTrue(ok, detail)
        self.assertEqual(detail, "the first commit %s only adds rows to %s; existing rows unchanged (3 lines), new rows "
                                 "(2) are left to the run at head (G6)" % (first[:12], self.LISTING))

    def test_an_added_row_and_a_changed_row(self):
        self.first_commit("entry a\tlabel\tA\nentry b\tlabel\tB2\nentry c\tlabel\tC\nentry new\tlabel\tNew\n")
        ok, detail = self.additions()
        self.assertFalse(ok)
        self.assertIn("changes, removes or reorders a line", detail)

    def test_a_removed_row(self):
        self.first_commit("entry a\tlabel\tA\nentry c\tlabel\tC\nentry new\tlabel\tNew\n")
        self.assertFalse(self.additions()[0])

    def test_a_reordered_listing(self):
        self.first_commit("entry b\tlabel\tB\nentry a\tlabel\tA\nentry c\tlabel\tC\nentry new\tlabel\tNew\n")
        self.assertFalse(self.additions()[0])

    def test_a_changed_line_ending(self):
        """Byte for byte: the last row gaining or losing its newline is a change."""
        self.repo.write(self.LISTING, self.ROWS.rstrip("\n"))
        self.base = self.repo.commit("no final newline")
        self.first_commit(self.ROWS + "entry new\tlabel\tNew")
        self.assertFalse(self.additions()[0])

    def test_an_addition_to_a_test_file(self):
        self.repo.write(self.LISTING, self.ROWS + "entry new\tlabel\tNew\n")
        self.first_commit(self.reader().replace("{\n", "{\n    int more;\n", 1), path=self.READER)
        ok, detail = self.additions()
        self.assertFalse(ok)
        self.assertIn("not only listings", detail)

    def test_a_new_listing(self):
        self.first_commit("entry x\tlabel\tX\n", path="tests/l10n/characterization/other-english.tsv")
        ok, detail = self.additions()
        self.assertFalse(ok)
        self.assertIn("is a new file", detail)

    def test_a_listing_no_listed_test_reads(self):
        """Then nothing would prove the new rows at head, so the first commit still runs."""
        self.repo.write("tests/l10n/rides.sources", "# " + self.READER + "\n")
        self.base = self.repo.commit("the reader commented out")
        self.first_commit(self.ROWS + "entry new\tlabel\tNew\n")
        ok, detail = self.additions()
        self.assertFalse(ok)
        self.assertIn("no characterization test that tests/run.sh runs", detail)

    def test_a_listed_test_without_a_main_method_is_not_a_reader(self):
        """Review of #446 (finding 1): a class that only holds the listing's name compares nothing."""
        self.repo.write(self.READER, self.reader(main=False))
        self.base = self.repo.commit("a reader that holds the name only")
        self.first_commit(self.ROWS + "entry new\tlabel\tNew\n")
        ok, detail = self.additions()
        self.assertFalse(ok)
        self.assertIn("no characterization test that tests/run.sh runs", detail)

    def test_a_listed_helper_is_not_a_reader_when_the_test_is_not_listed(self):
        """Review of #446 (finding 1): tests/run.sh compiles a listed helper but runs only tests/l10n/*Test.java,
        so a helper that mentions the listing (even with a main method) proves nothing at head."""
        self.repo.write(self.HELPER, self.reader("RidesListingRows"))
        self.repo.write("tests/l10n/rides.sources", self.HELPER + "\n# " + self.READER + "\n")
        self.base = self.repo.commit("only the helper is listed")
        self.first_commit(self.ROWS + "entry new\tlabel\tNew\n")
        ok, detail = self.additions()
        self.assertFalse(ok)
        self.assertIn("no characterization test that tests/run.sh runs", detail)

    def test_the_test_dropped_from_its_list_in_the_change(self):
        """Review of #446 (finding 1): the readers are the entries at head, not at the merge base."""
        self.repo.write(self.HELPER, self.reader("RidesListingRows"))
        self.repo.write("tests/l10n/rides.sources", self.READER + "\n" + self.HELPER + "\n")
        self.base = self.repo.commit("the test and a helper listed")
        self.repo.write(self.LISTING, self.ROWS + "entry new\tlabel\tNew\n")
        self.repo.commit("characterize")
        self.repo.write("tests/l10n/rides.sources", self.HELPER + "\n")
        self.repo.commit("the change drops the test from its list")
        self.assertFalse(self.additions()[0])

    def test_a_listing_changed_after_the_first_commit(self):
        self.first_commit(self.ROWS + "entry new\tlabel\tNew\n")
        self.repo.write(self.LISTING, self.ROWS + "entry new\tlabel\tNew\nentry later\tlabel\tLater\n")
        self.repo.commit("later")
        self.assertFalse(self.additions()[0])

    def test_no_characterization_change(self):
        self.repo.write("src/com/jjricks/volt/Rides.java", java_class("Rides", ""))
        self.repo.commit("code only")
        self.assertFalse(self.additions()[0])

    def test_the_run_rule_is_tests_run_sh_s(self):
        """RUN_PATTERN is the pattern tests/run.sh uses to pick the listed entries it runs, with the same meaning.
        If tests/run.sh's rule changes, this fails, so the two cannot drift apart."""
        here = os.path.dirname(os.path.abspath(__file__))
        with open(os.path.join(here, "..", "..", "tests", "run.sh"), encoding="utf-8") as handle:
            script = handle.read()
        rules = re.findall(r'if \[\[ "\$line" == (\S+) \]\]; then l10n_tests\+=', script)
        self.assertEqual(rules, [char_first.RUN_PATTERN], "tests/run.sh's rule for the l10n tests it runs changed")
        self.assertEqual(script.count("l10n_tests+="), 1, "tests/run.sh picks the l10n tests it runs in one place")
        for entry in ("tests/l10n/characterization/DashboardModelEnglishTest.java", "tests/l10n/LedgerTest.java",
                      "tests/l10n/characterization/RidesListingRows.java", "tests/l10n/characterization/rides-english.tsv",
                      "tests/RidesEnglishTest.java", "tests/l10n/characterization/RidesTest.java.bak",
                      "tests/l10n/Test.java", "tests/l10n/a/b/CTest.java"):
            bash = subprocess.run(["bash", "-c", '[[ "$1" == %s ]]' % char_first.RUN_PATTERN, "-", entry]).returncode == 0
            self.assertEqual(char_first.runs_in_run_sh(entry), bash, entry)

    def test_the_command_line(self):
        self.first_commit(self.ROWS + "entry new\tlabel\tNew\n")
        cwd = os.getcwd()
        os.chdir(self.repo.root)
        try:
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = char_first.main(["--base", self.base, "--listing-additions"])
        finally:
            os.chdir(cwd)
        self.assertEqual(code, 0)
        self.assertIn("existing rows unchanged (3 lines), new rows (1)", out.getvalue())


if __name__ == "__main__":
    unittest.main()
